"""Feature: the software factory — a project's features, defects and debt, and the runner that builds them.

Work items live in `mic-mooi` (features) and on GitHub (defects and debt, as labelled issues); this
feature is the one path to them, from the To-dos and Board views and from session agents alike, and
announces every change on the player's live channel:

- `work.item` `{projectId, item}`: an item was created or changed (its tasks travel with it);
- `work.removed` `{projectId, itemId}`;
- `work.issue` `{projectId, issue}`: a defect or debt issue was created or changed;
- `work.settings` `{projectId, settings}`: the project's factory limits changed.

Every plain session's agent decides whether a request is a single task (done in that very session), a
feature (which it designs, as orchestrator, into tasks with blocking dependencies), a defect or debt,
and records the last three through the agent endpoints below, guided by its session instructions.

**The runner.** Starting an item hands it to a loop that runs with no agent orchestrating and no device
connected, acting for the sign-in that pressed Start: each unblocked task gets its own new session (one
task, one session) within the account's session limit, on the account its assignment names unless that
account runs low on quota (then its alternatives, in order). A development task ends with its merge,
automatic (agentic on conflicts) or by the player; a QA task ends when its acceptance criteria are met,
and a defect it finds becomes a fix task the QA waits for. A finished task's session is closed. The
runner reconciles from `mic-mooi` on every tick, so a restart only relaunches the tasks it interrupted.
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import re
from collections.abc import Coroutine
from contextlib import suppress
from dataclasses import dataclass, field
from pathlib import PurePosixPath
from typing import Annotated, Any, Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Body, Depends, Query, Request, status
from pydantic import BaseModel, Field

from mic_sessions.features import agent_connections, sessions
from mic_sessions.features.sessions import (
    EVENT_MERGE_COMPLETED,
    EVENT_PERMISSION_REQUEST,
    EVENT_PERMISSION_RESOLVED,
    EVENT_QUESTION_REQUEST,
    EVENT_QUESTION_RESOLVED,
    EVENT_SESSION_STATUS,
    EVENT_TURN_RESULT,
    CreateSessionRequest,
    MergeRequest,
    SendMessageRequest,
    Session,
    get_registry,
)
from mic_sessions.shared import mooi, workspaces
from mic_sessions.shared.agents import PROVIDERS, STATUS_FAILED, STATUS_READY
from mic_sessions.shared.auth import Caller, current_caller
from mic_sessions.shared.env import get_settings
from mic_sessions.shared.web import ApiException

LOG = logging.getLogger("factory")

router = APIRouter(tags=["work"])

EVENT_WORK_ITEM = "work.item"
EVENT_WORK_REMOVED = "work.removed"
EVENT_WORK_ISSUE = "work.issue"
EVENT_WORK_SETTINGS = "work.settings"

Json = dict[str, Any]


def _publish_item(player_id: UUID, item: Json | None) -> Json | None:
    if item:
        sessions._channels.publish(player_id, EVENT_WORK_ITEM, {"projectId": item["projectId"], "item": item})
    return item


def _publish_issue(player_id: UUID, project_id: UUID, issue: Json | None) -> None:
    if issue:
        sessions._channels.publish(player_id, EVENT_WORK_ISSUE, {"projectId": str(project_id), "issue": issue})


# --- one path for every change, whichever side asks ---------------------------------------------


async def _create_feature(caller: Caller, project_id: UUID, draft: Json) -> Json:
    return _publish_item(caller.player_id, await mooi.send_work(caller, "POST", project_id, "/features", draft))


async def _update_item(caller: Caller, project_id: UUID, item_id: UUID, update: Json) -> Json:
    return _publish_item(caller.player_id, await mooi.send_work(caller, "PATCH", project_id, f"/items/{item_id}", update))


async def _add_task(caller: Caller, project_id: UUID, item_id: UUID, draft: Json) -> Json:
    return _publish_item(caller.player_id,
                         await mooi.send_work(caller, "POST", project_id, f"/items/{item_id}/tasks", draft))


async def _update_task(caller: Caller, project_id: UUID, item_id: UUID, task_id: UUID, update: Json) -> Json:
    return _publish_item(caller.player_id, await mooi.send_work(caller, "PATCH", project_id,
                                                               f"/items/{item_id}/tasks/{task_id}", update))


async def _delete_task(caller: Caller, project_id: UUID, item_id: UUID, task_id: UUID) -> Json:
    return _publish_item(caller.player_id, await mooi.send_work(caller, "DELETE", project_id,
                                                               f"/items/{item_id}/tasks/{task_id}"))


async def _retry_task(caller: Caller, project_id: UUID, item_id: UUID, task_id: UUID) -> Json:
    work = await mooi.fetch_work(caller, project_id)
    item = next((entry for entry in work.get("items", []) if entry["id"] == str(item_id)), None)
    task = (next((entry for entry in item.get("tasks", []) if entry["id"] == str(task_id)), None)
            if item else None)
    if task is None:
        raise ApiException.not_found("Task not found")
    retryable = task["status"] == "queued" or (
        task["status"] == "attention" and task.get("sessionId") is None)
    if not retryable:
        raise ApiException.conflict("Only a queued task or a task waiting to retry can be restarted")
    item = await mooi.send_work(caller, "POST", project_id, f"/items/{item_id}/tasks/{task_id}/retry")
    item = _publish_item(caller.player_id, item)
    if item and item.get("status") == "running":
        wake()
    return item or {}


async def _reassign_tasks(caller: Caller, project_id: UUID, item_id: UUID) -> Json:
    work = await mooi.fetch_work(caller, project_id)
    item = next((entry for entry in work.get("items", []) if entry["id"] == str(item_id)), None)
    if item is None:
        raise ApiException.not_found("Work item not found")
    limits = await mooi.send_work(caller, "GET", project_id, "/settings") or DEFAULT_LIMITS
    connections = [connection for connection in await mooi.fetch_agent_connections(caller)
                   if connection.get("provider") in PROVIDERS and not connection.get("stale")]
    quota_snapshots, catalogs = await asyncio.gather(
        _refresh_account_quotas(caller, connections), _account_catalogs(caller, connections))
    changed = 0
    eligible = 0
    for task in item.get("tasks", []):
        if task["status"] not in ("planned", "queued", "attention") or task.get("sessionId") is not None:
            continue
        eligible += 1
        current = task.get("assignment") or {}
        current_connection = _assigned_connection(current, connections)
        if current_connection is None or current_connection not in quota_snapshots:
            continue
        current_percent = _highest_percent(current_connection, quota_snapshots[current_connection])
        # Preserve the saved preference whenever its quota is healthy or unavailable. In particular,
        # resolving a provider's default account to its current id must not itself count as a change.
        if current_percent is None or current_percent < _quota_limit(limits):
            continue
        assignment = _select_assignment(task, connections, _quota_limit(limits), quota_snapshots,
                                        catalogs, quota_only=True, alternatives_first=True)
        if assignment is None or _same_assignment(task.get("assignment") or {}, assignment):
            continue
        old_alternatives = task.get("alternatives") or []
        deduplicated: list[Json] = []
        for alternative in [current, *old_alternatives]:
            if not _same_assignment(alternative, assignment) and not any(
                    _same_assignment(alternative, existing) for existing in deduplicated):
                deduplicated.append(alternative)
        item = await _update_task(caller, project_id, item_id, UUID(task["id"]), {
            "assignment": assignment, "alternatives": deduplicated[:5],
        })
        changed += 1
    return {"item": item, "changedCount": changed, "eligibleCount": eligible}


async def _create_issue(caller: Caller, project_id: UUID, draft: Json) -> Json:
    created = await mooi.send_work(caller, "POST", project_id, "/issues", draft, github=True) or {}
    _publish_issue(caller.player_id, project_id, created.get("issue"))
    _publish_item(caller.player_id, created.get("item"))
    return created


async def _sync_issues(caller: Caller, project_id: UUID) -> Json:
    """GitHub and factory state converge through the same operation for views, agents and runner."""
    result = await mooi.fetch_issues(caller, project_id)
    for issue in result.get("issues", []):
        _publish_issue(caller.player_id, project_id, issue)
    for item in result.get("items", []):
        _publish_item(caller.player_id, item)
    return result


async def _save_settings(caller: Caller, project_id: UUID, settings: Json) -> Json:
    saved = await mooi.send_work(caller, "PUT", project_id, "/settings", settings) or {}
    sessions._channels.publish(caller.player_id, EVENT_WORK_SETTINGS, {"projectId": str(project_id), "settings": saved})
    return saved


# --- the player's views (To-dos and Board) ------------------------------------------------------


@router.get("/projects/{project_id}/work/settings")
async def work_settings(project_id: UUID, caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await mooi.send_work(caller, "GET", project_id, "/settings") or {}


@router.put("/projects/{project_id}/work/settings")
async def save_work_settings(project_id: UUID, settings: Annotated[Json, Body()],
                             caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _save_settings(caller, project_id, settings)


@router.get("/projects/{project_id}/work")
async def project_work(project_id: UUID, caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await mooi.fetch_work(caller, project_id)


@router.get("/projects/{project_id}/work/issues")
async def project_issues(project_id: UUID, caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    async with _lock:
        return await _sync_issues(caller, project_id)


@router.post("/projects/{project_id}/work/features", status_code=status.HTTP_201_CREATED)
async def create_feature(project_id: UUID, draft: Annotated[Json, Body()],
                         caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _create_feature(caller, project_id, draft)


@router.patch("/projects/{project_id}/work/items/{item_id}")
async def update_item(project_id: UUID, item_id: UUID, update: Annotated[Json, Body()],
                      caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _update_item(caller, project_id, item_id, update)


@router.delete("/projects/{project_id}/work/items/{item_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_item(project_id: UUID, item_id: UUID, caller: Annotated[Caller, Depends(current_caller)]) -> None:
    await mooi.send_work(caller, "DELETE", project_id, f"/items/{item_id}")
    sessions._channels.publish(caller.player_id, EVENT_WORK_REMOVED,
                               {"projectId": str(project_id), "itemId": str(item_id)})


@router.post("/projects/{project_id}/work/items/{item_id}/tasks")
async def add_task(project_id: UUID, item_id: UUID, draft: Annotated[Json, Body()],
                   caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _add_task(caller, project_id, item_id, draft)


@router.patch("/projects/{project_id}/work/items/{item_id}/tasks/{task_id}")
async def update_task(project_id: UUID, item_id: UUID, task_id: UUID, update: Annotated[Json, Body()],
                      caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _update_task(caller, project_id, item_id, task_id, update)


@router.post("/projects/{project_id}/work/items/{item_id}/tasks/{task_id}/retry")
async def retry_task(project_id: UUID, item_id: UUID, task_id: UUID,
                     caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _retry_task(caller, project_id, item_id, task_id)


@router.post("/projects/{project_id}/work/items/{item_id}/reassign")
async def reassign_tasks(project_id: UUID, item_id: UUID,
                         caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _reassign_tasks(caller, project_id, item_id)


@router.delete("/projects/{project_id}/work/items/{item_id}/tasks/{task_id}")
async def delete_task(project_id: UUID, item_id: UUID, task_id: UUID,
                      caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _delete_task(caller, project_id, item_id, task_id)


@router.post("/projects/{project_id}/work/items/{item_id}/start")
async def start_item(project_id: UUID, item_id: UUID, caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    """Hands the item to the runner, which acts for this sign-in from now on, with or without devices."""
    item = _publish_item(caller.player_id, await mooi.send_work(caller, "POST", project_id, f"/items/{item_id}/start"))
    _callers[item_id] = caller
    wake()
    return item or {}


@router.post("/projects/{project_id}/work/items/{item_id}/pause")
async def pause_item(project_id: UUID, item_id: UUID, caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return _publish_item(caller.player_id,
                         await mooi.send_work(caller, "POST", project_id, f"/items/{item_id}/pause")) or {}


@router.post("/projects/{project_id}/work/issues", status_code=status.HTTP_201_CREATED)
async def create_issue(project_id: UUID, draft: Annotated[Json, Body()],
                       caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _create_issue(caller, project_id, draft)


@router.patch("/projects/{project_id}/work/issues/{number}")
async def update_issue(project_id: UUID, number: int, update: Annotated[Json, Body()],
                       caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    async with _lock:
        changed = await mooi.send_work(caller, "PATCH", project_id, f"/issues/{number}", update, github=True) or {}
        _publish_issue(caller.player_id, project_id, changed.get("issue"))
        _publish_item(caller.player_id, changed.get("item"))
        return changed


@router.put("/projects/{project_id}/work/issues/{number}/item")
async def issue_item(project_id: UUID, number: int, caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    """The item that runs an issue, created on first use so its settings can be edited."""
    return _publish_item(caller.player_id,
                         await mooi.send_work(caller, "PUT", project_id, f"/issues/{number}/item", github=True)) or {}


# --- session agents ------------------------------------------------------------------------------


class TaskFinding(BaseModel):
    """One bounded, reproducible defect found within the QA task's acceptance scope."""

    id: str = Field(min_length=1, max_length=100)
    title: str = Field(min_length=1, max_length=200)
    priority: Literal["critical", "high", "medium", "low"]
    criterion: str = Field(min_length=1, max_length=4_000)
    reproduction: str = Field(min_length=1, max_length=6_000)
    expected: str = Field(min_length=1, max_length=4_000)
    observed: str = Field(min_length=1, max_length=4_000)
    evidence: str = Field(default="", max_length=4_000)


