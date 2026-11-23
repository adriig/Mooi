"""Agent-owned sign-ins linked through this service: owner-bound, expiring attempts in which the
provider's own binary signs in (Codex device code, agy's Google OAuth with a pasted code); the
resulting credential goes straight to `mic-mooi` and never reaches the browser or the logs."""
from __future__ import annotations

import asyncio
import logging
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Annotated
from uuid import uuid4

from fastapi import APIRouter, Depends, Query
from fastapi import Path as PathParameter
from pydantic import BaseModel, ConfigDict, Field, StrictStr, field_validator

from mic_sessions.shared import antigravity, opencode
from mic_sessions.shared.agents import PROVIDERS
from mic_sessions.shared.auth import Caller, current_caller
from mic_sessions.shared.codex import Client
from mic_sessions.shared.env import get_settings
from mic_sessions.shared.mooi import (
    fetch_agent_connections,
    fetch_agent_credential,
    fetch_favorite_models,
    link_antigravity,
    link_codex,
    link_opencode,
    renewed,
    replace_favorite_models,
)
from mic_sessions.shared.web import ApiException

LOG = logging.getLogger("sessions")

router = APIRouter()


# --- Favorite models: provider-wide preferences, catalog-checked before persistence ---------------


class FavoriteModelsUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid")
    model_ids: list[Annotated[StrictStr, Field(min_length=1, max_length=255)]] = Field(
        alias="modelIds", max_length=100)

    @field_validator("model_ids")
    @classmethod
    def normalize(cls, values: list[str]) -> list[str]:
        normalized = []
        for value in values:
            model_id = value.strip()
            if not model_id or any(char.isspace() or ord(char) < 32 or 127 <= ord(char) <= 159
                                   for char in model_id):
                raise ValueError("Model ids must not be empty or contain whitespace or controls")
            if model_id not in normalized:
                normalized.append(model_id)
        return normalized


@router.get("/agents/favorite-models")
async def favorite_models(caller: Annotated[Caller, Depends(current_caller)]):
    return {"favorites": await fetch_favorite_models(caller)}


@router.get("/agents/{provider}/favorite-models")
async def provider_favorite_models(provider: str, caller: Annotated[Caller, Depends(current_caller)]):
    if provider not in PROVIDERS:
        raise ApiException.not_found("Unknown agent provider")
    return {"provider": provider, "modelIds": (await fetch_favorite_models(caller)).get(provider, [])}


@router.put("/agents/{provider}/favorite-models")
async def save_favorite_models(provider: str, update: FavoriteModelsUpdate,
                               caller: Annotated[Caller, Depends(current_caller)]):
    """A user can select only ids offered by their own connected accounts, never a supplied catalog.

    Validate in the service that owns provider catalogs and require its secret on the mic-mooi write.
    Empty lists do not need provider access, so preferences can be cleared after disconnecting.
    """
    descriptor = PROVIDERS.get(provider)
    if descriptor is None:
        raise ApiException.not_found("Unknown agent provider")
    if update.model_ids:
        connections = [connection for connection in await fetch_agent_connections(caller)
                       if connection.get("provider") == provider and not connection.get("stale")]
        if not connections:
            raise ApiException.bad_request("No usable account linked for this provider")
        available: set[str] = set()
        unavailable = False
        for connection in connections:
            try:
                async with asyncio.timeout(30):
                    prepared = await descriptor.prepare(caller, str(connection["id"]))
                    available.update(model["id"] for model in descriptor.configuration(prepared.id).get("models", []))
            except ApiException as error:
                if error.status_code in (401, 403):
                    raise
                unavailable = True
            except Exception:
                LOG.info("Could not load %s catalog for favorite model validation", provider, exc_info=True)
                unavailable = True
            if set(update.model_ids) <= available:
                break
        else:
            if unavailable:
                raise ApiException(502, "Could not validate favorite models. Try again when provider access is available.")
            raise ApiException.bad_request("Favorite models must be available in your connected provider catalogs")
    return await replace_favorite_models(caller, provider, update.model_ids)


@dataclass
class Attempt:
    owner: str
    name: str = ""
    # The existing account a sign-in again repairs; None adds a new account.
    connection_id: str | None = None
    task: asyncio.Task | None = None
    state: str = "pending"
    client: Client | None = None
    login_id: str | None = None
    ready: asyncio.Future | None = None
    finished: asyncio.Event = field(default_factory=asyncio.Event)


_attempts: dict[str, Attempt] = {}


