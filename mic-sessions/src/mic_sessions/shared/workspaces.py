"""Managed session directories, Git clones, release checkouts and index-preserving diffs.

Only marked directories in sessions/<UUID>, production/<operation UUID> and documents/<operation UUID> belong
to this implementation.
Legacy caches, workspaces and reservations are never reconciled.
"""

from __future__ import annotations

import asyncio
import base64
import contextlib
import fcntl
import hashlib
import json
import logging
import os
import re
import shutil
import signal
import stat
import time
from collections.abc import AsyncIterator
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path, PurePosixPath
from uuid import UUID, uuid4

from mic_sessions.shared.env import get_settings
from mic_sessions.shared.mooi import GitIdentity, Project
from mic_sessions.shared.web import ApiException

LOG = logging.getLogger("sessions")

_BRANCH_PATTERN = re.compile(r"^[A-Za-z0-9._\-/]{1,120}$")
_CREDENTIAL_PATTERN = re.compile(r"//[^/@\s]*@")
_NO_CREDENTIAL_HELPER = ("-c", "credential.helper=")
_SNAPSHOT_IDENTITY = GitIdentity(name="Mooi", email="mooi@localhost")


def _processes_inside(directory: Path) -> list[int]:
    """Processes whose working directory is inside `directory`; empty where `/proc` is unavailable."""
    found = []
    with contextlib.suppress(OSError):
        for entry in Path("/proc").iterdir():
            if not entry.name.isdigit() or int(entry.name) == os.getpid():
                continue
            with contextlib.suppress(OSError, ValueError):
                if Path(os.readlink(entry / "cwd")).is_relative_to(directory):
                    found.append(int(entry.name))
    return found


def _terminate_processes(directory: Path, grace_seconds: float = 5.0) -> None:
    """Stops what agents left running in a workspace (background dev servers, `nohup` jobs): they
    would outlive the session holding its ports and keep writing into the tree being removed."""
    directory = directory.resolve()
    pids = _processes_inside(directory)
    if pids:
        LOG.info("Stopping %s process(es) left running in %s", len(pids), directory)
    for sig in (signal.SIGTERM, signal.SIGKILL):
        for pid in pids:
            with contextlib.suppress(ProcessLookupError, PermissionError):
                os.kill(pid, sig)
        deadline = time.monotonic() + grace_seconds
        while pids and time.monotonic() < deadline:
            time.sleep(0.1)
            pids = _processes_inside(directory)
        if not pids:
            return


@dataclass(frozen=True)
class SessionWorkspace:
    """An isolated checkout of a repository, owned by exactly one session."""

    path: Path
    base_commit: str


@dataclass(frozen=True)
class WorkspaceInspection:
    """What a clone looks like on disk right now: its checked-out ref, size and pending edits."""

    head_commit: str | None
    head_branch: str | None
    dirty_files: int
    size_bytes: int


@dataclass(frozen=True)
class MergePreview:
    """What squashing the working tree onto the freshly fetched target would produce.

    `tree` is the merged tree when `conflicts` is empty; `up_to_date` means the target already holds
    every change, so there is nothing to publish. `merge_base` is the last commit both sides share.
    """

    target: str
    tree: str
    conflicts: list[str]
    up_to_date: bool
    merge_base: str | None


@dataclass(frozen=True)
class ChangedFile:
    path: str
    change: str
    added: int
    removed: int


@dataclass(frozen=True)
class ChangesSummary:
    files: list[ChangedFile]
    added: int
    removed: int


@dataclass(frozen=True)
class FileDiff:
    diff: str
    # "text", or "binary" / "too_large" when there is no diff to read.
    preview: str
    truncated: bool = False


@dataclass(frozen=True)
class FileListing:
    paths: list[str]
    truncated: bool = False


@dataclass(frozen=True)
class FileContent:
    # "text", or "binary" / "too_large" when there is nothing editable to show.
    preview: str
    content: str = ""
    revision: str = ""
    size: int = 0


@dataclass(frozen=True)
class RecoverySnapshot:
    """A completed, content-addressed recovery bundle and its safe task handoff metadata."""

    task_id: UUID
    attempt: int
    branch: str
    base_commit: str
    head_commit: str
    snapshot_revision: str
    files: list[str]


@dataclass(frozen=True)
class RecoveryLease:
    """An open shared lock that prevents retention cleanup from deleting an active restore input."""

    directory: Path
    lock_file: object


_BINARY_DIFF = re.compile(r"^Binary files .* differ$", re.MULTILINE)
_CHANGE_KINDS = {"A": "added", "M": "modified", "D": "deleted", "R": "renamed", "C": "renamed", "T": "modified"}
_RECOVERY_REVISION = re.compile(r"^[0-9a-f]{64}$")
_RECOVERY_BRANCH = re.compile(r"^[A-Za-z0-9._/-]{1,120}$")


def _identity_env(identity: GitIdentity) -> dict[str, str]:
    return {"GIT_AUTHOR_NAME": identity.name, "GIT_AUTHOR_EMAIL": identity.email,
            "GIT_COMMITTER_NAME": identity.name, "GIT_COMMITTER_EMAIL": identity.email}


def _redact(text: str) -> str:
    return _CREDENTIAL_PATTERN.sub("//***@", text)


def _directory_size(directory: Path) -> int:
    """Apparent size of every regular file below `directory`, never following symlinks."""
    total = 0
    for root, _, files in os.walk(directory, followlinks=False):
        for name in files:
            try:
                stat = os.lstat(os.path.join(root, name))
            except OSError:
                continue
            total += stat.st_size
    return total


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _open_recovery_lock(path: Path):
    """Open a lock without following a possibly substituted symlink."""
    descriptor = os.open(path, os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0), 0o600)
    return os.fdopen(descriptor, "r+b")


def _fsync_directory(path: Path) -> None:
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


