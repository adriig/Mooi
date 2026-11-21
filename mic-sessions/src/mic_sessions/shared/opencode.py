"""OpenCode CLI transport through the Agent Client Protocol.

Every Mooi session gets a private OpenCode home and one long-lived standalone ``opencode acp``
process. The account API key exists only in that process environment; the host environment and other
tenants' credentials are never inherited. JSON-RPC frames are recorded without environment data so
Trace and Download log can retain the provider-native conversation after the process exits.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import shutil
import signal
import time
from collections import deque
from collections.abc import Awaitable, Callable, Mapping, Sequence
from contextlib import suppress
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from mic_sessions.shared.mooi import Credential

LOG = logging.getLogger("sessions")

PROTOCOL_VERSION = 1
_INHERITED = ("PATH", "TMPDIR", "LANG", "LC_ALL", "JAVA_HOME", "DOCKER_HOST", "AGENT_BROWSER_DIRECTORY")
_CREDENTIAL_ENV = "MOOI_OPENCODE_API_KEY"
_RESERVED = frozenset({"HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME",
                       "OPENCODE_API_KEY", _CREDENTIAL_ENV})
_RESERVED_PREFIXES = ("OPENCODE_",)
_STDERR_TAIL = 20
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


def executable(configured: str) -> str:
    path = shutil.which(configured)
    if path is None:
        raise OpenCodeError("the OpenCode CLI is not installed", configured)
    return path


def prepare_home(home: Path) -> None:
    """Create an isolated OpenCode profile with non-interactive full-access permissions."""
    for path in (home, home / ".config" / "opencode", home / ".local" / "share", home / ".cache", home / ".mooi"):
        path.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(path, 0o700)
    config = home / ".config" / "opencode" / "opencode.json"
    # OpenCode 2.x treats OPENCODE_API_KEY as both an inference key and a Console account connection.
    # A personal key may list and call models but cannot read the account configuration endpoint, so that
    # implicit connection is marked signed out and every ACP prompt is rejected. An explicit provider key
    # keeps the credential on the inference path only. Both Console and Go use the same user-created key.
    provider = {"options": {"apiKey": f"{{env:{_CREDENTIAL_ENV}}}"}}
    config.write_text(json.dumps({
        "$schema": "https://opencode.ai/config.json",
        "permission": {"*": "allow"},
        "provider": {"opencode": provider, "opencode-go": provider},
    }, indent=2), encoding="utf-8")
    os.chmod(config, 0o600)


def environment(home: Path, credential: Credential, extra: Mapping[str, str] | None = None) -> dict[str, str]:
    """Minimal environment for OpenCode and the tools it starts; never merge service secrets."""
    if credential.mode != "api_key":
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
        _CREDENTIAL_ENV: credential.token,
        "OPENCODE_DISABLE_AUTOUPDATE": "1",
        "TERM": "dumb",
    })
    return values


def trace_path(home: Path) -> Path:
    return home / ".mooi" / "opencode-acp.jsonl"


class _Process:
    def __init__(self, arguments: Sequence[str], *, cwd: Path, env: Mapping[str, str]) -> None:
        self.arguments = list(arguments)
        self.cwd = cwd
        self.env = dict(env)
        self.process: asyncio.subprocess.Process | None = None
        self.stderr_tail: deque[str] = deque(maxlen=_STDERR_TAIL)
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
        while line := await process.stderr.readline():
            text = line.decode("utf-8", "replace").strip()
            if text:
                self.stderr_tail.append(text)

    def failure_detail(self) -> str:
        code = self.process.returncode if self.process else None
        tail = self.stderr_tail[-1] if self.stderr_tail else ""
        return f"exit code {code}{f': {tail}' if tail else ''}"

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
                 updates: UpdateHandler, requests: RequestHandler | None = None) -> None:
        self._process = _Process(arguments, cwd=cwd, env=env)
        self._trace = trace
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
        entry = {"mooiAt": datetime.now(timezone.utc).isoformat(), "direction": direction, "frame": frame}
        try:
            with self._trace.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(entry, ensure_ascii=False, separators=(",", ":")) + "\n")
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
                                                   str(error.get("message") or ""), error.get("code")))
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


def catalog(session: Mapping[str, Any]) -> tuple[list[dict[str, Any]], str | None]:
    option = model_option(session)
    if option is None:
        return [], None
    entries = option.get("options")
    models = [{"id": entry["value"], "label": entry.get("name") or entry["value"], "efforts": [],
               **({"description": entry["description"]} if entry.get("description") else {})}
              for entry in entries or [] if isinstance(entry, dict)
              and isinstance(entry.get("value"), str)
              and entry["value"].startswith(("opencode/", "opencode-go/"))]
    current = option.get("currentValue") or option.get("value")
    return models, (current if isinstance(current, str)
                    and current.startswith(("opencode/", "opencode-go/")) else None)


_catalogs: dict[str, tuple[float, list[dict[str, Any]], str | None]] = {}


async def models(credential: Credential, binary: str, scratch: Path,
                 *, refresh: bool = False) -> tuple[list[dict[str, Any]], str | None]:
    key = credential.connection_id or ""
    cached = _catalogs.get(key)
    if key and not refresh and cached and cached[0] > time.monotonic():
        return cached[1], cached[2]
    home, workspace = scratch / "home", scratch / "workspace"
    workspace.mkdir(parents=True, exist_ok=True)
    prepare_home(home)
    process_env = environment(home, credential)

    async def ignore(_: dict[str, Any]) -> None:
        return None

    acp = Acp([executable(binary), "acp"], cwd=workspace, env=process_env,
              trace=trace_path(home), updates=ignore)
    try:
        await acp.start()
        session = await acp.request("session/new", {"cwd": str(workspace), "mcpServers": []})
        result, default = catalog(session or {})
    finally:
        await acp.close()
    if not result:
        raise OpenCodeError("OpenCode returned no models for this API key")
    if key:
        _catalogs[key] = (time.monotonic() + 300, result, default)
    return result, default