async def _login(attempt: Attempt):
    with tempfile.TemporaryDirectory(prefix="mooi-codex-login-") as directory:
        client = Client(Path(directory), Path(directory))
        attempt.client = client
        try:
            async with asyncio.timeout(600):
                await client.start()
                login = (await client.call("account_login_start", {"type": "chatgptDeviceCode"})).root
                attempt.login_id = login.login_id
                attempt.ready.set_result({"verificationUrl": login.verification_url, "userCode": login.user_code})
                completed = await client.call("wait_for_login_completed", login.login_id)
                attempt.state = "authorized" if completed.success else "failed"
                # Completion is committed by an authenticated poll with a fresh Mooi token.
                if completed.success:
                    await attempt.finished.wait()
        except asyncio.CancelledError:
            attempt.state = "cancelled"
            raise
        except TimeoutError:
            attempt.state = "expired"
            if not attempt.ready.done():
                attempt.ready.set_exception(ApiException(502, "Codex login timed out. Try again."))
        except Exception:
            attempt.state = "failed"
            if not attempt.ready.done():
                attempt.ready.set_exception(ApiException(502, "Could not start Codex login. Try again."))
        finally:
            await client.close()
            attempt.client = None
            if attempt.state == "pending":
                attempt.state = "expired"


@router.post("/agents/codex/authorization")
async def start(caller: Annotated[Caller, Depends(current_caller)],
                name: Annotated[str, Query(min_length=1, max_length=100)],
                connection_id: Annotated[str | None, Query(alias="connectionId", max_length=64)] = None):
    name = name.strip()
    if not name:
        raise ApiException.bad_request("An account title is required")
    owner = str(caller.player_id)
    replaced = []
    for key, existing in list(_attempts.items()):
        if existing.owner == owner or existing.state in ("failed", "cancelled", "connected", "expired"):
            if existing.task and not existing.task.done():
                existing.task.cancel()
                replaced.append(existing.task)
            _attempts.pop(key, None)
    if len(_attempts) >= 32:
        raise ApiException(429, "Too many login attempts. Try again later.")
    key = uuid4().hex
    attempt = Attempt(owner, name=name, connection_id=connection_id, ready=asyncio.get_running_loop().create_future())
    _attempts[key] = attempt
    attempt.task = asyncio.create_task(_login(attempt))
    try:
        await asyncio.gather(*replaced, return_exceptions=True)
        async with asyncio.timeout(45):
            details = await asyncio.shield(attempt.ready)
        return {"id": key, **details}
    except BaseException as error:
        attempt.task.cancel()
        await asyncio.gather(attempt.task, return_exceptions=True)
        _attempts.pop(key, None)
        if isinstance(error, asyncio.CancelledError):
            raise
        raise ApiException(502, "Could not start Codex login. Try again.") from None


def _owned(key: str, caller: Caller) -> Attempt:
    attempt = _attempts.get(key)
    if attempt is None or attempt.owner != str(caller.player_id):
        raise ApiException.not_found("Login attempt not found")
    return attempt


@router.get("/agents/codex/authorization/{key}")
async def status(key: str, caller: Annotated[Caller, Depends(current_caller)]):
    attempt = _owned(key, caller)
    if attempt.state == "authorized" and attempt.client:
        # Claim before awaiting so overlapping polls cannot commit twice.
        attempt.state = "saving"
        try:
            account = await attempt.client.call("account_read", {"refreshToken": True})
            label = getattr(account.account.root, "email", None) if account.account else None
            home = attempt.client.home
            await link_codex(caller, (home / "auth.json").read_text(), label, attempt.name, attempt.connection_id)
            attempt.state = "connected"
        except Exception:
            attempt.state = "failed"
        finally:
            attempt.finished.set()
            if attempt.task:
                await asyncio.gather(attempt.task, return_exceptions=True)
    return {"status": attempt.state}


@router.delete("/agents/codex/authorization/{key}", status_code=204)
async def cancel(key: str, caller: Annotated[Caller, Depends(current_caller)]):
    attempt = _owned(key, caller)
    if attempt.task:
        attempt.task.cancel()
        await asyncio.gather(attempt.task, return_exceptions=True)
    _attempts.pop(key, None)


# --- Antigravity: agy's own Google sign-in, the player pastes the code Google shows ---------------

# An attempt left unfinished this long is closed: the URL agy printed (its PKCE state) dies with it.
GOOGLE_ATTEMPT_SECONDS = 600
_ATTEMPT_LIMIT = 32
# Used when agy reported no email for the new sign-in.
_GOOGLE_ACCOUNT_LABEL = "Google account"
# Status per `antigravity.LoginError.kind`.
_LOGIN_STATUS = {"invalid": 400, "rejected": 422, "ended": 410, "timeout": 504, "failed": 502}


