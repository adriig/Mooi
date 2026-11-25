"""Transversal aspect: a typed client for `mic-mooi`.

Every call forwards the caller's own bearer token, so `mic-mooi` still enforces its own
authorization; the service-only endpoints (the agent credential, the GitHub clone token) also carry
`X-Service-Token` — a browser holding a valid access token cannot reach them
directly, only this service can. Upstream 4xx responses are re-raised with the same status and the
upstream `message` when the body carries one (the same `ApiError` shape `mic-mooi` returns);
5xx responses and network failures collapse to a single `ApiException.bad_gateway`, since none of
those carry information this service should forward verbatim.
"""

from __future__ import annotations

import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any
from uuid import UUID

import httpx

from mic_sessions.shared.auth import Caller, verify_access_token
from mic_sessions.shared.env import get_settings
from mic_sessions.shared.web import ApiException, get_http_client

_SERVICE_TOKEN_HEADER = "X-Service-Token"
# Renewed this long before expiry, so a call started on a still valid token never lands on an expired one.
_RENEWAL_MARGIN_SECONDS = 120


@dataclass(frozen=True)
class Project:
    id: UUID
    full_name: str
    default_branch: str
    is_private: bool
    html_url: str
    # Player-declared in `mic-mooi`; only web applications may be deployed and previewed.
    web_application: bool
    # Platform-owned: a development deployment setup passed its test, so sessions deploy directly.
    development_deployment_ready: bool = False


@dataclass(frozen=True)
class Credential:
    provider: str
    mode: str
    token: str = field(repr=False)
    expires_at: datetime | None
    connection_id: str | None = None
    save: Callable[[str], Awaitable[None]] | None = field(default=None, repr=False)
    # Reports that the provider ended this sign-in, so the account page asks the player to sign in again.
    expire: Callable[[], Awaitable[None]] | None = field(default=None, repr=False)
    session_model: str | None = None
    session_effort: str | None = None
    # The platform's default adapter for this provider; None is the provider's first one.
    session_adapter: str | None = None


@dataclass(frozen=True)
class GitIdentity:
    """Author of the commits this service writes on the player's behalf."""

    name: str
    email: str


def _bearer(caller: Caller) -> dict[str, str]:
    return {"Authorization": f"Bearer {caller.token}"}


def _service_headers(caller: Caller | None) -> dict[str, str]:
    """`None` is this service acting on its own behalf, for the endpoints no player token may reach."""
    return {**(_bearer(caller) if caller else {}), _SERVICE_TOKEN_HEADER: get_settings().service_token}


def _raise_for_status(response: httpx.Response, not_found_message: str) -> None:
    if response.status_code == 404:
        raise ApiException.not_found(not_found_message)
    if 400 <= response.status_code < 500:
        message = not_found_message
        try:
            message = response.json().get("message") or message
        except ValueError:
            pass
        raise ApiException(response.status_code, message)
    if response.status_code >= 500:
        raise ApiException.bad_gateway()


async def _get(client: httpx.AsyncClient, url: str, headers: dict[str, str], not_found_message: str) -> dict:
    try:
        response = await client.get(url, headers=headers)
    except httpx.HTTPError:
        raise ApiException.bad_gateway() from None
    _raise_for_status(response, not_found_message)
    return response.json()


async def fetch_project(caller: Caller, project_id: UUID) -> Project:
    settings = get_settings()
    body = await _get(
        get_http_client(),
        f"{settings.mooi_api_base_url}/me/projects",
        _bearer(caller),
        "Project not found",
    )
    for project in body.get("projects", []):
        if project.get("id") == str(project_id):
            return Project(
                id=UUID(project["id"]),
                full_name=project["fullName"],
                default_branch=project["defaultBranch"],
                is_private=project["isPrivate"],
                html_url=project["htmlUrl"],
                web_application=bool(project.get("webApplication", False)),
                development_deployment_ready=bool(project.get("developmentDeploymentReady", False)),
            )
    raise ApiException.not_found("Project not found")


async def fetch_session_limit(caller: Caller) -> int:
    """The account's effective limit: its configured one, never above this pod's capacity."""
    settings = get_settings()
    body = await _get(get_http_client(), f"{settings.mooi_api_base_url}/me/session-limit",
                      _bearer(caller), "Account not found")
    return min(int(body["maxSessions"]), settings.max_sessions)


