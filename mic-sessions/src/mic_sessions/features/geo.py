"""Feature: AI visibility (GEO) runs — a project's tracked prompts asked to the player's own AI accounts.

`mic-mooi` keeps the prompts, brand terms, competitors and runs (manual "Run now" or the weekly
schedule); this feature executes them with no device connected:

- every minute a loop claims the due runs (`/internal/analytics/geo/...`) and acts for the sign-in each
  claim names;
- every enabled prompt goes, verbatim, to every *engine* (provider + model) of the player's linked
  accounts whose quota stays below the project's factory `quotaLimit`; an engine served by several
  eligible accounts spreads its prompts across them. Eligibility is the factory's (`_capacity`,
  `_quota_limit`, `_select_assignment`), checked again before each prompt, so an account that reaches
  the limit mid-run hands its remaining prompts to the others;
- each prompt is one headless turn (`ProviderDescriptor.answer`): web search and fetch only, an empty
  throwaway directory, at most 120 s, holding a slot of the account session limit while it runs, and no
  visible session or trace left behind;
- each answer is parsed (`parse_answer`) and the results reach `mic-mooi` in batches; a prompt that
  fails is stored with its `error` and the run goes on;
- with no eligible account (or no sign-in left to act as), a manual run fails with that reason and a
  weekly one is postponed 6 h.

Progress is announced on the player's live channel as `analytics.geo.run`
`{projectId, runId, status, done, total}`: `status` is `running`, `finished`, `failed` (with `reason`:
`quota`, `signin` or `error`) or `postponed`.
"""

from __future__ import annotations

import asyncio
import logging
import re
import time
from collections.abc import Callable, Coroutine
from contextlib import AsyncExitStack, suppress
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import urlparse
from uuid import UUID

from fastapi import status

from mic_sessions.features import factory, sessions
from mic_sessions.features.sessions import get_registry
from mic_sessions.shared import mooi
from mic_sessions.shared.agents import PROVIDERS
from mic_sessions.shared.auth import Caller
from mic_sessions.shared.web import ApiException

LOG = logging.getLogger("geo")

EVENT_GEO_RUN = "analytics.geo.run"

Json = dict[str, Any]

_TICK_SECONDS = 60
# Runs claimed per tick, and executed at once by this process.
_CLAIM_LIMIT = 5
_MAX_RUNS = 10
_PROMPT_TIMEOUT_SECONDS = 120
_POSTPONE_MINUTES = 6 * 60
_BATCH_SIZE = 10
# How long a prompt waits for a free slot of the account session limit before it fails.
_SLOT_WAIT_SECONDS = 15
_SLOT_PATIENCE_SECONDS = 600

# The limits of `mic-mooi`'s result rows.
_EXCERPT_LIMIT = 300
_ERROR_LIMIT = 2000
_URL_LIMIT = 2048
_LIST_LIMIT = 50


# --- answer parsing ------------------------------------------------------------------------------

_URL = re.compile(r"https?://[^\s<>\"'`()\[\]{}|]+", re.IGNORECASE)
_URL_TRAILING = ".,;:!?*_'\""
_LIST_ITEM = re.compile(r"^(?:[-*+•]|\d{1,3}[.)])\s+(\S.*)$")
_NUMBERED_HEADING = re.compile(r"^#{1,6}\s+(?:\*\*|__)?\d{1,3}[.)]\s+(\S.*)$")


def _domain(value: str) -> str:
    """A bare lowercase host without `www.`, from a domain or a URL."""
    text = value.strip().lower()
    host = (urlparse(text).hostname if "://" in text else text.split("/")[0].split(":")[0]) or ""
    return host.removeprefix("www.").strip(".")


def _on_domain(host: str, domain: str) -> bool:
    return bool(domain) and (host == domain or host.endswith("." + domain))


def _term(term: str) -> re.Pattern[str]:
    """A brand or competitor name as a whole word, any case."""
    return re.compile(r"(?<!\w)" + re.escape(term.strip()) + r"(?!\w)", re.IGNORECASE)


def _domain_term(domain: str) -> re.Pattern[str]:
    """A domain written out, subdomains included, never as part of a longer name."""
    return re.compile(r"(?<![\w-])" + re.escape(domain) + r"(?![\w-])", re.IGNORECASE)


def _urls(text: str) -> list[str]:
    seen: dict[str, None] = {}
    for match in _URL.finditer(text):
        url = match.group(0).rstrip(_URL_TRAILING)
        if len(url) <= _URL_LIMIT and urlparse(url).hostname:
            seen.setdefault(url, None)
    return list(seen)