@dataclass
class GoogleAttempt:
    owner: str
    name: str
    login: antigravity.Login
    # `pending` until the code arrives, then `submitting` until the attempt is dropped.
    state: str = "pending"
    expiry: asyncio.Task | None = None


_google_attempts: dict[str, GoogleAttempt] = {}


class GoogleCode(BaseModel):
    # The code Google's page shows, or the full URL of the page the sign-in lands on.
    code: str = Field(min_length=1, max_length=antigravity.LOGIN_CODE_LIMIT)


def _sentence(text: str) -> str:
    text = text.strip()
    return (text[:1].upper() + text[1:] + ("" if text.endswith(".") else ".")) if text else "Try again."


def _login_failure(error: antigravity.AntigravityError, fallback: int = 502) -> ApiException:
    """User-safe status and message; the diagnostic (never a code or token) goes to the log only."""
    kind = error.kind if isinstance(error, antigravity.LoginError) else "failed"
    LOG.info("Antigravity sign-in failed (%s): %s", kind, error.detail or "no detail")
    return ApiException(_LOGIN_STATUS.get(kind, fallback), _sentence(error.cause))


def _take_google(key: str) -> GoogleAttempt | None:
    """Removes the attempt from the registry synchronously (no await in between, so an owner never has
    two live attempts); the caller closes its login."""
    attempt = _google_attempts.pop(key, None)
    if attempt and attempt.expiry and attempt.expiry is not asyncio.current_task():
        attempt.expiry.cancel()
    return attempt


async def _discard_google(key: str) -> None:
    attempt = _take_google(key)
    if attempt:
        await attempt.login.close()


async def _expire_google(key: str, attempt: GoogleAttempt) -> None:
    await asyncio.sleep(GOOGLE_ATTEMPT_SECONDS)
    # A code being redeemed finishes on its own timeouts and drops the attempt itself.
    if _google_attempts.get(key) is attempt and attempt.state == "pending":
        await _discard_google(key)


@router.post("/agents/antigravity/authorization")
async def start_google(caller: Annotated[Caller, Depends(current_caller)],
                       name: Annotated[str, Query(min_length=1, max_length=100)]):
    """Starts agy's Google sign-in in a private temporary home and answers the authorization URL to
    open; the code Google then shows is posted to `…/{attemptId}/code`. One attempt per player: a new
    one replaces the previous."""
    name = name.strip()
    if not name:
        raise ApiException.bad_request("An account title is required")
    owner = str(caller.player_id)
    replaced: list[GoogleAttempt] = []
    for key, existing in list(_google_attempts.items()):
        if existing.owner == owner and _take_google(key):
            replaced.append(existing)
    if len(_google_attempts) >= _ATTEMPT_LIMIT:
        await asyncio.gather(*(attempt.login.close() for attempt in replaced), return_exceptions=True)
        raise ApiException(429, "Too many sign-in attempts. Try again later.")
    key = uuid4().hex
    attempt = GoogleAttempt(owner, name, antigravity.Login(get_settings().agent_antigravity_cli))
    _google_attempts[key] = attempt
    await asyncio.gather(*(previous.login.close() for previous in replaced), return_exceptions=True)
    try:
        url = await attempt.login.start()
    except antigravity.AntigravityError as error:
        await _discard_google(key)
        raise _login_failure(error) from None
    except BaseException:
        await _discard_google(key)
        raise
    if _google_attempts.get(key) is not attempt:
        # Cancelled or replaced while agy was starting.
        await attempt.login.close()
        raise ApiException(410, "This sign-in was cancelled. Start again.")
    attempt.expiry = asyncio.create_task(_expire_google(key, attempt))
    return {"attemptId": key, "authorizationUrl": url, "expiresIn": GOOGLE_ATTEMPT_SECONDS}


def _owned_google(key: str, caller: Caller) -> GoogleAttempt:
    attempt = _google_attempts.get(key)
    if attempt is None or attempt.owner != str(caller.player_id):
        raise ApiException.not_found("Sign-in attempt not found")
    return attempt