async def fetch_preview_deployment_limit() -> int:
    body = await _get(get_http_client(),
                      f"{get_settings().mooi_api_base_url}/internal/settings/preview-deployments",
                      _service_headers(None), "Preview settings not found")
    return int(body["maxPreviewDeployments"])


async def delete_merged_branch_by_default(caller: Caller) -> bool:
    body = await _get(get_http_client(), f"{get_settings().mooi_api_base_url}/me",
                      _bearer(caller), "Account not found")
    return bool((body.get("player") or {}).get("deleteMergedBranch", False))


async def fetch_suggested_commit_preferences(caller: Caller) -> dict:
    payload = await _get(
        get_http_client(),
        f"{get_settings().mooi_api_base_url}/me/preferences/suggested-commit",
        _bearer(caller),
        "Account not found",
    )
    return {"enabled": bool(payload.get("enabled", True)), "language": str(payload.get("language") or "en")}


async def fetch_github_token(caller: Caller) -> str:
    settings = get_settings()
    body = await _get(
        get_http_client(),
        f"{settings.mooi_api_base_url}/me/github/token",
        _service_headers(caller),
        "No GitHub account linked",
    )
    return body["token"]


async def fetch_production_recipe(caller: Caller, project_id: UUID) -> dict:
    return await _get(get_http_client(),
                      f"{get_settings().mooi_api_base_url}/me/projects/{project_id}/production/recipe",
                      _service_headers(caller), "Project not found")


async def fetch_production_recipe_status(caller: Caller, project_id: UUID) -> dict:
    return await _get(get_http_client(),
                      f"{get_settings().mooi_api_base_url}/me/projects/{project_id}/production/recipe/status",
                      _service_headers(caller), "Project not found")


async def fetch_production_deployment_detail(caller: Caller, project_id: UUID, operation_id: UUID) -> dict:
    return await _get(get_http_client(),
                      f"{get_settings().mooi_api_base_url}/me/projects/{project_id}/production/deployments/{operation_id}",
                      _service_headers(caller), "Deployment not found")


async def fetch_production_deployments(caller: Caller, project_id: UUID, page: int = 0) -> dict:
    return await _get(get_http_client(),
                      f"{get_settings().mooi_api_base_url}/me/projects/{project_id}/production/deployments?page={page}",
                      _service_headers(caller), "Project not found")


async def delete_production_deployment(caller: Caller, project_id: UUID, operation_id: UUID) -> None:
    try:
        response = await get_http_client().delete(
            f"{get_settings().mooi_api_base_url}/me/projects/{project_id}/production/deployments/{operation_id}",
            headers=_service_headers(caller))
    except httpx.HTTPError:
        raise ApiException.bad_gateway() from None
    _raise_for_status(response, "Deployment not found")


async def write_production_recipe(caller: Caller, project_id: UUID, recipe: dict) -> dict:
    try:
        response = await get_http_client().put(
            f"{get_settings().mooi_api_base_url}/me/projects/{project_id}/production/recipe/draft",
            headers=_service_headers(caller), json=recipe)
    except httpx.HTTPError:
        raise ApiException.bad_gateway() from None
    _raise_for_status(response, "Project not found")
    return response.json()


async def publish_production_recipe(project_id: UUID) -> dict:
    """Service-only: runs once a deployment proves the draft, possibly after the player's access token expired."""
    return await _send("POST", f"/internal/projects/{project_id}/production/recipe/publish", None,
                       "Project not found") or {}


async def delete_production_recipe(caller: Caller, project_id: UUID) -> None:
    try:
        response = await get_http_client().delete(
            f"{get_settings().mooi_api_base_url}/me/projects/{project_id}/production/recipe",
            headers=_service_headers(caller))
    except httpx.HTTPError:
        raise ApiException.bad_gateway() from None
    _raise_for_status(response, "Project not found")


async def fetch_production_environment(caller: Caller, project_id: UUID) -> dict[str, str]:
    """Stored environment values: for the deploy and status runners and the production agent."""
    payload = await _get(get_http_client(),
                         f"{get_settings().mooi_api_base_url}/me/projects/{project_id}/production/recipe/environment",
                         _service_headers(caller), "Project not found")
    return {str(key): str(value) for key, value in (payload.get("values") or {}).items()}


