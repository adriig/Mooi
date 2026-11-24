"""Production analytics and GEO, reachable from a development chat through the player's APIs."""

from __future__ import annotations

import asyncio
from datetime import datetime
from typing import Annotated, Any
from uuid import UUID

from fastapi import APIRouter, Body, Query, Request
from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

from mic_sessions.features import sessions
from mic_sessions.shared import mooi
from mic_sessions.shared.auth import Caller
from mic_sessions.shared.web import ApiException

router = APIRouter(tags=["analytics"])
Json = dict[str, Any]
Term = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=255)]


class GeoCompetitor(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: Term
    domain: Term


class GeoSettings(BaseModel):
    model_config = ConfigDict(extra="forbid")
    brandTerms: list[Term] = Field(default_factory=list, max_length=20)
    competitors: list[GeoCompetitor] = Field(default_factory=list, max_length=20)
    weeklyEnabled: bool = False


class GeoPrompt(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: UUID | None = None
    text: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=500)]
    enabled: bool = True


class GeoUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid")
    settings: GeoSettings | None = None
    prompts: list[GeoPrompt] | None = Field(default=None, max_length=50)

    @model_validator(mode="after")
    def unique_prompts(self) -> GeoUpdate:
        ids = [prompt.id for prompt in self.prompts or [] if prompt.id is not None]
        if len(ids) != len(set(ids)):
            raise ValueError("Prompt ids must be unique")
        return self


def _publish(player_id: UUID, project_id: UUID) -> None:
    sessions._channels.publish(player_id, "analytics.updated", {"projectId": str(project_id)})


@router.get("/sessions/{session_id}/analytics/agent/site")
async def agent_site(session_id: UUID, request: Request) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    return await mooi.fetch_analytics(caller, session.project_id, "/site")


@router.patch("/sessions/{session_id}/analytics/agent/site")
async def agent_update_site(session_id: UUID, request: Request, update: Annotated[Json, Body()]) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    saved = await mooi.send_analytics(caller, "PATCH", session.project_id, "/site", update)
    _publish(caller.player_id, session.project_id)
    return saved


async def _geo(caller: Caller, project_id: UUID) -> Json:
    settings, prompts = await asyncio.gather(
        mooi.fetch_analytics(caller, project_id, "/geo/settings"),
        mooi.fetch_analytics(caller, project_id, "/geo/prompts"),
    )
    return {"settings": settings, "prompts": prompts}


@router.get("/sessions/{session_id}/analytics/agent/geo")
async def agent_geo(session_id: UUID, request: Request) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    return await _geo(caller, session.project_id)


@router.put("/sessions/{session_id}/analytics/agent/geo")
async def agent_update_geo(session_id: UUID, request: Request, update: GeoUpdate) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    project_id = session.project_id
    existing = await mooi.fetch_analytics(caller, project_id, "/geo/prompts")
    retained = {str(prompt.id) for prompt in update.prompts or [] if prompt.id is not None}
    if not retained.issubset({prompt["id"] for prompt in existing}):
        raise ApiException.not_found("GEO prompt not found")
    changed = False
    try:
        if update.settings is not None:
            await mooi.send_analytics(caller, "PUT", project_id, "/geo/settings",
                                      update.settings.model_dump(mode="json"))
            changed = True
        if update.prompts is not None:
            # Remove first to stay within the upstream 50-prompt limit when replacing a full list.
            for prompt in existing:
                if prompt["id"] not in retained:
                    await mooi.send_analytics(caller, "DELETE", project_id, f"/geo/prompts/{prompt['id']}")
                    changed = True
            for position, prompt in enumerate(update.prompts):
                path = f"/geo/prompts/{prompt.id}" if prompt.id else "/geo/prompts"
                await mooi.send_analytics(caller, "PATCH" if prompt.id else "POST", project_id, path,
                                          {**prompt.model_dump(exclude={"id"}), "position": position})
                changed = True
    finally:
        # These upstream operations commit separately: also refresh views after a partial failure.
        if changed:
            _publish(caller.player_id, project_id)
    return await _geo(caller, project_id)


@router.post("/sessions/{session_id}/analytics/agent/geo/runs")
async def agent_geo_run(session_id: UUID, request: Request) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    run = await mooi.send_analytics(caller, "POST", session.project_id, "/geo/runs")
    _publish(caller.player_id, session.project_id)
    return run


@router.post("/sessions/{session_id}/analytics/agent/audits")
async def agent_audit(session_id: UUID, request: Request,
                      draft: Annotated[Json | None, Body()] = None) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    audit = await mooi.send_analytics(caller, "POST", session.project_id, "/audits", draft)
    _publish(caller.player_id, session.project_id)
    return audit


