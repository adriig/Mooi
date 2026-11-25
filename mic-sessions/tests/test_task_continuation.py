import unittest
from contextlib import ExitStack
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch
from uuid import uuid4

from mic_sessions.features import factory
from mic_sessions.features.sessions import STATUS_READY
from mic_sessions.shared.auth import Caller


def revision(char: str) -> str:
    return char * 64


class TaskContinuationTest(unittest.IsolatedAsyncioTestCase):
    """A factory turn that ends without a task report is continued by the runner, not handed to the user."""

    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.caller = Caller(uuid4(), uuid4(), "user", "test", 9999999999)
        self.item = {"id": str(uuid4()), "projectId": str(uuid4()), "kind": "feature", "title": "Feature",
                     "status": "running", "tasks": []}
        self.session = SimpleNamespace(
            id=uuid4(), closing=False, status=STATUS_READY, pending=None, queue=[], usage={},
            config=SimpleNamespace(model="model"), log=SimpleNamespace(last_seq=0), workspace=Path("/nonexistent"),
            branch="factory/task", base_commit="a" * 40, connection_id=uuid4(), detail=None)
        self.registry = SimpleNamespace(get=lambda session_id: self.session if session_id == self.session.id else None)
        self.mock(factory, "get_registry", Mock(return_value=self.registry))
        self.stack.enter_context(patch.dict(factory._runs, {}, clear=True))
        self.mock(factory, "_publish_item", Mock())
        self.mock(factory, "_touch", Mock())
        self.mock(factory, "_check_context", AsyncMock())
        self.mock(factory, "_run_caller", AsyncMock(return_value=self.caller))
        self.handoff = self.mock(factory, "_handoff_context", AsyncMock())
        self.merge = self.mock(factory, "_merge", AsyncMock())
        self.mock(factory.mooi, "fetch_work", AsyncMock(side_effect=lambda *a: {"items": [self.item]}))
        self.saves: list[dict] = []
        self.mock(factory.mooi, "report_task", AsyncMock(side_effect=self.save_progress))
        self.snapshot: dict | None = {"snapshotRevision": revision("1"), "files": ["app.py"]}
        self.persist = self.mock(factory, "_persist_recovery", AsyncMock(side_effect=lambda *a, **k: self.snapshot))
        self.queue = self.mock(factory.sessions, "queue_message", AsyncMock(side_effect=self.queue_message))
        self.run = self.new_run()

    def mock(self, target, name, value):
        return self.stack.enter_context(patch.object(target, name, value))

    def new_run(self, **changes) -> factory.TaskRun:
        values = {"session_id": self.session.id, "item_id": factory.UUID(self.item["id"]), "task_id": uuid4(),
                  "project_id": factory.UUID(self.item["projectId"]), "player_id": self.caller.player_id,
                  "qa": False, "title": "Task", "auto_merge": True, "issue_number": None, "attempts": 0}
        run = factory.TaskRun(**{**values, **changes})
        run.limits = {**factory.DEFAULT_LIMITS, "stallTurns": 3}
        factory._runs[self.session.id] = run
        return run

    async def save_progress(self, task_id, body):
        self.saves.append(body)
        return {"playerId": str(self.caller.player_id), "item": self.item}

    async def queue_message(self, session_id, request, caller):
        self.session.queue.append(SimpleNamespace(text=request.text))

    async def end_turn(self, *, deliver=True):
        """One agent turn ends: the session log records its result and the runner settles it."""
        self.session.log.last_seq += 1
        self.run.turn_end = self.session.log.last_seq
        await factory._after_turn(self.run)
        if deliver and self.session.queue:
            # The queue hands the continuation to the agent, which starts its next turn.
            self.session.queue.clear()
            await factory._continuation_delivered(self.run)

    def continuations(self) -> int:
        return sum(call.args[1].text.startswith(factory._CONTINUE_MARK) for call in self.queue.await_args_list)

    # 1
    async def test_turn_without_result_but_with_progress_continues_automatically(self):
        await self.end_turn(deliver=False)
        self.assertEqual(self.continuations(), 1)
        message = self.queue.await_args.args[1].text
        self.assertIn("Continue implementing the task now", message)
        self.assertIn("do not just explain what is left", message)
        self.assertIn("MUST POST exactly one task report", message)
        for outcome in ("`done`", "`continue`", "`blocked`"):
            self.assertIn(outcome, message)
        self.assertEqual(self.run.status, "running")
        self.assertEqual(self.run.stalled_segments, 0)
        saved = self.saves[-1]
        self.assertEqual(saved["status"], "running")
        self.assertEqual(saved["contextHandoff"], factory.CONTINUATION)
        self.assertEqual(saved["lastSnapshotRevision"], revision("1"))
        self.assertTrue(saved["report"].startswith("Continuación automática"))
        # The snapshot was saved before the continuation was queued.
        self.assertTrue(self.persist.await_count >= 1)

    # 2
    async def test_turns_without_result_or_progress_reach_stall_turns_and_wait_for_the_user(self):
        self.run.last_snapshot_revision = revision("1")
        for _ in range(2):
            await self.end_turn()
            self.assertEqual(self.run.status, "running")
        self.assertEqual(self.continuations(), 2)
        await self.end_turn()
        self.assertEqual(self.continuations(), 2)
        self.assertEqual(self.run.status, "attention")
        self.assertEqual(self.run.stalled_segments, 3)
        self.assertEqual(self.saves[-1]["status"], "attention")
        self.assertEqual(self.saves[-1]["stalledSegments"], 3)
        self.assertIsNone(self.saves[-1]["contextHandoff"])
        self.assertTrue(self.saves[-1]["report"].startswith("No progress: sin resultado de tarea tras 3 turnos"))

    async def test_progress_restarts_the_count(self):
        self.run.last_snapshot_revision = revision("1")
        await self.end_turn()
        await self.end_turn()
        self.assertEqual(self.run.stalled_segments, 2)
        self.snapshot = {"snapshotRevision": revision("2"), "files": ["app.py"]}
        await self.end_turn()
        self.assertEqual(self.run.stalled_segments, 0)
        self.assertEqual(self.run.status, "running")
        self.assertEqual(self.continuations(), 3)

    # 3
    async def test_count_and_pending_continuation_survive_a_runner_restart(self):
        self.run.last_snapshot_revision = revision("1")
        await self.end_turn(deliver=False)
        durable = self.saves[-1]
        self.assertEqual(durable["stalledSegments"], 1)
        self.assertEqual(durable["contextHandoff"], factory.CONTINUATION)
        # The process restarts before the continuation reached the agent: its session is gone.
        factory._runs.clear()
        self.session.queue.clear()
        task = {"id": str(self.run.task_id), "key": "task", "title": "Task", "status": "running",
                "sessionId": str(uuid4()), "attempts": 0, "attemptsBase": 0, "qaOf": None, "dependsOn": [],
                "autoMerge": True, "segments": durable["segments"], "stalledSegments": durable["stalledSegments"],
                "lastSnapshotRevision": durable["lastSnapshotRevision"], "contextHandoff": durable["contextHandoff"]}
        self.item.update(status="paused", tasks=[task])
        recovery = {"taskId": task["id"], "attempt": 0, "segment": 0, "snapshotValid": True, "branch": "factory/task",
                    "baseCommit": "a" * 40, "snapshotRevision": revision("1"), "files": ["app.py"]}
        self.mock(factory.mooi, "fetch_task_recovery", AsyncMock(return_value=recovery))
        self.mock(factory, "_caller", AsyncMock(return_value=self.caller))
        await factory._advance({"item": self.item, "playerId": str(self.caller.player_id)}, {})
        resumed = self.saves[-1]
        # Same attempt, a new segment, and the turn already counted is not counted again.
        self.assertEqual(resumed["status"], "queued")
        self.assertEqual(resumed["attempts"], 0)
        self.assertEqual(resumed["segments"], 1)
        self.assertEqual(resumed["stalledSegments"], 1)
        self.assertEqual(resumed["contextHandoff"], "resume")
        self.assertIsNone(resumed["sessionId"])
        self.assertTrue(resumed["report"].startswith("Continuación automática sin resultado"))

    async def test_adopted_live_session_continues_its_pending_continuation_once(self):
        self.run.last_snapshot_revision = revision("1")
        await self.end_turn(deliver=False)
        self.session.queue.clear()  # Lost with the runner's memory, before delivery.
        adopted = self.new_run(task_id=self.run.task_id, stalled_segments=1,
                               last_snapshot_revision=revision("1"), context_handoff=factory.CONTINUATION)
        adopted.turn_end = adopted.counted_turn = self.session.log.last_seq
        self.run = adopted
        await factory._after_turn(adopted)
        await factory._after_turn(adopted)
        self.assertEqual(self.continuations(), 2)
        self.assertEqual(adopted.stalled_segments, 1)

    # 4
    async def test_replaying_the_same_turn_end_never_duplicates_the_continuation(self):
        self.run.last_snapshot_revision = revision("1")
        await self.end_turn(deliver=False)
        self.session.queue.clear()  # Already delivered: the queue no longer shows it.
        await factory._after_turn(self.run)
        await factory._after_turn(self.run)
        self.assertEqual(self.continuations(), 1)
        self.assertEqual(self.run.stalled_segments, 1)

    async def test_a_continuation_already_waiting_in_the_queue_is_not_queued_again(self):
        self.session.queue.append(SimpleNamespace(text=factory.CONTINUE.format(revision="x", stalled=0, limit=3)))
        self.session.log.last_seq += 1
        self.run.turn_end = self.session.log.last_seq
        await factory._continue_without_result(self.run, self.session, self.snapshot)
        self.queue.assert_not_awaited()

    # 5
    async def test_paused_item_does_not_continue_until_resume(self):
        self.item["status"] = "paused"
        await self.end_turn(deliver=False)
        self.queue.assert_not_awaited()
        self.assertTrue(self.run.resettle)
        self.assertEqual(self.saves[-1]["contextHandoff"], factory.CONTINUATION)
        self.assertIn("al reanudar", self.saves[-1]["report"])
        stalled = self.run.stalled_segments
        # Resume: the next tick settles the deferred turn again (see `_advance`).
        self.item["status"] = "running"
        self.run.resettle = False
        await factory._after_turn(self.run)
        self.assertEqual(self.continuations(), 1)
        self.assertEqual(self.run.stalled_segments, stalled)

    # 6
    async def test_missing_snapshot_never_continues_silently(self):
        self.snapshot = None
        await self.end_turn()
        self.queue.assert_not_awaited()
        self.assertEqual(self.run.status, "attention")
        self.assertIn("snapshot could not be saved", self.saves[-1]["report"])

    async def test_lost_session_with_invalid_snapshot_needs_attention(self):
        task = {"id": str(self.run.task_id), "attempts": 0, "segments": 0, "stalledSegments": 1,
                "lastSnapshotRevision": revision("1"), "contextHandoff": factory.CONTINUATION}
        await factory._queue_context_continuation(task, {"snapshotValid": False, "validityReason": "corrupt"})
        self.assertEqual(self.saves[-1]["status"], "attention")
        self.assertIn("cannot be restored safely", self.saves[-1]["report"])
        await factory._queue_context_continuation(task, None)
        self.assertEqual(self.saves[-1]["status"], "attention")
        self.queue.assert_not_awaited()

    # 7
    async def test_valid_continue_report_keeps_the_context_handoff_flow(self):
        self.run.outcome = factory.TaskReport(outcome="continue", summary="half done", nextStep="finish")
        await self.end_turn()
        self.handoff.assert_awaited_once()
        self.queue.assert_not_awaited()

    # 8
    async def test_done_report_still_merges(self):
        self.mock(factory.workspaces, "changes", AsyncMock(return_value=SimpleNamespace(
            files=[SimpleNamespace(path="app.py")])))
        self.run.context_handoff = factory.CONTINUATION
        self.run.outcome = factory.TaskReport(outcome="done", summary="finished", files=["app.py"])
        await self.end_turn()
        self.merge.assert_awaited_once()
        self.queue.assert_not_awaited()
        self.assertIsNone(self.run.context_handoff)

    async def test_blocked_report_still_waits_for_the_user(self):
        self.run.outcome = factory.TaskReport(outcome="blocked", summary="needs a key",
                                              blockerCause="The API key is missing", blockerCategory="credentials")
        await self.end_turn()
        self.queue.assert_not_awaited()
        self.assertEqual(self.run.status, "attention")
        self.assertEqual(self.saves[-1]["report"], "needs a key")

    async def test_done_without_changes_is_still_rejected_once(self):
        self.mock(factory.workspaces, "changes", AsyncMock(return_value=SimpleNamespace(files=[])))
        self.run.outcome = factory.TaskReport(outcome="done", summary="nothing")
        await self.end_turn(deliver=False)
        self.merge.assert_not_awaited()
        self.assertTrue(self.run.verification_nudged)
        self.assertIn("no changes relative to its base", self.queue.await_args.args[1].text)

    # 10
    async def test_task_restarted_clean_spans_several_turns_and_completes(self):
        # Launched fresh after an explicit clean restart: a new attempt with no prior snapshot.
        self.run = self.new_run(attempts=5)
        for index, char in enumerate("23"):
            self.snapshot = {"snapshotRevision": revision(char), "files": ["app.py"]}
            await self.end_turn()
            self.assertEqual(self.run.status, "running", index)
        await self.end_turn()  # One turn with no visible progress either.
        self.assertEqual(self.run.status, "running")
        self.assertEqual(self.continuations(), 3)
        self.mock(factory.workspaces, "changes", AsyncMock(return_value=SimpleNamespace(
            files=[SimpleNamespace(path="app.py")])))
        self.run.outcome = factory.TaskReport(outcome="done", summary="finished", files=["app.py"])
        await self.end_turn()
        self.merge.assert_awaited_once()
        self.assertNotIn("attention", [body["status"] for body in self.saves])
        self.assertEqual({body["attempts"] for body in self.saves}, {5})


if __name__ == "__main__":
    unittest.main()