async def write_production_environment(caller: Caller, project_id: UUID, values: dict[str, str | None]) -> dict:
    try:
        response = await get_http_client().put(
            f"{get_settings().mooi_api_base_url}/me/projects/{project_id}/production/recipe/environment",
            headers=_service_headers(caller), json={"values": values})
    except httpx.HTTPError:
        raise ApiException.bad_gateway() from None
    _raise_for_status(response, "Project not found")
    return response.json()


async def start_production_deployment(caller: Caller, project_id: UUID, operation_id: UUID,
                                      session_id: UUID | None, release_tag: str, release_sha: str, files: dict) -> None:
    try:
        response = await get_http_client().post(
            f"{get_settings().mooi_api_base_url}/me/projects/{project_id}/production/deployments",
            headers=_service_headers(caller),
            json={"operationId": str(operation_id), "sessionId": str(session_id) if session_id else None,
                  "releaseTag": release_tag, "releaseSha": release_sha, "targets": files.get("targets", []),
                  "files": {name: files[name] for name in ("manifest", "script", "statusScript")}})
    except httpx.HTTPError:
        raise ApiException.bad_gateway() from None
    _raise_for_status(response, "Project not found")


async def finish_production_deployment(project_id: UUID, operation_id: UUID,
                                       state: str, message: str, logs: list[str]) -> None:
    """Service-only, so the outcome is saved even after the starting player's access token expired."""
    await _send("PUT", f"/internal/projects/{project_id}/production/deployments/{operation_id}", None,
                "Deployment not found", {"state": state, "message": message, "logs": logs})


async def reconcile_production_deployments(active: list[UUID]) -> int:
    """Fails every unfinished deployment outside `active`: ones a restart of this service orphaned."""
    body = await _send("POST", "/internal/production/deployments/reconcile", None, "Not found",
                       {"active": [str(operation_id) for operation_id in active]})
    return int((body or {}).get("interrupted", 0))


async def _send(method: str, path: str, caller: Caller | None, not_found_message: str,
                payload: dict | None = None, params: dict[str, str | int] | None = None) -> dict | None:
    """A service call that changes state in `mic-mooi`; answers its JSON body, or None when it has none."""
    try:
        response = await get_http_client().request(method, f"{get_settings().mooi_api_base_url}{path}",
                                                    headers=_service_headers(caller), json=payload, params=params)
    except httpx.HTTPError:
        raise ApiException.bad_gateway() from None
    _raise_for_status(response, not_found_message)
    return response.json() if response.content else None


async def renewed(caller: Caller) -> Caller:
    """`caller` itself while its token stays valid, else a new token for the same sign-in. For callers
    kept beyond their request (an agent's chat, a long run): `mic-mooi` refuses once the player signed
    out or the sign-in reached its maximum lifetime, exactly as a refresh would."""
    if caller.expires_at - time.time() > _RENEWAL_MARGIN_SECONDS:
        return caller
    body = await _send("POST", f"/internal/auth/sessions/{caller.session_id}/access-token", None,
                       "Session is no longer active", {"playerId": str(caller.player_id)})
    return verify_access_token((body or {})["accessToken"])


# --- development: the environment every session of a project shares ---------------------------


async def mark_development_deployment_ready(project_id: UUID) -> None:
    """Service-only and idempotent: the setup's test may finish after the player's access token expired."""
    await _send("PUT", f"/internal/projects/{project_id}/development/deployment/ready", None, "Project not found")


async def fetch_development_environment(caller: Caller, project_id: UUID) -> dict[str, str]:
    payload = await _get(get_http_client(),
                         f"{get_settings().mooi_api_base_url}/me/projects/{project_id}/development/environment",
                         _service_headers(caller), "Project not found")
    return {str(key): str(value) for key, value in (payload.get("values") or {}).items()}


async def write_development_environment(caller: Caller, project_id: UUID, values: dict[str, str | None]) -> list[str]:
    """Merges the values (null removes one) and answers the names now stored."""
    payload = await _send("PUT", f"/me/projects/{project_id}/development/environment", caller, "Project not found",
                          {"values": values})
    return [str(name) for name in (payload or {}).get("environment") or []]


# --- work: the project's software factory (features, defects and debt) -------------------------


async def fetch_work(caller: Caller, project_id: UUID) -> dict:
    return await _get(get_http_client(), f"{get_settings().mooi_api_base_url}/me/projects/{project_id}/work",
                      _service_headers(caller), "Project not found")