@router.post("/agents/antigravity/authorization/{key}/code")
async def submit_google(key: str, body: GoogleCode, caller: Annotated[Caller, Depends(current_caller)]):
    """Hands the pasted code to agy once, captures the sign-in it stored and links it to `mic-mooi` with
    the caller's own token; answers the new connection. A code that is not one (empty, several lines)
    keeps the attempt; any other outcome ends it."""
    attempt = _owned_google(key, caller)
    if attempt.state != "pending":
        raise ApiException.conflict("This sign-in is already being completed")
    try:
        code = antigravity.login_code(body.code)
    except antigravity.AntigravityError as error:
        raise _login_failure(error, 400) from None
    attempt.state = "submitting"
    try:
        try:
            bundle, account = await attempt.login.submit(code)
        except antigravity.AntigravityError as error:
            raise _login_failure(error) from None
        # The exchange may outlast the request's access token: link with a still valid one.
        try:
            return await link_antigravity(await renewed(caller), bundle, account or _GOOGLE_ACCOUNT_LABEL,
                                          attempt.name)
        except ApiException as error:
            # The attempt ends here either way: a 400 would tell the SPA to keep it and retry the code.
            if error.status_code == 400:
                raise ApiException(422, "Mooi could not store this sign-in. Start the sign-in again.") from None
            raise
    finally:
        if _google_attempts.get(key) is attempt:
            await _discard_google(key)
        else:
            await attempt.login.close()


@router.delete("/agents/antigravity/authorization/{key}", status_code=204)
async def cancel_google(key: str, caller: Annotated[Caller, Depends(current_caller)]):
    _owned_google(key, caller)
    await _discard_google(key)


# --- OpenCode account: the CLI's own device sign-in; the player authorizes on opencode.ai ----------------

_OPENCODE_ACCOUNT_LABEL = "OpenCode account"


@dataclass
class OpenCodeAttempt:
    owner: str
    name: str
    connection_id: str | None
    login: opencode.Login
    task: asyncio.Task | None = None
    expiry: asyncio.Task | None = None
    expires_at: float | None = None
    # pending -> authorized -> saving -> connected, or failed / expired. The credential waits only in memory.
    state: str = "pending"
    token: str | None = field(default=None, repr=False)
    connection: dict | None = None


_opencode_attempts: dict[str, OpenCodeAttempt] = {}


async def _opencode_wait(attempt: OpenCodeAttempt) -> None:
    try:
        attempt.token = await attempt.login.wait()
        attempt.state = "authorized"
    except asyncio.CancelledError:
        raise
    except opencode.OpenCodeError as error:
        LOG.info("OpenCode sign-in failed: %s", error.cause)
        attempt.state = "expired" if "expired" in error.cause else "failed"
    except Exception:
        LOG.info("OpenCode sign-in failed", exc_info=True)
        attempt.state = "failed"
    finally:
        await attempt.login.close()


async def _drop_opencode(key: str) -> None:
    attempt = _opencode_attempts.pop(key, None)
    if attempt is None:
        return
    if attempt.expiry and attempt.expiry is not asyncio.current_task():
        attempt.expiry.cancel()
        await asyncio.gather(attempt.expiry, return_exceptions=True)
    if attempt.task and not attempt.task.done():
        attempt.task.cancel()
        await asyncio.gather(attempt.task, return_exceptions=True)
    attempt.token = None
    await attempt.login.close()


async def _expire_opencode(key: str, attempt: OpenCodeAttempt) -> None:
    await asyncio.sleep(opencode.LOGIN_SECONDS)
    if _opencode_attempts.get(key) is attempt:
        await _drop_opencode(key)


@router.post("/agents/opencode/authorization")
async def start_opencode(caller: Annotated[Caller, Depends(current_caller)],
                         name: Annotated[str, Query(min_length=1, max_length=100)],
                         connection_id: Annotated[str | None, Query(alias="connectionId", max_length=64)] = None):
    """Starts the OpenCode CLI's device sign-in in a private temporary profile and answers the URL and code
    the player authorizes on opencode.ai; the SPA then polls `…/{attemptId}`. One attempt per player. With
    `connectionId` the sign-in repairs that account instead of adding one."""
    name = name.strip()
    if not name:
        raise ApiException.bad_request("An account title is required")
    owner = str(caller.player_id)
    for key, existing in list(_opencode_attempts.items()):
        if (existing.expires_at is not None and existing.expires_at <= asyncio.get_running_loop().time()
                or existing.owner == owner or existing.state in ("failed", "expired", "connected")):
            await _drop_opencode(key)
    if len(_opencode_attempts) >= _ATTEMPT_LIMIT:
        raise ApiException(429, "Too many sign-in attempts. Try again later.")
    key = uuid4().hex
    attempt = OpenCodeAttempt(owner, name, connection_id, opencode.Login(get_settings().agent_opencode_cli))
    _opencode_attempts[key] = attempt
    attempt.expires_at = asyncio.get_running_loop().time() + opencode.LOGIN_SECONDS
    attempt.expiry = asyncio.create_task(_expire_opencode(key, attempt))
    try:
        url, code = await attempt.login.start()
    except opencode.OpenCodeError as error:
        LOG.info("OpenCode sign-in could not start: %s", error.cause)
        await _drop_opencode(key)
        raise ApiException(502, "Could not start the OpenCode sign-in. Try again.") from None
    except BaseException:
        await _drop_opencode(key)
        raise
    attempt.task = asyncio.create_task(_opencode_wait(attempt))
    return {"attemptId": key, "verificationUrl": url, "userCode": code, "expiresIn": opencode.LOGIN_SECONDS}