class TaskReport(BaseModel):
    """What a factory task's agent reports once its turn's work is over."""

    outcome: Literal["done", "blocked", "passed", "defect"]
    summary: str = Field(default="", max_length=20_000)
    # A defect only: short legacy name; `summary` then holds steps, expected and actual behaviour.
    title: str | None = Field(default=None, max_length=200)
    # QA deliveries are stable across retries; older agents may omit this and use title/summary.
    deliveryId: str | None = Field(default=None, min_length=1, max_length=100)
    findings: list[TaskFinding] = Field(default_factory=list, max_length=20)
    blockerCategory: Literal["access", "credentials", "infrastructure", "capacity"] | None = None
    blockerCause: str | None = Field(default=None, max_length=4_000)
    next_step: str = Field(default="", alias="nextStep", max_length=20_000)
    files: list[str] = Field(default_factory=list, max_length=500)
    verifications: list[str] = Field(default_factory=list, max_length=200)
    preparation: str = Field(default="", max_length=20_000)
    obstacles: list[str] = Field(default_factory=list, max_length=200)


@router.get("/sessions/{session_id}/work/agent")
async def agent_work(session_id: UUID, request: Request) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    try:
        async with _lock:
            issues = (await _sync_issues(caller, session.project_id)).get("issues", [])
        issues_error = None
    except ApiException as failure:
        issues, issues_error = [], failure.message
    work = await mooi.fetch_work(caller, session.project_id)
    return {**work, "issues": issues, "issuesError": issues_error}


@router.get("/sessions/{session_id}/work/agent/assignment")
async def agent_assignment(session_id: UUID, request: Request) -> Json:
    """The assignment expert's brief with the accounts it chooses from, their models and remaining quota,
    the user's favorite models per provider and the project's effective quota limit."""
    session, caller = await sessions._agent_access(session_id, request, "session")
    settings = await mooi.send_work(caller, "GET", session.project_id, "/settings") or {}
    quota_limit = _quota_limit(settings)
    try:
        favorites = await mooi.fetch_favorite_models(caller)
    except ApiException:
        LOG.info("No favorite models for the assignment expert", exc_info=True)
        favorites = {}
    accounts = await _capacity(caller, quota_limit, favorites)
    available: dict[str, set[str]] = {}
    for account in accounts:
        available.setdefault(account["provider"], set()).update(model["id"] for model in account["models"])
    # Saved ids are preferences only: those no connected catalog offers any more are left out.
    favorites = {provider: [model for model in models if model in available.get(provider, set())]
                 for provider, models in favorites.items() if provider in available}
    return {"brief": ASSIGNMENT_BRIEF, "quotaLimit": quota_limit,
            "favorites": {provider: models for provider, models in favorites.items() if models},
            "accounts": accounts}


@router.get("/sessions/{session_id}/work/agent/context")
async def agent_task_context(session_id: UUID, request: Request,
                             section: Annotated[int | None, Query(ge=0)] = None,
                             revision: str | None = None, full: bool = False) -> Json:
    """Only this run's spec, never the project's task list. No GitHub sync or writes."""
    session, caller = await sessions._agent_access(session_id, request, "session")
    run = _runs.get(session_id)
    if run is None:
        raise ApiException.conflict("This session does not run a factory task")
    work = await mooi.fetch_work(caller, session.project_id)
    item = next((row for row in work.get("items", []) if row["id"] == str(run.item_id)), None)
    if item is None:
        raise ApiException.not_found("Work item not found")
    spec = item.get("spec") or ""
    current_revision = _spec_revision(spec)
    if revision is not None and revision != current_revision:
        raise ApiException.conflict("The spec changed; read the context index again before selecting sections")
    sections = _spec_sections(spec)
    result: Json = {"itemId": item["id"], "title": item["title"], "revision": current_revision}
    if full:
        return {**result, "spec": spec}
    if section is not None:
        if section >= len(sections):
            raise ApiException.not_found("Spec section not found")
        return {**result, "section": section, "content": sections[section][1]}
    return {**result, "sections": [{"section": index, "title": title, "characters": len(content)}
                                    for index, (title, content) in enumerate(sections)]}


@router.get("/sessions/{session_id}/work/agent/qa-handoff")
async def agent_qa_handoff(session_id: UUID, request: Request) -> Json:
    """Bounded, read-only detail for this QA run's one tested task; never a project task list or diff."""
    session, caller = await sessions._agent_access(session_id, request, "session")
    run = _runs.get(session_id)
    if run is None or not run.qa:
        raise ApiException.conflict("This session is not a factory QA task")
    item = await mooi.fetch_work(caller, session.project_id)
    work_item = next((row for row in item.get("items", []) if row["id"] == str(run.item_id)), None)
    if work_item is None:
        raise ApiException.not_found("Work item not found")
    qa_task = next((row for row in work_item.get("tasks", []) if row["id"] == str(run.task_id)), None)
    if qa_task is None or not qa_task.get("qaOf"):
        raise ApiException.not_found("QA task not found")
    tested = next((row for row in work_item.get("tasks", []) if row["id"] == qa_task["qaOf"]), None)
    if tested is None:
        return {"available": False, "reason": "The tested legacy task is unavailable"}
    handoff = tested.get("qaHandoff")
    if not isinstance(handoff, dict):
        return {"available": False, "reason": "No structured handoff is stored for this legacy task",
                "mergeSha": tested.get("mergeSha")}
    return {"available": True, "taskId": tested["id"], "title": tested["title"],
            "mergeSha": tested.get("mergeSha"), "summary": str(handoff.get("summary") or "")[:20_000],
            "files": _handoff_files(handoff.get("files") or []),
            "verifications": [_handoff_text(str(value))[:2_000]
                              for value in (handoff.get("verifications") or [])[:200]],
            "preparation": _handoff_text(str(handoff.get("preparation") or ""))}


@router.post("/sessions/{session_id}/work/agent/features", status_code=status.HTTP_201_CREATED)
async def agent_create_feature(session_id: UUID, request: Request, draft: Annotated[Json, Body()]) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    return await _create_feature(caller, session.project_id, draft)


@router.patch("/sessions/{session_id}/work/agent/items/{item_id}")
async def agent_update_item(session_id: UUID, item_id: UUID, request: Request, update: Annotated[Json, Body()]) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    return await _update_item(caller, session.project_id, item_id, update)


@router.post("/sessions/{session_id}/work/agent/items/{item_id}/tasks")
async def agent_add_task(session_id: UUID, item_id: UUID, request: Request, draft: Annotated[Json, Body()]) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    return await _add_task(caller, session.project_id, item_id, draft)


@router.patch("/sessions/{session_id}/work/agent/items/{item_id}/tasks/{task_id}")
async def agent_update_task(session_id: UUID, item_id: UUID, task_id: UUID, request: Request,
                            update: Annotated[Json, Body()]) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    return await _update_task(caller, session.project_id, item_id, task_id, update)


