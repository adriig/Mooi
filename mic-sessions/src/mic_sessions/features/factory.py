"""Feature: the software factory — a project's features, defects and debt, and the runner that builds them.

Work items live in `mic-mooi` (features) and on GitHub (defects and debt, as labelled issues); this
feature is the one path to them, from the Planification and Board views and from session agents alike, and
announces every change on the player's live channel:

- `work.item` `{projectId, item}`: an item was created or changed (its tasks travel with it);
- `work.removed` `{projectId, itemId}`;
- `work.issue` `{projectId, issue}`: a defect or debt issue was created or changed;
- `work.settings` `{projectId, settings}`: the project's factory limits changed;
- `work.definition` `{projectId, number, definition}`: an issue's definition changed;
- `work.definition.project` `{projectId, definition}`: the project's definition or roadmap changed.

Every plain session's agent decides whether a request is a single task (done in that very session), a
feature (which it designs, as orchestrator, into tasks with blocking dependencies), a defect or debt,
and records the last three through the agent endpoints below, guided by its session instructions.

**The runner.** Starting an item hands it to a loop that runs with no agent orchestrating and no device
connected, acting for the sign-in that pressed Start: each unblocked task gets its own new session (one
task, one session) within the account's session limit, on the account its assignment names unless that
account runs low on quota (then its alternatives, in order). A development task ends with its merge,
automatic (agentic on conflicts) or by the player; a QA task ends when its acceptance criteria are met,
and a defect it finds becomes a fix task the QA waits for. A finished task's session is closed. The
runner reconciles running and paused items from `mic-mooi` on every tick, adopting live sessions and
preserving durable results across restarts. Paused items open no sessions and defer auto-merge until Resume.

**Concurrency.** There is no global lock: each item advances in its own task, under its own lock, so
one player's slow merge never holds back another's work. Locks guard reading and saving state only;
snapshots, merges and pushes run unlocked under a per-run claim, and the state is checked again when
they return.
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import re
import time
import unicodedata
import weakref
from collections.abc import Coroutine
from contextlib import suppress
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import PurePosixPath
from typing import Annotated, Any, Literal, overload
from urllib.parse import quote
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
from mic_sessions.shared.agents import PROVIDERS, STATUS_FAILED, STATUS_READY, STATUS_WAITING, STATUS_WORKING
from mic_sessions.shared.auth import Caller, current_caller
from mic_sessions.shared.env import get_settings
from mic_sessions.shared.web import ApiException

LOG = logging.getLogger("factory")

router = APIRouter(tags=["work"])

EVENT_WORK_ITEM = "work.item"
EVENT_WORK_REMOVED = "work.removed"
EVENT_WORK_ISSUE = "work.issue"
EVENT_WORK_SETTINGS = "work.settings"
EVENT_WORK_DEFINITION = "work.definition"
EVENT_WORK_DEFINITION_PROJECT = "work.definition.project"

Json = dict[str, Any]


@overload
def _publish_item(player_id: UUID, item: Json) -> Json: ...


@overload
def _publish_item(player_id: UUID, item: None) -> None: ...


def _publish_item(player_id: UUID, item: Json | None) -> Json | None:
    if item:
        item = _session_visible_item(item)
        sessions._channels.publish(player_id, EVENT_WORK_ITEM, {"projectId": item["projectId"], "item": item})
    return item


def _session_visible_item(item: Json) -> Json:
    registry = get_registry()
    return {**item, "tasks": [
        {**task, "sessionAvailable": bool(task.get("sessionId")
                                           and registry.get(UUID(task["sessionId"])) is not None)}
        for task in item.get("tasks", [])
    ]}


def _publish_issue(player_id: UUID, project_id: UUID, issue: Json | None) -> None:
    if issue:
        sessions._channels.publish(player_id, EVENT_WORK_ISSUE, {"projectId": str(project_id), "issue": issue})


# --- one path for every change, whichever side asks ---------------------------------------------


async def _create_feature(caller: Caller, project_id: UUID, draft: Json) -> Json:
    return _publish_item(caller.player_id, await mooi.send_work(caller, "POST", project_id, "/features", draft) or {})


async def _update_item(caller: Caller, project_id: UUID, item_id: UUID, update: Json) -> Json:
    return _publish_item(caller.player_id, await mooi.send_work(caller, "PATCH", project_id, f"/items/{item_id}", update) or {})


async def _change_requests(caller: Caller, project_id: UUID, item_id: UUID) -> list[Json]:
    return await mooi.fetch_work_changes(caller, project_id, item_id)


async def _request_change(caller: Caller, project_id: UUID, item_id: UUID, draft: Json) -> Json:
    async with _lock_for(_item_locks, item_id):
        item = await mooi.send_work(caller, "POST", project_id, f"/items/{item_id}/change-requests", draft)
        _touch(item_id)
        _publish_item(caller.player_id, item)
    wake()
    return item or {}


async def _decide_change(caller: Caller, project_id: UUID, item_id: UUID, change_id: UUID, decision: Json) -> Json:
    async with _lock_for(_item_locks, item_id):
        item = await mooi.send_work(caller, "POST", project_id,
                                    f"/items/{item_id}/change-requests/{change_id}/decision", decision)
        _touch(item_id)
        _publish_item(caller.player_id, item)
    wake()
    return item or {}


async def _add_task(caller: Caller, project_id: UUID, item_id: UUID, draft: Json) -> Json:
    return _publish_item(caller.player_id,
                         await mooi.send_work(caller, "POST", project_id, f"/items/{item_id}/tasks", draft) or {})


async def _update_task(caller: Caller, project_id: UUID, item_id: UUID, task_id: UUID, update: Json) -> Json:
    return _publish_item(caller.player_id, await mooi.send_work(caller, "PATCH", project_id,
                                                               f"/items/{item_id}/tasks/{task_id}", update) or {})


async def _delete_task(caller: Caller, project_id: UUID, item_id: UUID, task_id: UUID) -> Json:
    return _publish_item(caller.player_id, await mooi.send_work(caller, "DELETE", project_id,
                                                               f"/items/{item_id}/tasks/{task_id}") or {})


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


async def _restart_clean_task(caller: Caller, project_id: UUID, item_id: UUID, task_id: UUID) -> Json:
    # Serialize with this runner; mic-mooi validates ownership and eligibility under its item lock.
    async with _lock_for(_item_locks, item_id):
        work = await mooi.fetch_work(caller, project_id)
        item = next((row for row in work.get("items", []) if row["id"] == str(item_id)), None)
        task = next((row for row in item.get("tasks", []) if row["id"] == str(task_id)), None) if item else None
        if task and task.get("sessionId"):
            session_id = UUID(task["sessionId"])
            if get_registry().get(session_id) is not None:
                raise ApiException.conflict("The task still has a registered session")
            # Only the session registry can establish that this reference is stale. Clear it through
            # the same durable runner operation before mic-mooi applies its structured eligibility.
            answer = await mooi.report_task(task_id, {
                "status": "attention", "sessionId": None, "attempts": task["attempts"],
                "report": task.get("report"), "recoveryState": (task.get("recoveryDisposition") or {}).get("state"),
            })
            _touch(item_id)
            _publish_item(caller.player_id, answer.get("item"))
        item = await mooi.send_work(caller, "POST", project_id,
                                    f"/items/{item_id}/tasks/{task_id}/restart-clean")
        _touch(item_id)
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
    # The create response is authoritative for the new issue. Let the runner reconcile any
    # already-running work against it without waiting for the normal project polling interval.
    _issue_sync_at.pop(project_id, None)
    wake()
    return created


async def _change_definition(caller: Caller, project_id: UUID, number: int, action: str, payload: Json) -> Json:
    """Views and definition agents share proposal persistence; acceptance belongs to the user."""
    async with _lock_for(_project_locks, project_id):
        result = await mooi.send_work(caller, "PUT" if action == "proposal" else "POST", project_id,
                                      f"/issues/{number}/definition/{action}", payload, github=True) or {}
        for issue in result.get("issues", []):
            _publish_issue(caller.player_id, project_id, issue)
        for item in result.get("items", []):
            _publish_item(caller.player_id, item)
        sessions._channels.publish(caller.player_id, EVENT_WORK_DEFINITION, {
            "projectId": str(project_id), "number": number, "definition": result.get("definition"),
        })
        return result


async def _change_project_definition(caller: Caller, project_id: UUID, method: str, path: str,
                                     payload: Json) -> Json:
    """Views and agents share proposals and roadmap links; acceptance and reopening belong to the user."""
    async with _lock_for(_project_locks, project_id):
        result = await mooi.send_work(caller, method, project_id, f"/definition{path}", payload) or {}
        for item in result.get("items", []):
            _publish_item(caller.player_id, item)
        sessions._channels.publish(caller.player_id, EVENT_WORK_DEFINITION_PROJECT, {
            "projectId": str(project_id), "definition": result.get("definition"),
        })
        return result


def _project_file(path: str) -> bool:
    """Whether a versioned path is project code for the new-project heuristic: design documents, GitHub
    configuration and the root README, LICENSE, .gitignore, AGENTS.md and CLAUDE.md do not count."""
    if path.startswith(("docs/", ".github/")):
        return False
    if "/" in path:
        return True
    name = path.lower()
    return name not in (".gitignore", "agents.md", "claude.md") and name.split(".")[0] not in ("readme", "license")


async def _new_project(caller: Caller, project_id: UUID, work: Json) -> bool:
    """A project with no feature and at most `_NEW_PROJECT_FILES` project files on its default branch is
    new: its first work is the project's definition. The count is cached briefly, since the views poll."""
    if any(item.get("kind") == "feature" for item in work.get("items", [])):
        return False
    async with _lock_for(_file_count_locks, project_id):
        cached = _file_counts.get(project_id)
        if cached is None or time.monotonic() - cached[0] >= _FILE_COUNT_TTL:
            try:
                project = await mooi.fetch_project(caller, project_id)
                token = await mooi.fetch_github_token(caller)
                files = await workspaces.tracked_files(project.full_name, project.default_branch, token)
                count: int | None = sum(1 for path in files if _project_file(path))
            except asyncio.CancelledError:
                raise
            except Exception:
                LOG.info("The files of project %s could not be counted", project_id, exc_info=True)
                count = None
            cached = (time.monotonic(), count)
            _file_counts[project_id] = cached
    return cached[1] is not None and cached[1] <= _NEW_PROJECT_FILES


async def _project_work(caller: Caller, project_id: UUID) -> Json:
    work = await mooi.fetch_work(caller, project_id)
    visible = {**work, "items": [_session_visible_item(item) for item in work.get("items", [])]}
    return {**visible, "newProject": await _new_project(caller, project_id, work)}


async def _sync_issues(caller: Caller, project_id: UUID) -> Json:
    """GitHub and factory state converge through the same operation for views, agents and runner."""
    # Throttle attempts as well as successful responses; repeated failures must not turn each
    # 20-second runner tick into another GitHub request.
    _issue_sync_at[project_id] = time.monotonic()
    result = await mooi.fetch_issues(caller, project_id)
    for issue in result.get("issues", []):
        _publish_issue(caller.player_id, project_id, issue)
    for item in result.get("items", []):
        _publish_item(caller.player_id, item)
    return result


async def _synced_issues(caller: Caller, project_id: UUID) -> Json:
    """One GitHub sync per project at a time; other projects sync in parallel."""
    async with _lock_for(_project_locks, project_id):
        return await _sync_issues(caller, project_id)


async def _sync_issues_if_due(caller: Caller, project_id: UUID) -> Json | None:
    """Keep background GitHub polling bounded while noticing external closes promptly."""
    async with _lock_for(_project_locks, project_id):
        # Checked under the project's lock, so a sync that just finished elsewhere counts.
        last_sync = _issue_sync_at.get(project_id)
        if last_sync is not None and time.monotonic() - last_sync < _ISSUE_SYNC_INTERVAL_SECONDS:
            return None
        return await _sync_issues(caller, project_id)


async def _save_settings(caller: Caller, project_id: UUID, settings: Json) -> Json:
    saved = await mooi.send_work(caller, "PUT", project_id, "/settings", settings) or {}
    sessions._channels.publish(caller.player_id, EVENT_WORK_SETTINGS, {"projectId": str(project_id), "settings": saved})
    return saved


# --- the player's views (Planification and Board) -----------------------------------------------