def _owned_opencode(key: str, caller: Caller) -> OpenCodeAttempt:
    attempt = _opencode_attempts.get(key)
    if attempt is None or attempt.owner != str(caller.player_id):
        raise ApiException.not_found("Sign-in attempt not found")
    return attempt


@router.get("/agents/opencode/authorization/{key}")
async def status_opencode(key: str, caller: Annotated[Caller, Depends(current_caller)]):
    """The attempt's state. Once the player authorized, this authenticated poll links the sign-in to
    `mic-mooi` as the caller and answers the connection (no secret in it)."""
    attempt = _owned_opencode(key, caller)
    if attempt.state == "authorized" and attempt.token:
        # Claim before awaiting so overlapping polls cannot commit twice.
        attempt.state = "saving"
        token, attempt.token = attempt.token, None
        try:
            attempt.connection = await link_opencode(await renewed(caller), token, _OPENCODE_ACCOUNT_LABEL,
                                                     attempt.name, attempt.connection_id)
            attempt.state = "connected"
        except Exception:
            LOG.info("OpenCode sign-in could not be saved", exc_info=True)
            attempt.state = "failed"
    body: dict = {"status": attempt.state}
    if attempt.state == "connected":
        body["connection"] = attempt.connection
        await _drop_opencode(key)
    elif attempt.state in ("failed", "expired"):
        await _drop_opencode(key)
    return body


@router.delete("/agents/opencode/authorization/{key}", status_code=204)
async def cancel_opencode(key: str, caller: Annotated[Caller, Depends(current_caller)]):
    _owned_opencode(key, caller)
    await _drop_opencode(key)


# --- Plan limits: what an account has left, read for the account page without a session -------------

# One read per account at a time: concurrent views wait for it and then share its cached result.
_usage_locks: dict[str, asyncio.Lock] = {}


def account_usage_lock(connection_id: str) -> asyncio.Lock:
    """Return the shared per-account lock used by every provider quota refresh."""
    if len(_usage_locks) >= 256:
        for key, lock in list(_usage_locks.items()):
            if not lock.locked():
                _usage_locks.pop(key, None)
    return _usage_locks.setdefault(connection_id, asyncio.Lock())


@router.get("/agents/connections/{connection_id}/usage")
async def account_usage(connection_id: Annotated[str, PathParameter(max_length=64)],
                        caller: Annotated[Caller, Depends(current_caller)],
                        refresh: bool = False):
    """The account's plan windows (5-hour, weekly, per model group…) as each provider reports them, in
    the shape sessions stream as `session.usage`. `limits` is false for a provider billed per use.
    The credential is fetched as the caller, so `mic-mooi` answers only for the caller's own account."""
    credential = await fetch_agent_credential(caller, connection_id)
    descriptor = PROVIDERS.get(credential.provider)
    if descriptor is None or descriptor.usage is None:
        return {"provider": credential.provider, "limits": False, "windows": {}}
    async with account_usage_lock(connection_id):
        try:
            async with asyncio.timeout(90):
                windows = await descriptor.usage(credential, refresh)
        except ApiException:
            raise
        except Exception:
            LOG.info("Plan limits of a %s account are unavailable", credential.provider, exc_info=True)
            windows = {}
    return {"provider": credential.provider, "limits": True, "windows": windows or {}}


async def close_all():
    tasks = [attempt.task for attempt in _attempts.values() if attempt.task]
    for task in tasks:
        task.cancel()
    await asyncio.gather(*tasks, return_exceptions=True)
    _attempts.clear()
    for key in list(_opencode_attempts):
        await _drop_opencode(key)
    google = [taken for key in list(_google_attempts) if (taken := _take_google(key))]
    await asyncio.gather(*(attempt.login.close() for attempt in google), return_exceptions=True)
