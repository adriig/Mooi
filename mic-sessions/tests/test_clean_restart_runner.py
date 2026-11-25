import json
import unittest
from contextlib import ExitStack
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch
from uuid import UUID, uuid4

from mic_sessions.features import factory
from mic_sessions.shared.auth import Caller
from mic_sessions.shared.web import ApiException


class CleanRestartRunnerTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.caller = Caller(uuid4(), uuid4(), "user", "test", 9999999999)
        self.assignment = {"provider": "codex", "connectionId": str(uuid4()), "model": "test", "effort": "high"}
        self.fallback = {"provider": "claude", "connectionId": str(uuid4()), "model": "fallback", "effort": "low"}
        self.task = {"id": str(uuid4()), "key": "runner", "title": "Task", "status": "planned",
                     "attempts": 4, "attemptsBase": 4, "sessionId": None, "qaOf": None,
                     "dependsOn": [], "autoMerge": False, "assignment": self.assignment,
                     "alternatives": [self.fallback], "segments": 0,
                     "recoveryDisposition": {"state": "discarded", "restartPending": True, "discardedAttempt": 4}}
        self.item = {"id": str(uuid4()), "projectId": str(uuid4()), "kind": "feature",
                     "title": "Feature", "status": "running", "tasks": [self.task]}
        self.recovery = {"taskId": self.task["id"], "attempt": 4, "segment": 0, "snapshotValid": True,
                         "branch": "factory/old", "baseCommit": "a" * 40, "snapshotRevision": "b" * 40,
                         "summary": "old progress", "files": ["app.py"]}
        self.registry = SimpleNamespace(get=lambda _: None)
        self.mock(factory, "get_registry", Mock(return_value=self.registry))
        self.stack.enter_context(patch.dict(factory._runs, {}, clear=True))
        self.mock(factory, "_caller", AsyncMock(return_value=self.caller))
        self.mock(factory, "_has_room", AsyncMock(return_value=True))
        self.mock(factory, "_write_feature_document", AsyncMock())
        self.mock(factory, "_advance_changes", AsyncMock(return_value=False))
        self.mock(factory, "_publish_item", Mock())
        self.mock(factory, "_touch", Mock())
        self.assign = self.mock(factory, "_assign", AsyncMock(return_value=self.assignment))
        self.fetch = self.mock(factory.mooi, "fetch_task_recovery", AsyncMock(return_value=self.recovery))
        self.mock(factory.mooi, "fetch_work", AsyncMock(side_effect=lambda *a: {"items": [self.item]}))
        self.progress = self.mock(factory.mooi, "report_task", AsyncMock(side_effect=self.save_progress))
        self.cleanup = self.mock(factory.workspaces, "delete_recovery_attempt", AsyncMock(return_value=True))
        self.open = self.mock(factory.sessions, "_open_session", AsyncMock(side_effect=self.open_session))
        self.close = self.mock(factory, "_close", AsyncMock())

    def mock(self, target, name, value):
        return self.stack.enter_context(patch.object(target, name, value))

    async def open_session(self, request, caller, **kwargs):
        if factory._clean_restart_pending(self.task):
            self.assertTrue(self.task["recoveryDisposition"]["restartPending"])
            self.assertIsNone(kwargs["recovery"])
        return SimpleNamespace(id=uuid4())

    async def save_progress(self, task_id, body):
        self.task.update(body)
        if body["status"] == "running" and body.get("sessionId"):
            self.task["recoveryDisposition"]["restartPending"] = False
        return {"playerId": str(self.caller.player_id), "item": self.item}

    async def advance(self):
        await factory._advance({"item": self.item, "playerId": str(self.caller.player_id)}, {})

    async def launch(self, **kwargs):
        await factory._launch(self.item, self.task, self.caller, self.caller.player_id,
                              factory.DEFAULT_LIMITS, **kwargs)

    def ordinary(self, **changes):
        self.task["recoveryDisposition"] = {"state": "recoverable", "restartPending": False,
                                             "discardedAttempt": None}
        self.task.update(attempts=5, **changes)

    async def test_clean_launch_ignores_even_supplied_old_recovery_and_explains_user_choice(self):
        await self.launch(recovery=self.recovery)
        self.fetch.assert_not_awaited()
        request = self.open.call_args.args[0]
        self.assertNotEqual(request.branch, self.recovery["branch"])
        self.assertTrue(request.branch.startswith("factory/runner-"))
        self.assertIsNone(self.open.call_args.kwargs["recovery"])
        self.assertEqual((request.provider, str(request.connectionId), request.model, request.effort),
                         ("codex", self.assignment["connectionId"], "test", "high"))
        self.assertIn("explicitly discarded", request.initialMessage)
        self.assertIn("currently integrated default branch", request.initialMessage)
        self.assertNotIn("Saved task handoff", request.initialMessage)
        self.assertNotIn("old progress", request.initialMessage)
        self.assertEqual(self.task["attempts"], 5)
        self.assertFalse(self.task["recoveryDisposition"]["restartPending"])
        run = next(iter(factory._runs.values()))
        self.assertFalse(run.recovered)
        self.assertIsNone(run.outcome)
        await self.advance()
        self.open.assert_awaited_once()

    async def test_capacity_wait_and_runner_restart_keep_durable_intent(self):
        self.mock(factory, "_has_room", AsyncMock(return_value=False))
        await self.advance()
        self.assertEqual(self.task["status"], "queued")
        self.assertTrue(self.task["recoveryDisposition"]["restartPending"])
        self.assertGreater(self.task["attempts"], 4)
        # Only the serialized API state survives. No runner-local recovery cache or intent is used.
        self.item = json.loads(json.dumps(self.item))
        self.task = self.item["tasks"][0]
        factory._runs.clear()
        self.mock(factory, "_has_room", AsyncMock(return_value=True))
        await self.advance()
        self.fetch.assert_not_awaited()
        self.open.assert_awaited_once()
        self.assertFalse(self.task["recoveryDisposition"]["restartPending"])

    async def test_no_assignment_or_open_failure_never_consumes_disposition(self):
        self.assign.return_value = None
        await self.launch()
        self.assertTrue(self.task["recoveryDisposition"]["restartPending"])
        self.open.assert_not_awaited()
        self.assign.return_value = self.assignment
        self.open.side_effect = ApiException.conflict("Session capacity")
        await self.launch()
        self.assertTrue(self.task["recoveryDisposition"]["restartPending"])
        self.fetch.assert_not_awaited()
        self.open.side_effect = self.open_session
        factory._runs.clear()
        await self.advance()
        self.assertFalse(self.task["recoveryDisposition"]["restartPending"])

    async def test_rejected_launch_preserves_marker_and_closes_unaccepted_session(self):
        self.progress.side_effect = lambda *a: {"item": self.item}
        await self.launch()
        self.close.assert_awaited_once()
        self.assertTrue(self.task["recoveryDisposition"]["restartPending"])

    async def test_cleanup_failure_is_logged_and_cannot_restore_discarded_evidence(self):
        self.cleanup.side_effect = OSError("cannot remove bundle")
        with self.assertLogs(factory.LOG, level="WARNING") as logs:
            await self.advance()
        self.assertIn("evidence remains ineligible", "\n".join(logs.output))
        self.fetch.assert_not_awaited()
        self.assertIsNone(self.open.call_args.kwargs["recovery"])
        self.assertEqual(self.task["recoveryDisposition"]["discardedAttempt"], 4)
        self.assertFalse(self.task["recoveryDisposition"]["restartPending"])

    async def test_leased_bundle_cleanup_is_deferred_without_restoring_eligibility(self):
        self.cleanup.return_value = False
        with self.assertLogs(factory.LOG, level="WARNING") as logs:
            await self.launch()
        self.assertIn("cleanup deferred", "\n".join(logs.output))
        self.fetch.assert_not_awaited()
        self.assertFalse(self.task["recoveryDisposition"]["restartPending"])
        self.assertEqual(self.task["recoveryDisposition"]["discardedAttempt"], 4)

    async def test_invalid_recovery_stays_attention_without_explicit_command(self):
        self.ordinary(status="attention")
        self.recovery.update(snapshotValid=False, validityReason="invalid bundle")
        await self.advance()
        self.assertEqual(self.task["status"], "attention")
        self.fetch.assert_not_awaited()
        self.open.assert_not_awaited()
        self.cleanup.assert_not_awaited()

    async def test_ordinary_retry_keeps_invalid_evidence_and_refuses_clean_launch(self):
        self.ordinary()
        self.recovery.update(snapshotValid=False, validityReason="invalid bundle")
        await self.advance()
        self.assertEqual(self.task["status"], "attention")
        self.assertIn("invalid bundle", self.task["report"])
        self.open.assert_not_awaited()
        self.cleanup.assert_not_awaited()
        self.assertFalse(self.recovery["snapshotValid"])

    async def test_retry_of_structured_unusable_recovery_cannot_fall_back_to_empty_workspace(self):
        self.ordinary()
        self.fetch.side_effect = ApiException.not_found("No previous attempt")
        for state in ("invalid", "incomplete", "unrestorable"):
            self.task.update(status="planned", attempts=4)
            self.task["recoveryDisposition"]["state"] = state
            await self.launch()
            self.assertEqual(self.task["status"], "attention")
        self.fetch.assert_not_awaited()
        self.open.assert_not_awaited()
        self.cleanup.assert_not_awaited()

    async def test_missing_previous_snapshot_needs_attention_without_explicit_clean_restart(self):
        self.ordinary()
        self.task["recoveryDisposition"]["state"] = "missing"
        self.fetch.side_effect = ApiException.not_found("No previous snapshot")
        await self.launch()
        self.assertEqual(self.task["status"], "attention")
        self.open.assert_not_awaited()
        self.cleanup.assert_not_awaited()

    async def test_valid_recovery_is_passed_to_session_provisioning(self):
        self.ordinary()
        # The new attempt has no row yet; its valid snapshot belongs to the previous attempt.
        self.task["recoveryDisposition"]["state"] = "missing"
        await self.launch()
        self.fetch.assert_awaited_once_with(UUID(self.task["id"]), 4)
        request = self.open.call_args.args[0]
        self.assertEqual(request.branch, "factory/old")
        self.assertEqual(self.open.call_args.kwargs["recovery"]["snapshotRevision"], "b" * 40)
        self.assertIn("restored workspace", request.initialMessage)
        self.assertTrue(next(iter(factory._runs.values())).recovered)
        self.cleanup.assert_not_awaited()

    async def test_quota_resume_uses_current_attempt_snapshot(self):
        self.ordinary(status="queued", report=factory._QUOTA_WAIT + " later")
        self.recovery["attempt"] = 5
        await self.launch()
        self.fetch.assert_awaited_once_with(UUID(self.task["id"]), 5)
        self.assertEqual(self.open.call_args.kwargs["recovery"]["attempt"], 5)
        self.assertEqual(self.task["attempts"], 5)
        self.cleanup.assert_not_awaited()

    async def test_handoff_after_runner_restart_keeps_attempt_revision_and_segment(self):
        self.ordinary(status="running", sessionId=str(uuid4()), contextHandoff="handoff", segments=2,
                      lastSnapshotRevision="a" * 40)
        self.recovery.update(attempt=5, segment=2)
        await self.advance()
        self.assertEqual(self.task["attempts"], 5)
        self.assertEqual(self.task["segments"], 3)
        self.assertEqual(self.task["lastSnapshotRevision"], "b" * 40)
        restored = self.open.call_args.kwargs["recovery"]
        self.assertEqual((restored["attempt"], restored["segment"], restored["snapshotRevision"]),
                         (5, 2, "b" * 40))
        self.assertIn("Restored attempt 5, segment 3", self.open.call_args.args[0].initialMessage)
        self.cleanup.assert_not_awaited()

    async def test_durable_qa_report_replays_without_fetching_workspace_recovery(self):
        self.ordinary(status="running", sessionId=str(uuid4()), qaOf=str(uuid4()),
                      qaReport={"outcome": "passed", "summary": "Verified"})
        report = self.mock(factory.mooi, "report_defect", AsyncMock(return_value={"item": self.item}))
        self.mock(factory, "_retire_task_recovery", AsyncMock())
        self.item["status"] = "paused"  # Replay still reconciles paused items; no new task launches.
        await self.advance()
        report.assert_awaited_once()
        self.assertEqual(report.call_args.args[1]["outcome"], "passed")
        self.fetch.assert_not_awaited()
        self.open.assert_not_awaited()

    async def test_clean_restart_keeps_assignment_ordered_fallbacks(self):
        self.assign.side_effect = None
        connections = [{"provider": assignment["provider"], "id": assignment["connectionId"]}
                       for assignment in (self.assignment, self.fallback)]
        catalogs = {assignment["connectionId"]: {"models": [{"id": assignment["model"]}]}
                    for assignment in (self.assignment, self.fallback)}
        snapshots = {self.assignment["connectionId"]: {"weekly": {"percent": 100}},
                     self.fallback["connectionId"]: {"weekly": {"percent": 0}}}
        self.assign.return_value = factory._select_assignment(self.task, connections, 85, catalogs, snapshots)
        await self.launch()
        request = self.open.call_args.args[0]
        self.assertEqual((request.provider, request.model, request.effort), ("claude", "fallback", "low"))
        self.assertEqual(self.task["alternatives"], [self.fallback])