@router.post("/sessions/{session_id}/work/agent/items/{item_id}/tasks/{task_id}/retry")
async def agent_retry_task(session_id: UUID, item_id: UUID, task_id: UUID, request: Request) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    return await _retry_task(caller, session.project_id, item_id, task_id)


@router.post("/sessions/{session_id}/work/agent/items/{item_id}/reassign")
async def agent_reassign_tasks(session_id: UUID, item_id: UUID, request: Request) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    return await _reassign_tasks(caller, session.project_id, item_id)


@router.delete("/sessions/{session_id}/work/agent/items/{item_id}/tasks/{task_id}")
async def agent_delete_task(session_id: UUID, item_id: UUID, task_id: UUID, request: Request) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    return await _delete_task(caller, session.project_id, item_id, task_id)


@router.post("/sessions/{session_id}/work/agent/issues", status_code=status.HTTP_201_CREATED)
async def agent_create_issue(session_id: UUID, request: Request, draft: Annotated[Json, Body()]) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    return await _create_issue(caller, session.project_id, draft)


@router.get("/sessions/{session_id}/work/agent/settings")
async def agent_work_settings(session_id: UUID, request: Request) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    return await mooi.send_work(caller, "GET", session.project_id, "/settings") or {}


@router.put("/sessions/{session_id}/work/agent/settings")
async def agent_save_work_settings(session_id: UUID, request: Request, settings: Annotated[Json, Body()]) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    return await _save_settings(caller, session.project_id, settings)


@router.post("/sessions/{session_id}/work/agent/task", status_code=status.HTTP_204_NO_CONTENT)
async def agent_report_task(session_id: UUID, request: Request, report: TaskReport) -> None:
    """Recorded now, acted on when the agent's turn ends: the agent may still be wrapping up."""
    session, _ = await sessions._agent_access(session_id, request, "session")
    run = _runs.get(session_id)
    if run is None:
        raise ApiException.conflict("This session does not run a factory task")
    if run.qa != (report.outcome in ("passed", "defect")) and report.outcome != "blocked":
        raise ApiException.bad_request("A QA task reports passed or defect; a development task reports done")
    if report.outcome == "defect" and not report.findings and not (report.title or "").strip():
        raise ApiException.bad_request("Provide findings or name the defect in `title`")
    if len({finding.id for finding in report.findings}) != len(report.findings):
        raise ApiException.bad_request("Finding ids must be unique within a delivery")
    if report.outcome == "blocked" and not (report.blockerCause or report.summary).strip():
        raise ApiException.bad_request("Explain the blocker in `blockerCause` or `summary`")
    if report.outcome in ("done", "passed") and not report.summary.strip():
        raise ApiException.bad_request("Provide a summary of the completed work")
    if run.qa:
        persisted = await mooi.report_task(run.task_id, {
            "status": "running",
            "sessionId": str(session_id),
            "attempts": run.attempts,
            "report": report.blockerCause or report.summary or report.title,
            "qaReport": report.model_dump(exclude_none=True, by_alias=True),
        })
        _publish_item(run.player_id, persisted.get("item"))
    await _persist_recovery(run, session, report=report)
    run.outcome = report


# --- standing guidance for every plain session ---------------------------------------------------


ASSIGNMENT_BRIEF = """You are the assignment expert of the software factory: for each task, pick the provider, account, model and thinking effort that will do it best without exhausting the user's quota.
- Choose only among the accounts, models and efforts listed with this brief. Never invent one.
- Each assignment and alternative must use a model and effort listed for its specific `connectionId`: accounts of the same provider can offer different catalogs.
- Decide in this order, and never let a later step override an earlier one:
  1. **Quota first, always.** `quotaLimit` is the project's quota percent from which an account is spared. An account whose known `percent` in any window is at or above `quotaLimit` (`eligible: false`) must not be assigned, nor used as an alternative, while any eligible account exists. `percent: null` means the quota is unknown right now (no live session reads it): such an account is eligible, but prefer known healthy accounts for the heaviest tasks. If no account is eligible, assign as usual: the platform keeps the task waiting until an account has quota left.
  2. **Fit the work.** Architecture, cross-cutting or subtle work gets a strong model with high effort; routine UI, wiring, configuration or documentation gets a fast, cheaper model with low or medium effort. Effort costs quota: raise it only where reasoning pays off. A QA task always runs on a different model than the task it checks, preferably from another provider, with medium effort.
  3. **Favorites among the eligible.** `favorites` lists the user's favorite models per provider (`favorite: true` on each account's models). Among eligible accounts, prefer a favorite model that fits the task over a non-favorite one. When no favorite is eligible or none fits the task, assign the best eligible non-favorite model: a favorite never justifies an account at or above the limit.
  4. **Spread the load.** Prefer the eligible accounts with the most quota left (the lowest `percent` across their windows) and avoid stacking parallel tasks of the same dependency level on one account, even when that means using a favorite model on another account or provider, or a non-favorite model.
- Give every task one to three `alternatives`, in order, on other eligible accounts or providers, following the same order (favorites first among the eligible): the platform checks quota again when the task launches and falls back to them, so no account runs out of credits mid-feature."""


def _work_section(session: Session, root: str) -> str:
    """Factory task sessions get their task's rules instead of the planning guidance."""
    run = _runs.get(session.id)
    if run is not None:
        return _task_section(run, root)
    work = f"{root}/work/agent"
    return f"""## Project work: features, defects and debt (the software factory)
Mooi runs this project as a software factory. Whenever the user asks for work, first decide what it is, and say which one you chose:
- **A single task** (one focused change): do it right here in this session. Never record it.
- **A feature** (work worth several tasks): do not implement it here. Design it as the factory's orchestrator and record it; the user reviews it in the To-dos and Board tabs and presses Start, and the platform then runs every task in its own session.
- **A defect** (something that does not work as it should) or **debt** (technical debt to pay back): record it as a GitHub issue through the platform API, never with your own tools. Fix it here only if the user asks you to.
Never start work items yourself: Start belongs to the user.

Designing a feature:
- Read the code first. Write a `spec`: the goal, the user-facing behaviour and the main technical decisions.
- Break it into tasks of about one to four hours of agent work. Each task runs in a fresh session with its own task and access to the feature's spec by section, so its `description` must be self-contained: what to build, where in the code, and the decisions it must follow. Name relevant spec sections explicitly, including shared decisions and preparation; QA must have enough preparation in its description. Write verifiable `acceptance` criteria for every task.
- Categories: `ux` (UX/UI), `dev` (development), `infra` (infrastructure/DevOps), `docs` (documentation). QA tasks are never written by hand: set `tests: true` on a task whose behaviour must be checked, with optional `qa` settings, and the platform adds its QA task, blocked until the task merges.
- `dependsOn` lists the keys of tasks of the same feature that must be merged first. Add a dependency only when a task really builds on another's code (a new system before the features that use it); everything else stays parallel. Never make a task depend on another feature; at most a whole feature `dependsOn` another feature's id.
- `autoMerge` (default true) merges a finished task on its own, resolving conflicts with the agent; set it to false where the user should review the merge.
- Assign every task: read `GET {work}/assignment` and follow its brief, as a dedicated sub-agent when your environment offers one, before recording the feature. Fill `assignment` (`provider`, `connectionId`, `model`, `effort`), `alternatives` and, for tasks with tests, `qa.assignment`.

Recording a defect or debt:
- A defect or debt still runs as one development task (and an optional QA task). Before creating its GitHub issue, read `GET {work}/assignment` and use the assignment expert to choose the task's `assignment` and ordered `alternatives`, weighing the issue's complexity, the user's favorite models and the accounts' current quota. Include both in `task` when calling `POST {work}/issues`; do not leave a task created by you unassigned.
- If the issue already exists, call `PUT {work}/issues/<number>/item`, then assign its development task through `PATCH {work}/items/<itemId>/tasks/<taskId>` with the same expert-selected `assignment` and `alternatives`. If tests are appropriate, also select and set the QA assignment, using a different model as the assignment brief requires.

Platform API:
- `GET {work}`: the project's `items` (features, and the defects and debt Mooi already runs, each with its `tasks`) and its `issues` (defects and debt on GitHub; `issuesError` explains an empty list). Each item includes `deployment` (`state`: `none`, `pending`, `deployed` or legacy `unknown`; `version`, `deployedAt`, `inCurrentRelease`). Tasks include `mergeSha`, `deployedVersion` and `deployedAt`; the first deployed version is retained on rollback.
- `POST {work}/features` with JSON `{{"title", "spec", "dependsOn": null, "tasks": [{{"key", "title", "category", "description", "acceptance", "assignment": {{"provider", "connectionId", "model", "effort"}}, "alternatives": [], "autoMerge": true, "tests": false, "qa": {{"acceptance", "assignment"}}, "dependsOn": ["<key>"]}}]}}`.
- `PATCH {work}/items/<itemId>` (`title`, `spec`, `dependsOn`: an item id, or "" to clear); `POST {work}/items/<itemId>/tasks` (one task as above); `PATCH {work}/items/<itemId>/tasks/<taskId>` (any task field; `dependsOn` replaces the list); `DELETE {work}/items/<itemId>/tasks/<taskId>`.
- Only when explicitly requested, `POST {work}/items/<itemId>/reassign` rechecks current quota for tasks not yet running and applies the first available saved assignment/fallback; `POST {work}/items/<itemId>/tasks/<taskId>/retry` restarts a queued task or one waiting to retry. Neither starts work on a planned item.
- `GET {work}/settings` and `PUT {work}/settings` with JSON `{{"quotaLimit": 85, "maxAttempts": 3, "maxQaRounds": 3, "maxResolutions": 2}}`: the project's factory limits (quota percent from which an account is spared, 10-100; sessions a task may lose before it waits for the user, 1-10; fix-and-check-again rounds after a failed QA, 0-10; agentic merge attempts on conflicts, 0-5). Change them only when the user asks.
- `POST {work}/issues` with JSON `{{"kind": "defect" | "debt", "title", "body", "task": {{"acceptance", "assignment", "alternatives", "autoMerge", "tests", "qa"}}}}`: opens the GitHub issue and the settings Mooi runs it with. `task.assignment` and `task.alternatives` come from the assignment expert, just like feature task settings."""


