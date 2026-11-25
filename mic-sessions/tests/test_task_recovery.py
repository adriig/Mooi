import os
import subprocess
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from uuid import uuid4

from mic_sessions.shared.web import ApiException
from mic_sessions.shared.workspaces import Workspaces

BRANCH = "mooi/task-recovery"
GIT_ENV = {**os.environ, "GIT_AUTHOR_NAME": "Test", "GIT_AUTHOR_EMAIL": "test@localhost",
           "GIT_COMMITTER_NAME": "Test", "GIT_COMMITTER_EMAIL": "test@localhost",
           "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1"}


def git(cwd: Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(cwd), *args], capture_output=True, text=True, check=True,
                          env=GIT_ENV).stdout.strip()


class FailingBundleWorkspaces(Workspaces):
    """Fails one `git bundle` subcommand after the temporary reference exists."""

    def __init__(self, root: Path, failing: str) -> None:
        super().__init__(root)
        self.failing = failing
        self.refs_during_bundle: list[str] = []

    async def _git(self, *args, **kwargs):
        if "bundle" in args and args[args.index("bundle") + 1] == self.failing:
            workspace = Path(args[args.index("-C") + 1])
            self.refs_during_bundle = recovery_refs(workspace)
            raise ApiException.bad_gateway("fatal: simulated bundle failure")
        return await super()._git(*args, **kwargs)


def recovery_refs(workspace: Path) -> list[str]:
    return [line for line in git(workspace, "for-each-ref", "--format=%(refname)", "refs/mooi/recovery/").splitlines()
            if line]


class TaskRecoveryTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self._directory = TemporaryDirectory()
        self.root = Path(self._directory.name).resolve()
        self.origin = self.root / "origin"
        self.origin.mkdir()
        git(self.origin, "init", "-q", "-b", "main")
        (self.origin / "app.py").write_text("print('base')\n")
        (self.origin / "README.md").write_text("base\n")
        git(self.origin, "add", "-A")
        git(self.origin, "commit", "-q", "-m", "base")
        self.base = git(self.origin, "rev-parse", "HEAD")
        self.workspace = self.new_workspace()
        self.scope = {"owner_id": uuid4(), "project_id": uuid4(), "item_id": uuid4(), "task_id": uuid4(),
                      "attempt": 1}
        self.workspaces = Workspaces(self.root)

    def tearDown(self):
        self._directory.cleanup()

    def new_workspace(self) -> Path:
        workspace = self.root / "sessions" / str(uuid4()) / "repository"
        workspace.parent.mkdir(parents=True)
        # --no-local copies only reachable objects, so the restore really depends on the bundle.
        subprocess.run(["git", "clone", "-q", "--no-local", str(self.origin), str(workspace)], check=True,
                       env=GIT_ENV)
        git(workspace, "checkout", "-q", "-b", BRANCH, self.base)
        git(workspace, "config", "user.name", "Test")
        git(workspace, "config", "user.email", "test@localhost")
        return workspace

    async def capture(self, workspaces: Workspaces | None = None):
        return await (workspaces or self.workspaces).capture_recovery(
            self.workspace, branch=BRANCH, base_commit=self.base, **self.scope)

    def revision_directory(self, revision: str) -> Path:
        scope = self.scope
        return (self.root / "recovery" / str(scope["owner_id"]) / str(scope["project_id"]) / str(scope["item_id"])
                / str(scope["task_id"]) / str(scope["attempt"]) / "revisions" / revision)

    async def restore(self, revision: str) -> Path:
        target = self.new_workspace()
        await self.workspaces.restore_recovery(target, revision=revision, expected_branch=BRANCH,
                                               expected_base=self.base, **self.scope)
        return target

    async def test_unpublished_change_is_bundled_verified_and_restored(self):
        (self.workspace / "app.py").write_text("print('changed')\n")
        (self.workspace / "notes").mkdir()
        (self.workspace / "notes" / "todo.md").write_text("next step\n")
        (self.workspace / "README.md").unlink()

        snapshot = await self.capture()

        self.assertEqual(snapshot.files, ["README.md", "app.py", "notes/todo.md"])
        bundle = self.revision_directory(snapshot.snapshot_revision) / "objects.bundle"
        self.assertTrue(bundle.is_file())
        git(self.workspace, "bundle", "verify", str(bundle))
        self.assertEqual(recovery_refs(self.workspace), [])

        restored = await self.restore(snapshot.snapshot_revision)
        self.assertEqual(git(restored, "symbolic-ref", "--short", "HEAD"), BRANCH)
        self.assertEqual(git(restored, "rev-parse", "HEAD~1"), self.base)
        self.assertEqual((restored / "app.py").read_text(), "print('changed')\n")
        self.assertEqual((restored / "notes" / "todo.md").read_text(), "next step\n")
        self.assertFalse((restored / "README.md").exists())
        self.assertEqual(git(restored, "status", "--porcelain", "--untracked-files=all"), "")
        self.assertEqual(recovery_refs(restored), [])

    async def test_local_commits_are_collapsed_into_the_snapshot(self):
        (self.workspace / "app.py").write_text("print('committed')\n")
        git(self.workspace, "commit", "-q", "-am", "local work")

        snapshot = await self.capture()

        restored = await self.restore(snapshot.snapshot_revision)
        self.assertEqual((restored / "app.py").read_text(), "print('committed')\n")
        self.assertEqual(recovery_refs(self.workspace), [])

    async def test_capture_without_changes_stays_valid_and_restorable(self):
        snapshot = await self.capture()

        self.assertEqual(snapshot.files, [])
        bundle = self.revision_directory(snapshot.snapshot_revision) / "objects.bundle"
        git(self.workspace, "bundle", "verify", str(bundle))
        self.assertEqual(recovery_refs(self.workspace), [])

        restored = await self.restore(snapshot.snapshot_revision)
        self.assertEqual(git(restored, "rev-parse", "HEAD~1"), self.base)
        self.assertEqual(git(restored, "rev-parse", "HEAD^{tree}"),
                         git(restored, "rev-parse", f"{self.base}^{{tree}}"))

    async def test_unchanged_content_reuses_the_previous_revision(self):
        (self.workspace / "app.py").write_text("print('changed')\n")
        first = await self.capture()

        second = await self.workspaces.capture_recovery(
            self.workspace, branch=BRANCH, base_commit=self.base, previous_revision=first.snapshot_revision,
            **self.scope)

        self.assertEqual(second.snapshot_revision, first.snapshot_revision)
        self.assertEqual(recovery_refs(self.workspace), [])

    async def test_temporary_reference_is_removed_when_bundle_creation_fails(self):
        await self.assert_failure_cleans_up("create")

    async def test_temporary_reference_is_removed_when_bundle_verification_fails(self):
        await self.assert_failure_cleans_up("verify")

    async def assert_failure_cleans_up(self, failing: str):
        (self.workspace / "app.py").write_text("print('changed')\n")
        workspaces = FailingBundleWorkspaces(self.root, failing)

        with self.assertRaises(ApiException) as raised:
            await self.capture(workspaces)

        self.assertEqual(raised.exception.status_code, 502)
        self.assertNotIn("simulated", str(raised.exception.message))
        self.assertEqual(len(workspaces.refs_during_bundle), 1)
        self.assertTrue(workspaces.refs_during_bundle[0].startswith("refs/mooi/recovery/"))
        self.assertEqual(recovery_refs(self.workspace), [])
        revisions = self.revision_directory("x").parent
        self.assertEqual(list(revisions.iterdir()) if revisions.exists() else [], [])

    async def test_unsafe_path_is_refused(self):
        (self.workspace / "-option.txt").write_text("unsafe\n")
        await self.assert_refused("unsafe file path")

    async def test_symlink_is_refused(self):
        (self.workspace / "link.txt").symlink_to(self.root / "origin" / "README.md")
        await self.assert_refused("symlinks")

    async def test_credential_content_is_refused(self):
        (self.workspace / "config.py").write_text("TOKEN = 'ghp_" + "a" * 36 + "'\n")
        await self.assert_refused("credential-like")

    async def test_sensitive_files_are_left_out(self):
        (self.workspace / ".env").write_text("harmless\n")
        (self.workspace / "app.py").write_text("print('changed')\n")

        snapshot = await self.capture()

        self.assertEqual(snapshot.files, ["app.py"])
        restored = await self.restore(snapshot.snapshot_revision)
        self.assertFalse((restored / ".env").exists())

    async def assert_refused(self, reason: str):
        with self.assertRaises(ApiException) as raised:
            await self.capture()
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn(reason, str(raised.exception.message))
        self.assertEqual(recovery_refs(self.workspace), [])
        self.assertFalse((self.root / "recovery").exists())


if __name__ == "__main__":
    unittest.main()
