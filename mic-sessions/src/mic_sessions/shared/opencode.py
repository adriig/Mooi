"""OpenCode CLI transport through the Agent Client Protocol.

Every Mooi session gets a private OpenCode home and one long-lived standalone ``opencode acp``
process. An API-key account's key exists only in that process environment; an OpenCode account (device
sign-in, Go subscriptions included) is imported with ``opencode auth import`` into the private home and
read back with ``opencode auth export``. The host environment and other tenants' credentials are never
inherited. JSON-RPC frames are recorded without environment data so
Trace and Download log can retain the provider-native conversation after the process exits.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import re
import shutil
import signal
import tempfile
import time
from collections.abc import Awaitable, Callable, Mapping, Sequence
from contextlib import suppress
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from mic_sessions.shared.mooi import Credential

LOG = logging.getLogger("sessions")

PROTOCOL_VERSION = 1
_INHERITED = ("PATH", "TMPDIR", "LANG", "LC_ALL", "JAVA_HOME", "DOCKER_HOST", "AGENT_BROWSER_DIRECTORY")
_CREDENTIAL_ENV = "MOOI_OPENCODE_API_KEY"
API_KEY_MODE = "api_key"
# An OpenCode account signed in through the CLI's own device flow: the `opencode auth export` JSON.
ACCOUNT_MODE = "account"
ACCOUNT_LIMIT = 64 * 1024
# The integration the CLI signs in to; OpenCode Go is part of the same account.
_ACCOUNT_INTEGRATION = "opencode"
_ACCOUNT_METHOD = "device"
LOGIN_SECONDS = 600
_ANSI = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]|\x1b\][^\x07]*\x07")
_USER_CODE = re.compile(r"Enter code:\s*([A-Z0-9-]{4,32})")
_VERIFICATION_URL = re.compile(r"https://\S+")
_RESERVED = frozenset({"HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME",
                       "OPENCODE_API_KEY", _CREDENTIAL_ENV})
_RESERVED_PREFIXES = ("OPENCODE_",)
_CLOSE_SECONDS = 10
_MESSAGE_LIMIT = 64 * 1024 * 1024

UpdateHandler = Callable[[dict[str, Any]], Awaitable[None]]
RequestHandler = Callable[[str, dict[str, Any]], Awaitable[dict[str, Any]]]


class OpenCodeError(RuntimeError):
    def __init__(self, cause: str, detail: str = "", code: int | None = None) -> None:
        super().__init__(f"{cause}: {detail}" if detail else cause)
        self.cause = cause
        self.detail = detail
        self.code = code


def _rpc_error_detail(error: Mapping[str, Any]) -> str:
    """Keep structured ACP error context so callers can classify provider failures."""
    message = str(error.get("message") or "").strip()
    data = error.get("data")
    if data is None:
        return message
    try:
        context = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    except (TypeError, ValueError):
        context = str(data)
    return f"{message}: {context}" if message else context


def executable(configured: str) -> str:
    path = shutil.which(configured)
    if path is None:
        raise OpenCodeError("the OpenCode CLI is not installed", configured)
    return path


def prepare_home(home: Path, mode: str = API_KEY_MODE, *, permission: Mapping[str, str] | None = None) -> None:
    """Create an isolated OpenCode profile with non-interactive full-access permissions, or the narrower
    `permission` rules given (OpenCode removes the tools a rule denies)."""
    for path in (home, home / ".config" / "opencode", home / ".local" / "share", home / ".cache", home / ".mooi"):
        path.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(path, 0o700)
    config = home / ".config" / "opencode" / "opencode.json"
    # OpenCode 2.x treats OPENCODE_API_KEY as both an inference key and a Console account connection.
    # A personal key may list and call models but cannot read the account configuration endpoint, so that
    # implicit connection is marked signed out and every ACP prompt is rejected. An explicit provider key
    # keeps the credential on the inference path only. Both Console and Go use the same user-created key.
    settings: dict[str, Any] = {"$schema": "https://opencode.ai/config.json",
                                "permission": dict(permission) if permission is not None else {"*": "allow"}}
    if mode == API_KEY_MODE:
        provider = {"options": {"apiKey": f"{{env:{_CREDENTIAL_ENV}}}"}}
        settings["provider"] = {"opencode": provider, "opencode-go": provider}
    config.write_text(json.dumps(settings, indent=2), encoding="utf-8")
    os.chmod(config, 0o600)


def environment(home: Path, credential: Credential, extra: Mapping[str, str] | None = None) -> dict[str, str]:
    """Minimal environment for OpenCode and the tools it starts; never merge service secrets."""
    if credential.mode not in (API_KEY_MODE, ACCOUNT_MODE):
        raise OpenCodeError("the OpenCode credential is invalid", f"unsupported mode {credential.mode}")
    values = {key: os.environ[key] for key in _INHERITED if key in os.environ}
    for key, value in (extra or {}).items():
        if key in _RESERVED or key.startswith(_RESERVED_PREFIXES):
            continue
        values[key] = value
    values.update({
        "HOME": str(home),
        "XDG_CONFIG_HOME": str(home / ".config"),
        "XDG_DATA_HOME": str(home / ".local" / "share"),
        "XDG_CACHE_HOME": str(home / ".cache"),
        "OPENCODE_DISABLE_AUTOUPDATE": "1",
        "TERM": "dumb",
    })
    if credential.mode == API_KEY_MODE:
        values[_CREDENTIAL_ENV] = credential.token
    return values


async def _run(binary: str, arguments: Sequence[str], env: Mapping[str, str], cwd: Path,
               stdin: str = "", timeout: float = 30) -> str:
    """One short OpenCode command in the private home; its output may hold secrets, so it is only returned."""
    try:
        process = await asyncio.create_subprocess_exec(
            binary, *arguments, cwd=str(cwd), env=dict(env), stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, start_new_session=True)
    except OSError as error:
        raise OpenCodeError("the OpenCode CLI could not start", str(error)) from error
    try:
        async with asyncio.timeout(timeout):
            out, _ = await process.communicate(stdin.encode())
    except TimeoutError:
        with suppress(ProcessLookupError):
            os.killpg(process.pid, signal.SIGKILL)
        await process.wait()
        raise OpenCodeError("the OpenCode CLI did not answer", arguments[1] if len(arguments) > 1 else "") from None
    if process.returncode != 0:
        raise OpenCodeError("the OpenCode CLI rejected the command", f"exit code {process.returncode}")
    return out.decode("utf-8", "replace")


def valid_account(token: str) -> bool:
    """Shape of an account credential: a non-empty JSON array of objects, as `opencode auth export` prints."""
    if not token or len(token) > ACCOUNT_LIMIT:
        return False
    try:
        value = json.loads(token)
    except ValueError:
        return False
    return isinstance(value, list) and bool(value) and all(isinstance(entry, dict) for entry in value)


def catalog_cache_key(credential: Credential) -> str:
    """Key account-specific ACP catalogs without retaining a credential in cache keys."""
    identity = json.dumps([credential.provider, credential.connection_id, credential.mode, credential.token],
                          ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(identity.encode("utf-8")).hexdigest()


async def install_account(home: Path, binary: str, credential: Credential) -> None:
    """Imports the account's sign-in into the session's private profile (never the host's)."""
    if credential.mode != ACCOUNT_MODE:
        return
    if not valid_account(credential.token):
        raise OpenCodeError("the OpenCode credential is invalid", "malformed account sign-in")
    await _run(binary, ["auth", "import", "--standalone"], environment(home, credential), home,
               stdin=credential.token)


async def export_account(home: Path, binary: str, credential: Credential) -> str | None:
    """The account sign-in as the CLI holds it now (it may have renewed it), or None when unreadable."""
    try:
        out = await _run(binary, ["auth", "export", _ACCOUNT_INTEGRATION, "--standalone"],
                         environment(home, credential), home)
    except OpenCodeError:
        return None
    out = out.strip()
    return out if valid_account(out) else None


class LoginError(OpenCodeError):
    pass


class Login:
    """The CLI's official device sign-in (`opencode auth login opencode --method device`) in a private,
    temporary profile. `start()` answers the verification URL and user code the CLI prints; `wait()` ends
    when the player authorized it and answers the credential `opencode auth export` then holds."""

    def __init__(self, binary: str) -> None:
        self._binary = binary
        self._directory: Path | None = None
        self._process: asyncio.subprocess.Process | None = None
        self._output = ""

    def _env(self) -> dict[str, str]:
        assert self._directory is not None
        return environment(self._directory, Credential("opencode", ACCOUNT_MODE, "", None))

    async def start(self, timeout: float = 45) -> tuple[str, str]:
        self._directory = Path(tempfile.mkdtemp(prefix="mooi-opencode-login-"))
        os.chmod(self._directory, 0o700)
        await asyncio.to_thread(prepare_home, self._directory, ACCOUNT_MODE)
        try:
            self._process = await asyncio.create_subprocess_exec(
                executable(self._binary), "auth", "login", _ACCOUNT_INTEGRATION, "--method", _ACCOUNT_METHOD,
                "--standalone", cwd=str(self._directory), env=self._env(), stdin=asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT, start_new_session=True)
        except OSError as error:
            raise LoginError("the OpenCode CLI could not start", str(error)) from error
        try:
            async with asyncio.timeout(timeout):
                while True:
                    code, url = _USER_CODE.search(self._output), _VERIFICATION_URL.search(self._output)
                    if code and url:
                        return url.group(0), code.group(1)
                    assert self._process.stdout is not None
                    chunk = await self._process.stdout.read(4096)
                    if not chunk:
                        raise LoginError("the OpenCode CLI ended the sign-in")
                    self._output = _ANSI.sub("", self._output + chunk.decode("utf-8", "replace"))[-8192:]
        except TimeoutError:
            raise LoginError("the OpenCode CLI did not start the sign-in") from None

    async def wait(self) -> str:
        process = self._process
        if process is None or self._directory is None:
            raise LoginError("the sign-in was not started")
        try:
            async with asyncio.timeout(LOGIN_SECONDS):
                assert process.stdout is not None
                while await process.stdout.read(4096):
                    pass
                await process.wait()
        except TimeoutError:
            raise LoginError("the sign-in expired") from None
        if process.returncode != 0:
            raise LoginError("the OpenCode sign-in was not completed")
        token = await export_account(self._directory, self._binary, Credential("opencode", ACCOUNT_MODE, "", None))
        if token is None:
            raise LoginError("the OpenCode CLI stored no account sign-in")
        return token

    async def close(self) -> None:
        process = self._process
        if process is not None and process.returncode is None:
            with suppress(ProcessLookupError):
                os.killpg(process.pid, signal.SIGKILL)
            await process.wait()
        if self._directory is not None:
            await asyncio.to_thread(shutil.rmtree, self._directory, True)
            self._directory = None


def trace_path(home: Path) -> Path:
    return home / ".mooi" / "opencode-acp.jsonl"


class _Process:
    def __init__(self, arguments: Sequence[str], *, cwd: Path, env: Mapping[str, str]) -> None:
        self.arguments = list(arguments)
        self.cwd = cwd
        self.env = dict(env)
        self.process: asyncio.subprocess.Process | None = None
        self.stderr_task: asyncio.Task[None] | None = None

    @property
    def running(self) -> bool:
        return self.process is not None and self.process.returncode is None

    async def spawn(self) -> asyncio.subprocess.Process:
        try:
            self.process = await asyncio.create_subprocess_exec(
                *self.arguments, cwd=str(self.cwd), env=self.env, stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, limit=_MESSAGE_LIMIT,
                start_new_session=True,
            )
        except OSError as error:
            raise OpenCodeError("the OpenCode CLI could not start", str(error)) from error
        self.stderr_task = asyncio.create_task(self._read_stderr())
        return self.process

    async def _read_stderr(self) -> None:
        process = self.process
        if process is None or process.stderr is None:
            return
        while await process.stderr.readline():
            pass

    def failure_detail(self) -> str:
        code = self.process.returncode if self.process else None
        # Provider stderr can contain credentials or account data; keep diagnostics non-sensitive.
        return f"exit code {code}"

    async def stop(self) -> None:
        process = self.process
        if process is not None and process.returncode is None:
            with suppress(ProcessLookupError):
                os.killpg(process.pid, signal.SIGTERM)
            try:
                await asyncio.wait_for(process.wait(), _CLOSE_SECONDS)
            except TimeoutError:
                with suppress(ProcessLookupError):
                    os.killpg(process.pid, signal.SIGKILL)
                await process.wait()
        if self.stderr_task is not None:
            self.stderr_task.cancel()
            await asyncio.gather(self.stderr_task, return_exceptions=True)


class Acp:
    """One ordered JSON-RPC stream to ``opencode acp``."""

    def __init__(self, arguments: Sequence[str], *, cwd: Path, env: Mapping[str, str], trace: Path,
                 updates: UpdateHandler, requests: RequestHandler | None = None,
                 secrets: Sequence[str] = ()) -> None:
        self._process = _Process(arguments, cwd=cwd, env=env)
        self._trace = trace
        self._secrets = tuple(secret for secret in secrets if secret)
        self._updates = updates
        self._requests = requests
        self._reader: asyncio.Task[None] | None = None
        self._responses: dict[int, asyncio.Future[Any]] = {}
        self._handlers: set[asyncio.Task[None]] = set()
        self._write_lock = asyncio.Lock()
        self._next_id = 0
        self._failure: OpenCodeError | None = None
        self.initialization: dict[str, Any] = {}

    @property
    def alive(self) -> bool:
        return self._process.running and self._failure is None

    @property
    def failure(self) -> OpenCodeError | None:
        return self._failure

    async def start(self, timeout: float = 60) -> dict[str, Any]:
        await self._process.spawn()
        self._reader = asyncio.create_task(self._read())
        try:
            self.initialization = await self.request("initialize", {
                "protocolVersion": PROTOCOL_VERSION,
                "clientCapabilities": {"fs": {"readTextFile": False, "writeTextFile": False}, "terminal": False},
                "clientInfo": {"name": "mooi", "title": "Mooi", "version": "0.1.0"},
            }, timeout=timeout)
        except BaseException:
            await self.close()
            raise
        return self.initialization

    async def request(self, method: str, params: dict[str, Any], timeout: float | None = 60) -> Any:
        self._next_id += 1
        request_id = self._next_id
        future: asyncio.Future[Any] = asyncio.get_running_loop().create_future()
        self._responses[request_id] = future
        try:
            await self._write({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
            if timeout is None:
                return await future
            async with asyncio.timeout(timeout):
                return await future
        except TimeoutError:
            raise OpenCodeError("the OpenCode CLI did not answer", method) from None
        finally:
            self._responses.pop(request_id, None)

    async def notify(self, method: str, params: dict[str, Any]) -> None:
        await self._write({"jsonrpc": "2.0", "method": method, "params": params})

    async def close(self) -> None:
        for task in list(self._handlers):
            task.cancel()
        await self._process.stop()
        if self._reader is not None:
            self._reader.cancel()
            await asyncio.gather(self._reader, return_exceptions=True)
        await asyncio.gather(*self._handlers, return_exceptions=True)
        self._fail(OpenCodeError("the OpenCode agent process exited"))

    def _record(self, direction: str, frame: dict[str, Any]) -> None:
        entry = {"mooiAt": datetime.now(UTC).isoformat(), "direction": direction, "frame": frame}
        try:
            with self._trace.open("a", encoding="utf-8") as handle:
                serialized = json.dumps(entry, ensure_ascii=False, separators=(",", ":"))
                for secret in self._secrets:
                    serialized = serialized.replace(secret, "[REDACTED]")
                handle.write(serialized + "\n")
        except OSError:
            LOG.debug("Could not append the OpenCode ACP trace", exc_info=True)

    async def _write(self, frame: dict[str, Any]) -> None:
        process = self._process.process
        if not self.alive or process is None or process.stdin is None:
            raise self._failure or OpenCodeError("the OpenCode agent process exited")
        data = (json.dumps(frame, ensure_ascii=False) + "\n").encode()
        async with self._write_lock:
            try:
                process.stdin.write(data)
                await process.stdin.drain()
                self._record("client", frame)
            except (BrokenPipeError, ConnectionResetError) as error:
                raise OpenCodeError("the OpenCode agent process exited") from error

    async def _read(self) -> None:
        process = self._process.process
        assert process is not None and process.stdout is not None
        try:
            while True:
                line = await process.stdout.readline()
                if not line:
                    break
                try:
                    frame = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if isinstance(frame, dict):
                    self._record("agent", frame)
                    await self._dispatch(frame)
            await process.wait()
            self._fail(OpenCodeError("the OpenCode agent process exited", self._process.failure_detail()))
        except asyncio.CancelledError:
            raise
        except Exception as error:
            self._fail(error if isinstance(error, OpenCodeError)
                       else OpenCodeError("the OpenCode agent stream broke", str(error)))

    async def _dispatch(self, frame: dict[str, Any]) -> None:
        method = frame.get("method")
        if method is None:
            future = self._responses.get(frame.get("id"))  # type: ignore[arg-type]
            if future is None or future.done():
                return
            error = frame.get("error")
            if isinstance(error, dict):
                future.set_exception(OpenCodeError("the OpenCode CLI rejected a request",
                                                   _rpc_error_detail(error), error.get("code")))
            else:
                future.set_result(frame.get("result"))
        elif "id" in frame:
            task = asyncio.create_task(self._answer(frame["id"], str(method), frame.get("params") or {}))
            self._handlers.add(task)
            task.add_done_callback(self._handlers.discard)
        elif method == "session/update":
            await self._updates(frame.get("params") or {})

    async def _answer(self, request_id: Any, method: str, params: dict[str, Any]) -> None:
        try:
            if self._requests is None:
                raise OpenCodeError("unsupported OpenCode request", method)
            result = await self._requests(method, params)
            reply = {"jsonrpc": "2.0", "id": request_id, "result": result}
        except asyncio.CancelledError:
            raise
        except Exception as error:
            reply = {"jsonrpc": "2.0", "id": request_id,
                     "error": {"code": -32603, "message": str(error)[:200]}}
        with suppress(OpenCodeError):
            await self._write(reply)

    def _fail(self, error: OpenCodeError) -> None:
        if self._failure is None:
            self._failure = error
        for future in self._responses.values():
            if not future.done():
                future.set_exception(self._failure)


def model_option(session: Mapping[str, Any]) -> Mapping[str, Any] | None:
    options = session.get("configOptions")
    if not isinstance(options, list):
        return None
    return next((entry for entry in options if isinstance(entry, dict) and entry.get("id") == "model"), None)


_NAMESPACE = re.compile(r"^(?:opencode-go|opencode)\s*[/:]\s*", re.IGNORECASE)


def display_name(name: Any, model_id: str) -> str:
    """The model's visible name without OpenCode's namespace; the canonical id (ACP's) is untouched."""
    text = name if isinstance(name, str) and name.strip() else model_id
    return _NAMESPACE.sub("", text.strip()) or text.strip()


def catalog(session: Mapping[str, Any], mode: str) -> tuple[list[dict[str, Any]], str | None]:
    option = model_option(session)
    if option is None:
        return [], None
    namespace = {
        API_KEY_MODE: "opencode/",
        ACCOUNT_MODE: "opencode-go/",
    }.get(mode)
    if namespace is None:
        raise OpenCodeError("the OpenCode credential is invalid", f"unsupported mode {mode}")
    entries = option.get("options")
    models = [{"id": entry["value"], "label": display_name(entry.get("name"), entry["value"]), "efforts": [],
               **({"description": entry["description"]} if entry.get("description") else {})}
              for entry in entries or [] if isinstance(entry, dict)
              and isinstance(entry.get("value"), str)
              and entry["value"].startswith(namespace)]
    # Models of the Go subscription and of Zen can share a display name: keep them apart.
    labels = [model["label"] for model in models]
    for model in models:
        if labels.count(model["label"]) > 1 and model["id"].startswith("opencode-go/"):
            model["label"] += " (Go)"
    current = option.get("currentValue") or option.get("value")
    return models, (current if isinstance(current, str) and any(model["id"] == current for model in models) else None)


_catalogs: dict[str, tuple[float, list[dict[str, Any]], str | None]] = {}


async def models(credential: Credential, binary: str, scratch: Path,
                 *, refresh: bool = False) -> tuple[list[dict[str, Any]], str | None]:
    key = catalog_cache_key(credential)
    cached = _catalogs.get(key)
    if key and not refresh and cached and cached[0] > time.monotonic():
        return cached[1], cached[2]
    home, workspace = scratch / "home", scratch / "workspace"
    workspace.mkdir(parents=True, exist_ok=True)
    prepare_home(home, credential.mode)
    process_env = environment(home, credential)
    await install_account(home, binary, credential)

    async def ignore(_: dict[str, Any]) -> None:
        return None

    acp = Acp([executable(binary), "acp"], cwd=workspace, env=process_env,
              trace=trace_path(home), updates=ignore, secrets=(credential.token,))
    try:
        await acp.start()
        session = await acp.request("session/new", {"cwd": str(workspace), "mcpServers": []})
        result, default = catalog(session or {}, credential.mode)
    finally:
        await acp.close()
    if not result:
        raise OpenCodeError("OpenCode returned no models for this account")
    for stale, (expires, *_) in list(_catalogs.items()):
        if expires <= time.monotonic():
            _catalogs.pop(stale, None)
    if len(_catalogs) >= 1024:
        _catalogs.pop(next(iter(_catalogs)))
    _catalogs[key] = (time.monotonic() + 300, result, default)
    return result, default