async def _optional_report(caller: Caller, project_id: UUID, path: str,
                            params: dict[str, str | int]) -> Any:
    try:
        return await mooi.fetch_analytics(caller, project_id, path, params)
    except ApiException as failure:
        return {"error": failure.message}


@router.get("/sessions/{session_id}/analytics/agent/report")
async def agent_report(session_id: UUID, request: Request,
                       from_: Annotated[datetime, Query(alias="from")], to: datetime) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    if from_.tzinfo is None or to.tzinfo is None or from_ >= to:
        raise ApiException.bad_request("Use timezone-aware from/to timestamps with from before to")
    project_id = session.project_id
    params: dict[str, str | int] = {"from": from_.isoformat(), "to": to.isoformat(), "tz": "UTC"}
    site = await mooi.fetch_analytics(caller, project_id, "/site")
    overview, pages, referrers, clicks, geo, audits, ai = await asyncio.gather(
        mooi.fetch_analytics(caller, project_id, "/overview", params),
        mooi.fetch_analytics(caller, project_id, "/breakdown", {**params, "dimension": "page", "limit": 10}),
        mooi.fetch_analytics(caller, project_id, "/breakdown", {**params, "dimension": "referrer", "limit": 10}),
        mooi.fetch_analytics(caller, project_id, "/breakdown", {**params, "dimension": "click_target", "limit": 10}),
        mooi.fetch_analytics(caller, project_id, "/geo/summary", params),
        mooi.fetch_analytics(caller, project_id, "/audits", {"limit": 1}),
        mooi.fetch_analytics(caller, project_id, "/breakdown", {**params, "dimension": "ai_source", "limit": 10}),
    )
    optional: Json = {}
    sources = {"searchConsole": ("searchConsoleSiteUrl", "/search"), "ga4": ("ga4PropertyId", "/ga4")}
    configured = {name: path for name, (field, path) in sources.items() if site.get(field)}
    values = await asyncio.gather(*(
        _optional_report(caller, project_id, path, {**params, "limit": 10}) for path in configured.values()
    ))
    optional.update(zip(configured, values))
    return {"from": params["from"], "to": params["to"], "overview": overview, "topPages": pages,
            "referrers": referrers, "clickTargets": clicks, "geo": geo, "aiReferrals": ai,
            "latestAuditScore": audits[0]["score"] if audits else None, **optional}


def _analytics_section(session: sessions.Session, root: str) -> str:
    if session.kind != "session":
        return ""
    base = f"{root}/analytics/agent"
    return f"""## Production Analytics, SEO and GEO
Use these platform endpoints to configure or read this project's Analytics from chat. Use the same
X-Mooi-Agent-Token header as the other platform endpoints. They act only for this session's project.
- GET {base}/site: site key, copy-ready tracker snippet, allowedOrigins and Google selections.
- PATCH {base}/site: JSON with allowedOrigins, googleConnectionId, ga4PropertyId and/or
  searchConsoleSiteUrl. Omitted fields stay unchanged; an empty string clears a Google selection.
- GET {base}/report?from=<ISO timestamp>&to=<ISO timestamp>: timezone-aware timestamps (URL-encode
  query values); overview, top pages, referrers, click targets, AI referrals, GEO visibility summary,
  latest audit score and configured Google Search Console queries / GA4 summary. Optional Google
  source errors are explicit; an unconfigured source is omitted.
- GET {base}/geo: settings (brandTerms, competitors [{{name, domain}}], weeklyEnabled) and prompts.
- PUT {base}/geo: {{settings?: {{brandTerms, competitors, weeklyEnabled}}, prompts?: [{{id?, text,
  enabled?}}]}}. Each supplied section replaces that section; omit it to preserve it. Prompts are
  ordered by the array; retain ids to edit, omit ids to create, omit existing prompts to delete them.
  At most 50 prompts. Read first; preserve entries the user did not ask to remove. Changes are saved
  individually; after an error read again before retrying. There is no own-domain setting: production
  targets define the project's hosts.
- POST {base}/geo/runs: queue a visibility run using already linked provider accounts, subject to
  quota and session limits. The response is the queued run; read report later for results.
- POST {base}/audits: optional JSON {{url}} (otherwise the production target); returns the score and
  SEO/GEO findings with fixes. Only production target hosts are permitted. Use findings to fix the
  app's code in this session when requested.
Install the snippet returned by /site in the application's HTML head for PRODUCTION only. Ensure
allowedOrigins contains its production origins. Dev previews are private and must never be measured;
gate the snippet on production configuration. Never commit Google credentials or invent account ids.
Analytics changes appear live on the Analytics page. Production public tracker reachability is configured
by operators through the production deployment feature; do not change deployment files here.
"""


sessions.instruction_sections.append(_analytics_section)