class Workspaces:
    """Owns the on-disk layout and every git invocation made by this service."""

    def __init__(self, root: Path | None = None) -> None:
        self.root = (root or get_settings().workspace_root).resolve()

    def workspace_path(self, session_id: UUID) -> Path:
        return self.root / "sessions" / str(session_id) / "repository"

    def _session_directory(self, session_id: UUID) -> Path:
        directory = self.workspace_path(session_id).parent
        if directory.parent.is_symlink() or directory.is_symlink():
            raise RuntimeError("Refusing a symlink in the managed session path")
        if directory.resolve() != directory:
            raise RuntimeError("Session path is outside the managed root")
        return directory

    # --- git ----------------------------------------------------------------------------------

    async def _run(self, *args: str, cwd: Path | None = None, auth: str | None = None,
                   extra_env: dict[str, str] | None = None, stdin: str | None = None) -> tuple[int, str, str]:
        """Runs one git command to completion and returns `(exit code, stdout, redacted stderr)`."""
        settings = get_settings()
        env = {**os.environ, "GIT_TERMINAL_PROMPT": "0", **(extra_env or {})}
        if auth:
            encoded = base64.b64encode(f"x-access-token:{auth}".encode()).decode()
            env.update({"GIT_CONFIG_COUNT": "2", "GIT_CONFIG_KEY_0": "credential.helper",
                        "GIT_CONFIG_VALUE_0": "", "GIT_CONFIG_KEY_1": "http.https://github.com/.extraheader",
                        "GIT_CONFIG_VALUE_1": f"Authorization: Basic {encoded}"})
        process = await asyncio.create_subprocess_exec(
            settings.git_binary,
            *args,
            cwd=str(cwd) if cwd else None,
            env=env,
            stdin=asyncio.subprocess.PIPE if stdin is not None else None,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        try:
            stdout, stderr = await asyncio.wait_for(
                process.communicate(stdin.encode() if stdin is not None else None), settings.git_timeout_seconds)
        except (TimeoutError, asyncio.CancelledError) as error:
            if process.returncode is None:
                process.kill()
            await process.wait()
            if isinstance(error, asyncio.CancelledError):
                raise
            raise ApiException.bad_gateway("The git command timed out") from None

        detail = _redact(stderr.decode("utf-8", "replace").strip())
        if auth:
            detail = detail.replace(auth, "***").replace(encoded, "***")
        return process.returncode or 0, stdout.decode("utf-8", "replace"), detail

    async def _git(self, *args: str, cwd: Path | None = None, auth: str | None = None,
                   extra_env: dict[str, str] | None = None, stdin: str | None = None) -> str:
        """Runs one git command and returns its stdout, failing the request on a non-zero exit."""
        code, stdout, detail = await self._run(*args, cwd=cwd, auth=auth, extra_env=extra_env, stdin=stdin)
        if code != 0:
            LOG.warning("git %s failed with %s: %s", args[0], code, detail or "no output")
            raise ApiException.bad_gateway(detail or "The git command failed")
        return stdout

    async def _git_ok(self, *args: str, cwd: Path | None = None) -> bool:
        """Runs one git command for its exit code alone, tolerating failure."""
        code, _, detail = await self._run(*args, cwd=cwd)
        if code != 0 and detail:
            LOG.info("git %s exited with %s: %s", args[0], code, detail)
        return code == 0

    async def _empty_tree(self, workspace: Path) -> str:
        """The empty tree of the clone's object format: the base of an unborn branch."""
        return (await self._git("-C", str(workspace), "mktree", stdin="")).strip()

    async def _born(self, workspace: Path) -> bool:
        """Whether HEAD names a commit; false on the unborn branch of an empty repository."""
        return await self._git_ok("-C", str(workspace), "rev-parse", "-q", "--verify", "HEAD^{commit}")

    async def validate_branch(self, branch: str) -> None:
        """Rejects anything git would not accept as a branch name, or would read as an option."""
        if not _BRANCH_PATTERN.match(branch) or branch.startswith("-"):
            raise ApiException.bad_request("Invalid branch name")
        if not await self._git_ok("check-ref-format", "--branch", branch):
            raise ApiException.bad_request("Invalid branch name")

    async def create_workspace(
        self, project: Project, session_id: UUID, branch: str, token: str, *, base_commit: str | None = None,
    ) -> SessionWorkspace:
        await self.validate_branch(branch)
        await self.validate_branch(project.default_branch)
        directory = self._session_directory(session_id)
        directory.parent.mkdir(parents=True, exist_ok=True)
        # Exclusive creation prevents one attempt from deleting another's workspace.
        directory.mkdir()
        try:
            (directory / ".mooi-session").write_text(str(session_id))
            path = directory / "repository"
            url = f"https://github.com/{project.full_name}.git"
            await self._git(*_NO_CREDENTIAL_HELPER, "clone", "--no-checkout", url, str(path), auth=token)
            remote = f"refs/remotes/origin/{branch}"
            default = f"refs/remotes/origin/{project.default_branch}"
            if base_commit is not None:
                if not re.fullmatch(r"[0-9a-f]{40,64}", base_commit):
                    raise ApiException.bad_request("Invalid recovery base revision")
                if await self._git_ok("-C", str(path), "cat-file", "-e", f"{base_commit}^{{commit}}"):
                    await self._git("-C", str(path), "checkout", "--no-track", "-b", branch, base_commit)
                    base_commit = (await self._git("-C", str(path), "rev-parse", "HEAD")).strip()
                elif not (await self._git("-C", str(path), "for-each-ref", "--count=1", "refs/remotes")).strip() \
                        and (await self._empty_tree(path)) == base_commit:
                    await self._git("-C", str(path), "symbolic-ref", "HEAD", f"refs/heads/{branch}")
                    LOG.info("Workspace %s created on recovered empty repository", session_id)
                    return SessionWorkspace(path=path, base_commit=base_commit)
                else:
                    await self._git(*_NO_CREDENTIAL_HELPER, "-C", str(path), "fetch", "--no-tags", "origin",
                                    base_commit, auth=token)
                    fetched = (await self._git("-C", str(path), "rev-parse", "FETCH_HEAD^{commit}")).strip()
                    if fetched != base_commit:
                        raise ApiException.conflict("Recovery base revision changed while preparing the workspace")
                    await self._git("-C", str(path), "checkout", "--no-track", "-b", branch, fetched)
                    base_commit = fetched
                LOG.info("Workspace %s created on recovery base %s", session_id, base_commit[:7])
                return SessionWorkspace(path=path, base_commit=base_commit)
            if await self._git_ok("-C", str(path), "show-ref", "--verify", "--quiet", remote):
                await self._git("-C", str(path), "checkout", "-B", branch, "--track", f"origin/{branch}")
            elif await self._git_ok("-C", str(path), "show-ref", "--verify", "--quiet", default):
                await self._git("-C", str(path), "checkout", "--no-track", "-b", branch, default)
            elif not (await self._git("-C", str(path), "for-each-ref", "--count=1", "refs/remotes")).strip():
                # An empty repository: the session starts on an unborn branch whose base is the
                # empty tree, and its first merge creates the default branch.
                await self._git("-C", str(path), "symbolic-ref", "HEAD", f"refs/heads/{branch}")
                base_tree = await self._empty_tree(path)
                LOG.info("Workspace %s created on %s from an empty repository", session_id, branch)
                return SessionWorkspace(path=path, base_commit=base_tree)
            else:
                raise ApiException.conflict(
                    f"The default branch {project.default_branch} was not found in {project.full_name}")
            base_commit = (await self._git("-C", str(path), "rev-parse", "HEAD")).strip()
            LOG.info("Workspace %s created on %s at %s", session_id, branch, base_commit[:7])
            return SessionWorkspace(path=path, base_commit=base_commit)
        except BaseException:
            # Git has stopped before cleanup begins. Await the deletion even if cancelled again.
            await self._remove_directory(directory)
            raise

    def _release_directory(self, operation_id: UUID) -> Path:
        production = self.root / "production"
        if production.is_symlink():
            raise RuntimeError("Managed production root cannot be a symlink")
        return production / str(operation_id)

    async def create_release_checkout(self, operation_id: UUID, project_full_name: str,
                                      tag: str, sha: str, token: str) -> Path:
        """Fetch only the selected release into a disposable, detached, marked checkout."""
        directory = self._release_directory(operation_id)
        directory.parent.mkdir(parents=True, exist_ok=True)
        directory.mkdir()
        try:
            (directory / ".mooi-release").write_text(str(operation_id))
            checkout = directory / "repository"
            checkout.mkdir()
            await self._git("-C", str(checkout), "init", "-q")
            await self._git("-C", str(checkout), "remote", "add", "origin",
                            f"https://github.com/{project_full_name}.git")
            await self._git(*_NO_CREDENTIAL_HELPER, "-C", str(checkout), "fetch", "--depth", "1",
                            "--no-tags", "origin", f"refs/tags/{tag}", auth=token)
            fetched = (await self._git("-C", str(checkout), "rev-parse", "FETCH_HEAD^{commit}")).strip()
            if fetched != sha:
                raise ApiException.conflict("The selected release changed while preparing the deployment")
            await self._git("-C", str(checkout), "checkout", "--detach", "--force", fetched)
            return checkout
        except BaseException:
            await asyncio.to_thread(shutil.rmtree, directory)
            raise

    def _document_directory(self, operation_id: UUID) -> Path:
        documents = self.root / "documents"
        if documents.is_symlink():
            raise RuntimeError("Managed documents root cannot be a symlink")
        return documents / str(operation_id)

    async def _remove_document_directory(self, operation_id: UUID) -> None:
        directory = self._document_directory(operation_id)
        if directory.is_symlink():
            raise RuntimeError("Refusing a symlink in the document checkout path")
        marker = directory / ".mooi-document"
        if directory.exists() and marker.is_file() and not marker.is_symlink() and marker.read_text() == str(operation_id):
            await asyncio.to_thread(shutil.rmtree, directory)

    async def commit_documents(
        self, project_full_name: str, target: str, directory: str, files: dict[str, str], message: str,
        identity: GitIdentity, token: str, *, adopt_suffix: str | None = None,
    ) -> str | None:
        """Writes `files` (names relative to `directory`) onto the target branch as one commit and pushes it.

        Works on a shallow fetch with a throwaway index and no checkout, so it never touches a session's
        workspace. A sibling directory whose name ends with `adopt_suffix` is moved into `directory` first
        (a renamed feature keeps its decisions). Returns None, with nothing pushed, when the target already
        holds the same tree; like `merge`, a concurrent push is rejected, never forced.
        """
        await self.validate_branch(target)
        parent_directory = PurePosixPath(directory).parent
        if not _BRANCH_PATTERN.match(directory) or ".." in PurePosixPath(directory).parts or directory.startswith("/"):
            raise ApiException.bad_request("Invalid document directory")
        operation_id = uuid4()
        root = self._document_directory(operation_id)
        root.parent.mkdir(parents=True, exist_ok=True)
        root.mkdir()
        try:
            (root / ".mooi-document").write_text(str(operation_id))
            repository = root / "repository"
            repository.mkdir()
            git = ("-C", str(repository))
            await self._git(*git, "init", "-q")
            await self._git(*git, "remote", "add", "origin", f"https://github.com/{project_full_name}.git")
            remote = f"refs/remotes/origin/{target}"
            code, _, detail = await self._run(*_NO_CREDENTIAL_HELPER, *git, "fetch", "--depth", "1", "--no-tags",
                                              "origin", f"+refs/heads/{target}:{remote}", auth=token)
            if code != 0:
                LOG.warning("git fetch failed with %s: %s", code, detail or "no output")
                raise ApiException.bad_gateway(detail or "The default branch could not be fetched")
            env = {"GIT_INDEX_FILE": str(root / "index"), **_identity_env(identity)}
            await self._git(*git, "read-tree", remote, extra_env=env)
            entries: list[str] = []
            if adopt_suffix:
                listed = await self._git(*git, "ls-tree", "-d", "-z", "--name-only", remote,
                                         f"{parent_directory}/")
                for previous in filter(None, listed.split("\0")):
                    if previous == directory or not PurePosixPath(previous).name.endswith(adopt_suffix):
                        continue
                    moved = await self._git(*git, "ls-tree", "-r", "-z", "--full-tree", remote, "--", previous)
                    for line in filter(None, moved.split("\0")):
                        meta, path = line.split("\t", 1)
                        mode, kind, sha = meta.split()
                        entries.append(f"0 {'0' * len(sha)}\t{path}")
                        entries.append(f"{mode} {kind} {sha}\t{directory}{path[len(previous):]}")
            for name, content in files.items():
                path = f"{directory}/{name}"
                blob = (await self._git(*git, "hash-object", "-w", "--stdin", stdin=content)).strip()
                entries.append(f"100644 blob {blob}\t{path}")
            await self._git(*git, "update-index", "-z", "--index-info", extra_env=env,
                            stdin="".join(f"{entry}\0" for entry in entries))
            tree = (await self._git(*git, "write-tree", extra_env=env)).strip()
            if tree == (await self._git(*git, "rev-parse", f"{remote}^{{tree}}")).strip():
                return None
            commit = (await self._git(*git, "commit-tree", tree, "-p", remote, "-m", message,
                                      extra_env=env)).strip()
            code, _, detail = await self._run(*_NO_CREDENTIAL_HELPER, *git, "push", "--porcelain", "origin",
                                              f"{commit}:refs/heads/{target}", auth=token)
            if code != 0:
                LOG.warning("git push failed with %s: %s", code, detail or "no output")
                if "rejected" in detail or "non-fast-forward" in detail or "fetch first" in detail:
                    raise ApiException.conflict(f"{target} changed while writing documents. Try again.")
                raise ApiException.bad_gateway(detail or "The documents could not be pushed")
            LOG.info("Documents in %s committed onto %s at %s", directory, target, commit[:7])
            return commit
        finally:
            await self._remove_document_directory(operation_id)

    async def remove_release_checkout(self, operation_id: UUID) -> None:
        directory = self._release_directory(operation_id)
        if directory.is_symlink():
            raise RuntimeError("Refusing a symlink in the release checkout path")
        marker = directory / ".mooi-release"
        if directory.exists() and marker.is_file() and not marker.is_symlink() and marker.read_text() == str(operation_id):
            await asyncio.to_thread(shutil.rmtree, directory)

    async def _remove_directory(self, directory: Path) -> None:
        def remove() -> None:
            # Keep ownership evidence until all potentially large deletions succeed.
            # A failed/terminated removal can then be retried at startup.
            marker = directory / ".mooi-session"
            _terminate_processes(directory)
            for child in directory.iterdir():
                if child == marker:
                    continue
                if child.is_dir() and not child.is_symlink():
                    shutil.rmtree(child)
                else:
                    child.unlink()
            marker.unlink(missing_ok=True)
            directory.rmdir()

        task = asyncio.create_task(asyncio.to_thread(remove))
        cancelled = False
        while not task.done():
            try:
                await asyncio.shield(task)
            except asyncio.CancelledError:
                cancelled = True
            except Exception:
                LOG.debug("Background operation encountered an exception", exc_info=True)
                break
        try:
            task.result()
        except Exception:
            LOG.exception("Workspace cleanup failed for %s", directory)
            raise
        if cancelled:
            raise asyncio.CancelledError

    async def remove_workspace(self, session_id: UUID) -> None:
        directory = self._session_directory(session_id)
        if not directory.exists():
            return
        marker = directory / ".mooi-session"
        if marker.is_symlink() or not marker.is_file() or marker.read_text() != str(session_id):
            raise RuntimeError("Refusing to remove an unowned session directory")
        await self._remove_directory(directory)
        LOG.info("Workspace %s removed", session_id)

    async def reconcile(self, *, preserve: set[UUID] | None = None) -> None:
        """Remove marked leftovers except sessions whose deployment cleanup failed at boot."""
        preserved = preserve or set()
        production = self.root / "production"
        if production.is_dir() and not production.is_symlink():
            for directory in production.iterdir():
                with_uuid = re.fullmatch(r"[0-9a-f-]{36}", directory.name)
                if with_uuid and directory.is_dir() and not directory.is_symlink():
                    await self.remove_release_checkout(UUID(directory.name))
        documents = self.root / "documents"
        if documents.is_dir() and not documents.is_symlink():
            for directory in documents.iterdir():
                if re.fullmatch(r"[0-9a-f-]{36}", directory.name) and directory.is_dir() and not directory.is_symlink():
                    await self._remove_document_directory(UUID(directory.name))
        sessions = self.root / "sessions"
        if sessions.is_symlink():
            raise RuntimeError("Managed sessions root cannot be a symlink")
        if not sessions.exists():
            return
        for directory in sessions.iterdir():
            try:
                session_id = UUID(directory.name)
            except ValueError:
                continue
            if (session_id in preserved or str(session_id) != directory.name
                    or directory.is_symlink() or not directory.is_dir()):
                continue
            marker = directory / ".mooi-session"
            if marker.is_file() and not marker.is_symlink() and marker.read_text() == str(session_id):
                await self.remove_workspace(session_id)

    # --- merge into the target branch ----------------------------------------------------------

    async def _fetch(self, workspace: Path, target: str, token: str) -> bool:
        """Fetches the target branch; false when the repository does not have it (yet), e.g. an
        empty repository whose first merge creates it."""
        await self.validate_branch(target)
        remote = f"refs/remotes/origin/{target}"
        code, _, detail = await self._run(*_NO_CREDENTIAL_HELPER, "-C", str(workspace), "fetch", "--no-tags",
                                          "origin", f"+refs/heads/{target}:{remote}", auth=token)
        if code == 0:
            return True
        if "couldn't find remote ref" in detail:
            await self._git_ok("-C", str(workspace), "update-ref", "-d", remote)
            return False
        LOG.warning("git fetch failed with %s: %s", code, detail or "no output")
        raise ApiException.bad_gateway(detail or "The git command failed")

    async def _snapshot(self, workspace: Path) -> str:
        """Commits the working tree, untracked files included, as a dangling child of HEAD.

        A throwaway index keeps the agent's own index and working tree untouched; copying the real
        one first reuses its stat cache so unchanged files are not re-hashed.
        """
        if await self._git_ok("-C", str(workspace), "rev-parse", "-q", "--verify", "MERGE_HEAD"):
            raise ApiException.conflict("A merge is in progress in the workspace; finish it in the chat first")
        git_dir = Path((await self._git("-C", str(workspace), "rev-parse", "--absolute-git-dir")).strip())
        index = git_dir / f"mooi-snapshot-{uuid4().hex}.index"
        born = await self._born(workspace)
        try:
            if (git_dir / "index").is_file():
                await asyncio.to_thread(shutil.copyfile, git_dir / "index", index)
            env = {"GIT_INDEX_FILE": str(index), **_identity_env(_SNAPSHOT_IDENTITY)}
            if not index.exists():
                await self._git("-C", str(workspace), "read-tree", *(("HEAD",) if born else ("--empty",)),
                                extra_env=env)
            await self._git("-C", str(workspace), "add", "-A", extra_env=env)
            tree = (await self._git("-C", str(workspace), "write-tree", extra_env=env)).strip()
            parent = ("-p", "HEAD") if born else ()
            return (await self._git("-C", str(workspace), "commit-tree", tree, *parent,
                                    "-m", "Mooi snapshot", extra_env=env)).strip()
        finally:
            index.unlink(missing_ok=True)

    async def revision(self, workspace: Path) -> str:
        """A stable identity for the complete tracked and untracked working tree."""
        snapshot = await self._snapshot(workspace)
        return (await self._git("-C", str(workspace), "rev-parse", f"{snapshot}^{{tree}}")).strip()

    # --- isolated task recovery -----------------------------------------------------------------

    def _recovery_directory(self, owner_id: UUID, project_id: UUID, item_id: UUID,
                            task_id: UUID, attempt: int) -> Path:
        """The opaque store key is the full ownership chain, never a repository-controlled path."""
        if not all(isinstance(value, UUID) for value in (owner_id, project_id, item_id, task_id)):
            raise ApiException.bad_request("Invalid recovery ownership scope")
        if type(attempt) is not int or attempt < 0:
            raise ApiException.bad_request("Recovery attempt must not be negative")
        root = self.root / "recovery"
        if root.is_symlink():
            raise RuntimeError("Managed recovery root cannot be a symlink")
        directory = root / str(owner_id) / str(project_id) / str(item_id) / str(task_id) / str(attempt)
        if directory.resolve(strict=False) != directory:
            raise RuntimeError("Recovery path is outside the managed root")
        return directory

    def _secure_recovery_directory(self, directory: Path) -> None:
        """Create every owner-scope component privately, rejecting links at each level."""
        root = self.root / "recovery"
        relative = directory.relative_to(root)
        current = root
        for part in (None, *relative.parts):
            if part is not None:
                current = current / part
            try:
                current.mkdir(mode=0o700)
            except FileExistsError:
                pass
            details = os.lstat(current)
            if stat.S_ISLNK(details.st_mode) or not stat.S_ISDIR(details.st_mode):
                raise RuntimeError("Managed recovery directory is unsafe")
            current.chmod(0o700)

    def _assert_managed_workspace(self, workspace: Path) -> Path:
        """Reject workspace symlinks and paths outside the sessions root before resolving them."""
        raw = workspace.absolute()
        sessions_root = (self.root / "sessions").resolve()
        if raw.is_symlink() or not raw.resolve(strict=True).is_relative_to(sessions_root):
            raise ApiException.bad_request("Recovery workspace is outside the managed sessions root")
        current = raw
        while current != self.root and current != current.parent:
            if current.is_symlink():
                raise ApiException.bad_request("Recovery refuses symlinks in workspace paths")
            current = current.parent
        return raw.resolve(strict=True)

    @staticmethod
    def _safe_recovery_path(path: str) -> bool:
        relative = Path(path)
        if (not path or "\0" in path or "\\" in path or relative.is_absolute() or ".." in relative.parts
                or ".git" in relative.parts or path.startswith("-")):
            return False
        excluded = {entry.strip("/") for entry in get_settings().changes_excluded_directories}
        return not any(part in excluded for part in relative.parts)

    @staticmethod
    def _sensitive_recovery_path(path: str) -> bool:
        name = Path(path).name.lower()
        return (name.startswith(".env") or "secret" in name or "credential" in name
                or name.endswith((".pem", ".key", ".p8", ".p12", ".pfx", ".jks", ".keystore"))
                or name in {"id_rsa", "id_ed25519"})

    @staticmethod
    def _contains_secret(data: bytes) -> bool:
        """Conservative high-confidence checks; suspicious content is refused, never copied."""
        sample = data[:2_000_000].decode("utf-8", "ignore")
        patterns = (
            r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",
            r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})\b",
            r"\bAKIA[0-9A-Z]{16}\b",
            r"(?i)(?:api[-_]?key|access[-_]?token|client[-_]?secret|password|private[-_]?key)\s*[:=]\s*['\"]?(?!\$\{|<|your_|change_me|example)[A-Za-z0-9/+=._-]{16,}",
            r"\b[A-Za-z][A-Za-z0-9+.-]*://[^/\s:@]+:[^/\s@]+@",
            r"(?im)^\s*(?:[A-Z0-9_]*(?:API_KEY|ACCESS_TOKEN|SECRET|PASSWORD|PRIVATE_KEY|CLIENT_SECRET))\s*[:=]\s*['\"]?(?!\$\{|<|your_|change_me|example)[A-Za-z0-9/+=._-]{12,}",
        )
        return any(re.search(pattern, sample) for pattern in patterns)

    @staticmethod
    def _safe_handoff_text(value: str, limit: int = 20_000) -> str:
        """Redact common credential forms from user/agent-authored handoff text."""
        text = _redact(value)
        text = re.sub(r"(?i)\b(Bearer\s+)[A-Za-z0-9._~+/=-]+", r"\1***", text)
        text = re.sub(r"(?i)\b(gh[pousr]_|github_pat_|sk-|xox[baprs]-)[A-Za-z0-9_-]{12,}", r"\1***", text)
        text = re.sub(r"(?im)^(\s*[A-Z0-9_]*(?:API_KEY|ACCESS_TOKEN|SECRET|PASSWORD|PRIVATE_KEY)\s*[:=]\s*)\S+",
                      r"\1***", text)
        return text[:limit]

    async def capture_recovery(
        self, workspace: Path, *, owner_id: UUID, project_id: UUID, item_id: UUID, task_id: UUID, attempt: int,
        branch: str, base_commit: str, previous_revision: str | None = None,
    ) -> RecoverySnapshot:
        """Atomically save only the eligible diff for one owner/project/item/task/attempt.

        v1 collapses local unpublished commits and eligible working-tree edits into one synthetic
        commit based on `base_commit`; this preserves the complete resulting change without copying
        arbitrary repository history, ignored files, Git config or credentials. The synthetic commit
        is bundled outside the checkout, addressed by SHA-256 and published only after verification.
        `previous_revision` is validated here; compare-and-set protection is enforced by the
        mic-mooi metadata API before a revision becomes the latest durable handoff. Invalid paths/symlinks or credential-like
        file content abort capture without publishing a partial snapshot. Errors intentionally omit
        repository paths and Git diagnostics. Bad requests mean unsafe scope/path/content or malformed
        revisions; conflicts mean a missing base or mismatched branch; gateway errors mean Git/storage
        failed. No commit is pushed.
        """
        if (not _RECOVERY_BRANCH.fullmatch(branch) or branch.startswith("-")
                or not _RECOVERY_REVISION.fullmatch(previous_revision or "")
                and previous_revision is not None):
            raise ApiException.bad_request("Invalid recovery branch or revision")
        try:
            await self.validate_branch(branch)
            workspace = self._assert_managed_workspace(workspace)
            if not workspace.is_dir():
                raise ApiException.conflict("Recovery workspace is unavailable")
            base = base_commit.strip()
            if not re.fullmatch(r"[0-9a-f]{40,64}", base):
                raise ApiException.bad_request("Invalid recovery base revision")
            if not await self._git_ok("-C", str(workspace), "cat-file", "-e", f"{base}^{{tree}}"):
                raise ApiException.conflict("Recovery base revision is unavailable")
            symbolic = (await self._git("-C", str(workspace), "symbolic-ref", "--quiet", "--short", "HEAD")).strip()
            if symbolic != branch:
                raise ApiException.conflict("Recovery workspace is on a different branch")

            tracked = await self._git("-C", str(workspace), "diff", "--no-renames", "--name-only", "-z", base, "--")
            candidates = {path for path in tracked.split("\0") if path}
            candidates.update(path for path in await self._untracked(workspace) if not path.endswith("/"))
            if any(not self._safe_recovery_path(path) for path in candidates):
                raise ApiException.bad_request("Recovery contains an unsafe file path")
            candidates = {path for path in candidates if not self._sensitive_recovery_path(path)}
            if len(candidates) > 500 or any(len(path) > 1024 for path in candidates):
                raise ApiException.bad_request("Recovery file manifest exceeds its safety limit")
            ignored = await self._ignored(workspace, list(candidates))
            candidates.difference_update(ignored)

            def inspect_candidates() -> None:
                root = workspace.resolve()
                for path in candidates:
                    target = workspace / path
                    current = workspace
                    for part in Path(path).parts:
                        current = current / part
                        try:
                            details = os.lstat(current)
                        except FileNotFoundError:
                            break
                        if stat.S_ISLNK(details.st_mode):
                            raise ApiException.bad_request("Recovery refuses symlinks")
                        if not current.resolve().is_relative_to(root):
                            raise ApiException.bad_request("Recovery contains an unsafe file path")
                    if target.exists() and target.is_file():
                        details = target.stat()
                        if details.st_nlink > 1:
                            raise ApiException.bad_request("Recovery refuses files linked outside the workspace")
                        if details.st_size > 20_000_000:
                            raise ApiException.bad_request("Recovery file exceeds the per-file safety limit")
                        if self._contains_secret(target.read_bytes()):
                            raise ApiException.bad_request("Recovery refused credential-like file content")

            await asyncio.to_thread(inspect_candidates)
            git_dir = Path((await self._git("-C", str(workspace), "rev-parse", "--absolute-git-dir")).strip())
            temporary_index = git_dir / f"mooi-recovery-{uuid4().hex}.index"
            bundle_tmp: Path | None = None
            try:
                env = {"GIT_INDEX_FILE": str(temporary_index), **_identity_env(_SNAPSHOT_IDENTITY)}
                await self._git("-C", str(workspace), "read-tree", base, extra_env=env)
                if candidates:
                    await self._git("--literal-pathspecs", "-C", str(workspace), "add", "-A",
                                     "--pathspec-from-file=-", "--pathspec-file-nul", extra_env=env,
                                     stdin="\0".join(sorted(candidates)) + "\0")
                tree = (await self._git("-C", str(workspace), "write-tree", extra_env=env)).strip()
                # Reuse the content revision when only the report (or local commit history) changed.
                # Random synthetic commits and capture timestamps otherwise hide stalled segments.
                if previous_revision:
                    lease = await self.acquire_recovery(owner_id, project_id, item_id, task_id, attempt,
                                                        previous_revision)
                    try:
                        manifest_path = lease.directory / "manifest.json"
                        bundle = lease.directory / "objects.bundle"
                        if manifest_path.is_symlink() or bundle.is_symlink():
                            raise ApiException.conflict("Recovery snapshot is unsafe")
                        manifest_bytes = manifest_path.read_bytes()
                        previous = json.loads(manifest_bytes)
                        digest = await asyncio.to_thread(_sha256_file, bundle)
                        if (hashlib.sha256(manifest_bytes + digest.encode()).hexdigest() != previous_revision
                                or previous.get("bundleSha256") != digest):
                            raise ApiException.conflict("Recovery snapshot is incomplete")
                        if (previous.get("ownerId") == str(owner_id) and previous.get("projectId") == str(project_id)
                                and previous.get("itemId") == str(item_id) and previous.get("taskId") == str(task_id)
                                and previous.get("attempt") == attempt and previous.get("branch") == branch
                                and previous.get("baseCommit") == base and previous.get("files") == sorted(candidates)):
                            old_commit = str(previous.get("syntheticCommit") or "")
                            if re.fullmatch(r"[0-9a-f]{40,64}", old_commit) and await self._git_ok(
                                    "-C", str(workspace), "cat-file", "-e", f"{old_commit}^{{tree}}"):
                                old_tree = (await self._git("-C", str(workspace), "rev-parse",
                                                            f"{old_commit}^{{tree}}")).strip()
                                if old_tree == tree:
                                    return RecoverySnapshot(task_id, attempt, branch, base, previous["headCommit"],
                                                            previous_revision, sorted(candidates))
                    finally:
                        await self.release_recovery(lease)
                base_is_commit = await self._git_ok("-C", str(workspace), "cat-file", "-e", f"{base}^{{commit}}")
                parent = ("-p", base) if base_is_commit else ()
                synthetic = (await self._git("-C", str(workspace), "commit-tree", tree, *parent,
                                             "-m", f"Mooi task recovery {uuid4().hex}", extra_env=env)).strip()
                head = (await self._git("-C", str(workspace), "rev-parse", "-q", "--verify", "HEAD")).strip()
                head = head or base

                directory = self._recovery_directory(owner_id, project_id, item_id, task_id, attempt)
                await asyncio.to_thread(self._secure_recovery_directory, directory)
                revisions = directory / "revisions"
                await asyncio.to_thread(self._secure_recovery_directory, revisions)

                staging = revisions / f".capture-{uuid4().hex}"
                staging.mkdir(mode=0o700)
                bundle_tmp = staging / "objects.bundle"
                bundle_args = ["-C", str(workspace), "bundle", "create", str(bundle_tmp), synthetic]
                if base_is_commit:
                    bundle_args.append(f"^{base}")
                await self._git(*bundle_args)
                await self._git("-C", str(workspace), "bundle", "verify", str(bundle_tmp))
                bundle_digest = await asyncio.to_thread(_sha256_file, bundle_tmp)
                manifest = {
                    "version": 1, "ownerId": str(owner_id), "projectId": str(project_id), "itemId": str(item_id),
                    "taskId": str(task_id), "attempt": attempt, "branch": branch, "baseCommit": base,
                    "headCommit": head, "syntheticCommit": synthetic, "files": sorted(candidates),
                    "bundleSha256": bundle_digest, "createdAt": int(time.time()),
                }
                manifest_bytes = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
                revision = hashlib.sha256(manifest_bytes + bundle_digest.encode()).hexdigest()
                (staging / "manifest.json").write_bytes(manifest_bytes)
                for child in staging.iterdir():
                    with child.open("rb") as stream:
                        os.fsync(stream.fileno())
                await asyncio.to_thread(_fsync_directory, staging)
                final = revisions / revision
                if final.exists():
                    await asyncio.to_thread(shutil.rmtree, staging)
                else:
                    os.replace(staging, final)
                    await asyncio.to_thread(_fsync_directory, revisions)
                return RecoverySnapshot(task_id, attempt, branch, base, head, revision, sorted(candidates))
            finally:
                temporary_index.unlink(missing_ok=True)
                if bundle_tmp is not None and bundle_tmp.parent.exists() and bundle_tmp.parent.name.startswith(".capture-"):
                    with contextlib.suppress(OSError):
                        shutil.rmtree(bundle_tmp.parent)
        except ApiException as error:
            if error.status_code == 502:
                raise ApiException.bad_gateway("Task recovery could not be saved safely") from None
            raise
        except (OSError, RuntimeError, ValueError) as error:
            LOG.warning("Task recovery capture failed (%s)", type(error).__name__)
            raise ApiException.bad_gateway("Task recovery could not be saved safely") from None

    async def acquire_recovery(
        self, owner_id: UUID, project_id: UUID, item_id: UUID, task_id: UUID, attempt: int, revision: str,
    ) -> RecoveryLease:
        """Acquire a process-shared lease before reading a bundle; cleanup skips leased revisions."""
        if not _RECOVERY_REVISION.fullmatch(revision):
            raise ApiException.bad_request("Invalid recovery revision")
        directory = self._recovery_directory(owner_id, project_id, item_id, task_id, attempt) / "revisions" / revision
        if directory.is_symlink() or not directory.is_dir() or directory.resolve() != directory:
            raise ApiException.not_found("Recovery evidence not found")
        lock_path = directory / ".consumer.lock"
        if lock_path.is_symlink():
            raise ApiException.conflict("Recovery evidence is unavailable")
        lock_file = await asyncio.to_thread(_open_recovery_lock, lock_path)
        await asyncio.to_thread(fcntl.flock, lock_file.fileno(), fcntl.LOCK_SH)
        return RecoveryLease(directory, lock_file)

    async def restore_recovery(
        self, workspace: Path, *, owner_id: UUID, project_id: UUID, item_id: UUID, task_id: UUID, attempt: int,
        revision: str, expected_branch: str, expected_base: str,
    ) -> str:
        """Restore one verified v1 snapshot to a clean new workspace without pushing.

        Requires the destination branch to be exactly at the recorded base, rejects unclean trees,
        unsafe manifests and incomplete bundles, and creates one local commit from the filtered
        handoff. It never resumes another provider's runtime or overwrites an existing workspace.
        A missing ownership/revision key is 404; unsafe/incomplete evidence or a dirty/wrong-base
        destination is 409; Git/storage failures are sanitized 502 errors. The caller must also require
        mic-mooi's `snapshotValid` and exact `attempt` before calling this helper.
        """
        lease = await self.acquire_recovery(owner_id, project_id, item_id, task_id, attempt, revision)
        try:
            manifest_path = lease.directory / "manifest.json"
            bundle = lease.directory / "objects.bundle"
            if manifest_path.is_symlink() or bundle.is_symlink() or not manifest_path.is_file() or not bundle.is_file():
                raise ApiException.conflict("Recovery snapshot is incomplete and cannot be restored")
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            if (manifest.get("version") != 1 or manifest.get("ownerId") != str(owner_id)
                    or manifest.get("projectId") != str(project_id) or manifest.get("itemId") != str(item_id)
                    or manifest.get("taskId") != str(task_id) or manifest.get("attempt") != attempt
                    or manifest.get("branch") != expected_branch or manifest.get("baseCommit") != expected_base):
                raise ApiException.not_found("Recovery evidence does not match this task attempt")
            digest = await asyncio.to_thread(_sha256_file, bundle)
            manifest_bytes = manifest_path.read_bytes()
            if (digest != manifest.get("bundleSha256")
                    or hashlib.sha256(manifest_bytes + digest.encode()).hexdigest() != revision):
                raise ApiException.conflict("Recovery snapshot is incomplete and cannot be restored")
            workspace = self._assert_managed_workspace(workspace)
            if not workspace.is_dir():
                raise ApiException.conflict("Recovery workspace is unavailable")
            branch = (await self._git("-C", str(workspace), "symbolic-ref", "--quiet", "--short", "HEAD")).strip()
            head = (await self._git("-C", str(workspace), "rev-parse", "-q", "--verify", "HEAD")).strip()
            if branch != expected_branch or (head and head != expected_base):
                raise ApiException.conflict("Recovery workspace is not at the recorded branch base")
            if (await self._git("-C", str(workspace), "status", "--porcelain", "--untracked-files=all")).strip():
                raise ApiException.conflict("Recovery workspace is not clean; refusing to overwrite changes")
            files = manifest.get("files")
            if not isinstance(files, list) or any(not isinstance(path, str) or not self._safe_recovery_path(path)
                                                   or self._sensitive_recovery_path(path) for path in files):
                raise ApiException.conflict("Recovery snapshot contains unsafe paths")
            for path in files:
                current = workspace
                for part in Path(path).parts:
                    current = current / part
                    if current.is_symlink():
                        raise ApiException.conflict("Recovery workspace contains a symlink at a recovery path")
            await self._git("-C", str(workspace), "bundle", "verify", str(bundle))
            synthetic = str(manifest["syntheticCommit"])
            await self._git("-C", str(workspace), "fetch", "--no-tags", str(bundle), synthetic)
            if not await self._git_ok("-C", str(workspace), "cat-file", "-e", f"{synthetic}^{{commit}}"):
                raise ApiException.conflict("Recovery snapshot is incomplete and cannot be restored")
            # A QA/context checkpoint can legitimately contain no repository changes. Keep the
            # empty synthetic commit so the new segment can resume and stall detection can count it.
            await self._git("-C", str(workspace), "cherry-pick", "--allow-empty", "--no-gpg-sign", synthetic)
            return (await self._git("-C", str(workspace), "rev-parse", "HEAD")).strip()
        except ApiException as error:
            if error.status_code == 502:
                raise ApiException.bad_gateway("Task recovery could not be restored safely") from None
            raise
        except (OSError, RuntimeError, ValueError, KeyError, TypeError) as error:
            LOG.warning("Task recovery restore failed (%s)", type(error).__name__)
            raise ApiException.bad_gateway("Task recovery could not be restored safely") from None
        finally:
            await self.release_recovery(lease)

    @staticmethod
    async def release_recovery(lease: RecoveryLease) -> None:
        """Release a previously acquired recovery lease."""
        lock_file = lease.lock_file
        await asyncio.to_thread(fcntl.flock, lock_file.fileno(), fcntl.LOCK_UN)
        await asyncio.to_thread(lock_file.close)

    async def cleanup_recovery(self, *, max_age_seconds: int = 7 * 24 * 60 * 60,
                               keep_per_attempt: int = 5) -> int:
        """Bound abandoned recovery storage; retain leased snapshots and always keep newest revisions."""
        root = self.root / "recovery"
        if root.is_symlink() or not root.is_dir():
            return 0
        now = time.time()
        removed = 0
        for current, dirs, _ in os.walk(root, topdown=True, followlinks=False):
            dirs[:] = [name for name in dirs if not (Path(current) / name).is_symlink()]
            if Path(current).name != "revisions":
                continue
            for name in list(dirs):
                incomplete = Path(current) / name
                if name.startswith(".capture-") and now - incomplete.stat().st_mtime > max_age_seconds:
                    with contextlib.suppress(OSError):
                        await asyncio.to_thread(shutil.rmtree, incomplete)
            revisions = []
            for name in dirs:
                candidate = Path(current) / name
                if not _RECOVERY_REVISION.fullmatch(name) or candidate.is_symlink():
                    continue
                with contextlib.suppress(OSError, ValueError):
                    manifest = json.loads((candidate / "manifest.json").read_text(encoding="utf-8"))
                    revisions.append((int(manifest.get("createdAt", 0)), candidate))
            revisions.sort(reverse=True)
            for index, (created, candidate) in enumerate(revisions):
                if index < keep_per_attempt and now - created <= max_age_seconds:
                    continue
                lock_path = candidate / ".consumer.lock"
                if lock_path.is_symlink():
                    continue
                lock_file = await asyncio.to_thread(_open_recovery_lock, lock_path)
                try:
                    try:
                        await asyncio.to_thread(fcntl.flock, lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                    except BlockingIOError:
                        continue
                    await asyncio.to_thread(shutil.rmtree, candidate)
                    removed += 1
                finally:
                    with contextlib.suppress(OSError):
                        await asyncio.to_thread(fcntl.flock, lock_file.fileno(), fcntl.LOCK_UN)
                    await asyncio.to_thread(lock_file.close)
        return removed

    async def delete_recovery_attempt(
        self, owner_id: UUID, project_id: UUID, item_id: UUID, task_id: UUID, attempt: int,
    ) -> bool:
        """Retire one finalized task attempt, leaving any revision held by an active consumer intact."""
        directory = self._recovery_directory(owner_id, project_id, item_id, task_id, attempt)
        revisions = directory / "revisions"
        if revisions.is_symlink() or not revisions.is_dir():
            return True
        complete = True
        for candidate in revisions.iterdir():
            if not _RECOVERY_REVISION.fullmatch(candidate.name) or candidate.is_symlink() or not candidate.is_dir():
                continue
            lock_path = candidate / ".consumer.lock"
            if lock_path.is_symlink():
                complete = False
                continue
            lock_file = await asyncio.to_thread(_open_recovery_lock, lock_path)
            try:
                try:
                    await asyncio.to_thread(fcntl.flock, lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    complete = False
                    continue
                await asyncio.to_thread(shutil.rmtree, candidate)
            finally:
                with contextlib.suppress(OSError):
                    await asyncio.to_thread(fcntl.flock, lock_file.fileno(), fcntl.LOCK_UN)
                await asyncio.to_thread(lock_file.close)
        if complete:
            with contextlib.suppress(OSError):
                await asyncio.to_thread(revisions.rmdir)
            with contextlib.suppress(OSError):
                await asyncio.to_thread(directory.rmdir)
        return complete

    async def set_identity(self, workspace: Path, identity: GitIdentity) -> None:
        """Makes commits the agent writes in this clone carry the player's identity."""
        await self._git("-C", str(workspace), "config", "user.name", identity.name)
        await self._git("-C", str(workspace), "config", "user.email", identity.email)

    async def preview_merge(self, workspace: Path, target: str, token: str) -> MergePreview:
        """Fetches the target and merges the working tree into it in memory (`git merge-tree`),
        never touching the checkout, so a conflict leaves the workspace exactly as it was."""
        exists = await self._fetch(workspace, target, token)
        remote = f"refs/remotes/origin/{target}"
        snapshot = await self._snapshot(workspace)
        if not exists:
            # The target does not exist yet: merging creates it holding the working tree.
            tree = (await self._git("-C", str(workspace), "rev-parse", f"{snapshot}^{{tree}}")).strip()
            return MergePreview(target=target, tree=tree, conflicts=[],
                                up_to_date=tree == await self._empty_tree(workspace), merge_base=None)
        code, stdout, detail = await self._run(
            "-C", str(workspace), "merge-tree", "--write-tree", "--name-only", "--no-messages", "-z",
            snapshot, remote,
        )
        if code not in (0, 1):
            LOG.warning("git merge-tree failed with %s: %s", code, detail or "no output")
            raise ApiException.bad_gateway(detail or "The branches could not be compared")
        fields = stdout.split("\0")
        tree = fields[0].strip()
        conflicts = list(dict.fromkeys(field for field in fields[1:] if field)) if code == 1 else []
        target_tree = (await self._git("-C", str(workspace), "rev-parse", f"{remote}^{{tree}}")).strip()
        base_code, base, _ = await self._run("-C", str(workspace), "merge-base", "HEAD", remote)
        return MergePreview(
            target=target,
            tree=tree,
            conflicts=conflicts,
            up_to_date=not conflicts and tree == target_tree,
            merge_base=(base.strip() or None) if base_code == 0 else None,
        )

    async def merge(
        self, workspace: Path, target: str, message: str, identity: GitIdentity, token: str,
    ) -> tuple[MergePreview, str | None]:
        """Squashes the working tree onto the target as one commit and pushes it.

        The push is a plain fast-forward of the fetched target: if someone pushed in between it is
        rejected, never forced. On success the session branch is moved onto the published commit,
        so the workspace keeps working from what the target now holds.
        """
        preview = await self.preview_merge(workspace, target, token)
        if preview.conflicts or preview.up_to_date:
            return preview, None
        env = _identity_env(identity)
        remote = f"refs/remotes/origin/{target}"
        # A target the preview did not find is created by a root commit.
        parent = ("-p", remote) if await self._git_ok("-C", str(workspace), "show-ref", "--verify", "--quiet",
                                                      remote) else ()
        commit = (await self._git("-C", str(workspace), "commit-tree", preview.tree,
                                  *parent, "-m", message, extra_env=env)).strip()
        code, _, detail = await self._run(*_NO_CREDENTIAL_HELPER, "-C", str(workspace), "push", "--porcelain",
                                          "origin", f"{commit}:refs/heads/{target}", auth=token)
        if code != 0:
            LOG.warning("git push failed with %s: %s", code, detail or "no output")
            if "rejected" in detail or "non-fast-forward" in detail or "fetch first" in detail:
                raise ApiException.conflict(f"{target} changed while merging. Try again.")
            if "403" in detail or "denied" in detail.lower():
                raise ApiException.forbidden(
                    f"GitHub refused the push to {target}. Grant the GitHub App write access to "
                    "repository contents and check the branch protection rules."
                )
            raise ApiException.bad_gateway(detail or "The merge could not be pushed")
        await self._git("-C", str(workspace), "reset", "--hard", commit)
        await self._git("-C", str(workspace), "update-ref", f"refs/remotes/origin/{target}", commit)
        LOG.info("Workspace %s squashed into %s at %s", workspace, target, commit[:7])
        return preview, commit

    async def delete_remote_branch(self, workspace: Path, branch: str, token: str) -> None:
        """Deletes a session branch after its changes have been published to the target."""
        code, _, detail = await self._run(*_NO_CREDENTIAL_HELPER, "-C", str(workspace), "push", "--porcelain",
                                          "origin", f":refs/heads/{branch}", auth=token)
        if code != 0:
            LOG.warning("Could not delete merged branch %s: %s", branch, detail or "no output")
            raise ApiException.bad_gateway(detail or "The merge succeeded, but the session branch could not be deleted")

    # --- inspection ------------------------------------------------------------------------------

    async def inspect(self, workspace: Path) -> WorkspaceInspection:
        """Best-effort snapshot of a clone; every probe tolerates failure so one broken clone never
        hides the rest of an overview."""
        if not workspace.is_dir():
            return WorkspaceInspection(head_commit=None, head_branch=None, dirty_files=0, size_bytes=0)
        head_code, head, _ = await self._run("-C", str(workspace), "rev-parse", "HEAD")
        branch_code, branch, _ = await self._run("-C", str(workspace), "rev-parse", "--abbrev-ref", "HEAD")
        status_code, status, _ = await self._run(
            "-C", str(workspace), "status", "--porcelain", "-z", "--untracked-files=all"
        )
        dirty = 0
        if status_code == 0:
            fields = [field for field in status.split("\0") if field]
            i = 0
            while i < len(fields):
                # A rename/copy entry carries its source path as the next field.
                i += 2 if fields[i][:1] in ("R", "C") else 1
                dirty += 1
        size = await asyncio.to_thread(_directory_size, workspace.parent)
        return WorkspaceInspection(
            head_commit=(head.strip() or None) if head_code == 0 else None,
            head_branch=(branch.strip() or None) if branch_code == 0 else None,
            dirty_files=dirty,
            size_bytes=size,
        )

    # --- diffs ----------------------------------------------------------------------------------

    async def _numstat(self, workspace: Path, base_commit: str, env: dict[str, str]) -> dict[str, tuple[int, int]]:
        """Maps each changed path to `(added, removed)`, using `-z` so a renamed path's two names
        arrive as separate NUL-terminated fields instead of an ambiguous `old => new` string."""
        raw = await self._git("-C", str(workspace), "diff", "--numstat", "-M", "-z", base_commit, "--",
                              *_visible_pathspec(), extra_env=env)
        fields = raw.split("\0")
        counts: dict[str, tuple[int, int]] = {}
        i = 0
        while i < len(fields) and fields[i]:
            added_raw, removed_raw, path = fields[i].split("\t", 2)
            added = 0 if added_raw == "-" else int(added_raw)
            removed = 0 if removed_raw == "-" else int(removed_raw)
            i += 1
            if path == "":
                # Rename/copy: the old and new paths follow as their own NUL-terminated fields.
                i += 1  # old path, unused
                path = fields[i]
                i += 1
            counts[path] = (added, removed)
        return counts

    async def _name_status(self, workspace: Path, base_commit: str, env: dict[str, str]) -> list[tuple[str, str]]:
        """Lists `(status, final_path)` pairs; `status` is the first letter of git's status code."""
        raw = await self._git("-C", str(workspace), "diff", "--name-status", "-M", "-z", base_commit, "--",
                              *_visible_pathspec(), extra_env=env)
        fields = [field for field in raw.split("\0") if field != ""]
        entries: list[tuple[str, str]] = []
        i = 0
        while i < len(fields):
            status = fields[i]
            i += 1
            if status[0] in ("R", "C"):
                i += 1  # old path, unused
                entries.append((status, fields[i]))
            else:
                entries.append((status, fields[i]))
            i += 1
        return entries

    async def _untracked(self, workspace: Path, *pathspec: str) -> list[str]:
        """Untracked files the working tree's own .gitignore files (edited or not, at any depth) and
        the excluded directories leave visible; an excluded directory is never even walked."""
        excludes = [f"--exclude={directory.strip('/')}/" for directory in get_settings().changes_excluded_directories]
        raw = await self._git("--literal-pathspecs", "-C", str(workspace), "ls-files", "--others",
                              "--exclude-standard", *excludes, "-z", *(("--", *pathspec) if pathspec else ()))
        return [path for path in raw.split("\0") if path]

    async def _ignored(self, workspace: Path, paths: list[str]) -> set[str]:
        """The given paths that the working tree's .gitignore files match, tracked ones included."""
        if not paths:
            return set()
        code, stdout, detail = await self._run("-C", str(workspace), "check-ignore", "--no-index", "-z", "--stdin",
                                               stdin="\0".join(paths) + "\0")
        if code not in (0, 1):  # 1: nothing ignored
            LOG.warning("git check-ignore failed with %s: %s", code, detail or "no output")
            return set()
        return {path for path in stdout.split("\0") if path}

    @contextlib.asynccontextmanager
    async def _intent_index(self, workspace: Path, untracked: list[str]) -> AsyncIterator[dict[str, str]]:
        """A throwaway copy of the index where untracked files are only intended to be added, so one
        diff against the base lists them (renames included) without hashing them nor touching the
        agent's own index."""
        git_dir = Path((await self._git("-C", str(workspace), "rev-parse", "--absolute-git-dir")).strip())
        index = git_dir / f"mooi-changes-{uuid4().hex}.index"
        env = {"GIT_INDEX_FILE": str(index)}
        try:
            if (git_dir / "index").is_file():
                await asyncio.to_thread(shutil.copyfile, git_dir / "index", index)
            else:
                await self._git("-C", str(workspace), "read-tree",
                                *(("HEAD",) if await self._born(workspace) else ("--empty",)), extra_env=env)
            if untracked:
                await self._git("--literal-pathspecs", "-C", str(workspace), "add", "--intent-to-add",
                                "--pathspec-from-file=-", "--pathspec-file-nul", extra_env=env,
                                stdin="\0".join(untracked) + "\0")
            yield env
        finally:
            index.unlink(missing_ok=True)

    async def changes(self, workspace: Path, base_commit: str) -> ChangesSummary:
        """Read tracked and untracked changes without touching the workspace index. Anything the
        working tree's .gitignore files match, or inside an excluded directory, is never listed."""
        untracked = await self._untracked(workspace)
        # A nested repository is listed as its directory and has no lines of its own to count.
        repositories = [path for path in untracked if path.endswith("/")]
        async with self._intent_index(workspace, [path for path in untracked if not path.endswith("/")]) as env:
            counts = await self._numstat(workspace, base_commit, env)
            statuses = await self._name_status(workspace, base_commit, env) + [("A", path) for path in repositories]
        ignored = await self._ignored(workspace, [path for _, path in statuses])
        files = [
            ChangedFile(
                path=path,
                change=_CHANGE_KINDS.get(status[0], "modified"),
                added=counts.get(path, (0, 0))[0],
                removed=counts.get(path, (0, 0))[1],
            )
            for status, path in statuses
            if path not in ignored
        ]
        return ChangesSummary(
            files=files,
            added=sum(file.added for file in files),
            removed=sum(file.removed for file in files),
        )

    async def file_diff(self, workspace: Path, base_commit: str, path: str) -> FileDiff:
        """Returns the unified diff of one file, rejecting a path that escapes the workspace. Binary
        files and files over the preview limit come back without a diff; a long diff is cut."""
        relative = Path(path)
        if relative.is_absolute() or ".." in relative.parts:
            raise ApiException.bad_request("Invalid file path")
        target = workspace / relative
        if not target.resolve().is_relative_to(workspace.resolve()):
            raise ApiException.bad_request("Invalid file path")
        limit = get_settings().changes_preview_max_bytes
        with contextlib.suppress(OSError):
            if target.is_dir():  # a nested repository
                return FileDiff(diff="", preview="binary")
            if target.is_file() and target.stat().st_size > limit:
                return FileDiff(diff="", preview="too_large")
        if path in await self._untracked(workspace, path):
            code, diff, _ = await self._run("diff", "--no-index", "--", "/dev/null", path, cwd=workspace)
            if code not in (0, 1):
                raise ApiException.bad_gateway("Could not read untracked file diff")
        else:
            diff = await self._git("--literal-pathspecs", "-C", str(workspace), "diff", base_commit, "--",
                                   relative.as_posix())
        return _preview(diff, limit)

    # --- file browser and editor ------------------------------------------------------------------

    @staticmethod
    def _file_target(workspace: Path, path: str) -> Path:
        """Resolves a workspace-relative file path, rejecting anything that escapes the workspace
        (symlinks included) or reaches into Git's own directory."""
        relative = Path(path)
        if not path or "\0" in path or relative.is_absolute() or ".." in relative.parts or ".git" in relative.parts:
            raise ApiException.bad_request("Invalid file path")
        root = workspace.resolve()
        target = (workspace / relative).resolve()
        if not target.is_relative_to(root) or ".git" in target.relative_to(root).parts:
            raise ApiException.bad_request("Invalid file path")
        return target

    async def list_files(self, workspace: Path) -> FileListing:
        """Every file of the working tree the .gitignore files and the excluded directories leave
        visible: tracked and untracked, deleted ones and nested repositories left out."""
        settings = get_settings()
        excluded = [f"/{directory.strip('/')}/" for directory in settings.changes_excluded_directories]
        raw = await self._git("--literal-pathspecs", "-C", str(workspace), "ls-files", "--cached", "--others",
                              "--exclude-standard", "-z")
        candidates = sorted({path for path in raw.split("\0")
                             if path and not path.endswith("/") and not any(mark in f"/{path}" for mark in excluded)})
        root = workspace.resolve()

        def existing() -> list[str]:
            return [path for path in candidates if (root / path).is_file()]

        paths = await asyncio.to_thread(existing)
        limit = settings.files_max_listed
        return FileListing(paths=paths[:limit], truncated=len(paths) > limit)

    async def read_file(self, workspace: Path, path: str) -> FileContent:
        target = self._file_target(workspace, path)
        limit = get_settings().changes_preview_max_bytes

        def load() -> FileContent:
            try:
                if not target.is_file():
                    raise ApiException.not_found("File not found")
                size = target.stat().st_size
                if size > limit:
                    return FileContent(preview="too_large", size=size)
                data = target.read_bytes()
            except OSError:
                raise ApiException.not_found("File not found") from None
            if b"\0" in data[:8192]:
                return FileContent(preview="binary", size=size)
            try:
                text = data.decode("utf-8")
            except UnicodeDecodeError:
                return FileContent(preview="binary", size=size)
            return FileContent(preview="text", content=text, revision=hashlib.sha256(data).hexdigest(), size=size)

        return await asyncio.to_thread(load)

    async def write_file(self, workspace: Path, path: str, content: str, base_revision: str) -> FileContent:
        """Replaces an existing text file atomically, but only when it still has the revision the
        editor read, so an edit by the agent in the meantime is reported, never overwritten."""
        target = self._file_target(workspace, path)
        data = content.encode("utf-8")
        if len(data) > get_settings().changes_preview_max_bytes:
            raise ApiException.bad_request("This file is too large to save from the editor")

        def store() -> FileContent:
            if not target.is_file():
                raise ApiException.not_found("File not found")
            if hashlib.sha256(target.read_bytes()).hexdigest() != base_revision:
                raise ApiException.conflict("This file changed since you opened it. Reload it to keep both edits")
            temporary = target.with_name(f".{target.name}.{uuid4().hex}.mooi-tmp")
            try:
                temporary.write_bytes(data)
                shutil.copymode(target, temporary)
                os.replace(temporary, target)
            except OSError:
                temporary.unlink(missing_ok=True)
                raise ApiException.bad_gateway("Could not save the file") from None
            return FileContent(preview="text", content=content, revision=hashlib.sha256(data).hexdigest(),
                               size=len(data))

        return await asyncio.to_thread(store)


def _visible_pathspec() -> list[str]:
    """Pathspec of the whole tree minus the excluded directories, at any depth."""
    return [".", *(f":(exclude,glob)**/{directory.strip('/')}/**"
                   for directory in get_settings().changes_excluded_directories)]


def _preview(diff: str, limit: int) -> FileDiff:
    """Classifies a one-file diff: binary when git found no text to compare, and cut at the last
    whole line within `limit` bytes when longer."""
    if "\n@@ " not in diff and _BINARY_DIFF.search(diff):
        return FileDiff(diff="", preview="binary")
    encoded = diff.encode("utf-8", "replace")
    if len(encoded) <= limit:
        return FileDiff(diff=diff, preview="text")
    cut = encoded[:limit]
    return FileDiff(diff=cut[: cut.rfind(b"\n") + 1].decode("utf-8", "ignore"), preview="text", truncated=True)


@lru_cache
def get_workspaces() -> Workspaces:
    return Workspaces()


async def create_workspace(
    project: Project, session_id: UUID, branch: str, token: str, *, base_commit: str | None = None,
) -> SessionWorkspace:
    return await get_workspaces().create_workspace(project, session_id, branch, token, base_commit=base_commit)


async def remove_workspace(session_id: UUID) -> None:
    await get_workspaces().remove_workspace(session_id)


async def inspect(workspace: Path) -> WorkspaceInspection:
    return await get_workspaces().inspect(workspace)


async def changes(workspace: Path, base_commit: str) -> ChangesSummary:
    return await get_workspaces().changes(workspace, base_commit)


async def revision(workspace: Path) -> str:
    return await get_workspaces().revision(workspace)


async def capture_recovery(
    workspace: Path, *, owner_id: UUID, project_id: UUID, item_id: UUID, task_id: UUID, attempt: int,
    branch: str, base_commit: str, previous_revision: str | None = None,
) -> RecoverySnapshot:
    """Persist a filtered v1 recovery bundle under the full ownership scope; never pushes it."""
    return await get_workspaces().capture_recovery(
        workspace, owner_id=owner_id, project_id=project_id, item_id=item_id, task_id=task_id, attempt=attempt,
        branch=branch, base_commit=base_commit, previous_revision=previous_revision,
    )


async def restore_recovery(
    workspace: Path, *, owner_id: UUID, project_id: UUID, item_id: UUID, task_id: UUID, attempt: int,
    revision: str, expected_branch: str, expected_base: str,
) -> str:
    """Restore a verified v1 bundle into a clean, matching session workspace without publishing it."""
    return await get_workspaces().restore_recovery(
        workspace, owner_id=owner_id, project_id=project_id, item_id=item_id, task_id=task_id, attempt=attempt,
        revision=revision, expected_branch=expected_branch, expected_base=expected_base,
    )


async def cleanup_recovery() -> int:
    """Apply bounded recovery retention, skipping every bundle held by an active consumer."""
    return await get_workspaces().cleanup_recovery()


async def delete_recovery_attempt(
    owner_id: UUID, project_id: UUID, item_id: UUID, task_id: UUID, attempt: int,
) -> bool:
    """Delete a completed attempt's bundles unless a recovery consumer currently holds one."""
    return await get_workspaces().delete_recovery_attempt(owner_id, project_id, item_id, task_id, attempt)


async def file_diff(workspace: Path, base_commit: str, path: str) -> FileDiff:
    return await get_workspaces().file_diff(workspace, base_commit, path)


async def list_files(workspace: Path) -> FileListing:
    return await get_workspaces().list_files(workspace)


async def read_file(workspace: Path, path: str) -> FileContent:
    return await get_workspaces().read_file(workspace, path)


async def write_file(workspace: Path, path: str, content: str, base_revision: str) -> FileContent:
    return await get_workspaces().write_file(workspace, path, content, base_revision)


async def preview_merge(workspace: Path, target: str, token: str) -> MergePreview:
    return await get_workspaces().preview_merge(workspace, target, token)


async def merge(
    workspace: Path, target: str, message: str, identity: GitIdentity, token: str,
) -> tuple[MergePreview, str | None]:
    return await get_workspaces().merge(workspace, target, message, identity, token)


async def delete_remote_branch(workspace: Path, branch: str, token: str) -> None:
    await get_workspaces().delete_remote_branch(workspace, branch, token)


async def set_identity(workspace: Path, identity: GitIdentity) -> None:
    await get_workspaces().set_identity(workspace, identity)


async def commit_documents(
    project_full_name: str, target: str, directory: str, files: dict[str, str], message: str,
    identity: GitIdentity, token: str, *, adopt_suffix: str | None = None,
) -> str | None:
    return await get_workspaces().commit_documents(project_full_name, target, directory, files, message, identity,
                                                   token, adopt_suffix=adopt_suffix)