async def fetch_analytics(caller: Caller, project_id: UUID, path: str,
                          params: dict[str, str | int] | None = None) -> Any:
    """Read production analytics as the session's player; ownership stays enforced by mic-mooi."""
    return await send_analytics(caller, "GET", project_id, path, params=params)


async def send_analytics(caller: Caller, method: str, project_id: UUID, path: str,
                         payload: dict | None = None,
                         params: dict[str, str | int] | None = None) -> Any:
    # An audit (20 s) and a site evidence collection (25 s) have server-side budgets beyond the 10 s default.
    slow = method == "POST" and path in ("/audits", "/geo/suggestions/evidence")
    # Saved analysis evidence can aggregate several provider requests for one source.
    analysis_evidence = method == "POST" and path.startswith("/analyses/") and "/evidence/" in path
    try:
        response = await get_http_client().request(
            method, f"{get_settings().mooi_api_base_url}/me/projects/{project_id}/analytics{path}",
            headers=_bearer(caller), params=params, json=payload,
            timeout=90.0 if analysis_evidence else 40.0 if slow else 10.0,
        )
    except httpx.HTTPError:
        raise ApiException.bad_gateway() from None
    _raise_for_status(response, "Analytics not found")
    return response.json() if response.content else None


async def fetch_analysis_events() -> list[dict]:
    response = await get_http_client().get(
        f"{get_settings().mooi_api_base_url}/internal/analytics/analyses/events",
        headers=_service_headers(None),
    )
    _raise_for_status(response, "Analytics events unavailable")
    return response.json()


async def acknowledge_analysis_event(analysis_id: UUID, revision: int) -> None:
    response = await get_http_client().delete(
        f"{get_settings().mooi_api_base_url}/internal/analytics/analyses/events/{analysis_id}",
        headers=_service_headers(None), params={"revision": revision},
    )
    _raise_for_status(response, "Analytics event unavailable")


async def claim_analysis_generations() -> list[dict]:
    response = await get_http_client().post(
        f"{get_settings().mooi_api_base_url}/internal/analytics/analyses/claim",
        headers=_service_headers(None),
    )
    _raise_for_status(response, "Analytics generation queue unavailable")
    return response.json()


async def update_analysis_generation(project_id: UUID, player_id: UUID, analysis_id: UUID,
                                     payload: dict) -> dict:
    return await _send("PUT", f"/internal/projects/{project_id}/analytics/analyses/{analysis_id}/generation"
                       f"?playerId={player_id}", None, "Analytics analysis unavailable", payload)


async def fetch_issues(caller: Caller, project_id: UUID) -> dict:
    """Defects and debt as GitHub has them; the grant is renewed first, since this feature only reads it."""
    await fetch_github_token(caller)
    return await _get(get_http_client(), f"{get_settings().mooi_api_base_url}/me/projects/{project_id}/work/issues",
                      _service_headers(caller), "Project not found")


async def send_work(caller: Caller, method: str, project_id: UUID, path: str, payload: dict | None = None,
                    *, github: bool = False) -> dict | None:
    """One change to the project's work (`path` below `/work`); `github` renews the grant first."""
    if github:
        await fetch_github_token(caller)
    return await _send(method, f"/me/projects/{project_id}/work{path}", caller, "Not found", payload)


async def fetch_work_changes(caller: Caller, project_id: UUID, item_id: UUID) -> list[dict]:
    response: Any = await _send("GET", f"/me/projects/{project_id}/work/items/{item_id}/change-requests", caller,
                                "Change request not found")
    return response or []


async def reconcile_work_changes(live_sessions: list[str], advancing_items: list[str]) -> list[dict]:
    # This service endpoint takes a collection rather than the usual object body.
    try:
        response = await get_http_client().post(
            f"{get_settings().mooi_api_base_url}/internal/work/change-requests/reconcile",
            headers=_service_headers(None), json=live_sessions, params={"advancingItems": advancing_items})
    except httpx.HTTPError:
        raise ApiException.bad_gateway() from None
    _raise_for_status(response, "Change requests not found")
    return list(response.json().get("items") or [])


async def fetch_running_work() -> list[dict]:
    """Service-only: every started item of every player, for the runner."""
    return list(((await _send("GET", "/internal/work/running", None, "Not found")) or {}).get("items") or [])


async def progress_work_change(item_id: UUID, change_id: UUID, progress: dict) -> dict:
    return await _send("PUT", f"/internal/work/items/{item_id}/change-requests/{change_id}", None,
                       "Change request not found", progress) or {}