def _task_section(run: TaskRun, root: str) -> str:
    report = f"{root}/work/agent/task"
    if run.phase == "resolving":
        branch_guidance = (
            "- The platform explicitly started merge-conflict resolution. Follow its conflict-resolution prompt: "
            "merge the default branch into this task branch, resolve the conflicts and commit that resolution here. "
            "Do not push, switch branches, or merge this task branch into the default branch; the platform performs "
            "that final merge."
        )
    else:
        branch_guidance = (
            "- Do not merge this task branch into the default branch, push, or switch branches; the platform owns "
            "the final merge. Exception: only when the platform explicitly starts merge-conflict resolution, follow "
            "its prompt to merge the default branch into this task branch and commit the resolution locally. Do not "
            "push or switch branches."
        )
    if run.qa:
        outcomes = ('`{"outcome":"passed","summary":"<independent checks and results>"}` only when every criterion '
                    'passes, `{"outcome":"defect","deliveryId":"<stable id>","findings":[{"id":"<stable id>",'
                    '"title":"...","priority":"critical|high|medium|low","criterion":"...","reproduction":"...",'
                    '"expected":"...","observed":"...","evidence":"<redacted>"}]}` for all reproducible defects '
                    'in scope (at most 20), or `{"outcome":"blocked","blockerCategory":"access|credentials|'
                    'infrastructure|capacity","blockerCause":"<specific cause>"}` when independent validation '
                    'cannot proceed')
    else:
        outcomes = '`{"outcome": "done", "summary": "<what you changed>"}` once the task is complete'
    return f"""## Software factory task
This session runs one task of the project's software factory, on its own; there may be nobody watching. Its first message is the task.
- Stay within the task. Other tasks run in their own sessions; the tasks this one depended on are already merged into the default branch.
- Ask the user only when you truly cannot go on: the task then waits for their answer.
- Spec access: `GET {root}/work/agent/context` returns only this item's spec index and revision; add `?section=<number>&revision=<revision>` for one complete section or `?full=true` for the entire unabridged spec. Use this instead of downloading the project's work list. If a revision changed, reload the index.
{branch_guidance}
- When reporting development work, include `nextStep`, `files`, `verifications`, `preparation` requirements for independent QA, and `obstacles` where useful. Keep the handoff concise and never include credentials or secret values; legacy reports with only `outcome` and `summary` remain accepted.
- QA policy: validate independently against the acceptance criteria using this task's isolated preview and the existing deployment brief. A summary is not proof of correctness. Keep exploration bounded to the criteria; report all reproducible in-scope defects together, prioritized, without searching indefinitely for unrelated problems. Separate product defects from access, credential, capacity or infrastructure blockers; blockers are not defects and must not request fixes.
- Before your turn ends, report the outcome with `POST {report}`: {outcomes}. When you cannot finish, report `{{"outcome": "blocked", "summary": "<why>"}}` instead. Legacy QA reports with `outcome=defect,title,summary` and generic `blocked,summary` remain accepted."""


sessions.instruction_sections.append(_work_section)


# --- capacity: what the assignment expert and the quota guard read ------------------------------


def _quota(connection_id: str) -> dict[str, Any]:
    """The latest account limits any live session of this account read; empty when none did."""
    windows: dict[str, Any] = {}
    for session in get_registry().all():
        if str(session.connection_id) == connection_id and isinstance(session.usage.get("quota"), dict):
            windows.update(session.usage["quota"])
    return windows


def _highest_percent(connection_id: str, snapshot: Json | None = None) -> float | None:
    windows = _quota(connection_id) if snapshot is None else snapshot
    percents = [value.get("percent") for value in windows.values() if isinstance(value, dict)]
    known = [float(percent) for percent in percents if isinstance(percent, (int, float))]
    return max(known) if known else None


async def _refresh_account_quotas(caller: Caller, connections: list[Json]) -> dict[str, Json]:
    """Fetch fresh provider quota snapshots for a user-requested assignment recalculation."""
    async def refresh(connection: Json) -> tuple[str, Json] | None:
        provider = PROVIDERS[connection["provider"]]
        if provider.usage is None:
            return None
        connection_id = str(connection["id"])
        try:
            async with asyncio.timeout(35):
                credential = await mooi.fetch_agent_credential(caller, connection_id)
                async with agent_connections.account_usage_lock(connection_id):
                    snapshot = await provider.usage(credential, True)
            return (connection_id, snapshot) if snapshot else None
        except Exception:
            LOG.info("Could not refresh quota for account %s during manual reassignment",
                     connection_id, exc_info=True)
            return None

    # The bound includes time spent waiting for a concurrent usage read on the same account. Each
    # account refresh runs concurrently, while account_usage_lock prevents provider refresh races.
    try:
        async with asyncio.timeout(35):
            results = await asyncio.gather(*(refresh(connection) for connection in connections))
    except TimeoutError:
        LOG.info("Quota refresh timed out during manual reassignment")
        return {}
    return dict(result for result in results if result is not None)


def _quota_limit(settings: Json) -> float:
    limit = settings.get("quotaLimit")
    return float(limit if isinstance(limit, (int, float)) else DEFAULT_LIMITS["quotaLimit"])


async def _account_catalogs(caller: Caller, connections: list[Json]) -> dict[str, Json]:
    """Load each account once, concurrently; catalogs are never shared between connections."""
    async def load(connection: Json) -> tuple[str, Json]:
        connection_id = str(connection["id"])
        try:
            descriptor = PROVIDERS[connection["provider"]]
            # Preparation and configuration must share a task: account catalogs use ContextVars.
            async with asyncio.timeout(20):
                prepared = await descriptor.prepare(caller, connection_id)
                catalog = descriptor.configuration(prepared.id)
            return connection_id, {"models": [{"id": model["id"], "efforts": model.get("efforts", [])}
                                               for model in catalog.get("models", [])],
                                   "defaultModel": catalog.get("defaultModel")}
        except Exception:
            LOG.debug("No model catalog for account %s", connection_id, exc_info=True)
            return connection_id, {"models": [], "defaultModel": None}

    unique = {str(connection["id"]): connection for connection in connections}
    return dict(await asyncio.gather(*(load(connection) for connection in unique.values())))


async def _capacity(caller: Caller, quota_limit: float, favorites: dict[str, list[str]]) -> list[Json]:
    """Every usable account with its catalog, favorites marked, and whether its quota keeps it eligible."""
    connections = [connection for connection in await mooi.fetch_agent_connections(caller)
                   if connection.get("provider") in PROVIDERS and not connection.get("stale")]
    catalogs = await _account_catalogs(caller, connections)
    accounts = []
    for connection in connections:
        provider = connection["provider"]
        catalog = catalogs[str(connection["id"])]
        quota = {name: {"percent": value.get("percent"), "resetsAt": value.get("resetsAt")}
                 for name, value in _quota(str(connection["id"])).items() if isinstance(value, dict)}
        percent = _highest_percent(str(connection["id"]))
        preferred = set(favorites.get(provider) or [])
        accounts.append({"connectionId": connection["id"], "provider": provider,
                         "name": connection.get("name") or connection.get("accountLabel") or connection.get("label"),
                         "defaultAccount": bool(connection.get("defaultAccount")),
                         "quota": quota or None, "percent": percent,
                         "eligible": percent is None or percent < quota_limit,
                         **catalog,
                         "models": [{**model, "favorite": model["id"] in preferred}
                                    for model in catalog["models"]]})
    return accounts


# --- the runner ----------------------------------------------------------------------------------

_TICK_SECONDS = 20
# The project's limits when mic-mooi sends none; each project changes them in its factory settings.
DEFAULT_LIMITS: Json = {"quotaLimit": 85, "maxAttempts": 3, "maxQaRounds": 3, "maxResolutions": 2}

NUDGE = ("Your turn ended without reporting this factory task's outcome. Finish the task if anything is left, "
         "then report it as your Software factory task instructions say.")


@dataclass
class TaskRun:
    """One task's live session, as only this process knows it."""

    session_id: UUID
    item_id: UUID
    task_id: UUID
    project_id: UUID
    player_id: UUID
    qa: bool
    title: str
    auto_merge: bool
    issue_number: int | None
    attempts: int
    qa_rounds: int = 0
    outcome: TaskReport | None = None
    nudged: bool = False
    resolutions: int = 0
    # `working`, `resolving` (agentic merge running), `merging` or `review` (waiting for the player's merge).
    phase: str = "working"
    status: str = "running"
    recovered: bool = False
    recovery_expected: bool = False
    # The project's factory limits as of the latest tick (see `DEFAULT_LIMITS`).
    limits: Json = field(default_factory=lambda: dict(DEFAULT_LIMITS))
    recovery_lock: asyncio.Lock = field(default_factory=asyncio.Lock, repr=False)


_runs: dict[UUID, TaskRun] = {}
# The caller each started item acts as, renewed as it ages; rebuilt from mic-mooi after a restart.
_callers: dict[UUID, Caller] = {}
_tasks: set[asyncio.Task[None]] = set()
_runner_task: asyncio.Task[None] | None = None
_wake_event: asyncio.Event | None = None
_lock = asyncio.Lock()
_last_recovery_cleanup = 0.0
_LAUNCH_QUOTA_TTL = 300
_launch_quota_cache: dict[str, tuple[float, Json | None]] = {}


def wake() -> None:
    if _wake_event is not None:
        _wake_event.set()


def _background(coro: Coroutine[Any, Any, None]) -> None:
    task = asyncio.create_task(coro)
    _tasks.add(task)
    task.add_done_callback(_tasks.discard)


async def _caller(item_id: UUID, player_id: UUID, auth_session_id: str | None) -> Caller | None:
    caller = _callers.get(item_id)
    if caller is None and auth_session_id:
        caller = Caller(player_id=player_id, session_id=UUID(auth_session_id), role="", token="", expires_at=0)
    if caller is None:
        return None
    try:
        caller = await mooi.renewed(caller)
    except ApiException:
        LOG.warning("Work item %s lost the sign-in it runs as", item_id)
        _callers.pop(item_id, None)
        return None
    _callers[item_id] = caller
    return caller