@router.get("/projects/{project_id}/work/settings")
async def work_settings(project_id: UUID, caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await mooi.send_work(caller, "GET", project_id, "/settings") or {}


@router.put("/projects/{project_id}/work/settings")
async def save_work_settings(project_id: UUID, settings: Annotated[Json, Body()],
                             caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _save_settings(caller, project_id, settings)


@router.get("/projects/{project_id}/work")
async def project_work(project_id: UUID, caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _project_work(caller, project_id)


@router.get("/projects/{project_id}/work/definition")
async def project_definition(project_id: UUID, caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await mooi.send_work(caller, "GET", project_id, "/definition") or {}


@router.put("/projects/{project_id}/work/definition/proposal")
async def propose_project_definition(project_id: UUID, proposal: Annotated[Json, Body()],
                                     caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _change_project_definition(caller, project_id, "PUT", "/proposal", proposal)


@router.post("/projects/{project_id}/work/definition/accept")
async def accept_project_definition(project_id: UUID, revision: Annotated[Json, Body()],
                                    caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _change_project_definition(caller, project_id, "POST", "/accept", revision)


@router.post("/projects/{project_id}/work/definition/reopen")
async def reopen_project_definition(project_id: UUID, revision: Annotated[Json, Body()],
                                    caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _change_project_definition(caller, project_id, "POST", "/reopen", revision)


@router.get("/projects/{project_id}/work/issues")
async def project_issues(project_id: UUID, caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _synced_issues(caller, project_id)


@router.post("/projects/{project_id}/work/features", status_code=status.HTTP_201_CREATED)
async def create_feature(project_id: UUID, draft: Annotated[Json, Body()],
                         caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _create_feature(caller, project_id, draft)


@router.get("/projects/{project_id}/work/items/{item_id}/change-requests")
async def change_requests(project_id: UUID, item_id: UUID,
                          caller: Annotated[Caller, Depends(current_caller)]) -> list[Json]:
    return await _change_requests(caller, project_id, item_id)


@router.post("/projects/{project_id}/work/items/{item_id}/change-requests", status_code=status.HTTP_201_CREATED)
async def request_change(project_id: UUID, item_id: UUID, draft: Annotated[Json, Body()],
                         caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _request_change(caller, project_id, item_id, draft)


@router.post("/projects/{project_id}/work/items/{item_id}/change-requests/{change_id}/decision")
async def decide_change(project_id: UUID, item_id: UUID, change_id: UUID, decision: Annotated[Json, Body()],
                        caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _decide_change(caller, project_id, item_id, change_id, decision)


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


@router.post("/projects/{project_id}/work/items/{item_id}/tasks/{task_id}/restart-clean")
async def restart_clean_task(project_id: UUID, item_id: UUID, task_id: UUID,
                             caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _restart_clean_task(caller, project_id, item_id, task_id)


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
    """Starts or resumes the item. The runner acts with the player's factory credential, issued by
    mic-mooi on every start, never with this sign-in: signing out of this device does not stop it."""
    async with _lock_for(_item_locks, item_id):
        item = _publish_item(caller.player_id,
                             await mooi.send_work(caller, "POST", project_id, f"/items/{item_id}/start"))
        _callers.pop(item_id, None)
        _credentials.pop(item_id, None)
    wake()
    return item or {}


@router.post("/projects/{project_id}/work/items/{item_id}/pause")
async def pause_item(project_id: UUID, item_id: UUID, caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    # Serialize Pause with the item's launches and merge claims: an operation already in flight finishes
    # before Pause takes effect, and no runner operation can use an earlier item state afterwards.
    async with _lock_for(_item_locks, item_id):
        item = _publish_item(caller.player_id,
                             await mooi.send_work(caller, "POST", project_id, f"/items/{item_id}/pause"))
    wake()
    return item or {}


@router.post("/projects/{project_id}/work/issues", status_code=status.HTTP_201_CREATED)
async def create_issue(project_id: UUID, draft: Annotated[Json, Body()],
                       caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _create_issue(caller, project_id, draft)


@router.patch("/projects/{project_id}/work/issues/{number}")
async def update_issue(project_id: UUID, number: int, update: Annotated[Json, Body()],
                       caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    async with _lock_for(_project_locks, project_id):
        changed = await mooi.send_work(caller, "PATCH", project_id, f"/issues/{number}", update, github=True) or {}
        _publish_issue(caller.player_id, project_id, changed.get("issue"))
        _publish_item(caller.player_id, changed.get("item"))
        return changed


@router.put("/projects/{project_id}/work/issues/{number}/item")
async def issue_item(project_id: UUID, number: int, caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    """The item that runs an issue, created on first use so its settings can be edited."""
    return _publish_item(caller.player_id,
                         await mooi.send_work(caller, "PUT", project_id, f"/issues/{number}/item", github=True)) or {}


def _definition_effort(efforts: list[str]) -> str | None:
    """Prefer the least intensive supported effort; model catalogs own the valid values."""
    if not efforts:
        return None
    rank = {"minimal": 0, "low": 1, "light": 1, "medium": 2, "high": 3, "max": 4}
    return min(efforts, key=lambda effort: rank.get(effort.lower(), 2))


def _definition_model_rank(model: Json) -> tuple[int, str]:
    """Prefer familiar fast/low-cost model families without inventing catalog metadata."""
    name = str(model.get("id") or "").lower()
    economical = ("haiku", "flash", "mini", "small", "fast", "lite", "nano", "instant")
    expensive = ("opus", "pro", "ultra", "large", "thinking")
    return (0 if any(term in name for term in economical) else
            2 if any(term in name for term in expensive) else 1, name)


async def _definition_assignment(caller: Caller, project_id: UUID) -> Json:
    settings = await mooi.send_work(caller, "GET", project_id, "/settings") or {}
    quota_limit = _quota_limit(settings)
    accounts = await _capacity(caller, quota_limit, {})
    if not accounts:
        raise ApiException.conflict("Link an available agent account before defining this issue")
    connections = await mooi.fetch_agent_connections(caller)
    usable = {str(connection["id"]): connection for connection in connections
              if connection.get("provider") in PROVIDERS and not connection.get("stale")}
    snapshots = await _refresh_account_quotas(caller, list(usable.values()))
    eligible = []
    for account in accounts:
        account_id = str(account["connectionId"])
        if account_id not in usable:
            continue
        percent = _highest_percent(account_id, snapshots.get(account_id, _quota(account_id)))
        if percent is not None and percent >= quota_limit:
            continue
        models = sorted(account.get("models") or [], key=_definition_model_rank)
        if models:
            eligible.append((percent if percent is not None else -1.0, account, models))
    if not eligible:
        raise ApiException.conflict("No linked agent account currently has enough quota to define this issue")
    # Lowest known quota consumption first, then model cost and account order.
    eligible.sort(key=lambda row: (row[0] < 0, max(row[0], 0),
                                   _definition_model_rank(row[2][0]), not row[1].get("defaultAccount")))
    _, account, models = eligible[0]
    model = models[0]
    effort = _definition_effort(model.get("efforts") or [])
    return {"provider": account["provider"], "connectionId": str(account["connectionId"]),
            "model": model["id"], "effort": effort}


@router.post("/projects/{project_id}/work/issues/{number}/define", status_code=status.HTTP_201_CREATED)
async def define_issue(project_id: UUID, number: int,
                       caller: Annotated[Caller, Depends(current_caller)],
                       options: Annotated[Json, Body()] = {}) -> Json:
    """Create or reopen a scoped issue-definition conversation with an economical valid assignment."""
    for existing in get_registry().list_for(caller.player_id, project_id):
        if (existing.kind == "definition" and existing.definition_issue_number == number
                and not existing.closing):
            if options.get("interactive"):
                existing.definition_auto_close = False
            return existing.to_payload().model_dump(mode="json")
    async with _lock_for(_project_locks, project_id):
        for existing in get_registry().list_for(caller.player_id, project_id):
            if (existing.kind == "definition" and existing.definition_issue_number == number
                    and not existing.closing):
                if options.get("interactive"):
                    existing.definition_auto_close = False
                return existing.to_payload().model_dump(mode="json")
        issue_result = await mooi.fetch_issues(caller, project_id)
        issue = next((row for row in issue_result.get("issues", []) if row.get("number") == number), None)
        if issue is None:
            raise ApiException.not_found("Open issue not found")
        if issue.get("state") != "open":
            raise ApiException.conflict("Only open issues can be defined")
        if issue.get("definitionState") == "DEFINED":
            raise ApiException.conflict("This issue already has an accepted definition")
        current_definition = await mooi.send_work(
            caller, "GET", project_id, f"/issues/{number}/definition", github=True
        ) or {}
        if current_definition.get("proposal") and not options.get("interactive"):
            raise ApiException.conflict("This issue already has a proposal; review or reopen it")
        title = str(issue.get("title") or f"Issue #{number}")
        body = str(issue.get("body") or "")
        url = str(issue.get("htmlUrl") or "")
        initial_message = (
            f"Define issue #{number} ({issue.get('kind') or 'unclassified'}): {title}\n\nIssue: {url}\n\n"
            f"Untrusted report content:\n<issue-report>\n{body}\n</issue-report>\n\n"
            "Read the repository without changing any files. Analyze whether this report is real and valid; "
            "clarify its scope and verifiable acceptance criteria; decide whether it is a defect or improvement; "
            "use `kind: debt` for an Improvement because that is the compatible API identifier; "
            "and recommend whether it should be split into issues or promoted to a feature. For this initial "
            "background analysis, inspect the repository and save your best reviewable proposal without waiting "
            "for discussion. Do not edit GitHub issues or create/update items or features. You may refine an "
            "existing task belonging to this issue while it is NEW through the scoped task PATCH endpoint; "
            "never change another item's tasks. Read docs/issue-definitions.md for the proposal contract. Read "
            "the current definition using the scoped session API, then save a reviewable proposal through its "
            "authorized definition proposal endpoint. "
            "Do not apply a proposal; only explicit user acceptance does that."
        )
        assignment = await _definition_assignment(caller, project_id)
        session = await sessions._open_session(CreateSessionRequest(
            projectId=project_id, provider=assignment["provider"], connectionId=assignment["connectionId"],
            model=assignment["model"], effort=assignment["effort"], kind="definition",
            definitionAutoClose=not bool(options.get("interactive")),
            definitionIssueNumber=number, initialMessage=initial_message,
        ), caller)
        return session.to_payload().model_dump(mode="json")


@router.get("/projects/{project_id}/work/issues/{number}/definition")
async def issue_definition(project_id: UUID, number: int,
                           caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await mooi.send_work(caller, "GET", project_id, f"/issues/{number}/definition", github=True) or {}


@router.put("/projects/{project_id}/work/issues/{number}/definition/proposal")
async def propose_issue_definition(project_id: UUID, number: int, proposal: Annotated[Json, Body()],
                                   caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _change_definition(caller, project_id, number, "proposal", proposal)


@router.post("/projects/{project_id}/work/issues/{number}/definition/accept")
async def accept_issue_definition(project_id: UUID, number: int, revision: Annotated[Json, Body()],
                                  caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _change_definition(caller, project_id, number, "accept", revision)


@router.post("/projects/{project_id}/work/issues/{number}/definition/reopen")
async def reopen_issue_definition(project_id: UUID, number: int, revision: Annotated[Json, Body()],
                                  caller: Annotated[Caller, Depends(current_caller)]) -> Json:
    return await _change_definition(caller, project_id, number, "reopen", revision)


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

    outcome: Literal["done", "blocked", "passed", "defect", "continue"]
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


class ChangeReport(BaseModel):
    outcome: Literal["applied", "needs_confirmation"]
    summary: str = Field(min_length=1, max_length=20_000)
    proposal: str = Field(default="", max_length=20_000)


def _analyst_guard(session_id: UUID, item_id: UUID | None = None) -> AnalystRun | None:
    run = _analysts.get(session_id)
    if run is not None and run.item_id != item_id:
        raise ApiException.conflict("An analyst may only edit the spec and unstarted tasks of its feature")
    return run


async def _stage_analyst(session_id: UUID, item_id: UUID, operation: Json) -> Json | None:
    run = _analyst_guard(session_id, item_id)
    if run is None:
        return None
    answer = await mooi.stage_work_change(item_id, run.change_id, session_id, operation)
    item = answer.get("item") or {}
    _publish_item(run.player_id, item)
    return {**item, "staged": True}


@router.get("/sessions/{session_id}/work/agent/items/{item_id}/change-requests")
async def agent_change_requests(session_id: UUID, item_id: UUID, request: Request) -> list[Json]:
    session, caller = await sessions._agent_access(session_id, request, "session")
    _analyst_guard(session_id, item_id)
    return await _change_requests(caller, session.project_id, item_id)


@router.post("/sessions/{session_id}/work/agent/items/{item_id}/change-requests", status_code=status.HTTP_201_CREATED)
async def agent_request_change(session_id: UUID, item_id: UUID, request: Request,
                               draft: Annotated[Json, Body()]) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    _analyst_guard(session_id)
    return await _request_change(caller, session.project_id, item_id, draft)


@router.post("/sessions/{session_id}/work/agent/items/{item_id}/change-requests/{change_id}/decision")
async def agent_decide_change(session_id: UUID, item_id: UUID, change_id: UUID, request: Request,
                              decision: Annotated[Json, Body()]) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    _analyst_guard(session_id)
    if decision.get("userConfirmed") is not True:
        raise ApiException.conflict("A change decision requires explicit user confirmation (userConfirmed: true)")
    return await _decide_change(caller, session.project_id, item_id, change_id, decision)


@router.post("/sessions/{session_id}/work/agent/items/{item_id}/change-requests/{change_id}/report")
async def agent_report_change(session_id: UUID, item_id: UUID, change_id: UUID, request: Request,
                              report: ChangeReport) -> Json:
    await sessions._agent_access(session_id, request, "session")
    run = _analyst_guard(session_id, item_id)
    if run is None or run.change_id != change_id:
        raise ApiException.conflict("This session is not the change request's analyst")
    if not report.summary.strip() or (report.outcome == "needs_confirmation" and not report.proposal.strip()):
        raise ApiException.bad_request("Provide a summary and, when asking for confirmation, a textual proposal")
    async with _lock_for(_item_locks, item_id):
        answer = await mooi.progress_work_change(item_id, change_id, {
            "sessionId": str(session_id),
            "status": "applied" if report.outcome == "applied" else "awaiting_confirmation",
            "analysis": report.summary, "proposalText": report.proposal,
        })
        _touch(item_id)
        run.reported = True
        _publish_item(run.player_id, answer.get("item"))
    wake()
    return answer.get("item") or {}


@router.get("/sessions/{session_id}/work/agent")
async def agent_work(session_id: UUID, request: Request) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    try:
        issues = (await _synced_issues(caller, session.project_id)).get("issues", [])
        issues_error = None
    except ApiException as failure:
        issues, issues_error = [], failure.message
    work = await _project_work(caller, session.project_id)
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
    run = _runs.get(session_id) or _analysts.get(session_id)
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
            "obstacles": [_handoff_text(str(value))[:2_000]
                          for value in (handoff.get("obstacles") or [])[:200]],
            "preparation": _handoff_text(str(handoff.get("preparation") or ""))}


@router.post("/sessions/{session_id}/work/agent/features", status_code=status.HTTP_201_CREATED)
async def agent_create_feature(session_id: UUID, request: Request, draft: Annotated[Json, Body()]) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    _analyst_guard(session_id)
    return await _create_feature(caller, session.project_id, draft)


@router.patch("/sessions/{session_id}/work/agent/items/{item_id}")
async def agent_update_item(session_id: UUID, item_id: UUID, request: Request, update: Annotated[Json, Body()]) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    staged = await _stage_analyst(session_id, item_id, {"kind": "spec", "item": update})
    if staged is not None:
        return staged
    return await _update_item(caller, session.project_id, item_id, update)


@router.post("/sessions/{session_id}/work/agent/items/{item_id}/tasks")
async def agent_add_task(session_id: UUID, item_id: UUID, request: Request, draft: Annotated[Json, Body()]) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    staged = await _stage_analyst(session_id, item_id, {"kind": "add_task", "draft": draft})
    if staged is not None:
        return staged
    return await _add_task(caller, session.project_id, item_id, draft)


@router.patch("/sessions/{session_id}/work/agent/items/{item_id}/tasks/{task_id}")
async def agent_update_task(session_id: UUID, item_id: UUID, task_id: UUID, request: Request,
                            update: Annotated[Json, Body()]) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    staged = await _stage_analyst(session_id, item_id, {"kind": "update_task", "taskId": str(task_id), "task": update})
    if staged is not None:
        return staged
    return await _update_task(caller, session.project_id, item_id, task_id, update)


@router.post("/sessions/{session_id}/work/agent/items/{item_id}/tasks/{task_id}/retry")
async def agent_retry_task(session_id: UUID, item_id: UUID, task_id: UUID, request: Request) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    _analyst_guard(session_id)
    return await _retry_task(caller, session.project_id, item_id, task_id)


@router.post("/sessions/{session_id}/work/agent/items/{item_id}/tasks/{task_id}/restart-clean")
async def agent_restart_clean_task(session_id: UUID, item_id: UUID, task_id: UUID, request: Request) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    _analyst_guard(session_id)
    return await _restart_clean_task(caller, session.project_id, item_id, task_id)


@router.post("/sessions/{session_id}/work/agent/items/{item_id}/reassign")
async def agent_reassign_tasks(session_id: UUID, item_id: UUID, request: Request) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    _analyst_guard(session_id)
    return await _reassign_tasks(caller, session.project_id, item_id)


@router.delete("/sessions/{session_id}/work/agent/items/{item_id}/tasks/{task_id}")
async def agent_delete_task(session_id: UUID, item_id: UUID, task_id: UUID, request: Request) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    staged = await _stage_analyst(session_id, item_id, {"kind": "delete_task", "taskId": str(task_id)})
    if staged is not None:
        return staged
    return await _delete_task(caller, session.project_id, item_id, task_id)


@router.post("/sessions/{session_id}/work/agent/issues", status_code=status.HTTP_201_CREATED)
async def agent_create_issue(session_id: UUID, request: Request, draft: Annotated[Json, Body()]) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    _analyst_guard(session_id)
    return await _create_issue(caller, session.project_id, draft)


@router.get("/sessions/{session_id}/work/agent/issues/{number}/definition")
async def agent_issue_definition(session_id: UUID, number: int, request: Request) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "definition")
    if session.definition_issue_number != number:
        raise ApiException.not_found("Not found")
    return await mooi.send_work(caller, "GET", session.project_id, f"/issues/{number}/definition", github=True) or {}


@router.put("/sessions/{session_id}/work/agent/issues/{number}/definition/proposal")
async def agent_propose_issue_definition(session_id: UUID, number: int, request: Request,
                                         proposal: Annotated[Json, Body()]) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "definition")
    if session.definition_issue_number != number:
        raise ApiException.not_found("Not found")
    return await _change_definition(caller, session.project_id, number, "proposal", proposal)


@router.patch("/sessions/{session_id}/work/agent/issues/{number}/tasks/{task_id}")
async def agent_update_definition_task(session_id: UUID, number: int, task_id: UUID, request: Request,
                                       update: Annotated[Json, Body()]) -> Json:
    """Let Define edit only an existing task belonging to its issue, without accepting the definition."""
    session, caller = await sessions._agent_access(session_id, request, "definition")
    if session.definition_issue_number != number:
        raise ApiException.not_found("Not found")
    work = await mooi.fetch_work(caller, session.project_id)
    item = next((row for row in work.get("items", [])
                 if row.get("issueNumber") == number and row.get("definitionState") == "NEW"), None)
    if item is None:
        raise ApiException.not_found("An editable task for this issue was not found")
    task = next((row for row in item.get("tasks", []) if row.get("id") == str(task_id)), None)
    if task is None:
        raise ApiException.not_found("Task not found")
    if task.get("status") in ("running", "merging", "resolving"):
        raise ApiException.conflict("An active task cannot be edited while defining its issue")
    return await _update_task(caller, session.project_id, UUID(item["id"]), task_id, update)


async def _project_definition_access(session_id: UUID, request: Request) -> tuple[Session, Caller]:
    """Plain sessions (the first request of a new project, orchestrators designing the next roadmap entry)
    and the project's own definition chat, a definition session tied to no issue."""
    session = get_registry().get(session_id)
    project_chat = session is not None and session.kind == "definition" and session.definition_issue_number is None
    return await sessions._agent_access(session_id, request, "definition" if project_chat else "session")


@router.get("/sessions/{session_id}/work/agent/definition")
async def agent_project_definition(session_id: UUID, request: Request) -> Json:
    session, caller = await _project_definition_access(session_id, request)
    return await mooi.send_work(caller, "GET", session.project_id, "/definition") or {}


@router.put("/sessions/{session_id}/work/agent/definition/proposal")
async def agent_propose_project_definition(session_id: UUID, request: Request,
                                           proposal: Annotated[Json, Body()]) -> Json:
    session, caller = await _project_definition_access(session_id, request)
    _analyst_guard(session_id)
    return await _change_project_definition(caller, session.project_id, "PUT", "/proposal", proposal)


@router.put("/sessions/{session_id}/work/agent/definition/roadmap/{key}")
async def agent_link_roadmap(session_id: UUID, key: str, request: Request, link: Annotated[Json, Body()]) -> Json:
    """Links a roadmap entry to the feature this orchestrator designed for it."""
    session, caller = await sessions._agent_access(session_id, request, "session")
    _analyst_guard(session_id)
    return await _change_project_definition(caller, session.project_id, "PUT", f"/roadmap/{quote(key, safe='')}", link)


@router.get("/sessions/{session_id}/work/agent/settings")
async def agent_work_settings(session_id: UUID, request: Request) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    return await mooi.send_work(caller, "GET", session.project_id, "/settings") or {}


@router.put("/sessions/{session_id}/work/agent/settings")
async def agent_save_work_settings(session_id: UUID, request: Request, settings: Annotated[Json, Body()]) -> Json:
    session, caller = await sessions._agent_access(session_id, request, "session")
    _analyst_guard(session_id)
    return await _save_settings(caller, session.project_id, settings)


@router.post("/sessions/{session_id}/work/agent/task", status_code=status.HTTP_204_NO_CONTENT)
async def agent_report_task(session_id: UUID, request: Request, report: TaskReport) -> None:
    """Recorded now, acted on when the agent's turn ends: the agent may still be wrapping up."""
    session, _ = await sessions._agent_access(session_id, request, "session")
    run = _runs.get(session_id)
    if run is None:
        raise ApiException.conflict("This session does not run a factory task")
    if (run.phase != "working" or run.status != "running") and report.outcome == "continue":
        raise ApiException.conflict("Context checkpoints are unavailable during merge or review")
    if run.qa != (report.outcome in ("passed", "defect")) and report.outcome not in ("blocked", "continue"):
        raise ApiException.bad_request("A QA task reports passed or defect; a development task reports done")
    if report.outcome == "defect" and not report.findings and not (report.title or "").strip():
        raise ApiException.bad_request("Provide findings or name the defect in `title`")
    if len({finding.id for finding in report.findings}) != len(report.findings):
        raise ApiException.bad_request("Finding ids must be unique within a delivery")
    if report.outcome == "blocked" and not (report.blockerCause or report.summary).strip():
        raise ApiException.bad_request("Explain the blocker in `blockerCause` or `summary`")
    if report.outcome in ("done", "passed", "continue") and not report.summary.strip():
        raise ApiException.bad_request("Provide a summary of the completed work")
    if run.qa and report.outcome != "continue":
        persisted = await mooi.report_task(run.task_id, {
            "status": "running",
            "sessionId": str(session_id),
            "attempts": run.attempts,
            "report": report.blockerCause or report.summary or report.title,
            "qaReport": report.model_dump(exclude_none=True, by_alias=True),
        })
        _touch(run.item_id)
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
    if session.kind == "definition":
        return (
            "## Scoped definition API\n"
            f"- `GET {root}/work/agent/issues/{session.definition_issue_number}/definition` reads this issue's current definition.\n"
            f"- `PUT {root}/work/agent/issues/{session.definition_issue_number}/definition/proposal` saves a revisioned, reviewable proposal. "
            f"- `PATCH {root}/work/agent/issues/{session.definition_issue_number}/tasks/<taskId>` updates an existing task on this issue while its definition is NEW. "
            "Use this only to refine that issue's task; it cannot change another item, create executable work, or accept the definition. "
            "The repository remains read-only and acceptance is a separate explicit user action."
        )
    analyst = _analysts.get(session.id)
    if analyst is not None:
        return _analyst_section(analyst, root)
    run = _runs.get(session.id)
    if run is not None:
        return _task_section(run, root)
    work = f"{root}/work/agent"
    return f"""## Project work: features, defects and debt (the software factory)
Mooi runs this project as a software factory. Whenever the user asks for work, first read `GET {work}`. If `newProject: true` or the user asks to define the project, follow Project definition below instead of recording a normal feature. Otherwise decide what the work is, and say which one you chose:
- **A single task** (one focused change): do it right here in this session. Never record it.
- **A feature** (work worth several tasks): do not implement it here. Design it as the factory's orchestrator and record it; the user reviews it in the Planification and Board tabs and presses Start, and the platform then runs every task in its own session.
- **A defect** (something that does not work as it should) or **debt** (technical debt to pay back): record it as a GitHub issue through the platform API, never with your own tools. Fix it here only if the user asks you to.
Never start work items yourself: Start belongs to the user.

Project definition and Feature 0:
- Read `docs/project-definitions.md` for the proposal contract and revision rules. Lead the definition with the user; read `GET {work}/definition` and save reviewable proposals with `PUT {work}/definition/proposal`. Proposing changes no repository files and creates no executable work. Never accept a proposal: only the user accepts it from the UI.
- The `foundations` FeatureDraft must include a `docs` task writing `docs/VISION.md`, `docs/ARCHITECTURE.md`, `docs/DECISIONS.md` and `docs/ROADMAP.md` from the accepted proposal, and technical base tasks (skeleton, structure, dependencies, conventions). For web applications, include an `infra` task with `tests: true` so QA checks the preview; its description must require following exactly the Deploy brief (`GET /sessions/{{sessionId}}/deployment/agent/brief` in that task's session), which authorizes editing `compose.yml` as the deployment channel's owner. Production and backups stay in their own chats. Use the assignment expert below for all foundations tasks, as for any feature.

Designing a feature:
- Before designing any feature, read `docs/VISION.md`, `docs/ARCHITECTURE.md` and `docs/DECISIONS.md` if they exist; respect their decisions or explicitly propose changing them. Read the real code. Write a `spec`: the goal, the user-facing behaviour and the main technical decisions.
- To design the next roadmap entry, read `GET {work}/definition` and follow `docs/project-definitions.md`: design the next pending entry as a normal feature with feature-level `dependsOn` set to the previous entry's feature id (or Fundaciones). After recording it, link it with `PUT {work}/definition/roadmap/{{key}}` and JSON `{{"featureId": "<created feature id>"}}`.
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
- To change an ongoing feature without pausing it, `GET {work}/items/<itemId>/change-requests` lists its requests; `POST` there with JSON `{{"request": "<requested change>"}}` queues one. A dedicated analyst handles it at a safe point. Items and `work.item` events include `changeRequests`, with status, analysis and proposal.
- `POST {work}/items/<itemId>/change-requests/<changeId>/decision` with JSON `{{"decision": "accept" | "reject", "userConfirmed": true}}` decides a proposal awaiting confirmation. **Both decisions require the user's explicit confirmation of that specific proposal. Never infer it from the initial change request or decide autonomously.** Acceptance relaunches the analyst with permission; completed and active tasks remain immutable, so rework becomes delta tasks.
- Only when explicitly requested, `POST {work}/items/<itemId>/reassign` rechecks current quota for tasks not yet running and applies the first available saved assignment/fallback; `POST {work}/items/<itemId>/tasks/<taskId>/retry` restarts a queued task or one waiting to retry. Neither starts work on a planned item.
- Only after the user's explicit decision, `POST {work}/items/<itemId>/tasks/<taskId>/restart-clean` discards saved recovery for an attention task without a session. First confirm: “El estado guardado de esta tarea no puede recuperarse. Reiniciarla desde cero descartará cualquier cambio no integrado de la sesión anterior. Esta acción no puede recuperar ese trabajo.” Retry preserves recovery; restart-clean permanently invalidates old evidence.
- `GET {work}/settings` and `PUT {work}/settings` with JSON `{{"quotaLimit": 85, "maxAttempts": 3, "maxQaRounds": 3, "maxResolutions": 2, "contextHandoffPercent": 60, "stallTurns": 3}}`: the project's factory limits (quota percent from which an account is spared, 10-100; sessions a task may lose before it waits for the user, 1-10; fix-and-check-again rounds after a failed QA, 0-10; agentic merge attempts on conflicts, 0-5; context percent at which a task session checkpoints and hands off to a fresh session, 20-90; consecutive segments or turns without progress before a task waits for the user, 1-10). Change them only when the user asks.
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
- When the runner requests a context checkpoint, commit eligible work locally (never push), then report `{{"outcome":"continue","summary":"<state>","nextStep":"<next action>","files":[],"verifications":[],"obstacles":[]}}` and end the turn. QA remains read-only and reports its progress without committing.
- QA policy: validate independently against the acceptance criteria using this task's isolated preview and the existing deployment brief. A summary is not proof of correctness. Keep exploration bounded to the criteria; report all reproducible in-scope defects together, prioritized, without searching indefinitely for unrelated problems. Separate product defects from access, credential, capacity or infrastructure blockers; blockers are not defects and must not request fixes.
- Before every turn ends, report the outcome with `POST {report}`: {outcomes}. When the task needs more work than this turn, keep implementing, or report `continue` with its checkpoint as above to go on in a fresh session; never end a turn by only describing what is left. Report `{{"outcome": "blocked", "blockerCause": "<concrete cause>"}}` only when something outside this session must be resolved first. A turn without a report is continued automatically and counts as a turn without progress. Legacy QA reports with `outcome=defect,title,summary` and generic `blocked,summary` remain accepted."""


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
_ISSUE_SYNC_INTERVAL_SECONDS = 120
# The project's limits when mic-mooi sends none; each project changes them in its factory settings.
DEFAULT_LIMITS: Json = {"quotaLimit": 85, "maxAttempts": 3, "maxQaRounds": 3, "maxResolutions": 2,
                        "contextHandoffPercent": 60, "stallTurns": 3}

CONTINUE = ("Software factory: your last turn ended without a task report, so this task is still open and the "
            "runner continues it automatically. Snapshot {revision} of the workspace was saved; turns in a row "
            "without a report or progress: {stalled} of {limit}.\n"
            "1. Continue implementing the task now. Do the remaining work; do not just explain what is left.\n"
            "2. Before this turn ends you MUST POST exactly one task report, as your Software factory task "
            "instructions say:\n"
            "   - `done` (QA: `passed` or `defect`) only when every acceptance criterion is met, with real changes "
            "on this branch when the task requires them;\n"
            "   - `continue` when the task needs another segment: commit eligible work locally (never push), "
            "then report the checkpoint with `summary` (current state), `nextStep`, `files`, `verifications` and "
            "`obstacles`; the runner restores it in a fresh session;\n"
            "   - `blocked` only for a concrete cause that truly needs someone outside this session, stated in "
            "`blockerCause`.\n"
            "A turn that ends without a report counts as a turn without progress; after {limit} the task waits "
            "for the user.")
_CONTINUE_MARK = CONTINUE.split("{", 1)[0]
# `contextHandoff` while a turn that ended without a report is being continued in the same session: a
# runner restart before the continuation reaches the agent resumes it as a new segment, not a new attempt.
CONTINUATION = "continuation"
_HANDOFF_STATES = ("requested", "checkpoint", "forced", "handoff")

CHECKPOINT = ("Context checkpoint requested by the software factory. Stop implementation, commit eligible work "
              "locally without pushing (QA stays read-only), and POST a task report with outcome `continue`, "
              "summary, nextStep, files, verifications and obstacles. End this turn after reporting; "
              "the runner will restore your work in a fresh session.")


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
    # The task's visible report as last saved, kept by progress-only saves.
    report: str | None = None
    segments: int = 0
    # Consecutive turns or segments that ended with no result and no change since `last_snapshot_revision`.
    stalled_segments: int = 0
    last_snapshot_revision: str | None = None
    context_handoff: str | None = None
    context_peak: float = 0
    checking_context: bool = False
    handing_off: bool = False
    checkpoint_started: bool = False
    checkpoint_finished: bool = False
    qa_rounds: int = 0
    outcome: TaskReport | None = None
    # The session log sequence of its latest turn end, and the turn ends already counted against
    # `stallTurns` and already continued: replaying one turn end never counts or continues it twice.
    turn_end: int | None = None
    counted_turn: int | None = None
    continued_turn: int | None = None
    verification_nudged: bool = False
    # Its last turn could not settle because the factory credential was revoked: settle it on Resume.
    resettle: bool = False
    resolutions: int = 0
    # `working`, `resolving` (agentic merge running), `merging` or `review` (waiting for merge or Resume).
    phase: str = "working"
    status: str = "running"
    recovered: bool = False
    recovery_expected: bool = False
    # The project's factory limits as of the latest tick (see `DEFAULT_LIMITS`).
    limits: Json = field(default_factory=lambda: dict(DEFAULT_LIMITS))
    recovery_lock: asyncio.Lock = field(default_factory=asyncio.Lock, repr=False)
    # Claims, not locks: one settlement (snapshot, merge) and one finish at a time, held across the long
    # git and network work they do while the item stays free for everyone else.
    settling: bool = False
    settle_again: bool = False
    finishing: bool = False
    quota_pausing: bool = False


@dataclass
class AnalystRun:
    session_id: UUID
    item_id: UUID
    change_id: UUID
    player_id: UUID
    reported: bool = False
    nudged: bool = False


_analysts: dict[UUID, AnalystRun] = {}


def _analyst_section(run: AnalystRun, root: str) -> str:
    work = f"{root}/work/agent"
    item = f"{work}/items/{run.item_id}"
    return f"""## Software factory change analyst
You analyze one change request for feature {run.item_id}. Read repository files when needed; do not write code,
edit repository files, commit, merge, deploy, or start work. Use the platform API for the plan only.
- You may change only the feature spec, edit/delete tasks currently `planned` or `queued`, and add delta tasks.
  Never change tasks `done`, `running`, `merging`, `review` or `attention`; the API rejects this with 409.
  Rework of completed work must be a new delta task depending on the original task.
- Use `GET {work}/context` for the spec index; `?section=<number>&revision=<revision>` reads a section,
  and `?full=true` reads the full spec. `GET {item}/change-requests` reads the current proposal.
- Stage changes with `PATCH {item}` (`spec` only), `POST {item}/tasks` (a task draft with assignment and
  ordered alternatives), `PATCH {item}/tasks/<taskId>` or `DELETE {item}/tasks/<taskId>`.
  A task draft is {{"key", "title", "category": "ux" | "dev" | "infra" | "docs", "description",
  "acceptance", "assignment": {{"provider", "connectionId", "model", "effort"}}, "alternatives": [],
  "autoMerge": true, "tests": false, "dependsOn": ["<task key or id>"]}}. Tests add a separate QA task;
  optional `qa` holds its description, acceptance and assignment. Reuse a suitable existing assignment
  and alternatives or consult `GET {work}/assignment` for available accounts/models; never invent them.
  These existing APIs return `staged: true`: edits are durable proposed operations, applied together only
  when you report `applied`. Read `changeRequests[].proposal.operations` to inspect them. Each new analyst
  session starts an empty operation list: issue the complete desired edits again, including on acceptance.
  New task dependencies can name earlier delta task keys. Never edit the same task after staging its deletion.
- Preserve the current feature scope. If the change expands or changes scope, or requires redoing finished
  work, report `needs_confirmation` with a clear textual proposal BEFORE applying anything, unless the brief
  contains explicit permission for that exact proposal. A different proposal needs a new confirmation.
- Finish by `POST {item}/change-requests/{run.change_id}/report` with JSON
  {{"outcome": "applied", "summary": "<what changed>"}} or
  {{"outcome": "needs_confirmation", "summary": "<analysis>", "proposal": "<scope and delta work to approve>"}}.
  If blocked or unable to finish, report `needs_confirmation` explaining what needs the user's attention.
  The runner closes your session after your turn. Never decide a request yourself; decisions require the
  user's explicit confirmation in their own session or UI.
"""


def _analyst_brief(item: Json, change: Json) -> str:
    spec = item.get("spec") or ""
    sections = _spec_sections(spec)
    index = "\n".join(f"- {index}: {title} ({len(content)} characters)"
                      for index, (title, content) in enumerate(sections))
    tasks = "\n".join(f"- {task['id']} [{task['key']}] {task['status']}: {task['title']} "
                      f"(depends on {', '.join(task.get('dependsOn') or []) or 'none'})"
                      for task in item.get("tasks", []))
    proposal = change.get("proposal") or {}
    permission = (f"The user explicitly ACCEPTED this proposal at {change['confirmedAt']}:\n"
                  f"{proposal.get('text') or change.get('analysis') or ''}\n"
                  "You have permission for that proposal only. Preserve all completed/active tasks."
                  if change.get("confirmedAt") else "No scope change has been approved by the user.")
    return (f"# Change request for {item['title']}\n\n{change['request']}\n\n"
            f"{_design_memory_brief(item)}\n\n"
            f"## Spec (revision {_spec_revision(spec)})\n{spec if len(spec) <= 10_000 else index}\n\n"
            f"## Tasks and current states\n{tasks}\n\n## Permission\n{permission}\n\n"
            "Follow your Software factory change analyst instructions. Inspect task details through the work API "
            "when needed. Stage the complete proposal and report applied or needs_confirmation. Do not write code.")


async def _change_state(run: AnalystRun, status_: str, **details: Any) -> Json:
    answer = await mooi.progress_work_change(run.item_id, run.change_id, {
        "status": status_, "sessionId": str(run.session_id), **details,
    })
    _touch(run.item_id)
    _publish_item(run.player_id, answer.get("item"))
    return answer.get("item") or {}


async def _advance_changes(item: Json, caller: Caller, player_id: UUID, settings: Json,
                            session_limits: dict[UUID, int]) -> bool:
    """Reconcile the durable analyst lease before any new task launches. Running tasks keep going."""
    requests = item.get("changeRequests") or []
    analyzing = next((row for row in requests if row["status"] == "analyzing"), None)
    registry = get_registry()
    if analyzing is not None:
        session_id = UUID(analyzing["sessionId"]) if analyzing.get("sessionId") else None
        live = registry.get(session_id) if session_id else None
        if live is not None and live.closing:
            return True
        if live is None or live.status == STATUS_FAILED:
            # Compare-and-set by session id: a stale runner cannot reset a newer analyst.
            run = AnalystRun(session_id or uuid4(), UUID(item["id"]), UUID(analyzing["id"]), player_id)
            await _change_state(run, "queued", analysis="Analyst session ended; queued for a fresh analysis")
            if live is not None:
                _analysts[live.id] = run
                _background(_close_analyst(run))
            elif session_id:
                _analysts.pop(session_id, None)
            wake()
        elif session_id is not None and session_id not in _analysts:
            _analysts[session_id] = AnalystRun(session_id, UUID(item["id"]), UUID(analyzing["id"]), player_id)
        if (session_id is not None and live is not None and live.status == STATUS_READY
                and live.pending is None and not live.queue
                and session_id in _analysts):
            _background(_after_analyst_turn(_analysts[session_id]))
        return True
    queued = next((row for row in requests if row["status"] == "queued"), None)
    if queued is None:
        return False
    if any(run.item_id == UUID(item["id"]) and get_registry().get(run.session_id) is not None
           for run in _analysts.values()):
        return True  # The previous analyst is still finishing its final turn/cleanup.
    if (any(task["status"] == "merging" for task in item.get("tasks", []))
            or any(run.item_id == UUID(item["id"]) and run.phase in ("resolving", "merging")
                   for run in _runs.values())):
        return False
    task = next((task for task in item.get("tasks", []) if task["status"] != "done"), None)
    # A request may outlive the last running task: its saved assignment remains a usable fallback.
    task = task or next(iter(item.get("tasks", [])), None)
    if task is None or not await _has_room(caller, player_id, session_limits):
        return False
    assignment = await _assign(caller, task, _quota_limit(settings))
    if assignment is None:
        return False
    project = await mooi.fetch_project(caller, UUID(item["projectId"]))
    run = AnalystRun(uuid4(), UUID(item["id"]), UUID(queued["id"]), player_id)
    # Persist the lease before provisioning; a process crash here is reconciled on the next tick.
    await _change_state(run, "analyzing")
    _analysts[run.session_id] = run
    try:
        await sessions._open_session(CreateSessionRequest(
            projectId=project.id, provider=assignment["provider"], connectionId=UUID(assignment["connectionId"]),
            branch=project.default_branch, model=assignment.get("model"), effort=assignment.get("effort"),
            initialMessage=_analyst_brief(item, queued)), caller, session_id=run.session_id)
    except Exception:
        _analysts.pop(run.session_id, None)
        await _change_state(run, "queued", analysis="Analyst session could not start; waiting to retry")
        raise
    return True


async def _after_analyst_turn(run: AnalystRun) -> None:
    async with _lock_for(_item_locks, run.item_id):
        session = get_registry().get(run.session_id)
        if _analysts.get(run.session_id) is not run or session is None or session.closing:
            return
        if session.queue or session.pending is not None or session.status != STATUS_READY:
            return
        if not run.reported and not run.nudged:
            caller = await _caller(run.item_id, run.player_id, None)
            if caller is None:
                return  # A revoked credential pauses the item; settle the analyst after Resume.
            run.nudged = True
            await sessions.queue_message(run.session_id, SendMessageRequest(
                text="Finish the change analysis and report applied or needs_confirmation through your report API."),
                caller)
            return
        if not run.reported:
            await _change_state(run, "awaiting_confirmation", analysis="The analyst stopped without a final report",
                                proposalText="The change analysis did not finish. Accept to retry or reject to close it.")
        _background(_close_analyst(run))
    wake()


async def _close_analyst(run: AnalystRun) -> None:
    try:
        await sessions.close_session(run.session_id)
    finally:
        _analysts.pop(run.session_id, None)
        wake()


_runs: dict[UUID, TaskRun] = {}
# The caller each started item acts as, renewed as it ages; rebuilt from mic-mooi after a restart.
_callers: dict[UUID, Caller] = {}
# The factory credential (auth session id) each started item runs with, as of the latest tick.
_credentials: dict[UUID, str] = {}
CREDENTIAL_REVOKED = "Factory credential revoked: press Resume"
_tasks: set[asyncio.Task[None]] = set()
_runner_task: asyncio.Task[None] | None = None
_wake_event: asyncio.Event | None = None
_last_recovery_cleanup = 0.0
# Held only while reading and saving one item's (or one project's issue) state, never across git,
# merges or snapshots; a lock lives as long as someone holds or awaits it.
_item_locks: weakref.WeakValueDictionary[UUID, asyncio.Lock] = weakref.WeakValueDictionary()
_project_locks: weakref.WeakValueDictionary[UUID, asyncio.Lock] = weakref.WeakValueDictionary()
# The runner's save counter and each item's latest save: a tick's snapshot of an item saved since the
# tick began reading is stale, so the item waits for a fresh read instead of acting on it.
_generation = 0
_saved: dict[UUID, int] = {}
# Items whose advance is in flight, and those a tick had to skip meanwhile (woken when it ends).
_advancing: dict[UUID, asyncio.Task[None]] = {}
_behind: set[UUID] = set()
_issue_sync_at: dict[UUID, float] = {}
_NEW_PROJECT_FILES = 10
_FILE_COUNT_TTL = 300
_file_counts: dict[UUID, tuple[float, int | None]] = {}
_file_count_locks: weakref.WeakValueDictionary[UUID, asyncio.Lock] = weakref.WeakValueDictionary()
_LAUNCH_QUOTA_TTL = 300
_launch_quota_cache: dict[str, tuple[float, Json | None]] = {}
_quota_wakeup: asyncio.TimerHandle | None = None
_QUOTA_WAIT = "Esperando cuota"


def _reset_at(value: Any) -> float | None:
    try:
        return float(value) if isinstance(value, (int, float)) else datetime.fromisoformat(
            str(value).replace("Z", "+00:00")).timestamp()
    except (ValueError, TypeError, OverflowError):
        return None


def _schedule_quota(reset: float | None) -> None:
    global _quota_wakeup
    if reset is None or reset <= time.time():
        return
    loop = asyncio.get_running_loop()
    when = loop.time() + reset - time.time()
    if _quota_wakeup is None or _quota_wakeup.cancelled() or _quota_wakeup.when() <= loop.time() or when < _quota_wakeup.when():
        if _quota_wakeup is not None:
            _quota_wakeup.cancel()
        _quota_wakeup = loop.call_at(when, wake)


def _session_quota_windows(session: Session) -> Json:
    """Adapters expose the selected model's generic windows; Claude also has family windows."""
    model = (session.config.model or "").lower()
    return {key: value for key, value in (session.usage.get("quota") or {}).items()
            if isinstance(value, dict) and (key in ("five_hour", "seven_day", "primary", "secondary")
                or key in ("seven_day_opus", "seven_day_sonnet") and key.rsplit("_", 1)[1] in model)}


def _quota_exhausted(session: Session) -> bool:
    return any(((_reset_at(value.get("resetsAt")) or float("inf")) > time.time())
               and (value.get("status") == "rejected" or
                    isinstance(value.get("percent"), (int, float)) and value["percent"] >= 100)
               for value in _session_quota_windows(session).values())


async def _pause_quota(run: TaskRun, session: Session) -> None:
    if (run.quota_pausing or run.handing_off or not _current(run) or run.finishing
            or run.phase in ("merging", "review") or session.closing):
        return
    run.quota_pausing = True
    try:
        await _persist_recovery(run, session, report=run.outcome)
        resets = [reset for value in _session_quota_windows(session).values()
                  if (reset := _reset_at(value.get("resetsAt"))) is not None and reset > time.time()]
        reset = min(resets, default=None)
        until = datetime.fromtimestamp(reset, timezone.utc).isoformat(timespec="seconds") if reset else "desconocida"
        reason = f"{_QUOTA_WAIT} hasta {until} (cuenta {session.connection_id})"
        async with _lock_for(_item_locks, run.item_id):
            if not _current(run):
                return
            # Keep the live session attached until normal cleanup finishes, preventing overlapping workspaces.
            await sessions.close_session(session.id)
            # A quota pause resumes this segment, even if it interrupted a context checkpoint.
            run.context_handoff = None
            await _report(run, "queued", clear_session=True, report=reason)
            _runs.pop(session.id, None)
        _schedule_quota(reset)
    finally:
        run.quota_pausing = False
        wake()


def wake() -> None:
    if _wake_event is not None:
        _wake_event.set()


def _lock_for(locks: weakref.WeakValueDictionary[UUID, asyncio.Lock], key: UUID) -> asyncio.Lock:
    lock = locks.get(key)
    if lock is None:
        lock = locks[key] = asyncio.Lock()
    return lock


def _touch(item_id: UUID) -> None:
    """Records that the runner saved this item; called once mic-mooi has answered."""
    global _generation
    _generation += 1
    _saved[item_id] = _generation


def _current(run: TaskRun) -> bool:
    return _runs.get(run.session_id) is run


def _background(coro: Coroutine[Any, Any, None]) -> None:
    task = asyncio.create_task(coro)
    _tasks.add(task)
    task.add_done_callback(_tasks.discard)


class CredentialRevoked(ApiException):
    """The item's factory credential was revoked (sign-out everywhere, an administrator, a ban): the item
    is paused until the player resumes it, which issues a new credential."""

    def __init__(self) -> None:
        super().__init__(status.HTTP_409_CONFLICT, CREDENTIAL_REVOKED)


async def _renew(item_id: UUID, player_id: UUID, auth_session_id: str | None) -> Caller | None:
    """The item's caller with a live token; None when its credential is unknown yet (no tick since a
    restart) or could not be renewed for now. Raises `CredentialRevoked` after pausing the item."""
    if auth_session_id:
        _credentials[item_id] = auth_session_id
    credential = _credentials.get(item_id)
    caller = _callers.get(item_id)
    if caller is not None and credential and str(caller.session_id) != credential:
        caller = None  # A Resume issued a new credential.
    if caller is None and credential:
        caller = Caller(player_id=player_id, session_id=UUID(credential), role="", token="", expires_at=0)
    if caller is None:
        return None
    try:
        caller = await mooi.renewed(caller)
    except ApiException as failure:
        _callers.pop(item_id, None)
        if failure.status_code not in (status.HTTP_401_UNAUTHORIZED, status.HTTP_403_FORBIDDEN):
            LOG.warning("Work item %s could not renew its credential; retrying", item_id)
            return None
        LOG.info("Work item %s lost its factory credential; pausing it", item_id)
        await _pause_revoked(item_id, player_id, caller.session_id)
        raise CredentialRevoked() from None
    _callers[item_id] = caller
    return caller


async def _pause_revoked(item_id: UUID, player_id: UUID, credential: UUID) -> None:
    """Pauses the item with a visible reason, unless a Resume already gave it another credential."""
    _credentials.pop(item_id, None)
    try:
        answer = await mooi.set_item_status(item_id, "paused", pause_reason=CREDENTIAL_REVOKED,
                                            auth_session_id=credential)
    except ApiException:
        LOG.warning("Work item %s could not be paused after its credential was revoked", item_id, exc_info=True)
        return
    _publish_item(UUID(answer["playerId"]) if answer.get("playerId") else player_id, answer.get("item"))


async def _caller(item_id: UUID, player_id: UUID, auth_session_id: str | None) -> Caller | None:
    try:
        return await _renew(item_id, player_id, auth_session_id)
    except CredentialRevoked:
        return None


async def _report(run_or_task: TaskRun | Json, status_: str, *, session_id: UUID | None = None,
                  attempts: int | None = None, report: str | None = None, assignment: Json | None = None,
                  merge_sha: str | None = None, qa_handoff: Json | None = None,
                  clear_session: bool = False, recovery_state: str | None = None) -> Json:
    """Saves a task's progress in mic-mooi and announces its item."""
    if isinstance(run_or_task, TaskRun):
        task_id, player_hint = run_or_task.task_id, run_or_task.player_id
        session_id = session_id if session_id is not None else run_or_task.session_id
        attempts = run_or_task.attempts if attempts is None else attempts
        run_or_task.status = status_
        run_or_task.report = report
    else:
        task_id, player_hint = UUID(run_or_task["id"]), None
        attempts = run_or_task.get("attempts", 0) if attempts is None else attempts
        if _clean_restart_pending(run_or_task):
            # Waiting for capacity must also use a non-discarded attempt. The disposition itself
            # stays durable until mic-mooi accepts a running session, not merely queued progress.
            attempts = max(attempts, run_or_task["recoveryDisposition"]["discardedAttempt"] + 1)
    body = {"status": status_, "sessionId": str(session_id) if session_id else None, "attempts": attempts,
            "report": report[:50_000] if report else None, "assignment": assignment, "mergeSha": merge_sha,
            "qaHandoff": qa_handoff}
    if clear_session:
        body["sessionId"] = None
    if recovery_state:
        body["recoveryState"] = recovery_state
    elif status_ == "running":
        body["recoveryState"] = None
    if isinstance(run_or_task, TaskRun):
        body.update(segments=run_or_task.segments, stalledSegments=run_or_task.stalled_segments,
                    lastSnapshotRevision=run_or_task.last_snapshot_revision, contextHandoff=run_or_task.context_handoff)
    else:
        for key in ("segments", "stalledSegments", "lastSnapshotRevision", "contextHandoff"):
            if key in run_or_task:
                body[key] = run_or_task[key]
    answer = await mooi.report_task(task_id, body)
    item_id = run_or_task.item_id if isinstance(run_or_task, TaskRun) else (answer.get("item") or {}).get("id")
    if item_id:
        _touch(UUID(str(item_id)))
    player_id = UUID(answer["playerId"]) if answer.get("playerId") else player_hint
    if player_id is not None:
        _publish_item(player_id, answer.get("item"))
    return answer.get("item") or {}


async def _task_recovery(task_id: UUID, attempt: int, segment: int | None = None) -> Json | None:
    try:
        recovery = await mooi.fetch_task_recovery(task_id, attempt)
    except ApiException as failure:
        if failure.status_code == 404:
            return None
        raise
    if (str(recovery.get("taskId")) != str(task_id) or recovery.get("attempt") != attempt
            or (segment is not None and recovery.get("segment", 0) != segment)):
        return {**recovery, "snapshotValid": False,
                "validityReason": "Saved recovery metadata does not match this task attempt and segment"}
    return recovery


def _clean_restart_pending(task: Json) -> bool:
    return bool((task.get("recoveryDisposition") or {}).get("restartPending"))


async def _cleanup_discarded_recovery(item: Json, task: Json, player_id: UUID) -> None:
    # The API has already durably invalidated these attempts. Cleanup never changes eligibility
    # or removes the historical metadata, and is safe to repeat after a process restart.
    for attempt in range(task["recoveryDisposition"]["discardedAttempt"] + 1):
        try:
            deleted = await workspaces.delete_recovery_attempt(
                player_id, UUID(item["projectId"]), UUID(item["id"]), UUID(task["id"]), attempt)
            if not deleted:
                LOG.warning("Task %s discarded recovery cleanup deferred for attempt %s; evidence remains ineligible",
                            task["id"], attempt)
        except Exception:
            LOG.warning("Task %s discarded recovery cleanup failed for attempt %s; evidence remains ineligible",
                        task["id"], attempt, exc_info=True)


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
    """Hands every running or paused item to its own advance, so no item waits for another's."""
    for session_id, run in list(_analysts.items()):
        if get_registry().get(session_id) is None and run.item_id not in _advancing:
            _analysts.pop(session_id, None)
    # A lease is saved before session provisioning. Exclude advancing items from reconciliation:
    # another tick can run while an open awaits the platform, before its session is registered.
    live_sessions = [str(session.id) for session in get_registry().all()]
    for changed in await mooi.reconcile_work_changes(live_sessions, [str(item_id) for item_id in _advancing]):
        changed_item = changed.get("item")
        if changed_item:
            _touch(UUID(changed_item["id"]))
        _publish_item(UUID(changed["playerId"]), changed_item)
        # The API terminally reconciles done items and returns their former session ids so this
        # process can close any still-live agent sessions, including sessions left by an old runner.
        for session_id in changed.get("sessionsToClose") or []:
            parsed_id = UUID(str(session_id))
            _runs.pop(parsed_id, None)
            with suppress(Exception):
                await sessions.close_session(parsed_id)
    started = _generation
    for item_id, saved in list(_saved.items()):
        if saved <= started and item_id not in _advancing:
            del _saved[item_id]
    entries = await mooi.fetch_running_work()
    # Each player's session limit, fetched once per tick and shared by every item advanced in it.
    session_limits: dict[UUID, int] = {}
    synchronized: dict[UUID, asyncio.Task[Json | None]] = {}
    for entry in entries:
        try:
            item = entry["item"]
            if item.get("status") not in ("running", "paused"):
                continue
            item_id = UUID(item["id"])
        except Exception:
            LOG.warning("The factory runner skipped an unreadable work item", exc_info=True)
            continue
        if item_id in _advancing:
            _behind.add(item_id)
            continue
        task = asyncio.create_task(_advance_item(entry, started, session_limits, synchronized))
        _advancing[item_id] = task
        _tasks.add(task)
        task.add_done_callback(lambda done, item_id=item_id: _advanced(item_id, done))


def _advanced(item_id: UUID, task: asyncio.Task[None]) -> None:
    _tasks.discard(task)
    if _advancing.get(item_id) is task:
        del _advancing[item_id]
    if item_id in _behind:
        _behind.discard(item_id)
        wake()


async def _advance_item(entry: Json, started: int, session_limits: dict[UUID, int],
                        synchronized: dict[UUID, asyncio.Task[Json | None]]) -> None:
    item = entry["item"]
    try:
        item_id = UUID(item["id"])
        if item.get("issueNumber") is not None:
            player_id, project_id = UUID(entry["playerId"]), UUID(item["projectId"])
            caller = await _caller(item_id, player_id, entry.get("authSessionId"))
            if caller is None:
                return
            # At most one GitHub sync per project and tick, shared by the project's items, and only
            # once the project's polling interval has passed.
            if project_id not in synchronized:
                synchronized[project_id] = asyncio.create_task(_sync_issues_if_due(caller, project_id))
            synced = await synchronized[project_id]
            if synced is not None:
                entry = {**entry, "item": next(
                    (row for row in synced.get("items", []) if row["id"] == item["id"]), item)}
        async with _lock_for(_item_locks, item_id):
            if _saved.get(item_id, 0) > started:
                # Saved by a settlement or finish since this tick read it: act on a fresh read instead.
                _behind.add(item_id)
                return
            await _advance(entry, session_limits)
    except asyncio.CancelledError:
        raise
    except Exception:
        LOG.warning("Work item %s could not advance", item.get("id"), exc_info=True)


async def _advance(entry: Json, session_limits: dict[UUID, int]) -> None:
    """Runs under the item's lock: relaunches lost tasks and launches the unblocked ones."""
    item = entry["item"]
    if item.get("status") not in ("running", "paused"):
        return
    item_id, player_id = UUID(item["id"]), UUID(entry["playerId"])
    settings: Json = {**DEFAULT_LIMITS, **(entry.get("settings") or {})}
    for run in _runs.values():
        if run.item_id == item_id:
            run.limits = settings
    caller = await _caller(item_id, player_id, entry.get("authSessionId"))
    if caller is None:
        return
    for run in list(_runs.values()):
        if run.item_id == item_id and run.resettle and item.get("status") == "running":
            run.resettle = False
            _background(_after_turn(run))
    registry = get_registry()
    tasks = item["tasks"]
    recoveries: dict[str, Json] = {}

    # A missing session is resumable only from a validated durable snapshot. Never turn a lost
    # workspace into a clean launch: the user gets an explicit attention state instead.
    for task in tasks:
        if task["status"] == "running" and not task.get("sessionId"):
            item = await _report(task, "attention", clear_session=True,
                                 report="Task was marked running without a live session; attention is required")
            tasks = item.get("tasks", tasks)
            continue
        if _clean_restart_pending(task):
            continue
        session_id = task.get("sessionId")
        if task["status"] not in ("running", "merging", "review", "attention") or not session_id:
            continue
        live = registry.get(UUID(session_id))
        if (known := _runs.get(UUID(session_id))) is not None and (known.handing_off or known.quota_pausing):
            continue
        if live is not None and live.closing:
            # close_session keeps the session registered until its recovery handoff and workspace
            # removal finish, so wait rather than racing a launch against the old checkout.
            continue
        if live is not None and not live.closing:
            run = _runs.get(live.id)
            adopted = run is None
            if run is None:
                recovery = await _task_recovery(UUID(task["id"]), task["attempts"])
                run = _adopt(item, task, live, player_id, settings, recovery=recovery)
                # This workspace is already alive; only its runner metadata was lost.
                run.recovery_expected = bool(getattr(live, "recovery_failed", False))
                if run.context_handoff == CONTINUATION:
                    # Its last turn end was counted before the continuation was saved; continue it once.
                    run.turn_end = run.counted_turn = live.log.last_seq
            if run.handing_off or run.quota_pausing:
                continue
            if run.context_handoff == "handoff":
                _background(_handoff_context(run))
                continue
            if run.phase not in ("merging", "review") and _quota_exhausted(live):
                _background(_pause_quota(run, live))
                continue
            if run.phase == "working" and run.status == "running" and live.status != STATUS_FAILED:
                _background(_check_context(run))
            if live.status == STATUS_FAILED:
                if task.get("qaOf") and isinstance(task.get("qaReport"), dict):
                    try:
                        report = TaskReport.model_validate(task["qaReport"])
                    except Exception:
                        LOG.warning("Could not restore saved QA report for task %s", task["id"], exc_info=True)
                        item = await _report(task, "attention", session_id=None,
                                             report="Its saved QA result is invalid and needs attention")
                    else:
                        answer = await mooi.report_defect(UUID(task["id"]), _qa_delivery_payload(report))
                        _touch(item_id)
                        item = answer.get("item") or item
                        _publish_item(player_id, item)
                        await _retire_task_recovery(player_id, UUID(item["projectId"]), UUID(item["id"]),
                                                    UUID(task["id"]), task["attempts"])
                    await _close(run)
                    tasks = item.get("tasks", tasks)
                    continue
                if run.recovery_expected:
                    reason = live.detail or "The saved task state could not be restored"
                    recovery = await _task_recovery(UUID(task["id"]), task["attempts"], task.get("segments", 0))
                    if recovery is not None:
                        reason = f"Recovery restore failed: {reason}"
                        try:
                            await mooi.invalidate_task_recovery(UUID(task["id"]), task["attempts"], reason)
                        except Exception:
                            LOG.warning("Could not invalidate unrestorable snapshot for task %s",
                                        task["id"], exc_info=True)
                    item = await _report(task, "attention", session_id=None, attempts=task["attempts"],
                                         report=f"Task recovery could not be restored safely: {reason}",
                                         recovery_state="unrestorable" if recovery else None,
                                         clear_session=True)
                    await _close(run)
                    tasks = item.get("tasks", tasks)
                else:
                    # Let normal close lifecycle save this workspace, then the next tick will restore
                    # only that durable handoff. A provisioning failure before cloning yields attention.
                    # The close snapshots the workspace, so it runs outside the item's lock; ticks skip
                    # the session meanwhile, as it is closing.
                    _background(_close_failed(live.id))
                continue
            if (live.status == STATUS_READY and live.pending is None and not live.queue
                    and run.phase != "merging" and not run.settling and not run.finishing):
                # Snapshots and settlements do git work and take the item's lock to save, so they run
                # outside this one; a settlement snapshots the workspace first.
                if run.status != "attention" and (
                        run.outcome is not None or run.phase == "resolving"
                        or (run.context_handoff == CONTINUATION and run.continued_turn is None)) and (
                        adopted or item.get("status") == "running"):
                    _background(_after_turn(run))
                elif adopted:
                    _background(_snapshot(run, live))
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
                _touch(item_id)
                item = answer.get("item") or item
                _publish_item(player_id, item)
                await _retire_task_recovery(player_id, UUID(item["projectId"]), UUID(item["id"]),
                                            UUID(task["id"]), task["attempts"])
            tasks = item.get("tasks", tasks)
            continue
        _runs.pop(UUID(session_id), None)
        recovery = await _task_recovery(UUID(task["id"]), task["attempts"], task.get("segments", 0))
        if task["status"] in ("running", "attention"):
            blocker = (recovery or {}).get("reportedOutcome") == "blocked" or (
                isinstance(task.get("qaReport"), dict)
                and bool(task["qaReport"].get("blockerCause")))
            stalled = str(task.get("report") or "").lower().startswith("no progress:")
            # Explicit external blockers and exhausted-progress states wait for the user. Other
            # orphaned active/attention tasks resume from their exact attempt and segment.
            if blocker or stalled:
                reason = task.get("report") or (recovery or {}).get("reportedSummary") or "Task is blocked"
                item = await _report(task, "attention", clear_session=True, report=reason)
                tasks = item.get("tasks", tasks)
                continue
            if _recovery_problem(recovery) is not None:
                problem = _recovery_problem(recovery)
                item = await _report(task, "attention", clear_session=True,
                                     report=f"Task recovery needs attention: {problem}",
                                     recovery_state="missing" if recovery is None else "invalid")
                tasks = item.get("tasks", tasks)
                continue
            assert recovery is not None
            LOG.warning("Task %s session %s disappeared; restoring attempt %s segment %s snapshot %s",
                        task["id"], session_id, task["attempts"], task.get("segments", 0),
                        recovery["snapshotRevision"])
            queued = await _queue_context_continuation(task, recovery, settings)
            tasks = queued.get("tasks", tasks)
            resumed = next((row for row in tasks if row["id"] == task["id"]), None)
            if resumed and resumed["status"] == "queued":
                recoveries[task["id"]] = recovery
            continue
        if task.get("contextHandoff") in (*_HANDOFF_STATES, CONTINUATION):
            # Complete a durable handoff after a runner restart between close and queue. A session lost
            # while its checkpoint was pending was being cut anyway, and one lost before its automatic
            # continuation reached the agent was continuing the same work: both continue from the last
            # saved snapshot as a new segment, consuming no attempt.
            item = await _queue_context_continuation(task, recovery, settings)
            tasks = item.get("tasks", tasks)
            continue
        # `attempts` never rewinds (snapshots are keyed by it); Retry and Start move `attemptsBase` instead.
        attempts = task["attempts"] + 1
        if attempts - task.get("attemptsBase", 0) > settings["maxAttempts"]:
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
            assert recovery is not None  # _recovery_problem(None) is always a failure.
            item = await _report(task, task["status"] if task["status"] in ("review", "merging") else "planned",
                                 attempts=attempts)
            if recovery is not None:
                recoveries[task["id"]] = recovery
        tasks = item.get("tasks", tasks)

    # Reconcile paused tasks above, but never open a new session until Resume.
    if item.get("status") != "running":
        return
    # Before launching, so the tasks it unblocks already find the current design on the default branch.
    await _write_feature_document(item, caller)
    if item.get("dependsOn") and not await _dependency_done(caller, item):
        return
    done = {task["id"] for task in tasks if task["status"] == "done"}
    if await _advance_changes(item, caller, player_id, settings, session_limits):
        return
    ready = [task for task in tasks if task["status"] in ("planned", "queued", "review", "merging")
             and not task.get("sessionId")
             and all(dependency in done for dependency in task["dependsOn"])]
    for task in ready:
        if not await _has_room(caller, player_id, session_limits):
            if not str(task.get("report") or "").startswith(_QUOTA_WAIT):
                await _wait(task, "Waiting for a free session")
            continue
        await _launch(item, task, caller, player_id, settings, recovery=recoveries.get(task["id"]))


async def _dependency_done(caller: Caller, item: Json) -> bool:
    work = await mooi.fetch_work(caller, UUID(item["projectId"]))
    other = next((entry for entry in work.get("items", []) if entry["id"] == item["dependsOn"]), None)
    return other is None or other["status"] == "done"


async def _has_room(caller: Caller, player_id: UUID, session_limits: dict[UUID, int]) -> bool:
    if player_id not in session_limits:
        session_limits[player_id] = await mooi.fetch_session_limit(caller)
    registry = get_registry()
    return (registry.count() < get_settings().max_sessions
            and registry.owned(player_id) < session_limits[player_id])


def _adopt(item: Json, task: Json, session: Session, player_id: UUID, settings: Json, *,
           recovery: Json | None = None) -> TaskRun:
    run = TaskRun(session_id=session.id, item_id=UUID(item["id"]), task_id=UUID(task["id"]),
                  project_id=UUID(item["projectId"]), player_id=player_id, qa=task["qaOf"] is not None,
                  title=task["title"], auto_merge=bool(task["autoMerge"]), issue_number=item.get("issueNumber"),
                  attempts=task["attempts"], report=task.get("report"),
                  segments=task.get("segments", 0), stalled_segments=task.get("stalledSegments", 0),
                  last_snapshot_revision=task.get("lastSnapshotRevision"),
                  context_handoff=task.get("contextHandoff"),
                  checkpoint_started=task.get("contextHandoff") == "checkpoint",
                  qa_rounds=task.get("qaRounds", 0),
                  status=task["status"], phase="review" if task["status"] == "review" else "working", limits=settings,
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


async def _launch(item: Json, task: Json, caller: Caller, player_id: UUID, settings: Json, *,
                  recovery: Json | None = None) -> None:
    clean_restart = _clean_restart_pending(task)
    if clean_restart:
        recovery = None
    elif (recovery is None and not task.get("qaOf")
          and (task.get("recoveryDisposition") or {}).get("state") in ("invalid", "incomplete", "unrestorable")):
        # Retry preserves unusable evidence. In particular, an absent previous-attempt lookup
        # must not turn structured evidence of a failed restore into an implicit clean start.
        await _report(task, "attention", report="Task recovery is unusable and needs attention; "
                      "Retry preserves saved evidence. A clean restart requires an explicit user decision.")
        return
    quota_wait = not clean_restart and str(task.get("report") or "").startswith(_QUOTA_WAIT)
    if not clean_restart and recovery is None and task.get("contextHandoff") in ("resume", "handoff"):
        source_segment = task.get("segments", 0) - (1 if task.get("contextHandoff") == "resume" else 0)
        recovery = await _task_recovery(UUID(task["id"]), task["attempts"], source_segment)
        if recovery is None:
            await _report(task, "attention", report=_recovery_problem(None))
            return
    elif (not clean_restart and recovery is None
          and (quota_wait or not (task.get("qaOf") and task["status"] in ("planned", "queued")))):
        source_attempt = task["attempts"] if quota_wait else max(0, task["attempts"] - 1)
        discarded = (task.get("recoveryDisposition") or {}).get("discardedAttempt")
        if discarded is not None and source_attempt <= discarded:
            # Classify it as missing so an explicit clean restart is offered; mic-mooi keeps the
            # historical discardedAttempt marker, and Retry still refuses to start from scratch.
            await _report(task, "attention", report="No eligible recovery snapshot exists after the discarded attempt",
                          recovery_state="missing")
            return
        recovery = await _task_recovery(UUID(task["id"]), source_attempt, task.get("segments", 0))
        if quota_wait and recovery is None:
            await _report(task, "attention", report="Task recovery needs attention: no saved quota checkpoint")
            return
        if recovery is None and task["attempts"] > 0 and not task.get("qaOf"):
            await _report(task, "attention", report=_recovery_problem(None))
            return
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
    assignment = await _assign(caller, task, _quota_limit(settings))
    if assignment is None:
        if not quota_wait:
            resets = [reset for windows in _launch_quota_cache.values() if windows[1]
                      for value in windows[1].values() if isinstance(value, dict)
                      if (reset := _reset_at(value.get("resetsAt"))) is not None and reset > time.time()]
            reset = min(resets, default=None)
            suffix = f" until {datetime.fromtimestamp(reset, timezone.utc):%H:%M} UTC" if reset else ""
            await _wait(task, "Waiting for an account with the requested model and quota left" + suffix)
            _schedule_quota(reset)
        return
    # Assignment can take time; refresh the item before opening any session, including features.
    if item.get("issueNumber") is not None:
        item = await mooi.send_work(caller, "PUT", UUID(item["projectId"]),
                                    f"/issues/{item['issueNumber']}/item", github=True) or {}
        _publish_item(player_id, item)
    else:
        work = await mooi.fetch_work(caller, UUID(item["projectId"]))
        item = next((row for row in work.get("items", []) if row["id"] == item["id"]), {})
    task = next((row for row in item.get("tasks", []) if row["id"] == task["id"]), {})
    if (item.get("status") != "running"
            or any(change["status"] == "analyzing" for change in item.get("changeRequests", []))
            or task.get("status") not in ("planned", "queued", "review", "merging")
            or task.get("sessionId")):
        return
    # An assignment lookup may race a user decision or another runner's accepted launch.
    # Reconcile from the fresh durable intent on the next tick instead of using stale evidence.
    if clean_restart != _clean_restart_pending(task):
        return
    if clean_restart:
        task = {**task, "attempts": max(task["attempts"], task["recoveryDisposition"]["discardedAttempt"] + 1)}
        await _cleanup_discarded_recovery(item, task, player_id)
    key = re.sub(r"[^a-z0-9-]+", "-", task["key"].lower()).strip("-")[:40] or "task"
    branch = recovery["branch"] if recovery else f"factory/{key}-{uuid4().hex if clean_restart else uuid4().hex[:6]}"
    recovery_context = _recovery_context(recovery, item, task, player_id) if recovery else None
    qa_report_pending = bool(task.get("qaOf") and task["status"] not in ("planned", "queued")
                             and task.get("sessionId") and isinstance(task.get("qaReport"), dict))
    recovered_outcome = ((task.get("qaReport") or {}).get("outcome")
                         if qa_report_pending else (recovery or {}).get("reportedOutcome"))
    completed_report = recovered_outcome in ("done", "passed", "defect") or (
        task.get("qaOf") and recovered_outcome == "blocked")
    initial_message = None if completed_report else _brief(item, task, recovery)
    if initial_message and recovery:
        initial_message += (
            "\n\nFactory recovery: the runner detected that the previous session record no longer exists; "
            "its provider runtime is not being resumed. "
            f"The runner validated and restored snapshot revision {recovery['snapshotRevision']} "
            f"from attempt {recovery['attempt']}, segment {recovery.get('segment', 0)}. "
            "Continue in this new provider runtime and report the task outcome through the task API."
        )
    try:
        session = await sessions._open_session(CreateSessionRequest(
            projectId=UUID(item["projectId"]), provider=assignment["provider"],
            connectionId=UUID(assignment["connectionId"]), branch=branch, model=assignment.get("model"),
            effort=assignment.get("effort"), initialMessage=initial_message), caller, recovery=recovery_context)
    except ApiException as failure:
        if failure.status_code == status.HTTP_409_CONFLICT:
            if not quota_wait:
                await _wait(task, failure.message)
            return
        await _report(task, "attention", report=f"Its session could not start: {failure.message}")
        return
    # Registered before provisioning builds the agent's instructions, which read it.
    launch_status = task["status"] if task["status"] in ("review", "merging") else "running"
    run_task = {**task, "status": launch_status, "contextHandoff": None}
    if task["status"] in ("planned", "queued"):
        run_task["qaReport"] = None
    run = _adopt(item, run_task, session, player_id, settings, recovery=recovery)
    updated = await _report(run, launch_status, session_id=session.id, attempts=task["attempts"],
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
    wait = re.match(r"Esperando cuota hasta (\S+) \(cuenta ([^)]+)\)", str(task.get("report") or ""))
    if wait:
        reset = _reset_at(wait[1])
        if reset is None or reset > time.time():
            # Respect a healthy provider snapshot; the durable wait only fills missing evidence.
            if not snapshots.get(wait[2]) and not _quota(wait[2]):
                snapshots[wait[2]] = {"quota_wait": {"percent": 100}}
            _schedule_quota(reset)
        else:
            # Expired evidence must allow a retry even if the last provider snapshot is still exhausted.
            windows = snapshots.get(wait[2], _quota(wait[2]))
            snapshots[wait[2]] = {key: value for key, value in windows.items()
                                  if not isinstance(value, dict)
                                  or (_reset_at(value.get("resetsAt")) or float("inf")) > time.time()}
    resets = [reset for windows in snapshots.values() for value in windows.values()
              if isinstance(value, dict)
              if (reset := _reset_at(value.get("resetsAt"))) is not None and reset > time.time()]
    _schedule_quota(min(resets, default=None))
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


# --- design memory: each feature's document in the repository -------------------------------------
#
# A feature's spec and task breakdown are also written to `docs/features/<slug>-<id8>/FEATURE.md` on the
# default branch, so the design travels with merges and reads without Mooi; development tasks add their
# `decisions/<task key>.md` beside it on their own branches. The platform stays the source of truth for
# state: the document holds no statuses, assignments or dates, so it only changes when the design does.

_CATEGORY_LABELS = {"ux": "UX/UI", "dev": "Development", "qa": "QA", "infra": "Infra/DevOps", "docs": "Documentation"}

# Item id → digest of the document the default branch is known to hold; lost on restart, which only
# costs one fetch that finds nothing to commit.
_documents: dict[UUID, str] = {}


def _feature_slug(item: Json) -> str:
    title = unicodedata.normalize("NFKD", item.get("title") or "").encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", "-", title).strip("-")[:48].strip("-") or "feature"


def feature_directory(item: Json) -> str:
    """The one place a feature's design memory lives in its repository: `docs/features/<slug>-<id8>/`. The
    first 8 characters of the item id keep it unique and let a renamed feature's folder be found again."""
    return f"docs/features/{_feature_slug(item)}-{str(item['id'])[:8]}/"


def _design_memory_brief(item: Json, task: Json | None = None) -> str:
    if item.get("kind") != "feature":
        return ""
    directory = feature_directory(item)
    brief = (f"Design memory: `{directory}`. Before starting, read `FEATURE.md` and the files in `decisions/` "
             "there; skip missing files or folders without error.")
    if task is not None and not task.get("qaOf"):
        brief += (f"\nCreate or update `{directory}decisions/{task['key']}.md` on your task branch, even if the "
                  "folder is missing, so it travels with your task merge. Keep it brief and concrete, with sections "
                  "`Decisiones`, `Alternativas descartadas` (and why), and `Ficheros clave`.")
    return brief


def _table_cell(value: str) -> str:
    return " <br> ".join(line.strip() for line in value.strip().splitlines() if line.strip()).replace("|", "\\|")


def feature_document(item: Json) -> str:
    """`FEATURE.md`: title, item id, the full spec and the task table (fix tasks QA raises are execution,
    not design, and stay out)."""
    tasks = [task for task in item.get("tasks", []) if not task.get("fixOf")]
    keys = {task["id"]: task.get("key") or task["id"] for task in tasks}
    rows = [
        "| " + " | ".join(_table_cell(value) for value in (
            task.get("key") or "", task.get("title") or "",
            _CATEGORY_LABELS.get(task.get("category") or "", task.get("category") or ""),
            ", ".join(keys.get(dependency, dependency) for dependency in task.get("dependsOn") or []) or "-",
            task.get("acceptance") or "-",
        )) + " |"
        for task in tasks
    ]
    table = "\n".join(["| Key | Title | Category | Depends on | Acceptance criteria |",
                       "| --- | --- | --- | --- | --- |", *rows]) if rows else "No tasks yet."
    return "\n".join([
        f"# {(item.get('title') or '').strip()}",
        "",
        "Design memory of a Mooi software factory feature, kept by the platform. Its live state (task statuses,",
        "sessions, assignments) lives in Mooi; each task's technical decisions are in `decisions/`.",
        "",
        "## Item",
        "",
        f"`{item['id']}`",
        "",
        "## Goal and spec",
        "",
        (item.get("spec") or "").strip() or "No spec yet.",
        "",
        "## Tasks",
        "",
        table,
        "",
    ])


async def _write_feature_document(item: Json, caller: Caller) -> None:
    """Commits the feature's document onto the default branch when its design changed since the last
    write. Runs under the item's lock, so no task of the item claims a merge meanwhile; a failure is
    only logged and retried on the next tick, never blocking or pausing the feature."""
    if item.get("kind") != "feature" or item.get("issueNumber") is not None:
        return
    item_id = UUID(item["id"])
    directory, content = feature_directory(item), feature_document(item)
    digest = hashlib.sha256(f"{directory}\0{content}".encode()).hexdigest()
    if _documents.get(item_id) == digest:
        return
    if (any(task["status"] == "merging" for task in item.get("tasks", []))
            or any(run.item_id == item_id and run.phase in ("resolving", "merging") for run in _runs.values())):
        return  # A task merge already claimed is pushing: write on a later tick instead of racing it.
    slug = _feature_slug(item)
    try:
        project = await mooi.fetch_project(caller, UUID(item["projectId"]))
        token, identity = await asyncio.gather(mooi.fetch_github_token(caller), mooi.fetch_github_identity(caller))
        await workspaces.commit_documents(
            project.full_name, project.default_branch, directory.rstrip("/"), {"FEATURE.md": content},
            f"docs(factory): update {slug} feature document", identity, token,
            adopt_suffix=f"-{str(item['id'])[:8]}")
    except asyncio.CancelledError:
        raise
    except Exception:
        LOG.warning("The document of feature %s could not be written; retrying on the next tick", item_id,
                    exc_info=True)
        return
    _documents[item_id] = digest


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
    if _clean_restart_pending(task):
        lines += ["", "## Explicit clean restart", "",
                  "This session starts clean from the currently integrated default branch because the user "
                  "explicitly discarded the previous unrecoverable task state. Previous unmerged work "
                  "was discarded; no previous task work was restored."]
        recovery = None
    if memory := _design_memory_brief(item, task):
        lines += ["", memory]
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
                block("Known obstacles", "\n".join(
                    f"- {value}" for value in handoff.get("obstacles", [])), "(none reported)")
                block("Declared preparation", handoff.get("preparation"), "(not declared)")
                lines += ["", ("This report is orientation only. Independently verify the integrated default branch "
                                "using the acceptance criteria and this QA task's preparation.")]
            else:
                lines += ["", "## Legacy evidence limits", "",
                          f"Merged commit: {tested.get('mergeSha') or 'missing; no SHA is available to verify'}",
                          ("Development summary, changed files, prior checks and declared preparation: unavailable "
                           "in this legacy task record. Do not infer them; validate independently from the criteria.")]
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
        if task.get("contextHandoff") == "resume":
            block("Context handoff", f"Continuación por contexto, tramo {task.get('segments', 0) + 1}. "
                  f"Restored attempt {recovery['attempt']}, segment {recovery.get('segment', 0) + 1}; "
                  "continue from the previous checkpoint without repeating completed work.")
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
    analyst = _analysts.get(session.id)
    if analyst is not None:
        if type_ == EVENT_TURN_RESULT:
            _background(_after_analyst_turn(analyst))
        elif type_ == EVENT_SESSION_STATUS and data.get("status") == STATUS_FAILED:
            wake()
        return
    run = _runs.get(session.id)
    if run is None:
        return
    quota_error = type_ in ("error", EVENT_TURN_RESULT, EVENT_SESSION_STATUS) and bool(re.search(
        r"rate[ _-]?limit|usage[ _-]?limit|quota.{0,30}(?:exceed|exhaust)|too many requests|limit.{0,20}reached",
        str(data), re.IGNORECASE))
    if (run.phase not in ("merging", "review") and not run.finishing and not run.handing_off
            and (_quota_exhausted(session) or quota_error)):
        _background(_pause_quota(run, session))
        return
    if type_ == EVENT_TURN_RESULT:
        # Capture at event time: queue delivery can start the checkpoint before settlement runs.
        if run.checkpoint_started:
            run.checkpoint_finished = True
        run.turn_end = session.log.last_seq
        _background(_after_turn(run))
    elif type_ == "session.usage":
        if run.phase == "working" and run.status == "running":
            # Remember the event synchronously: automatic compaction can lower usage before the
            # background handler runs, or while it is already queuing the checkpoint.
            run.context_peak = max(run.context_peak, _context_percent(session))
        _background(_check_context(run))
    elif type_ == "message.user" and data.get("text") == CHECKPOINT:
        run.checkpoint_started = True
        run.context_handoff = "checkpoint"
        _background(_checkpoint_started(run))
    elif type_ == "message.user" and str(data.get("text") or "").startswith(_CONTINUE_MARK):
        _background(_continuation_delivered(run))
    elif (type_ == EVENT_SESSION_STATUS and data.get("status") == STATUS_READY
          and run.recovered and run.outcome is not None):
        # Durable results are settled by the deterministic runner after the restored workspace is ready;
        # do not send the implementation brief again or ask the model to repeat completed work.
        _background(_after_turn(run))
    elif type_ in (EVENT_QUESTION_REQUEST, EVENT_PERMISSION_REQUEST):
        _background(_set_status(run, "attention", "The agent is waiting for your answer in its session"))
    elif ((type_ in (EVENT_QUESTION_RESOLVED, EVENT_PERMISSION_RESOLVED) and session.pending is None)
          or (type_ == "message.user" and run.status == "attention")):
        if type_ == "message.user":
            run.stalled_segments = 0  # The user stepped in: count turns without progress afresh.
        _background(_set_status(run, "running", None))
    elif type_ == EVENT_SESSION_STATUS and data.get("status") == STATUS_FAILED:
        _background(_set_status(run, "attention", "Its session failed: reconnect it from the session page"))
    elif type_ == EVENT_MERGE_COMPLETED:
        commit = data.get("commit")
        _background(_finish(run, f"Merged as {str(commit or '')[:10]}", merge_sha=commit))


sessions.session_listeners.append(_on_session_event)


def _context_percent(session: Session) -> float:
    context = session.usage.get("context") or {}
    percent = context.get("percent")
    return float(percent) if isinstance(percent, (int, float)) else 0


async def _checkpoint_started(run: TaskRun) -> None:
    await _save(run, run.status, report="Checkpoint por contexto en curso")


async def _check_context(run: TaskRun) -> None:
    """One checkpoint request per segment, including usage events while a turn is active."""
    session = get_registry().get(run.session_id)
    if (run.checking_context or run.handing_off or run.quota_pausing
            or not _current(run) or session is None or session.closing
            or run.phase != "working" or run.status != "running" or run.finishing
            or (run.outcome is not None and run.outcome.outcome != "continue")):
        return
    threshold = run.limits["contextHandoffPercent"]
    percent = max(_context_percent(session), run.context_peak)
    if percent < threshold and run.context_handoff not in _HANDOFF_STATES:
        return
    run.checking_context = True
    try:
        if percent >= threshold + 15 or run.context_handoff in ("forced", "handoff"):
            run.context_handoff = "forced"
            await _handoff_context(run)
            return
        if run.context_handoff == "checkpoint":
            if session.status == STATUS_READY and not session.queue:
                run.checkpoint_finished = True
                await _handoff_context(run)
            return
        if run.context_handoff == "requested" and any(message.text == CHECKPOINT for message in session.queue):
            return
        caller = await _run_caller(run)
        run.context_handoff = "requested"
        await _save(run, run.status, report="Checkpoint solicitado por contexto")
        if run.quota_pausing or not _current(run) or session.closing:
            return
        active = session.status in (STATUS_WORKING, STATUS_WAITING)
        # Queue before interrupting: a fast terminal result must not lose the checkpoint request.
        await sessions.queue_message(run.session_id, SendMessageRequest(text=CHECKPOINT), caller)
        if active and not run.checkpoint_started and session.status in (STATUS_WORKING, STATUS_WAITING):
            with suppress(ApiException):
                await sessions.interrupt_session(run.session_id, caller)
        if max(_context_percent(session), run.context_peak) >= threshold + 15:
            run.context_handoff = "forced"
            await _handoff_context(run)
    except CredentialRevoked:
        run.resettle = True
    except Exception:
        LOG.warning("Task %s could not request its context checkpoint", run.task_id, exc_info=True)
        # A saved request with no queued message is retried on the next tick.
    finally:
        run.checking_context = False


async def _queue_context_continuation(task: Json, recovery: Json | None,
                                      limits: Json | None = None) -> Json:
    """Finish the durable close -> queue transition, also used after runner restart."""
    if problem := _recovery_problem(recovery):
        return await _report(task, "attention", clear_session=True, report=problem)
    assert recovery is not None
    revision = recovery["snapshotRevision"]
    unchanged = revision == task.get("lastSnapshotRevision") or (
        task.get("lastSnapshotRevision") is None and not recovery.get("files"))
    # A pending automatic continuation already counted the turn that ended without a report.
    continuation = task.get("contextHandoff") == CONTINUATION
    stalled = task.get("stalledSegments", 0) + (0 if continuation else 1) if unchanged else 0
    task = {**task, "segments": task.get("segments", 0) + 1, "stalledSegments": stalled,
            "lastSnapshotRevision": revision, "contextHandoff": "resume"}
    limit = (limits or DEFAULT_LIMITS)["stallTurns"]
    status_ = "attention" if stalled >= limit else "queued"
    reason = (_no_progress(stalled, "tramos") if status_ == "attention"
              else f"Continuación automática sin resultado, tramo {task['segments'] + 1}" if continuation
              else f"Continuación por contexto, tramo {task['segments'] + 1}")
    return await _report(task, status_, clear_session=True, report=reason)


def _no_progress(count: int, unit: str) -> str:
    return f"No progress: sin progreso tras {count} {unit} consecutivos"


async def _continue_without_result(run: TaskRun, session: Session, saved: Json | None) -> None:
    """A working turn ended with no task report: the runner continues the task in the same session.

    Deterministic, never an LLM supervisor. The turn's end-of-turn snapshot must have been saved first;
    without it the task waits for the user (the recovery flow) instead of going on from unsaved work.
    Each turn counts against `stallTurns`, sharing the count with segments: an unchanged snapshot revision
    is one more turn without progress, any change restarts it. The count and the pending continuation are
    saved before the continuation is queued, and each turn end is counted and continued once, so replays
    and restarts never duplicate them. A paused item continues on Resume."""
    turn = run.turn_end
    if turn is not None and turn == run.continued_turn:
        return
    if saved is None:
        LOG.warning("Task %s ended a turn without a report and its snapshot could not be saved", run.task_id)
        run.context_handoff = None
        await _save(run, "attention", report=(
            "Task recovery needs attention: the turn ended without a task report and its workspace snapshot "
            "could not be saved, so the task was not continued automatically."))
        return
    if turn is None or turn != run.counted_turn:
        revision = saved["snapshotRevision"]
        unchanged = revision == run.last_snapshot_revision or (
            run.last_snapshot_revision is None and not saved.get("files"))
        run.stalled_segments = run.stalled_segments + 1 if unchanged else 0
        run.last_snapshot_revision = revision
        run.counted_turn = turn
    limit = run.limits["stallTurns"]
    if run.stalled_segments >= limit:
        LOG.info("Task %s waits for the user after %s turns without a report or progress",
                 run.task_id, run.stalled_segments)
        run.context_handoff = None
        await _save(run, "attention", report=(
            f"No progress: sin resultado de tarea tras {run.stalled_segments} turnos consecutivos sin progreso"))
        return
    if _quota_exhausted(session):
        await _pause_quota(run, session)
        return
    caller = await _run_caller(run)
    run.context_handoff = CONTINUATION
    async with _lock_for(_item_locks, run.item_id):
        # Read under the item's lock, like merge claims: Pause lands either before (no new turn until
        # Resume) or after the continuation was queued.
        if not _current(run) or session.closing or run.status != "running" or run.outcome is not None:
            return
        work = await mooi.fetch_work(caller, run.project_id)
        item = next((row for row in work.get("items", []) if row["id"] == str(run.item_id)), {})
        if item.get("status") != "running":
            run.resettle = True
            await _report(run, "running", report="Turno sin resultado: continuará automáticamente al reanudar")
            return
        await _report(run, "running", report=(
            f"Continuación automática: el turno terminó sin resultado ({run.stalled_segments} de {limit} "
            "sin progreso)"))
        if not any(message.text.startswith(_CONTINUE_MARK) for message in session.queue):
            await sessions.queue_message(run.session_id, SendMessageRequest(text=CONTINUE.format(
                revision=str(saved["snapshotRevision"])[:12], stalled=run.stalled_segments, limit=limit)),
                caller)
        run.continued_turn = turn
    LOG.info("Task %s continues automatically after a turn without a report (%s of %s without progress)",
             run.task_id, run.stalled_segments, limit)


async def _continuation_delivered(run: TaskRun) -> None:
    """The continuation reached the agent: from here a lost session is an ordinary lost turn again."""
    if run.context_handoff == CONTINUATION:
        run.context_handoff = None
        with suppress(Exception):
            await _save(run, run.status, report=run.report)


async def _handoff_context(run: TaskRun) -> None:
    """Stop the runtime before its final snapshot; handoff never consumes an attempt."""
    if (run.handing_off or run.quota_pausing or not _current(run)
            or run.phase != "working" or run.status != "running" or run.finishing
            or (run.outcome is not None and run.outcome.outcome != "continue")):
        return
    run.handing_off = True
    try:
        await _run_caller(run)
        run.context_handoff = "handoff"
        await _save(run, run.status, report="Guardando continuación por contexto")
        # Keep the run registered so the normal cleanup hook captures after runtime.close().
        await sessions.close_session(run.session_id)
        recovery = await _task_recovery(run.task_id, run.attempts, run.segments)
        async with _lock_for(_item_locks, run.item_id):
            if not _current(run):
                return
            await _queue_context_continuation({
                "id": str(run.task_id), "attempts": run.attempts, "segments": run.segments,
                "stalledSegments": run.stalled_segments, "lastSnapshotRevision": run.last_snapshot_revision,
            }, recovery, run.limits)
            _runs.pop(run.session_id, None)
    except CredentialRevoked:
        run.resettle = True
    except Exception:
        LOG.warning("Task %s could not complete its context handoff; the runner will reconcile it",
                    run.task_id, exc_info=True)
        if get_registry().get(run.session_id) is None:
            _runs.pop(run.session_id, None)
    finally:
        run.handing_off = False
        wake()


def _handoff_text(value: str) -> str:
    """Keep recovery metadata compact and redact credentials and host-local paths."""
    text = workspaces.Workspaces._safe_handoff_text(value)
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
                            final_summary: str | None = None, final_outcome: str | None = None) -> Json | None:
    """Capture one end-of-turn/handoff snapshot and persist its revisioned metadata for recovery_runner.

    The object bundle stays in workspaces' private store. Only opaque revision and compact, scrubbed
    metadata cross to mic-mooi. A failed capture is persisted as invalid evidence so no consumer can
    mistake missing/corrupt data for a recoverable workspace. Returns the saved metadata when this
    capture succeeded, None when it failed (whether or not an older valid snapshot was kept).
    """
    async with run.recovery_lock:
        existing = await _task_recovery(run.task_id, run.attempts)
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
                previous_revision=existing.get("snapshotRevision") if existing and existing.get("snapshotValid") else None,
            )
            head_commit = snapshot.head_commit
            revision = snapshot.snapshot_revision
            saved_files = snapshot.files
            valid, reason = True, None
            captured = True
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
            captured = False
        if report is None and existing:
            outcome = outcome or existing.get("reportedOutcome")
            title = title or existing.get("reportedTitle")
            reported_summary = reported_summary or existing.get("reportedSummary")
            preparation = preparation or _handoff_text(existing.get("reportedPreparation") or "")
            if not safe_summary:
                safe_summary = existing.get("summary") or "Workspace captured without a new handoff report."
            safe_next_step = existing.get("nextStep") or ""
            verifications = existing.get("verifications") or []
            obstacles = existing.get("obstacles") or []
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
        saved: Json = {
            "attempt": run.attempts,
            "segment": run.segments,
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
        }
        await mooi.save_task_recovery(run.task_id, saved)
        return saved if captured else None


async def _before_workspace_cleanup(session: Session) -> None:
    """Last chance to preserve an active factory workspace before normal session removal."""
    run = _runs.get(session.id)
    if run is not None and not session.recovery_failed:
        await _persist_recovery(run, session, report=run.outcome)


sessions.workspace_cleanup_listeners.append(_before_workspace_cleanup)


async def _snapshot(run: TaskRun, session: Session) -> None:
    """Saves an adopted workspace's recovery evidence in the background."""
    try:
        await _persist_recovery(run, session, report=run.outcome)
    except Exception:
        LOG.warning("Task %s could not save its recovery snapshot", run.task_id, exc_info=True)


async def _set_status(run: TaskRun, status_: str, report: str | None) -> None:
    async with _lock_for(_item_locks, run.item_id):
        if _current(run) and run.status != status_ and run.phase != "merging" and not run.finishing:
            await _report(run, status_, report=report)


async def _save(run: TaskRun, status_: str, *, report: str | None = None) -> bool:
    """Saves a run's progress under its item's lock, unless the run ended while it worked unlocked."""
    async with _lock_for(_item_locks, run.item_id):
        if not _current(run):
            return False
        await _report(run, status_, report=report)
        return True


async def _deliver(run: TaskRun, report: TaskReport) -> None:
    """Hands a QA result to mic-mooi, which records its fixes or the pass, and closes the QA session."""
    async with _lock_for(_item_locks, run.item_id):
        if not _current(run):
            return
        answer = await mooi.report_defect(run.task_id, _qa_delivery_payload(report))
        _touch(run.item_id)
        _publish_item(run.player_id, answer.get("item"))
        run.outcome = None
        await _close(run)
    await _retire_task_recovery(run.player_id, run.project_id, run.item_id, run.task_id, run.attempts)


async def _after_turn(run: TaskRun) -> None:
    """Settles the end of a turn. One settlement per run at a time: a turn ending meanwhile is settled
    right after it, never alongside, so a task is merged once."""
    if run.settling:
        run.settle_again = True
        return
    run.settling = True
    try:
        run.settle_again = True
        while run.settle_again:
            run.settle_again = False
            await _settle_turn(run)
    finally:
        run.settling = False
    wake()


async def _settle_turn(run: TaskRun) -> None:
    session = get_registry().get(run.session_id)
    if (not _current(run) or session is None or session.closing or run.finishing
            or run.handing_off or run.quota_pausing):
        return
    try:
        saved = await _persist_recovery(run, session, report=run.outcome)
        if not _current(run) or session.closing or run.handing_off or run.quota_pausing:
            return
        if run.phase == "working" and run.status == "running":
            # A checkpoint's terminal result hands off even when it omitted its report.
            if ((run.checkpoint_finished or run.context_handoff in ("forced", "handoff")
                 or (run.context_handoff not in _HANDOFF_STATES and run.outcome is not None
                     and run.outcome.outcome == "continue" and session.status == STATUS_READY))
                    and (run.outcome is None or run.outcome.outcome == "continue")):
                await _handoff_context(run)
                return
            if run.outcome is None:
                await _check_context(run)
                if run.context_handoff in _HANDOFF_STATES or session.closing or run.quota_pausing:
                    return
                # Turns cut for a checkpoint are not counted: their segment is, once it hands off.
                # A message the user queued meanwhile is their own continuation: it goes out first.
                if not session.queue and session.status == STATUS_READY and session.pending is None:
                    await _continue_without_result(run, session, saved)
                return
        if session.queue:
            return
        await _settle(run, session)
    except CredentialRevoked:
        # The item is paused; its task keeps its state and settles again once resumed.
        run.resettle = True
    except Exception as failure:
        LOG.warning("Task %s could not settle", run.task_id, exc_info=True)
        run.phase = "working"
        reason = failure.message if isinstance(failure, ApiException) else "Its work could not be wrapped up"
        with suppress(Exception):
            await _save(run, "attention", report=f"{reason}: continue in its session or start the item again")


async def _settle(run: TaskRun, session: Session) -> None:
    if run.phase == "merging":
        # The merge went through and its `merge.completed` event finishes the task.
        return
    if run.phase == "resolving" or (run.phase == "review" and run.auto_merge and not run.qa):
        # `review` with auto-merge: a merge deferred while its item was paused, retried on Resume.
        await _merge(run)
        return
    if run.phase == "review":
        return
    report = run.outcome
    if report is None:
        # A working turn without a report is continued by `_continue_without_result`; a task waiting
        # for the user stays so until they step in.
        return
    if run.context_handoff == CONTINUATION:
        run.context_handoff = None  # Reported: the automatic continuation is over.
    if not run.qa and report.outcome == "done":
        changes = await workspaces.changes(session.workspace, session.base_commit)
        changed_files = {file.path for file in changes.files}
        reported_files = set(_handoff_files(report.files))
        missing_reported = sorted(reported_files - changed_files)
        unreported_changes = sorted(changed_files - reported_files)
        for label, paths in (("Reported files not changed", missing_reported),
                             ("Changed files not reported", unreported_changes)):
            if paths:
                shown = paths[:50]
                suffix = f", and {len(paths) - len(shown)} more" if len(paths) > len(shown) else ""
                note = _handoff_text(f"{label}: {', '.join(shown)}{suffix}")[:2_000]
                if note not in report.obstacles:
                    report.obstacles = report.obstacles[:199]
                    report.obstacles.append(note)
        # Persist the comparison notes even when the result is rejected, so recovery retains
        # the evidence used by the runner.
        await _persist_recovery(run, session, report=report)
        if not changed_files:
            if not run.verification_nudged:
                run.verification_nudged = True
                run.outcome = None
                await sessions.queue_message(
                    run.session_id,
                    SendMessageRequest(text=(
                        "You reported `done`, but this branch has no changes relative to its base commit. "
                        "Make the required task changes, then report the outcome again. Do not claim completion "
                        "without a real change."
                    )),
                    await _run_caller(run),
                )
                return
            run.outcome = None
            await _save(
                run, "attention",
                report=("Reported done again, but the task branch still has no changes relative to its base "
                        "commit. No merge was performed."),
            )
            return
        run.verification_nudged = False
    elif report.outcome == "blocked":
        run.verification_nudged = False
    if report.outcome == "blocked":
        if run.qa:
            await _deliver(run, report)
        else:
            run.outcome = None
            await _save(run, "attention", report=report.summary)
    elif report.outcome == "passed":
        if run.qa:
            await _deliver(run, report)
        else:
            run.outcome = None
            await _finish(run, report.summary)
    elif report.outcome == "defect":
        await _deliver(run, report)
    elif run.auto_merge:
        await _merge(run)
    else:
        run.phase = "review"
        await _save(run, "review", report=f"{report.summary}\n\nReady for your merge from its session.")


async def _merge(run: TaskRun) -> None:
    """Merges a finished development task: clean merges at once, conflicts through the agent first.
    Git and GitHub work runs with no lock held; the run is checked again after each step. A paused item
    defers the merge until Resume."""
    caller = await _run_caller(run)
    if not await _claim_merge(run, caller):
        return
    preview = await sessions.check_merge(run.session_id, caller)
    if not _current(run):
        return
    if preview.state == "up_to_date":
        await _finish(run, "Nothing to merge: the default branch already holds this work")
        return
    if preview.state == "conflicts":
        if run.resolutions >= run.limits["maxResolutions"]:
            await _save(run, "attention", report="Its merge conflicts could not be resolved automatically")
            return
        if await _claim_merge(run, caller, phase="resolving", report="Resolving merge conflicts with the agent"):
            run.resolutions += 1
            await sessions.resolve_merge_conflicts(run.session_id, caller)
        return
    message = (preview.suggestedMessage or f"{'fix' if run.issue_number else 'feat'}: {run.title}").strip()
    if run.issue_number:
        message = f"{message[:180]} (fixes #{run.issue_number})"
    if not await _claim_merge(run, caller, phase="merging"):
        return
    result = await sessions.merge_session(run.session_id, MergeRequest(message=message[:200]), caller)
    if result.state == "conflicts" and _current(run):
        run.phase = "working"
        await _merge(run)
    elif result.state == "up_to_date":
        run.phase = "working"
        await _finish(run, "Nothing to merge: the default branch already holds this work")
    # A merged result is finished by the `merge.completed` event.


async def _claim_merge(run: TaskRun, caller: Caller, *, phase: str | None = None, report: str | None = None) -> bool:
    """Whether the run's item still runs, read under the item's lock so Pause lands either before (and
    defers the merge, keeping the work in review until Resume) or after the claim. With a `phase`, the
    run also enters it and saves its task as merging in the same lock."""
    async with _lock_for(_item_locks, run.item_id):
        if not _current(run):
            return False
        work = await mooi.fetch_work(caller, run.project_id)
        item = next((row for row in work.get("items", []) if row["id"] == str(run.item_id)), {})
        if any(change["status"] == "analyzing" for change in item.get("changeRequests", [])):
            run.phase = "review"
            if run.status != "review":
                await _report(run, "review", report="Ready to merge when the change analysis finishes.")
            return False
        if item.get("status") != "running":
            if item.get("status") == "paused":
                run.phase = "review"
                if run.status != "review":
                    summary = run.outcome.summary if run.outcome else "Work finished."
                    await _report(run, "review", report=f"{summary}\n\nReady to merge when the item resumes.")
            return False
        if phase is not None:
            run.phase = phase
            await _report(run, "merging", report=report)
        return True


async def _finish(run: TaskRun, report: str, *, merge_sha: str | None = None) -> None:
    """The task is done: saved as such, its session closed, and the runner looks for what it unblocked.
    Claimed by the run, so it happens once; its snapshot is taken before the item's lock."""
    if run.finishing or not _current(run):
        return
    run.finishing = True
    try:
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
                "obstacles": [
                    _handoff_text(value)[:2_000] for value in (outcome.obstacles if outcome else [])[:200]
                ],
            }
        async with _lock_for(_item_locks, run.item_id):
            if not _current(run):
                return
            item = await _report(run, "done", report=report, merge_sha=merge_sha if not run.qa else None,
                                 qa_handoff=handoff)
            await _close(run)
    except Exception as failure:
        LOG.warning("Task %s could not finish", run.task_id, exc_info=True)
        run.phase = "working"
        reason = failure.message if isinstance(failure, ApiException) else "Its work could not be wrapped up"
        with suppress(Exception):
            await _save(run, "attention", report=f"{reason}: continue in its session or start the item again")
        return
    finally:
        run.finishing = False
    wake()
    await _retire_task_recovery(run.player_id, run.project_id, run.item_id, run.task_id, run.attempts)
    if run.issue_number and item.get("status") == "done":
        # A merge titled by the player may not name the issue: closing it here is idempotent.
        with suppress(Exception):
            caller = await _run_caller(run)
            changed = await mooi.send_work(caller, "PATCH", run.project_id, f"/issues/{run.issue_number}",
                                           {"state": "closed"}, github=True) or {}
            _publish_issue(run.player_id, run.project_id, changed.get("issue"))
            # Closing an issue is a runner-owned event, so reconcile its other queued tasks now
            # instead of making them wait for the next periodic GitHub poll.
            await _synced_issues(caller, run.project_id)


async def _close(run: TaskRun) -> None:
    _runs.pop(run.session_id, None)
    _background(sessions.close_session(run.session_id))


async def _close_failed(session_id: UUID) -> None:
    """Closes a failed task session; its run stays known until the close saved its workspace's handoff."""
    await sessions.close_session(session_id)
    _runs.pop(session_id, None)


async def _run_caller(run: TaskRun) -> Caller:
    caller = await _renew(run.item_id, run.player_id, None)
    if caller is None:
        raise ApiException.conflict("The factory credential of this work is not available right now")
    return caller


def start_runner() -> None:
    """Called once from `main.py`'s lifespan; `close_runner` stops it again."""
    global _runner_task
    if _runner_task is None:
        _runner_task = asyncio.create_task(_runner_loop())


async def close_runner() -> None:
    global _runner_task, _quota_wakeup
    if _quota_wakeup is not None:
        _quota_wakeup.cancel()
        _quota_wakeup = None
    tasks = [task for task in (_runner_task, *_tasks) if task is not None]
    for task in tasks:
        task.cancel()
    await asyncio.gather(*tasks, return_exceptions=True)
    _runner_task = None