async def stage_work_change(item_id: UUID, change_id: UUID, session_id: UUID, operation: dict) -> dict:
    return await _send("POST", f"/internal/work/items/{item_id}/change-requests/{change_id}/operations", None,
                       "Change request not found", {"sessionId": str(session_id), "operation": operation}) or {}


async def record_work_deployment(project_id: UUID, release_tag: str, release_sha: str,
                                  deployed_at: str, contained_task_ids: list[str]) -> dict:
    """Reconciles first deployment versions and current-release membership under item row locks."""
    return await _send("POST", f"/internal/projects/{project_id}/work/deployment", None, "Project not found",
                       {"releaseTag": release_tag, "releaseSha": release_sha, "deployedAt": deployed_at,
                        "containedTaskIds": contained_task_ids}) or {}


async def report_task(task_id: UUID, progress: dict) -> dict:
    """Service-only: the runner's account of one task; answers the item with its owner."""
    return await _send("PUT", f"/internal/work/tasks/{task_id}", None, "Task not found", progress) or {}


async def save_task_recovery(task_id: UUID, recovery: dict) -> dict:
    """Persist one revisioned task handoff/result; recovery bundles never pass through this API."""
    return await _send("PUT", f"/internal/work/tasks/{task_id}/recovery", None, "Task not found",
                       recovery) or {}


async def fetch_task_recovery(task_id: UUID, attempt: int) -> dict:
    """Read one durable attempt record for recovery_runner; ownership is resolved server-side."""
    return await _send("GET", f"/internal/work/tasks/{task_id}/recovery", None, "Task not found",
                       params={"attempt": attempt}) or {}


async def report_defect(qa_task_id: UUID, report: dict) -> dict:
    return await _send("POST", f"/internal/work/tasks/{qa_task_id}/fix", None, "Task not found",
                       report) or {}


async def set_item_status(item_id: UUID, status: str, *, pause_reason: str | None = None,
                          auth_session_id: UUID | None = None) -> dict:
    """With `auth_session_id`, applies only while the item still runs with that credential."""
    return await _send("PUT", f"/internal/work/items/{item_id}/status", None, "Item not found",
                       {"status": status, "pauseReason": pause_reason,
                        "authSessionId": str(auth_session_id) if auth_session_id else None}) or {}


# --- analytics: AI visibility (GEO) runs, for the runner ----------------------------------------


async def claim_geo_runs(active: list[UUID], limit: int = 5) -> list[dict]:
    """Service-only: queued manual runs and due weekly ones, now `running` and owned by the caller.
    `active` names the runs this process still executes; any other running run is failed as interrupted."""
    body = await _send("POST", "/internal/analytics/geo/runs/claim", None, "Not found",
                       {"limit": limit, "active": [str(run_id) for run_id in active]})
    return list(body or [])  # type: ignore[arg-type]  # the endpoint answers a JSON array


async def set_geo_run_status(run_id: UUID, status: str, reason: str | None = None) -> dict:
    return await _send("PUT", f"/internal/analytics/geo/runs/{run_id}/status", None, "Run not found",
                       {"status": status, "reason": reason}) or {}


async def post_geo_results(run_id: UUID, results: list[dict]) -> dict:
    return await _send("POST", f"/internal/analytics/geo/runs/{run_id}/results", None, "Run not found",
                       {"results": results}) or {}


async def postpone_geo_run(run_id: UUID, minutes: int) -> dict:
    """Drops a weekly run that could not start; its site is due again in `minutes`."""
    return await _send("POST", f"/internal/analytics/geo/runs/{run_id}/postpone", None, "Run not found",
                       {"minutes": minutes}) or {}


async def fetch_agent_connections(caller: Caller) -> list[dict]:
    """The player's provider accounts, as the account page lists them (no secrets)."""
    body = await _get(get_http_client(), f"{get_settings().mooi_api_base_url}/me/agents/connections",
                      _bearer(caller), "Account not found")
    return list(body.get("connections") or [])


async def fetch_favorite_models(caller: Caller) -> dict[str, list[str]]:
    """User + service authenticated read for assignment; missing preferences are empty lists.

    Saved ids are preferences, not eligibility: consumers still intersect them with live catalogs
    and apply the project's quota limit before considering favorites.
    """
    body = await _get(get_http_client(), f"{get_settings().mooi_api_base_url}/internal/me/agents/favorite-models",
                      _service_headers(caller), "Account not found")
    return body.get("favorites") or {}