async def _report(run_or_task: TaskRun | Json, status_: str, *, session_id: UUID | None = None,
                  attempts: int | None = None, report: str | None = None, assignment: Json | None = None,
                  merge_sha: str | None = None, qa_handoff: Json | None = None) -> Json:
    """Saves a task's progress in mic-mooi and announces its item."""
    if isinstance(run_or_task, TaskRun):
        task_id, player_hint = run_or_task.task_id, run_or_task.player_id
        session_id = session_id if session_id is not None else run_or_task.session_id
        attempts = run_or_task.attempts if attempts is None else attempts
        run_or_task.status = status_
    else:
        task_id, player_hint = UUID(run_or_task["id"]), None
        attempts = run_or_task.get("attempts", 0) if attempts is None else attempts
    body = {"status": status_, "sessionId": str(session_id) if session_id else None, "attempts": attempts,
            "report": report[:50_000] if report else None, "assignment": assignment, "mergeSha": merge_sha,
            "qaHandoff": qa_handoff}
    answer = await mooi.report_task(task_id, body)
    player_id = UUID(answer["playerId"]) if answer.get("playerId") else player_hint
    if player_id is not None:
        _publish_item(player_id, answer.get("item"))
    return answer.get("item") or {}


async def _task_recovery(task_id: UUID, attempt: int) -> Json | None:
    try:
        recovery = await mooi.fetch_task_recovery(task_id, attempt)
    except ApiException as failure:
        if failure.status_code == 404:
            return None
        raise
    if str(recovery.get("taskId")) != str(task_id) or recovery.get("attempt") != attempt:
        return {**recovery, "snapshotValid": False,
                "validityReason": "Saved recovery metadata does not match this task attempt"}
    return recovery


async def _retire_task_recovery(owner_id: UUID, project_id: UUID, item_id: UUID, task_id: UUID,
                                attempts: int) -> None:
    for attempt in range(attempts + 1):
        with suppress(Exception):
            await workspaces.delete_recovery_attempt(owner_id, project_id, item_id, task_id, attempt)


def _recovery_problem(recovery: Json | None) -> str | None:
    if recovery is None:
        return "No saved recovery snapshot is available; the task was not restarted to avoid losing work."
    if not recovery.get("snapshotValid"):
        reason = _handoff_text(str(recovery.get("validityReason") or "The saved snapshot is marked unusable"))
        return f"The saved task state cannot be restored safely: {reason}"
    if not all(recovery.get(key) for key in ("branch", "baseCommit", "snapshotRevision")):
        return "The saved task state is incomplete and cannot be restored safely."
    return None


def _recovery_context(recovery: Json, item: Json, task: Json, player_id: UUID) -> Json:
    return {
        **recovery,
        "ownerId": str(player_id),
        "projectId": item["projectId"],
        "itemId": item["id"],
        "taskId": task["id"],
    }


async def _runner_loop() -> None:
    global _wake_event, _last_recovery_cleanup
    _wake_event = asyncio.Event()
    while True:
        try:
            await _tick()
            if asyncio.get_running_loop().time() - _last_recovery_cleanup > 3600:
                await workspaces.cleanup_recovery()
                _last_recovery_cleanup = asyncio.get_running_loop().time()
        except asyncio.CancelledError:
            raise
        except Exception:
            LOG.warning("The factory runner could not advance; retrying", exc_info=True)
        try:
            await asyncio.wait_for(_wake_event.wait(), timeout=_TICK_SECONDS)
        except TimeoutError:
            pass
        _wake_event.clear()


async def _tick() -> None:
    entries = await mooi.fetch_running_work()
    async with _lock:
        limits: dict[UUID, int] = {}
        synchronized: dict[UUID, Json] = {}
        for entry in entries:
            try:
                item = entry["item"]
                if item.get("status") != "running":
                    continue
                if item.get("issueNumber") is not None:
                    item_id, player_id = UUID(item["id"]), UUID(entry["playerId"])
                    project_id = UUID(item["projectId"])
                    caller = await _caller(item_id, player_id, entry.get("authSessionId"))
                    if caller is None:
                        continue
                    if project_id not in synchronized:
                        synchronized[project_id] = await _sync_issues(caller, project_id)
                    entry = {**entry, "item": next(
                        (row for row in synchronized[project_id].get("items", []) if row["id"] == item["id"]), item)}
                await _advance(entry, limits)
            except asyncio.CancelledError:
                raise
            except Exception:
                LOG.warning("Work item %s could not advance", (entry.get("item") or {}).get("id"), exc_info=True)


async def _advance(entry: Json, limits: dict[UUID, int]) -> None:
    item = entry["item"]
    if item.get("status") != "running":
        return
    item_id, player_id = UUID(item["id"]), UUID(entry["playerId"])
    limits = {**DEFAULT_LIMITS, **(entry.get("settings") or {})}
    for run in _runs.values():
        if run.item_id == item_id:
            run.limits = limits
    caller = await _caller(item_id, player_id, entry.get("authSessionId"))
    if caller is None:
        return
    registry = get_registry()
    tasks = item["tasks"]
    recoveries: dict[str, Json] = {}

    # A missing session is resumable only from a validated durable snapshot. Never turn a lost
    # workspace into a clean launch: the user gets an explicit attention state instead.
    for task in tasks:
        session_id = task.get("sessionId")
        if task["status"] not in ("running", "merging", "review", "attention") or not session_id:
            continue
        live = registry.get(UUID(session_id))
        if live is not None and live.closing:
            # close_session keeps the session registered until its recovery handoff and workspace
            # removal finish, so wait rather than racing a launch against the old checkout.
            continue
        if live is not None and not live.closing:
            if live.status == STATUS_FAILED:
                run = _runs.get(live.id)
                if task.get("qaOf") and isinstance(task.get("qaReport"), dict):
                    try:
                        report = TaskReport.model_validate(task["qaReport"])
                    except Exception:
                        LOG.warning("Could not restore saved QA report for task %s", task["id"], exc_info=True)
                        item = await _report(task, "attention", session_id=None,
                                             report="Its saved QA result is invalid and needs attention")
                    else:
                        answer = await mooi.report_defect(UUID(task["id"]), _qa_delivery_payload(report))
                        item = answer.get("item") or item
                        _publish_item(player_id, item)
                        await _retire_task_recovery(player_id, UUID(item["projectId"]), UUID(item["id"]),
                                                    UUID(task["id"]), task["attempts"])
                    if run is not None:
                        await _close(run)
                    else:
                        await sessions.close_session(live.id)
                    tasks = item.get("tasks", tasks)
                    continue
                recovery_failure = bool(run and run.recovery_expected)
                if recovery_failure:
                    reason = live.detail or "The saved task state could not be restored"
                    item = await _report(task, "attention", session_id=None, attempts=task["attempts"],
                                         report=f"Task recovery could not be restored safely: {reason}")
                    await _close(run)
                    tasks = item.get("tasks", tasks)
                else:
                    # Let normal close lifecycle save this workspace, then the next tick will restore
                    # only that durable handoff. A provisioning failure before cloning yields attention.
                    await sessions.close_session(live.id)
                    _runs.pop(live.id, None)
                continue
            if UUID(session_id) not in _runs:
                _adopt(item, task, live, player_id, limits)
            continue
        if task.get("qaOf") and isinstance(task.get("qaReport"), dict):
            # QA's structured delivery is durable on the task itself. It can be committed safely
            # without reopening the read-only QA workspace; delivery IDs make replay idempotent.
            _runs.pop(UUID(session_id), None)
            try:
                report = TaskReport.model_validate(task["qaReport"])
            except Exception:
                LOG.warning("Could not restore saved QA report for task %s", task["id"], exc_info=True)
                item = await _report(task, "attention", session_id=None,
                                     report="Its saved QA result is invalid and needs attention")
            else:
                answer = await mooi.report_defect(UUID(task["id"]), _qa_delivery_payload(report))
                item = answer.get("item") or item
                _publish_item(player_id, item)
                await _retire_task_recovery(player_id, UUID(item["projectId"]), UUID(item["id"]),
                                            UUID(task["id"]), task["attempts"])
            tasks = item.get("tasks", tasks)
            continue
        _runs.pop(UUID(session_id), None)
        recovery = await _task_recovery(UUID(task["id"]), task["attempts"])
        # `attempts` never rewinds (snapshots are keyed by it); Retry and Start move `attemptsBase` instead.
        attempts = task["attempts"] + 1
        if attempts - task.get("attemptsBase", 0) > limits["maxAttempts"]:
            problem = _recovery_problem(recovery)
            detail = "Its session ended several times before finishing. Start the item again to retry."
            if problem:
                detail = f"{detail} {problem}"
            item = await _report(task, "attention", attempts=attempts,
                                 report=detail)
        elif (problem := _recovery_problem(recovery)) is not None:
            item = await _report(task, "attention", attempts=attempts,
                                 report=f"Task recovery needs attention: {problem}")
        else:
            item = await _report(task, task["status"] if task["status"] in ("review", "merging") else "planned",
                                 attempts=attempts)
            recoveries[task["id"]] = recovery
        tasks = item.get("tasks", tasks)

    if item.get("dependsOn") and not await _dependency_done(caller, item):
        return
    done = {task["id"] for task in tasks if task["status"] == "done"}
    if item.get("status") != "running":
        return
    ready = [task for task in tasks if task["status"] in ("planned", "queued", "review", "merging")
             and not task.get("sessionId")
             and all(dependency in done for dependency in task["dependsOn"])]
    for task in ready:
        if not await _has_room(caller, player_id, limits):
            await _wait(task, "Waiting for a free session")
            continue
        await _launch(item, task, caller, player_id, limits, recovery=recoveries.get(task["id"]))


async def _dependency_done(caller: Caller, item: Json) -> bool:
    work = await mooi.fetch_work(caller, UUID(item["projectId"]))
    other = next((entry for entry in work.get("items", []) if entry["id"] == item["dependsOn"]), None)
    return other is None or other["status"] == "done"


async def _has_room(caller: Caller, player_id: UUID, limits: dict[UUID, int]) -> bool:
    if player_id not in limits:
        limits[player_id] = await mooi.fetch_session_limit(caller)
    registry = get_registry()
    return (registry.count() < get_settings().max_sessions
            and registry.owned(player_id) < limits[player_id])