def _list_items(text: str) -> list[str]:
    """The answer's top-level list entries (bulleted, numbered or numbered headings), in order."""
    items = []
    for line in text.splitlines():
        if len(line) - len(line.lstrip()) >= 2:
            continue
        stripped = line.strip()
        heading = _NUMBERED_HEADING.match(stripped)
        item = heading or _LIST_ITEM.match(stripped.lstrip("*_") if stripped.startswith(("**", "__")) else stripped)
        if item:
            items.append(item.group(1))
    return items


def _excerpt(text: str, at: int | None) -> str:
    """At most 300 characters of the answer around `at` (its start when the brand is absent)."""
    start = 0 if at is None else max(0, at - _EXCERPT_LIMIT // 3)
    end = min(len(text), start + _EXCERPT_LIMIT)
    window = " ".join(text[start:end].split())
    prefix, suffix = ("…" if start > 0 else ""), ("…" if end < len(text) else "")
    room = _EXCERPT_LIMIT - len(prefix) - len(suffix)
    return prefix + window[:room] + suffix if window else ""


def parse_answer(text: str, brand_terms: list[str], domains: list[str], competitors: list[Json]) -> Json:
    """What one answer says about the brand, as `mic-mooi` stores it: `mentioned` (a brand term or an own
    domain, any case), `cited` (a URL on an own domain), `citedUrls` (every URL it gives), `position`
    (ordinal of the first list entry naming the brand, else of the brand among the recognised entities by
    first appearance; null when absent), `competitorsMentioned` and an `excerpt` around the mention."""
    own = [domain for domain in dict.fromkeys(_domain(value) for value in domains) if domain]
    brand = [_term(term) for term in brand_terms if term.strip()] + [_domain_term(domain) for domain in own]
    urls = _urls(text)
    hosts = [_domain(url) for url in urls]
    cited = any(_on_domain(host, domain) for host in hosts for domain in own)

    def first(patterns: list[re.Pattern[str]], within: str) -> int | None:
        found = [match.start() for pattern in patterns if (match := pattern.search(within))]
        return min(found) if found else None

    brand_at = first(brand, text)
    mentioned = brand_at is not None or cited
    rivals: list[tuple[str, int | None]] = []
    for competitor in competitors:
        name = str(competitor.get("name") or "").strip()
        domain = _domain(str(competitor.get("domain") or ""))
        patterns = ([_term(name)] if name else []) + ([_domain_term(domain)] if domain else [])
        at = first(patterns, text)
        if at is None and domain and any(_on_domain(host, domain) for host in hosts):
            at = len(text)
        if name and at is not None:
            rivals.append((name[:255], at))

    position: int | None = None
    if brand_at is not None:
        items = _list_items(text)
        position = next((index for index, item in enumerate(items, 1) if first(brand, item) is not None), None)
        if position is None:
            position = 1 + sum(1 for _, at in rivals if at is not None and at < brand_at)
    return {
        "mentioned": mentioned,
        "cited": cited,
        "citedUrls": urls[:_LIST_LIMIT],
        "position": position,
        "competitorsMentioned": list(dict.fromkeys(name for name, _ in rivals))[:_LIST_LIMIT],
        "excerpt": _excerpt(text, brand_at) or None,
        "error": None,
    }


# --- runs ----------------------------------------------------------------------------------------


@dataclass
class _Engine:
    """One provider + model the prompts are asked to, and the eligible accounts that serve it."""

    provider: str
    model: str
    accounts: list[Json]


@dataclass
class _Run:
    claim: Json
    run_id: UUID
    project_id: UUID
    player_id: UUID
    caller: Caller
    total: int = 0
    done: int = 0
    pending: list[Json] = field(default_factory=list)
    flush_lock: asyncio.Lock = field(default_factory=asyncio.Lock)

    async def current_caller(self) -> Caller:
        self.caller = await mooi.renewed(self.caller)
        return self.caller

    def publish(self, status_: str, reason: str | None = None) -> None:
        _publish(self.player_id, self.project_id, self.run_id, status_, self.done, self.total, reason)

    async def add(self, result: Json) -> None:
        self.pending.append(result)
        self.done += 1
        if len(self.pending) >= _BATCH_SIZE:
            await self.flush()
        self.publish("running")

    async def flush(self) -> None:
        async with self.flush_lock:
            batch, self.pending = self.pending, []
            if batch:
                await _post_results(self.run_id, batch)


_active: set[UUID] = set()
_tasks: set[asyncio.Task[None]] = set()
_runner_task: asyncio.Task[None] | None = None


def _publish(player_id: UUID, project_id: UUID, run_id: UUID, status_: str, done: int, total: int,
             reason: str | None = None) -> None:
    payload: Json = {"projectId": str(project_id), "runId": str(run_id), "status": status_,
                     "done": done, "total": total}
    if reason:
        payload["reason"] = reason
    sessions._channels.publish(player_id, EVENT_GEO_RUN, payload)


def _background(coro: Coroutine[Any, Any, None]) -> None:
    task = asyncio.create_task(coro)
    _tasks.add(task)
    task.add_done_callback(_tasks.discard)


async def _runner_loop() -> None:
    while True:
        try:
            await _tick()
        except asyncio.CancelledError:
            raise
        except Exception:
            LOG.warning("The AI visibility runner could not claim runs; retrying", exc_info=True)
        await asyncio.sleep(_TICK_SECONDS)


async def _tick() -> None:
    room = min(_CLAIM_LIMIT, _MAX_RUNS - len(_active))
    if room <= 0:
        return
    for claim in await mooi.claim_geo_runs(list(_active), room):
        run_id = UUID(claim["runId"])
        if run_id in _active:
            continue
        # Registered before the next claim reports which runs this process still executes.
        _active.add(run_id)
        _background(_execute(claim))


async def _claim_caller(claim: Json) -> Caller | None:
    """The sign-in the run acts as, renewed; None once the player has none left."""
    if not claim.get("authSessionId"):
        return None
    caller = Caller(player_id=UUID(claim["playerId"]), session_id=UUID(claim["authSessionId"]), role="",
                    token="", expires_at=0)
    try:
        return await mooi.renewed(caller)
    except ApiException:
        return None


async def _give_up(claim: Json, reason: str) -> None:
    """A run that cannot start: a weekly one waits 6 h for its next try, a manual one fails."""
    run_id, project_id, player_id = UUID(claim["runId"]), UUID(claim["projectId"]), UUID(claim["playerId"])
    if claim.get("trigger") == "weekly":
        await mooi.postpone_geo_run(run_id, _POSTPONE_MINUTES)
        LOG.info("AI visibility run %s postponed (%s)", run_id, reason)
        _publish(player_id, project_id, run_id, "postponed", 0, 0, reason)
    else:
        await mooi.set_geo_run_status(run_id, "failed", reason)
        LOG.info("AI visibility run %s failed (%s)", run_id, reason)
        _publish(player_id, project_id, run_id, "failed", 0, 0, reason)


async def _execute(claim: Json) -> None:
    run_id = UUID(claim["runId"])
    run: _Run | None = None
    try:
        caller = await _claim_caller(claim)
        if caller is None:
            await _give_up(claim, "signin")
            return
        run = _Run(claim=claim, run_id=run_id, project_id=UUID(claim["projectId"]),
                   player_id=UUID(claim["playerId"]), caller=caller)
        prompts = [prompt for prompt in claim.get("prompts") or []
                   if prompt.get("enabled", True) and str(prompt.get("text") or "").strip()]
        settings = await mooi.send_work(caller, "GET", run.project_id, "/settings") or {}
        quota_limit = factory._quota_limit(settings)
        engines, check = await _engines(caller, quota_limit)
        if not engines:
            await _give_up(claim, "quota")
            return
        run.total = len(prompts) * len(engines)
        run.publish("running")
        LOG.info("AI visibility run %s: %s prompts on %s engines", run_id, len(prompts), len(engines))
        await asyncio.gather(*(_run_engine(run, engine, prompts, check) for engine in engines))
        await run.flush()
        await mooi.set_geo_run_status(run_id, "finished")
        run.publish("finished")
    except asyncio.CancelledError:
        # Shutting down: the run stays `running` and mic-mooi fails it as interrupted once no process
        # reports it active any more.
        raise
    except Exception:
        LOG.warning("AI visibility run %s failed", run_id, exc_info=True)
        with suppress(Exception):
            if run is not None:
                await run.flush()
            await mooi.set_geo_run_status(run_id, "failed", "error")
        if run is not None:
            run.publish("failed", "error")
        else:
            _publish(UUID(claim["playerId"]), UUID(claim["projectId"]), run_id, "failed", 0, 0, "error")
    finally:
        _active.discard(run_id)


async def _engines(caller: Caller, quota_limit: float) -> tuple[list[_Engine], Callable[[Json, str], bool]]:
    """The engines the run asks, each with its eligible accounts, and the check that an account may still
    take a prompt for a model. Quota is the freshest known: a live session's reading, else the provider's,
    read once here."""
    accounts = await factory._capacity(caller, quota_limit, {})
    connections = [{"id": account["connectionId"], "provider": account["provider"],
                    "defaultAccount": account["defaultAccount"]} for account in accounts]
    catalogs = {str(account["connectionId"]): {"models": account["models"], "defaultModel": account["defaultModel"]}
                for account in accounts}
    snapshots = await factory._refresh_account_quotas(caller, connections)

    def eligible(connection: Json, model: str) -> bool:
        connection_id = str(connection["id"])
        current = {connection_id: factory._quota(connection_id) or snapshots.get(connection_id) or {}}
        task = {"assignment": {"provider": connection["provider"], "connectionId": connection_id, "model": model}}
        return factory._select_assignment(task, [connection], quota_limit, catalogs, current) is not None

    engines: dict[tuple[str, str], _Engine] = {}
    for account, connection in zip(accounts, connections, strict=True):
        model = account.get("defaultModel")
        if not account["eligible"] or not model or not eligible(connection, model):
            continue
        engine = engines.setdefault((account["provider"], model), _Engine(account["provider"], model, []))
        engine.accounts.append(connection)
    return list(engines.values()), eligible


async def _run_engine(run: _Run, engine: _Engine, prompts: list[Json],
                      eligible: Callable[[Json, str], bool]) -> None:
    """Spreads the prompts across the engine's accounts, one prompt at a time per account; an account
    that reaches the quota limit leaves its share to the others."""
    queue: asyncio.Queue[Json] = asyncio.Queue()
    for prompt in prompts:
        queue.put_nowait(prompt)

    async def work(connection: Json) -> None:
        while True:
            try:
                prompt = queue.get_nowait()
            except asyncio.QueueEmpty:
                return
            if not eligible(connection, engine.model):
                queue.put_nowait(prompt)
                return
            await run.add(await _ask(run, engine, str(connection["id"]), prompt))

    await asyncio.gather(*(work(connection) for connection in engine.accounts))
    while not queue.empty():
        await run.add(_failure(engine, queue.get_nowait(), "No account below the quota limit was left for it"))


def _failure(engine: _Engine, prompt: Json, error: str) -> Json:
    return {"promptId": prompt["id"], "provider": engine.provider, "model": engine.model, "mentioned": False,
            "cited": False, "position": None, "citedUrls": [], "competitorsMentioned": [], "excerpt": None,
            "error": error[:_ERROR_LIMIT]}


async def _ask(run: _Run, engine: _Engine, connection_id: str, prompt: Json) -> Json:
    try:
        caller = await run.current_caller()
        async with AsyncExitStack() as stack:
            await _hold_slot(stack, caller)
            answer = await PROVIDERS[engine.provider].answer(caller, connection_id, str(prompt["text"]),
                                                             model=engine.model, timeout=_PROMPT_TIMEOUT_SECONDS)
    except asyncio.CancelledError:
        raise
    except TimeoutError:
        return _failure(engine, prompt, f"No answer within {_PROMPT_TIMEOUT_SECONDS} s")
    except ApiException as failure:
        return _failure(engine, prompt, failure.message)
    except Exception as error:
        LOG.info("An AI visibility prompt failed on account %s", connection_id, exc_info=True)
        return _failure(engine, prompt, str(error) or type(error).__name__)
    if not answer.text.strip():
        return _failure(engine, prompt, "The model returned an empty answer")
    claim = run.claim
    return {"promptId": prompt["id"], "provider": engine.provider, "model": answer.model,
            **parse_answer(answer.text, list(claim.get("brandTerms") or []), list(claim.get("ownDomains") or []),
                           list(claim.get("competitors") or []))}


async def _hold_slot(stack: AsyncExitStack, caller: Caller) -> None:
    """Takes a slot of the account session limit for the prompt, waiting while every slot is busy."""
    deadline = time.monotonic() + _SLOT_PATIENCE_SECONDS
    while True:
        try:
            await stack.enter_async_context(get_registry().headless(caller))
            return
        except ApiException as failure:
            if failure.status_code != status.HTTP_409_CONFLICT or time.monotonic() >= deadline:
                raise
        await asyncio.sleep(_SLOT_WAIT_SECONDS)


async def _post_results(run_id: UUID, results: list[Json]) -> None:
    """One batch; a batch refused as a whole (a prompt deleted meanwhile) is retried row by row."""
    try:
        await mooi.post_geo_results(run_id, results)
    except ApiException as failure:
        if failure.status_code != status.HTTP_400_BAD_REQUEST or len(results) == 1:
            raise
        for result in results:
            try:
                await mooi.post_geo_results(run_id, [result])
            except ApiException as single:
                if single.status_code != status.HTTP_400_BAD_REQUEST:
                    raise
                LOG.info("Dropped an AI visibility result of run %s: %s", run_id, single.message)


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