async def replace_favorite_models(caller: Caller, provider: str, model_ids: list[str]) -> dict:
    """Only after checking ids against this player's connected-provider catalogs."""
    return await _send("PUT", f"/me/agents/{provider}/favorite-models", caller, "Unknown agent provider",
                       {"modelIds": model_ids}) or {}


# --- backups: configuration and durable records ------------------------------------------------


async def fetch_backup_recipe(caller: Caller, project_id: UUID) -> dict:
    return await _get(get_http_client(), f"{get_settings().mooi_api_base_url}/me/projects/{project_id}/backups/recipe",
                      _service_headers(caller), "Project not found")


async def write_backup_recipe(caller: Caller, project_id: UUID, recipe: dict) -> dict:
    return await _send("PUT", f"/me/projects/{project_id}/backups/recipe/draft", caller, "Project not found", recipe)


async def publish_backup_recipe(project_id: UUID) -> dict:
    """Service-only: runs once a restore proves the draft, possibly after the player's access token expired."""
    return await _send("POST", f"/internal/projects/{project_id}/backups/recipe/publish", None,
                       "Project not found") or {}


async def delete_backup_recipe(caller: Caller, project_id: UUID) -> None:
    await _send("DELETE", f"/me/projects/{project_id}/backups/recipe", caller, "Project not found")


async def fetch_backup_environment(caller: Caller, project_id: UUID) -> dict[str, str]:
    """Stored environment values: for the backup runner and the backup agent."""
    payload = await _get(get_http_client(),
                         f"{get_settings().mooi_api_base_url}/me/projects/{project_id}/backups/recipe/environment",
                         _service_headers(caller), "Project not found")
    return {str(key): str(value) for key, value in (payload.get("values") or {}).items()}


async def write_backup_environment(caller: Caller, project_id: UUID, values: dict[str, str | None]) -> dict:
    return await _send("PUT", f"/me/projects/{project_id}/backups/recipe/environment", caller, "Project not found",
                       {"values": values})


async def fetch_backups(caller: Caller, project_id: UUID, page: int = 0) -> dict:
    return await _get(get_http_client(), f"{get_settings().mooi_api_base_url}/me/projects/{project_id}/backups?page={page}",
                      _service_headers(caller), "Project not found")


async def fetch_backup_detail(caller: Caller, project_id: UUID, backup_id: UUID) -> dict:
    return await _get(get_http_client(), f"{get_settings().mooi_api_base_url}/me/projects/{project_id}/backups/{backup_id}",
                      _service_headers(caller), "Backup not found")


async def start_backup(caller: Caller, project_id: UUID, backup_id: UUID, operation_id: UUID, session_id: UUID | None,
                       release_tag: str, started_at: datetime, script: str) -> None:
    await _send("POST", f"/me/projects/{project_id}/backups", caller, "Project not found", {
        "backupId": str(backup_id), "operationId": str(operation_id),
        "sessionId": str(session_id) if session_id else None, "releaseTag": release_tag,
        "startedAt": started_at.isoformat().replace("+00:00", "Z"), "script": script})


async def start_backup_operation(caller: Caller, project_id: UUID, backup_id: UUID, operation_id: UUID,
                                 action: str, target: str | None, script: str) -> None:
    await _send("POST", f"/me/projects/{project_id}/backups/{backup_id}/operations", caller, "Backup not found", {
        "operationId": str(operation_id), "action": action, "target": target, "script": script})


async def finish_backup_operation(project_id: UUID, backup_id: UUID, operation_id: UUID,
                                  state: str, message: str, logs: list[str]) -> None:
    """Service-only, so the outcome is saved even after the starting player's access token expired."""
    await _send("PUT", f"/internal/projects/{project_id}/backups/{backup_id}/operations/{operation_id}", None,
                "Backup not found", {"state": state, "message": message, "logs": logs})


async def reconcile_backup_operations(active: list[UUID]) -> int:
    """Fails every unfinished backup operation outside `active`: ones a restart of this service orphaned."""
    body = await _send("POST", "/internal/backups/operations/reconcile", None, "Not found",
                       {"active": [str(operation_id) for operation_id in active]})
    return int((body or {}).get("interrupted", 0))


