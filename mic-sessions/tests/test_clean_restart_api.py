import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
from uuid import uuid4

from mic_sessions.features import factory
from mic_sessions.shared.auth import Caller
from mic_sessions.shared.web import ApiException


class CleanRestartApiTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.project, self.item_id, self.task_id, self.session_id = (uuid4() for _ in range(4))
        self.caller = Caller(uuid4(), uuid4(), "user", "test", 9999999999)
        self.item = {
            "id": str(self.item_id), "projectId": str(self.project), "status": "running",
            "tasks": [{"id": str(self.task_id), "status": "planned", "sessionId": None,
                       "recoveryDisposition": {"state": "discarded", "restartPending": True,
                                               "discardedAttempt": 4}}],
        }

    async def test_user_operation_publishes_committed_payload_then_wakes(self):
        calls = []
        async def save(*args):
            calls.append("committed")
            return self.item

        with patch.object(factory.mooi, "send_work", side_effect=save) as send, \
                patch.object(factory.sessions._channels, "publish", side_effect=lambda *a: calls.append("event")) as event, \
                patch.object(factory, "wake", side_effect=lambda: calls.append("wake")):
            result = await factory.restart_clean_task(self.project, self.item_id, self.task_id, self.caller)
        send.assert_awaited_once_with(self.caller, "POST", self.project,
                                     f"/items/{self.item_id}/tasks/{self.task_id}/restart-clean")
        event.assert_called_once_with(self.caller.player_id, "work.item",
                                      {"projectId": str(self.project), "item": self.item})
        self.assertEqual(result, self.item)
        self.assertEqual(calls, ["committed", "event", "wake"])

    async def test_planned_and_paused_items_are_not_started(self):
        for status in ("planned", "paused"):
            self.item["status"] = status
            with patch.object(factory.mooi, "send_work", new=AsyncMock(return_value=self.item)), \
                    patch.object(factory.sessions._channels, "publish"), patch.object(factory, "wake") as wake:
                await factory._restart_clean_task(self.caller, self.project, self.item_id, self.task_id)
            wake.assert_not_called()

    async def test_conflict_or_access_denial_publishes_nothing(self):
        for error in (ApiException.conflict("Clean restart requires a task in attention without a session"),
                      ApiException.not_found("Project not found")):
            with patch.object(factory.mooi, "send_work", new=AsyncMock(side_effect=error)), \
                    patch.object(factory.sessions._channels, "publish") as event, \
                    patch.object(factory, "wake") as wake:
                with self.assertRaises(ApiException):
                    await factory._restart_clean_task(self.caller, self.project, self.item_id, self.task_id)
            event.assert_not_called()
            wake.assert_not_called()

    async def test_agent_uses_authenticated_session_project_and_shared_operation(self):
        request = object()
        session = SimpleNamespace(project_id=self.project)
        with patch.object(factory.sessions, "_agent_access", new=AsyncMock(return_value=(session, self.caller))) as access, \
                patch.object(factory, "_analyst_guard") as guard, \
                patch.object(factory, "_restart_clean_task", new=AsyncMock(return_value=self.item)) as operation:
            result = await factory.agent_restart_clean_task(self.session_id, self.item_id, self.task_id, request)
        access.assert_awaited_once_with(self.session_id, request, "session")
        guard.assert_called_once_with(self.session_id)
        operation.assert_awaited_once_with(self.caller, self.project, self.item_id, self.task_id)
        self.assertEqual(result, self.item)

    async def test_agent_denied_access_never_calls_operation(self):
        with patch.object(factory.sessions, "_agent_access", new=AsyncMock(side_effect=ApiException.not_found("Session not found"))), \
                patch.object(factory, "_restart_clean_task", new=AsyncMock()) as operation:
            with self.assertRaises(ApiException):
                await factory.agent_restart_clean_task(self.session_id, self.item_id, self.task_id, object())
        operation.assert_not_awaited()

    async def test_retry_keeps_its_original_endpoint(self):
        task = {"id": str(self.task_id), "status": "attention", "sessionId": None}
        with patch.object(factory.mooi, "fetch_work", new=AsyncMock(return_value={
                "items": [{"id": str(self.item_id), "tasks": [task]}]})), \
                patch.object(factory.mooi, "send_work", new=AsyncMock(return_value=self.item)) as send, \
                patch.object(factory.sessions._channels, "publish"), patch.object(factory, "wake"):
            await factory._retry_task(self.caller, self.project, self.item_id, self.task_id)
        self.assertEqual(send.call_args.args[-1], f"/items/{self.item_id}/tasks/{self.task_id}/retry")