def _adopt(item: Json, task: Json, session: Session, player_id: UUID, limits: Json, *,
           recovery: Json | None = None) -> TaskRun:
    run = TaskRun(session_id=session.id, item_id=UUID(item["id"]), task_id=UUID(task["id"]),
                  project_id=UUID(item["projectId"]), player_id=player_id, qa=task["qaOf"] is not None,
                  title=task["title"], auto_merge=bool(task["autoMerge"]), issue_number=item.get("issueNumber"),
                  attempts=task["attempts"],
                  qa_rounds=task.get("qaRounds", 0),
                  status=task["status"], phase="review" if task["status"] == "review" else "working", limits=limits,
                  recovered=recovery is not None, recovery_expected=recovery is not None)
    if run.qa and task.get("status") not in ("planned", "queued") and isinstance(task.get("qaReport"), dict):
        try:
            run.outcome = TaskReport.model_validate(task["qaReport"])
        except Exception:
            LOG.warning("Could not restore saved QA report for task %s", run.task_id, exc_info=True)
    elif run.qa and recovery and recovery.get("reportedOutcome") in ("passed", "blocked"):
        summary = recovery.get("reportedSummary") or recovery.get("summary") or ""
        run.outcome = TaskReport(
            outcome=recovery["reportedOutcome"], summary=summary,
            blockerCause=summary if recovery["reportedOutcome"] == "blocked" else None,
        )
    elif recovery and recovery.get("reportedOutcome") == "done":
        run.outcome = TaskReport(
            outcome="done", summary=recovery.get("reportedSummary") or recovery.get("summary") or "",
            nextStep=recovery.get("nextStep") or "", files=recovery.get("files") or [],
            verifications=recovery.get("verifications") or [], obstacles=recovery.get("obstacles") or [],
            preparation=recovery.get("reportedPreparation") or "",
        )
    _runs[session.id] = run
    return run


async def _wait(task: Json, reason: str) -> None:
    """Queues a task that cannot launch yet; saying it again on every tick would only add noise."""
    if task["status"] != "queued" or task.get("report") != reason:
        await _report(task, "queued", report=reason)


async def _launch(item: Json, task: Json, caller: Caller, player_id: UUID, limits: Json, *,
                  recovery: Json | None = None) -> None:
    if recovery is None and not (task.get("qaOf") and task["status"] in ("planned", "queued")):
        source_attempt = max(0, task["attempts"] - 1) if task["attempts"] else 0
        recovery = await _task_recovery(UUID(task["id"]), source_attempt)
    if recovery is not None:
        problem = _recovery_problem(recovery)
        if problem:
            await _report(task, "attention", session_id=None,
                          report=f"Task recovery needs attention: {problem}")
            return
        if (task.get("qaOf") and recovery.get("reportedOutcome") == "defect"
                and not isinstance(task.get("qaReport"), dict)):
            await _report(task, "attention", session_id=None,
                          report="Saved QA findings are incomplete; they cannot be safely recreated from the summary.")
            return
    assignment = await _assign(caller, task, _quota_limit(limits))
    if assignment is None:
        await _wait(task, "Waiting for an account with the requested model and quota left")
        return
    # Assignment can take time; refresh GitHub and the locked item before opening a session.
    if item.get("issueNumber") is not None:
        item = await mooi.send_work(caller, "PUT", UUID(item["projectId"]),
                                    f"/issues/{item['issueNumber']}/item", github=True) or {}
        _publish_item(player_id, item)
        task = next((row for row in item.get("tasks", []) if row["id"] == task["id"]), {})
        if (item.get("status") != "running"
                or task.get("status") not in ("planned", "queued", "review", "merging")
                or task.get("sessionId")):
            return
    key = re.sub(r"[^a-z0-9-]+", "-", task["key"].lower()).strip("-")[:40] or "task"
    branch = recovery["branch"] if recovery else f"factory/{key}-{uuid4().hex[:6]}"
    recovery_context = _recovery_context(recovery, item, task, player_id) if recovery else None
    qa_report_pending = bool(task.get("qaOf") and task["status"] not in ("planned", "queued")
                             and task.get("sessionId") and isinstance(task.get("qaReport"), dict))
    recovered_outcome = ((task.get("qaReport") or {}).get("outcome")
                         if qa_report_pending else (recovery or {}).get("reportedOutcome"))
    completed_report = recovered_outcome in ("done", "passed", "defect") or (
        task.get("qaOf") and recovered_outcome == "blocked")
    initial_message = None if completed_report else _brief(item, task, recovery)
    try:
        session = await sessions._open_session(CreateSessionRequest(
            projectId=UUID(item["projectId"]), provider=assignment["provider"],
            connectionId=UUID(assignment["connectionId"]), branch=branch, model=assignment.get("model"),
            effort=assignment.get("effort"), initialMessage=initial_message), caller, recovery=recovery_context)
    except ApiException as failure:
        if failure.status_code == status.HTTP_409_CONFLICT:
            await _wait(task, failure.message)
            return
        await _report(task, "attention", report=f"Its session could not start: {failure.message}")
        return
    # Registered before provisioning builds the agent's instructions, which read it.
    launch_status = task["status"] if task["status"] in ("review", "merging") else "running"
    run_task = {**task, "status": launch_status}
    if task["status"] in ("planned", "queued"):
        run_task["qaReport"] = None
    run = _adopt(item, run_task, session, player_id, limits, recovery=recovery)
    updated = await _report(task, launch_status, session_id=session.id, attempts=task["attempts"],
                            assignment=assignment,
                            report=(f"Resuming saved progress: {_handoff_text(recovery.get('summary') or recovery.get('nextStep') or 'restored task state')}"
                                    if recovery else None))
    saved = next((row for row in updated.get("tasks", []) if row["id"] == task["id"]), {})
    if saved.get("sessionId") != str(session.id):
        # Closure can race another service's write: explicitly close a session whose launch was rejected.
        await _close(run)
        return
    LOG.info("Work item %s launched task %s in session %s", item["id"], task["key"], session.id)


async def _assign(caller: Caller, task: Json, quota_limit: float) -> Json | None:
    """The task's assignment, or its first alternative, on an account with quota left. An unnamed account
    is the provider's default one; an account running low is swapped for another of the same provider.
    Quota is checked again here, at launch: no saved preference (favorite model, named account) ever
    launches on an account whose known quota reached `quota_limit` or whose catalog lacks the model."""
    connections = [connection for connection in await mooi.fetch_agent_connections(caller)
                   if connection.get("provider") in PROVIDERS and not connection.get("stale")]
    catalogs, snapshots = await asyncio.gather(
        _account_catalogs(caller, connections), _launch_quota_snapshots(caller, connections))
    return _select_assignment(task, connections, quota_limit, catalogs, snapshots)


async def _launch_quota_snapshots(caller: Caller, connections: list[Json]) -> dict[str, Json]:
    """Refresh provider quota for accounts without live session data, caching results briefly."""
    now = asyncio.get_running_loop().time()
    missing = [connection for connection in connections
               if _highest_percent(str(connection["id"])) is None]
    pending: list[Json] = []
    snapshots: dict[str, Json] = {}
    for connection in missing:
        connection_id = str(connection["id"])
        cached = _launch_quota_cache.get(connection_id)
        if cached and now - cached[0] < _LAUNCH_QUOTA_TTL:
            if cached[1] is not None:
                snapshots[connection_id] = cached[1]
        else:
            pending.append(connection)
    if pending:
        refreshed = await _refresh_account_quotas(caller, pending)
        checked_at = asyncio.get_running_loop().time()
        for connection in pending:
            connection_id = str(connection["id"])
            snapshot = refreshed.get(connection_id)
            _launch_quota_cache[connection_id] = (checked_at, snapshot)
            if snapshot:
                snapshots[connection_id] = snapshot
        if len(_launch_quota_cache) > 1024:
            oldest = sorted(_launch_quota_cache, key=lambda key: _launch_quota_cache[key][0])
            for connection_id in oldest[:len(_launch_quota_cache) - 1024]:
                _launch_quota_cache.pop(connection_id, None)
    return snapshots


def _select_assignment(task: Json, connections: list[Json], quota_limit: float,
                       catalogs: dict[str, Json], quota_snapshots: dict[str, Json] | None = None, *,
                       quota_only: bool = False, alternatives_first: bool = False) -> Json | None:
    """Pick the first configured assignment/fallback whose account offers the model and has quota."""
    if not connections:
        return None
    snapshots = quota_snapshots or {}

    def account_percent(connection: Json) -> float | None:
        connection_id = str(connection["id"])
        if quota_only and connection_id not in snapshots:
            return None
        return _highest_percent(connection_id, snapshots.get(connection_id))

    primary = task.get("assignment") or {}
    alternatives = task.get("alternatives") or []
    candidates = [*alternatives, primary] if alternatives_first else [primary, *alternatives]
    for candidate in candidates:
        provider = candidate.get("provider") or next(
            (connection["provider"] for connection in connections if connection.get("defaultAccount")),
            connections[0]["provider"])
        accounts = [connection for connection in connections if connection["provider"] == provider]
        accounts.sort(key=lambda connection: (str(connection["id"]) != candidate.get("connectionId"),
                                              not connection.get("defaultAccount"),
                                              account_percent(connection) or 0.0))
        for account in accounts:
            percent = account_percent(account)
            catalog = catalogs[str(account["id"])]
            model = candidate.get("model") or catalog.get("defaultModel")
            if not any(entry["id"] == model for entry in catalog["models"]):
                continue
            if (percent is not None and percent < quota_limit) or (percent is None and not quota_only):
                return {"provider": provider, "connectionId": str(account["id"]),
                        "model": candidate.get("model"), "effort": candidate.get("effort")}
    return None


def _assigned_connection(assignment: Json, connections: list[Json]) -> str | None:
    provider = assignment.get("provider")
    accounts = [connection for connection in connections if connection.get("provider") == provider]
    if assignment.get("connectionId"):
        match = next((connection for connection in accounts
                      if str(connection["id"]) == str(assignment["connectionId"])), None)
    else:
        match = next((connection for connection in accounts if connection.get("defaultAccount")), None)
        if match is None and accounts:
            match = accounts[0]
    return str(match["id"]) if match else None