async def delete_backup(caller: Caller, project_id: UUID, backup_id: UUID) -> None:
    await _send("DELETE", f"/me/projects/{project_id}/backups/{backup_id}", caller, "Backup not found")


async def fetch_github_identity(caller: Caller) -> GitIdentity:
    """The linked GitHub account as a commit author, using GitHub's private noreply address so the
    player's real email is never written into repository history."""
    settings = get_settings()
    body = await _get(
        get_http_client(),
        f"{settings.mooi_api_base_url}/me/github/connection",
        _bearer(caller),
        "No GitHub account linked",
    )
    connection = body.get("connection")
    if not connection:
        raise ApiException.not_found("No GitHub account linked")
    login = connection["login"]
    return GitIdentity(
        name=connection.get("name") or login,
        email=f"{connection['githubUserId']}+{login}@users.noreply.github.com",
    )


async def fetch_agent_credential(caller: Caller, connection_id: str) -> Credential:
    settings = get_settings()
    body = await _get(
        get_http_client(),
        f"{settings.mooi_api_base_url}/me/agents/connections/{connection_id}/credential",
        _service_headers(caller),
        "No agent provider linked",
    )
    expires_at = body.get("expiresAt")

    async def save(token: str) -> None:
        response = await get_http_client().put(
            f"{settings.mooi_api_base_url}/me/agents/{body['provider']}/credential",
            headers=_service_headers(caller),
            json={"token": token, "connectionId": body.get("connectionId")},
        )
        _raise_for_status(response, "Agent connection changed; reconnect the session")

    async def expire() -> None:
        response = await get_http_client().put(
            f"{settings.mooi_api_base_url}/me/agents/{body['provider']}/credential/expired",
            headers=_service_headers(caller),
            json={"connectionId": body.get("connectionId")},
        )
        _raise_for_status(response, "Agent connection changed; reconnect the session")

    return Credential(
        provider=body["provider"],
        mode=body["mode"],
        token=body["token"],
        connection_id=body.get("connectionId"),
        session_model=body.get("sessionModel"),
        session_effort=body.get("sessionEffort"),
        session_adapter=body.get("sessionAdapter"),
        # CLI-owned sign-ins the session renews itself and writes back (Codex `auth.json`, the agy bundle).
        save=save if body["provider"] in ("codex", "antigravity") or body["mode"] == "account" else None,
        expire=expire if body["provider"] == "codex" else None,
        expires_at=datetime.fromisoformat(expires_at) if expires_at else None,
    )


async def link_codex(caller: Caller, token: str, account_label: str | None, name: str,
                     connection_id: str | None = None) -> None:
    """Saves a Codex sign-in as a new account, or into `connection_id` when signing an account in again."""
    response = await get_http_client().post(
        f"{get_settings().mooi_api_base_url}/me/agents/codex/device-connection",
        headers=_service_headers(caller),
        json={"token": token, "accountLabel": account_label, "name": name, "connectionId": connection_id},
    )
    _raise_for_status(response, "Could not save the Codex connection")


async def link_opencode(caller: Caller, token: str, account_label: str | None, name: str,
                        connection_id: str | None = None) -> dict:
    """Links an OpenCode account sign-in (the CLI's own `auth export` JSON) as an `account` connection, or
    signs `connection_id` in again, and answers `mic-mooi`'s connection payload (no secret in it)."""
    try:
        response = await get_http_client().post(
            f"{get_settings().mooi_api_base_url}/me/agents/opencode/account-connection",
            headers=_service_headers(caller),
            json={"token": token, "accountLabel": account_label, "name": name, "connectionId": connection_id},
        )
    except httpx.HTTPError:
        raise ApiException.bad_gateway() from None
    _raise_for_status(response, "Could not save the OpenCode connection")
    return response.json()


async def link_antigravity(caller: Caller, token: str, account_label: str | None, name: str) -> dict:
    """Links agy's own Google sign-in (the opaque credential bundle) as an `agent_oauth` connection and
    answers `mic-mooi`'s connection payload (no secret in it)."""
    try:
        response = await get_http_client().post(
            f"{get_settings().mooi_api_base_url}/me/agents/antigravity/agent-connection",
            headers=_service_headers(caller), json={"token": token, "accountLabel": account_label, "name": name},
        )
    except httpx.HTTPError:
        raise ApiException.bad_gateway() from None
    _raise_for_status(response, "Could not save the Antigravity connection")
    return response.json()
