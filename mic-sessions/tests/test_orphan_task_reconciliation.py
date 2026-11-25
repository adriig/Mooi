import asyncio
import unittest
from contextlib import ExitStack
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch
from uuid import uuid4

from mic_sessions.features import factory
from mic_sessions.shared.auth import Caller
from mic_sessions.shared.web import ApiException


class OrphanTaskReconciliationTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.caller = Caller(uuid4(), uuid4(), "user", "test", 9_999_999_999)
        self.task = {"id": str(uuid4()), "key": "resume", "title": "Resume", "status": "attention",
                     "attempts": 4, "attemptsBase": 3, "segments": 2, "stalledSegments": 1,
                     "lastSnapshotRevision": "a" * 64, "contextHandoff": "continuation",
                     "sessionId": str(uuid4()), "qaOf": None, "dependsOn": [], "autoMerge": False,
                     "assignment": {"provider": "claude", "connectionId": str(uuid4()), "model": "sonnet"},
                     "alternatives": [], "recoveryDisposition": {"state": "recoverable"}}
        self.item = {"id": str(uuid4()), "projectId": str(uuid4()), "kind": "feature", "title": "Feature",
                     "status": "running", "tasks": [self.task], "changeRequests": []}
        self.recovery = {"taskId": self.task["id"], "attempt": 4, "segment": 2, "snapshotValid": True,
                         "branch": "factory/task", "baseCommit": "b" * 40, "snapshotRevision": "c" * 64,
                         "summary": "saved handoff", "nextStep": "continue", "files": ["src/app.py"]}
        self.registry = SimpleNamespace(get=Mock(return_value=None), all=Mock(return_value=[]),
                                        count=Mock(return_value=0), owned=Mock(return_value=0))
        self.mock(factory, "get_registry", Mock(return_value=self.registry))
        self.mock(factory, "_caller", AsyncMock(return_value=self.caller))
        self.mock(factory, "_write_feature_document", AsyncMock())
        self.mock(factory, "_advance_changes", AsyncMock(return_value=False))
        self.mock(factory, "_has_room", AsyncMock(return_value=True))
        self.mock(factory, "_launch", AsyncMock())
        self.mock(factory.mooi, "fetch_task_recovery", AsyncMock(return_value=self.recovery))
        self.mock(factory.mooi, "fetch_work", AsyncMock(return_value={"items": [self.item]}))
        self.progress = self.mock(factory, "_report", AsyncMock(side_effect=self.report))
        self.queue = self.mock(factory, "_queue_context_continuation", AsyncMock(side_effect=self.queue_recovery))
        self.mock(factory, "_publish_item", Mock())
        self.mock(factory, "_touch", Mock())

    def mock(self, target, name, value):
        return self.stack.enter_context(patch.object(target, name, value))

    async def report(self, task, status, **kwargs):
        task.update(status=status, sessionId=None, report=kwargs.get("report"))
        return self.item

    async def queue_recovery(self, task, recovery, limits):
        task.update(status="queued", sessionId=None, segments=3, contextHandoff="resume")
        return self.item

    async def advance(self):
        await factory._advance({"item": self.item, "playerId": str(self.caller.player_id)}, {})

    async def test_attention_orphan_restores_exact_attempt_and_queues_one_launch(self):
        await self.advance()
        self.queue.assert_awaited_once_with(self.task, self.recovery, factory.DEFAULT_LIMITS)
        self.assertEqual(self.task["attempts"], 4)
        self.assertEqual(self.task["segments"], 3)
        factory._launch.assert_awaited_once()
        self.assertIs(factory._launch.await_args.kwargs["recovery"], self.recovery)

    async def test_external_blocker_is_not_resumed(self):
        self.recovery["reportedOutcome"] = "blocked"
        await self.advance()
        self.queue.assert_not_awaited()
        self.progress.assert_awaited_once()
        self.assertEqual(self.progress.await_args.args[1], "attention")
        self.assertTrue(self.progress.await_args.kwargs["clear_session"])

    async def test_missing_snapshot_is_classified_and_session_reference_cleared(self):
        self.mock(factory.mooi, "fetch_task_recovery", AsyncMock(side_effect=ApiException.not_found("missing")))
        await self.advance()
        self.queue.assert_not_awaited()
        self.assertTrue(self.progress.await_args.kwargs["clear_session"])
        self.assertEqual(self.progress.await_args.kwargs["recovery_state"], "missing")

    async def test_existing_session_is_not_cleared_or_relaunched(self):
        live = SimpleNamespace(id=uuid4(), closing=False, status="working", pending=None, queue=[],
                               log=SimpleNamespace(last_seq=1))
        live.id = __import__("uuid").UUID(self.task["sessionId"])
        self.registry.get.return_value = live
        self.mock(factory, "_quota_exhausted", Mock(return_value=False))
        await self.advance()
        self.progress.assert_not_awaited()
        self.queue.assert_not_awaited()
        factory._launch.assert_not_awaited()

    async def test_paused_item_queues_recovery_without_opening_session(self):
        self.item["status"] = "paused"
        await self.advance()
        self.queue.assert_awaited_once()
        factory._launch.assert_not_awaited()

    async def test_no_progress_attention_is_not_automatically_restarted(self):
        self.task["report"] = "No progress: sin progreso tras 3 turnos consecutivos"
        await self.advance()
        self.queue.assert_not_awaited()
        self.assertTrue(self.progress.await_args.kwargs["clear_session"])

    async def test_terminal_tasks_are_never_reconciled_or_launched(self):
        for status in ("done", "cancelled"):
            self.task.update(status=status)
            self.progress.reset_mock()
            self.queue.reset_mock()
            factory._launch.reset_mock()
            await self.advance()
            self.progress.assert_not_awaited()
            self.queue.assert_not_awaited()
            factory._launch.assert_not_awaited()

    async def test_work_payload_marks_session_link_from_registry_authoritatively(self):
        self.registry.get.return_value = None
        unavailable = factory._session_visible_item(self.item)["tasks"][0]
        self.assertFalse(unavailable["sessionAvailable"])
        self.registry.get.return_value = object()
        available = factory._session_visible_item(self.item)["tasks"][0]
        self.assertTrue(available["sessionAvailable"])

    async def test_concurrent_runner_ticks_claim_an_item_once(self):
        self.mock(factory, "_advancing", {})
        self.mock(factory, "_saved", {})
        self.mock(factory, "_behind", set())
        self.mock(factory.mooi, "reconcile_work_changes", AsyncMock(return_value=[]))
        self.mock(factory.mooi, "fetch_running_work", AsyncMock(return_value=[{
            "item": self.item, "playerId": str(self.caller.player_id),
        }]))

        async def advance_later(*args):
            await asyncio.sleep(0.02)

        advance = self.mock(factory, "_advance_item", AsyncMock(side_effect=advance_later))
        await asyncio.gather(factory._tick(), factory._tick())
        await asyncio.sleep(0.03)

        advance.assert_awaited_once()

    async def test_failed_physical_restore_is_durably_unrestorable(self):
        self.task.update(status="running", contextHandoff="resume")
        session_id = __import__("uuid").UUID(self.task["sessionId"])
        live = SimpleNamespace(id=session_id, closing=False, status=factory.STATUS_FAILED,
                               detail="Task recovery could not be restored safely", recovery_failed=True)
        self.registry.get.return_value = live
        self.mock(factory, "_quota_exhausted", Mock(return_value=False))
        run = SimpleNamespace(item_id=__import__("uuid").UUID(self.item["id"]), handing_off=False,
                              quota_pausing=False, recovery_expected=True, phase="working", status="running",
                              settling=False, finishing=False, context_handoff="resume")
        self.mock(factory, "_adopt", Mock(return_value=run))
        invalidated = self.mock(factory.mooi, "invalidate_task_recovery", AsyncMock())
        self.mock(factory, "_close", AsyncMock())

        await self.advance()

        invalidated.assert_awaited_once_with(__import__("uuid").UUID(self.task["id"]), 4,
                                            "Recovery restore failed: Task recovery could not be restored safely")
        self.assertEqual(self.progress.await_args.kwargs["recovery_state"], "unrestorable")
        self.assertTrue(self.progress.await_args.kwargs["clear_session"])


if __name__ == "__main__":
    unittest.main()