def _same_assignment(left: Json, right: Json) -> bool:
    fields = ("provider", "connectionId", "model", "effort")
    return all(left.get(field) == right.get(field) for field in fields)


def _spec_revision(spec: str) -> str:
    return hashlib.sha256(spec.encode()).hexdigest()[:16]


def _spec_sections(spec: str) -> list[tuple[str, str]]:
    """Lossless ATX heading boundaries, ignoring headings inside Markdown code fences.

    Existing prose-only specs remain one section; no inferred summaries or semantic cuts.
    Joining the returned contents reproduces the original stored spec exactly.
    """
    sections: list[tuple[str, str]] = []
    title = "Overview / shared decisions"
    lines: list[str] = []
    fence = ""
    for line in spec.splitlines(keepends=True):
        marker = re.match(r"^ {0,3}(`{3,}|~{3,})(.*)$", line)
        if marker:
            run, rest = marker.groups()
            if not fence:
                fence = run
            elif run[0] == fence[0] and len(run) >= len(fence) and not rest.strip():
                fence = ""
        heading = re.match(r"^ {0,3}#{1,6}\s+(.+?)\s*#*\s*$", line) if not fence else None
        if heading:
            if lines:
                sections.append((title, "".join(lines)))
            title, lines = heading[1], []
        lines.append(line)
    if lines:
        sections.append((title, "".join(lines)))
    return sections


def _brief(item: Json, task: Json, recovery: Json | None = None) -> str:
    spec = item.get("spec") or ""
    kind = {"feature": "Feature", "defect": "Defect", "debt": "Debt"}[item["kind"]]
    issue = f" (GitHub issue #{item['issueNumber']})" if item.get("issueNumber") else ""
    lines = [f"# {kind}{issue}: {item['title']}"]
    seen: dict[str, str] = {}

    def block(title: str, content: str | None, fallback: str = "") -> None:
        text = (content or "").strip() or fallback
        if not text:
            return
        if text in seen:
            lines.extend(["", f"## {title}", "", f"Same as {seen[text]} above."])
        else:
            lines.extend(["", f"## {title}", "", text])
            seen[text] = title

    # A duplicated issue body belongs to the task description, once, regardless of its size.
    description = (task.get("description") or "").strip()
    duplicated_spec = spec.strip() and (
        spec.strip() == description
        # Existing issue tasks prefix the exact body with "GitHub issue #N: <title>".
        or (item["kind"] in ("defect", "debt") and spec.strip() in description)
    )
    if spec.strip() and not duplicated_spec:
        if len(spec) <= 2_000 and not task.get("qaOf"):
            block("Specification", spec)
        else:
            lines += ["", "## Specification index", "",
                      (f"Revision: {_spec_revision(spec)}. Read sections through the spec access endpoint in "
                       "your Software factory instructions."),
                      ("Before working, read the goal, overview/shared decisions, sections named by the task, and all "
                       "sections needed for its decisions, acceptance and preparation. Select from this index; "
                       "do not assume omitted content. If relevance is unclear or there are no headings, read "
                       "the full spec. QA should select only shared rules and the behaviour/preparation it checks.")]
            lines += [f"- {index}: {title} ({len(content)} characters)"
                      for index, (title, content) in enumerate(_spec_sections(spec))]
    if task.get("qaOf"):
        tested = next((other for other in item.get("tasks", []) if other["id"] == task["qaOf"]), None)
        lines += ["", f"# Your task: {task['title']} (QA)", "",
                  (f"Check the work of \"{tested['title'] if tested else 'its task'}\". It is already merged into the "
                   "default branch together with everything merged before it, and this session's branch starts from "
                   "there. Do not change repository files.")]
        if tested:
            block("Task under review", tested.get("description"))
            block("Tested task acceptance criteria", tested.get("acceptance"), "Use the tested task description.")
            handoff = tested.get("qaHandoff")
            if isinstance(handoff, dict):
                lines += ["", "## Integrated change handoff (reported evidence, not proof)", "",
                          f"Merged commit: {tested.get('mergeSha') or 'missing; no verified commit SHA recorded'}"]
                block("Development summary", handoff.get("summary"), "(not reported)")
                block("Changed files", "\n".join(f"- {path}" for path in handoff.get("files", [])),
                      "(not reported)")
                block("Checks reported before QA", "\n".join(
                    f"- {value}" for value in handoff.get("verifications", [])), "(not reported)")
                block("Declared preparation", handoff.get("preparation"), "(not declared)")
                lines += ["", "This report is orientation only. Independently verify the integrated default branch "
                          "using the acceptance criteria and this QA task's preparation."]
            else:
                lines += ["", "## Legacy evidence limits", "",
                          f"Merged commit: {tested.get('mergeSha') or 'missing; no SHA is available to verify'}",
                          "Development summary, changed files, prior checks and declared preparation: unavailable "
                          "in this legacy task record. Do not infer them; validate independently from the criteria."]
            lines += ["", ("For bounded task handoff details, use GET `/sessions/{session_id}/work/agent/qa-handoff`. "
                            "It returns only the task this QA run checks; it never returns a diff or other tasks.")]
        else:
            lines += ["", ("The tested task is unavailable. Use the QA description and criteria below; read "
                           "the full spec if needed and report blocked if the expected behaviour cannot be established.")]
        block("QA preparation and scope", task.get("description"))
        block("QA acceptance criteria", task.get("acceptance"),
              "Use the tested task acceptance criteria and QA description." if tested else "Use the QA description.")
        lines += ["", ("Run the project the way its development deployment brief says (read it with the deployment "
                       "brief endpoint of your session instructions) and verify the criteria through Playwright, as a "
                       "user would. When the project is not a web application, verify them with the project's own means.")]
    else:
        lines += ["", f"# Your task: {task['title']}"]
        block("Description", task.get("description"))
        block("Acceptance criteria", task.get("acceptance"), "(none given)")
        lines += ["", ("Implement it completely, keep the project's checks and build passing, and commit nothing "
                        "else than this task.")]
    if recovery:
        lines += ["", "## Saved task handoff", "",
                  ("Continue from this restored workspace and the last saved progress below. Do not discard or "
                   "recreate existing changes. Treat the saved summary as progress context, not proof that acceptance "
                   "criteria pass; verify any remaining work and report the current result.")]
        block("Last progress", recovery.get("summary"))
        block("Next step", recovery.get("nextStep"))
        block("Files in the saved change", "\n".join(f"- {path}" for path in recovery.get("files", [])))
        block("Verifications already reported", "\n".join(
            f"- {value}" for value in recovery.get("verifications", [])))
        block("Known obstacles", "\n".join(f"- {value}" for value in recovery.get("obstacles", [])))
    return "\n".join(lines)


# --- reacting to the task sessions ---------------------------------------------------------------


def _on_session_event(session: Session, type_: str, data: Json) -> None:
    run = _runs.get(session.id)
    if run is None:
        return
    if type_ == EVENT_TURN_RESULT:
        _background(_after_turn(run))
    elif (type_ == EVENT_SESSION_STATUS and data.get("status") == STATUS_READY
          and run.recovered and run.outcome is not None):
        # Durable results are settled by the deterministic runner after the restored workspace is ready;
        # do not send the implementation brief again or ask the model to repeat completed work.
        _background(_after_turn(run))
    elif type_ in (EVENT_QUESTION_REQUEST, EVENT_PERMISSION_REQUEST):
        _background(_set_status(run, "attention", "The agent is waiting for your answer in its session"))
    elif ((type_ in (EVENT_QUESTION_RESOLVED, EVENT_PERMISSION_RESOLVED) and session.pending is None)
          or (type_ == "message.user" and run.status == "attention")):
        _background(_set_status(run, "running", None))
    elif type_ == EVENT_SESSION_STATUS and data.get("status") == STATUS_FAILED:
        _background(_set_status(run, "attention", "Its session failed: reconnect it from the session page"))
    elif type_ == EVENT_MERGE_COMPLETED:
        commit = data.get("commit")
        _background(_finish(run, f"Merged as {str(commit or '')[:10]}", merge_sha=commit))


sessions.session_listeners.append(_on_session_event)


def _handoff_text(value: str) -> str:
    """Keep recovery metadata compact and redact credentials and host-local paths."""
    text = workspaces._safe_handoff_text(value)
    return re.sub(r"(?<![A-Za-z0-9])/(?:home|tmp|var/tmp|workspace)/[^\s'\"`]+", "[local path]", text)[:20_000]


def _handoff_files(values: list[str]) -> list[str]:
    safe = []
    for value in values:
        path = PurePosixPath(value)
        if (not value or path.is_absolute() or ".." in path.parts or ".git" in path.parts
                or value.startswith("-") or "\0" in value or "\\" in value):
            continue
        safe.append(value[:1024])
    return sorted(set(safe))[:500]


def _qa_delivery_payload(report: TaskReport) -> Json:
    """Exclude task-recovery handoff fields from the separate QA delivery contract."""
    return report.model_dump(exclude_none=True, include={
        "outcome", "summary", "title", "deliveryId", "findings", "blockerCategory", "blockerCause",
    })


async def _persist_recovery(run: TaskRun, session: Session, *, report: TaskReport | None = None,
                            final_summary: str | None = None, final_outcome: str | None = None) -> None:
    """Capture one end-of-turn/handoff snapshot and persist its revisioned metadata for recovery_runner.

    The object bundle stays in workspaces' private store. Only opaque revision and compact, scrubbed
    metadata cross to mic-mooi. A failed capture is persisted as invalid evidence so no consumer can
    mistake missing/corrupt data for a recoverable workspace.
    """
    async with run.recovery_lock:
        summary = (report.summary or report.blockerCause or "") if report else (final_summary or "")
        next_step = report.next_step if report else ""
        files = _handoff_files(report.files if report else [])
        verifications = [_handoff_text(value)[:2000] for value in report.verifications[:200]] if report else []
        obstacles = [_handoff_text(value)[:2000] for value in report.obstacles[:200]] if report else []
        if report and report.outcome == "blocked" and report.blockerCause:
            category = report.blockerCategory or "unspecified"
            obstacles.insert(0, _handoff_text(f"Blocked ({category}): {report.blockerCause}")[:2000])
            obstacles = obstacles[:200]
        outcome = report.outcome if report else final_outcome
        title = report.title if report else None
        preparation = _handoff_text(report.preparation) if report else ""
        safe_summary = _handoff_text(summary)
        reported_summary = safe_summary if outcome else None
        safe_next_step = _handoff_text(next_step)
        branch = session.branch
        base_commit = session.base_commit or "0" * 40
        try:
            snapshot = await workspaces.capture_recovery(
                session.workspace, owner_id=run.player_id, project_id=run.project_id, item_id=run.item_id,
                task_id=run.task_id, attempt=run.attempts, branch=session.branch, base_commit=session.base_commit,
            )
            head_commit = snapshot.head_commit
            revision = snapshot.snapshot_revision
            saved_files = snapshot.files
            valid, reason = True, None
        except ApiException as failure:
            # The agent report remains durable, but an unavailable/unsafe snapshot is explicitly unusable.
            inspection = await workspaces.inspect(session.workspace)
            head_commit = inspection.head_commit or session.base_commit or "0" * 40
            revision = hashlib.sha256(
                f"invalid:{run.task_id}:{run.attempts}:{session.id}:{uuid4()}".encode()
            ).hexdigest()
            saved_files = []
            valid = False
            reason = _handoff_text(failure.message)[:2000] or "Recovery snapshot could not be validated"
        existing: Json | None
        try:
            existing = await mooi.fetch_task_recovery(run.task_id, run.attempts)
        except ApiException as failure:
            if failure.status_code != 404:
                raise
            existing = None
        if report is None and existing:
            outcome = outcome or existing.get("reportedOutcome")
            title = title or existing.get("reportedTitle")
            reported_summary = reported_summary or existing.get("reportedSummary")
            preparation = preparation or _handoff_text(existing.get("reportedPreparation") or "")
            if not safe_summary:
                safe_summary = "Workspace snapshot captured at turn end; no new handoff report was submitted."
        if not valid and existing and existing.get("snapshotValid"):
            # Keep the last known-good snapshot and its matching result intact; a report from an
            # uncaptured workspace must never be attached to older code.
            revision = existing["snapshotRevision"]
            head_commit = existing["headCommit"]
            branch = existing["branch"]
            base_commit = existing["baseCommit"]
            saved_files = existing.get("files") or []
            safe_summary = existing.get("summary") or ""
            safe_next_step = existing.get("nextStep") or ""
            verifications = existing.get("verifications") or []
            obstacles = existing.get("obstacles") or []
            outcome = existing.get("reportedOutcome")
            title = existing.get("reportedTitle")
            reported_summary = existing.get("reportedSummary")
            preparation = _handoff_text(existing.get("reportedPreparation") or "")
            valid = True
            reason = existing.get("validityReason")
        await mooi.save_task_recovery(run.task_id, {
            "attempt": run.attempts,
            "branch": branch,
            "baseCommit": base_commit,
            "headCommit": head_commit,
            "snapshotRevision": revision,
            "previousRevision": (existing or {}).get("snapshotRevision"),
            "summary": safe_summary,
            "nextStep": safe_next_step,
            "files": saved_files if valid else files,
            "verifications": verifications,
            "obstacles": obstacles,
            "reportedOutcome": outcome,
            "reportedTitle": _handoff_text(title)[:200] if title else None,
            "reportedSummary": reported_summary,
            "reportedPreparation": preparation or None,
            "snapshotValid": valid,
            "validityReason": reason,
        })


async def _before_workspace_cleanup(session: Session) -> None:
    """Last chance to preserve an active factory workspace before normal session removal."""
    run = _runs.get(session.id)
    if run is not None and not session.recovery_failed:
        await _persist_recovery(run, session, report=run.outcome)


sessions.workspace_cleanup_listeners.append(_before_workspace_cleanup)


async def _set_status(run: TaskRun, status_: str, report: str | None) -> None:
    async with _lock:
        if _runs.get(run.session_id) is run and run.status != status_ and run.phase != "merging":
            await _report(run, status_, report=report)


async def _after_turn(run: TaskRun) -> None:
    async with _lock:
        session = get_registry().get(run.session_id)
        if _runs.get(run.session_id) is not run or session is None or session.closing:
            return
        try:
            await _persist_recovery(run, session, report=run.outcome)
            if session.queue:
                return
            await _settle(run, session)
        except Exception as failure:
            LOG.warning("Task %s could not settle", run.task_id, exc_info=True)
            run.phase = "working"
            reason = failure.message if isinstance(failure, ApiException) else "Its work could not be wrapped up"
            with suppress(Exception):
                await _report(run, "attention", report=f"{reason}: continue in its session or start the item again")
    wake()


async def _settle(run: TaskRun, session: Session) -> None:
    if run.phase == "resolving":
        await _merge(run)
        return
    if run.phase == "review":
        return
    report = run.outcome
    if report is None:
        if not run.nudged:
            run.nudged = True
            await sessions.queue_message(run.session_id, SendMessageRequest(text=NUDGE), await _run_caller(run))
            return
        await _report(run, "attention", report="The agent stopped without reporting its outcome: continue in its session")
        return
    run.nudged = False
    if report.outcome == "blocked":
        if run.qa:
            answer = await mooi.report_defect(run.task_id, _qa_delivery_payload(report))
            _publish_item(run.player_id, answer.get("item"))
            await _retire_task_recovery(run.player_id, run.project_id, run.item_id, run.task_id, run.attempts)
            run.outcome = None
            await _close(run)
        else:
            run.outcome = None
            await _report(run, "attention", report=report.summary)
    elif report.outcome == "passed":
        if run.qa:
            answer = await mooi.report_defect(run.task_id, _qa_delivery_payload(report))
            _publish_item(run.player_id, answer.get("item"))
            await _retire_task_recovery(run.player_id, run.project_id, run.item_id, run.task_id, run.attempts)
            run.outcome = None
            await _close(run)
        else:
            run.outcome = None
            await _finish(run, report.summary, locked=True)
    elif report.outcome == "defect":
        answer = await mooi.report_defect(run.task_id, _qa_delivery_payload(report))
        _publish_item(run.player_id, answer.get("item"))
        await _retire_task_recovery(run.player_id, run.project_id, run.item_id, run.task_id, run.attempts)
        run.outcome = None
        await _close(run)
    elif run.auto_merge:
        await _merge(run)
    else:
        run.phase = "review"
        await _report(run, "review", report=f"{report.summary}\n\nReady for your merge from its session.")


async def _merge(run: TaskRun) -> None:
    """Merges a finished development task: clean merges at once, conflicts through the agent first."""
    caller = await _run_caller(run)
    preview = await sessions.check_merge(run.session_id, caller)
    if preview.state == "up_to_date":
        await _finish(run, "Nothing to merge: the default branch already holds this work", locked=True)
        return
    if preview.state == "conflicts":
        if run.resolutions >= run.limits["maxResolutions"]:
            await _report(run, "attention", report="Its merge conflicts could not be resolved automatically")
            return
        run.resolutions += 1
        run.phase = "resolving"
        await _report(run, "merging", report="Resolving merge conflicts with the agent")
        await sessions.resolve_merge_conflicts(run.session_id, caller)
        return
    message = (preview.suggestedMessage or f"{'fix' if run.issue_number else 'feat'}: {run.title}").strip()
    if run.issue_number:
        message = f"{message[:180]} (fixes #{run.issue_number})"
    run.phase = "merging"
    await _report(run, "merging", report=None)
    result = await sessions.merge_session(run.session_id, MergeRequest(message=message[:200]), caller)
    if result.state == "conflicts":
        run.phase = "working"
        await _merge(run)
    # A merged result is finished by the `merge.completed` event.


async def _finish(run: TaskRun, report: str, *, locked: bool = False, merge_sha: str | None = None) -> None:
    """The task is done: saved as such, its session closed, and the runner looks for what it unblocked."""
    if not locked:
        async with _lock:
            await _finish(run, report, locked=True, merge_sha=merge_sha)
        wake()
        return
    if _runs.get(run.session_id) is not run:
        return
    session = get_registry().get(run.session_id)
    if session is not None:
        await _persist_recovery(run, session, final_summary=report,
                                final_outcome="passed" if run.qa else "done")
    handoff = None
    if not run.qa:
        outcome = run.outcome
        handoff = {
            "summary": _handoff_text(outcome.summary if outcome else report),
            "files": _handoff_files(outcome.files if outcome else []),
            "verifications": [
                _handoff_text(value)[:2_000] for value in (outcome.verifications if outcome else [])[:200]
            ],
            "preparation": _handoff_text(outcome.preparation if outcome else ""),
        }
    item = await _report(run, "done", report=report, merge_sha=merge_sha if not run.qa else None,
                         qa_handoff=handoff)
    await _retire_task_recovery(run.player_id, run.project_id, run.item_id, run.task_id, run.attempts)
    await _close(run)
    if run.issue_number and item.get("status") == "done":
        # A merge titled by the player may not name the issue: closing it here is idempotent.
        with suppress(Exception):
            caller = await _run_caller(run)
            changed = await mooi.send_work(caller, "PATCH", run.project_id, f"/issues/{run.issue_number}",
                                           {"state": "closed"}, github=True) or {}
            _publish_issue(run.player_id, run.project_id, changed.get("issue"))


async def _close(run: TaskRun) -> None:
    _runs.pop(run.session_id, None)
    _background(sessions.close_session(run.session_id))


async def _run_caller(run: TaskRun) -> Caller:
    caller = await _caller(run.item_id, run.player_id, None)
    if caller is None:
        raise ApiException.conflict("The sign-in that started this work ended: start it again")
    return caller


def start_runner() -> None:
    """Called once from `main.py`'s lifespan; `close_runner` stops it again."""
    global _runner_task
    if _runner_task is None:
        _runner_task = asyncio.create_task(_runner_loop())


async def close_runner() -> None:
    global _runner_task
    tasks = [task for task in (_runner_task, *_tasks) if task is not None]
    for task in tasks:
        task.cancel()
    await asyncio.gather(*tasks, return_exceptions=True)
    _runner_task = None
