package dev.mooi.mic.features;

import dev.mooi.mic.features.ProjectWorkFeature.*;
import dev.mooi.mic.shared.Auth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkCleanRestartTest {
    final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), itemId = UUID.randomUUID();
    final WorkProjectRepository projects = mock(WorkProjectRepository.class);
    final WorkItemRepository items = mock(WorkItemRepository.class);
    final WorkTaskRepository tasks = mock(WorkTaskRepository.class);
    final TaskRecoveryRepository recoveries = mock(TaskRecoveryRepository.class);
    final WorkChangeRequestRepository changes = mock(WorkChangeRequestRepository.class);
    final WorkService work = new WorkService(projects, items, tasks, recoveries,
            mock(WorkQaDeliveryRepository.class), changes, mock(WorkSettingsRepository.class),
            mock(IssueDefinitionRepository.class), mock(Auth.FactoryCredentials.class), Clock.systemUTC());
    WorkItem item;
    WorkTask task;

    @BeforeEach
    void setup() {
        item = new WorkItem();
        item.setId(itemId);
        item.setProjectId(project);
        item.setKind(ItemKind.feature);
        item.setStatus(ItemStatus.running);
        task = new WorkTask();
        task.setId(UUID.randomUUID());
        task.setItemId(itemId);
        task.setStatus(TaskStatus.attention);
        task.setAttempts(4);
        task.setAttemptsBase(1);
        task.setSegments(3);
        task.setStalledSegments(2);
        task.setLastSnapshotRevision("snapshot");
        task.setContextHandoff("requested");
        task.setReport("Recovery is unusable");
        task.setStartedAt(OffsetDateTime.now());
        task.setFinishedAt(OffsetDateTime.now());
        when(projects.findByIdAndPlayerId(project, owner)).thenReturn(Optional.of(new WorkProject()));
        when(items.findLockedById(itemId)).thenReturn(Optional.of(item));
        when(tasks.findByItemId(itemId)).thenReturn(List.of(task));
    }

    @Test
    void persistsDiscardAndClearsOnlyTransientState() {
        task.setQaReport("{\"outcome\":\"defect\"}");
        task.setQaRounds(2);
        var payload = work.restartCleanTask(owner, project, itemId, task.getId()).tasks().getFirst();
        assertEquals(TaskStatus.planned, payload.status());
        assertEquals(4, payload.attempts());
        assertEquals(4, payload.attemptsBase());
        assertEquals(new RecoveryDisposition("discarded", true, 4), payload.recoveryDisposition());
        assertEquals("clean_restart", task.getRecoveryDisposition());
        assertEquals(0, payload.segments());
        assertEquals(0, payload.stalledSegments());
        assertNull(payload.lastSnapshotRevision());
        assertNull(payload.contextHandoff());
        assertNull(payload.report());
        assertNull(payload.sessionId());
        assertNull(payload.startedAt());
        assertNull(payload.finishedAt());
        assertEquals(2, payload.qaRounds());
        assertEquals("defect", payload.qaReport().get("outcome"));
        verify(tasks).save(task);
        verify(recoveries, never()).save(any());
        verify(recoveries, never()).delete(any());
    }

    @Test
    void repeatedDecisionIsAStableConflictAndCannotReenableEvidence() {
        work.restartCleanTask(owner, project, itemId, task.getId());
        var first = assertThrows(ResponseStatusException.class,
                () -> work.restartCleanTask(owner, project, itemId, task.getId()));
        var again = assertThrows(ResponseStatusException.class,
                () -> work.restartCleanTask(owner, project, itemId, task.getId()));
        assertEquals(409, first.getStatusCode().value());
        assertEquals(first.getReason(), again.getReason());
        assertEquals(4, task.getDiscardedAttempt());
        assertEquals("clean_restart", task.getRecoveryDisposition());
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> work.recovery(task.getId(), 4)).getStatusCode().value());
        verify(recoveries, never()).findByTaskIdAndAttempt(any(), anyInt());
    }

    @Test
    void discardedAttemptRejectsDelayedRecoveryWritesAndProgress() {
        work.restartCleanTask(owner, project, itemId, task.getId());
        when(tasks.findLockedById(task.getId())).thenReturn(Optional.of(task));
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        when(projects.findById(project)).thenReturn(Optional.of(new WorkProject()));
        var update = new TaskRecoveryUpdate(4, 0, "mooi/task", "a".repeat(40), "b".repeat(40),
                "c".repeat(64), null, "", "", List.of(), List.of(), List.of(),
                null, null, null, null, true, null);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> work.saveRecovery(task.getId(), update)).getStatusCode().value());
        var progress = new TaskProgress(TaskStatus.attention, null, 4, 0, 0,
                null, null, "late report", null, null, null, null);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> work.progress(task.getId(), progress)).getStatusCode().value());
        assertEquals(TaskStatus.planned, task.getStatus());
        verify(recoveries, never()).save(any());
    }

    @Test
    void cleanDispositionSurvivesWaitingAndIsConsumedOnlyWithAcceptedRunningSession() {
        work.restartCleanTask(owner, project, itemId, task.getId());
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        when(tasks.findLockedById(task.getId())).thenReturn(Optional.of(task));
        work.progress(task.getId(), launchProgress(TaskStatus.queued, null));
        assertEquals("clean_restart", task.getRecoveryDisposition());
        assertEquals(5, task.getAttempts());
        item.setStatus(ItemStatus.paused);
        UUID session = UUID.randomUUID();
        work.progress(task.getId(), launchProgress(TaskStatus.running, session));
        assertNull(task.getSessionId());
        assertEquals("clean_restart", task.getRecoveryDisposition());
        item.setStatus(ItemStatus.running);
        work.progress(task.getId(), launchProgress(TaskStatus.attention, null));
        assertEquals("clean_restart", task.getRecoveryDisposition());
        work.progress(task.getId(), launchProgress(TaskStatus.running, null));
        assertEquals("clean_restart", task.getRecoveryDisposition());
        work.progress(task.getId(), launchProgress(TaskStatus.running, session));
        assertEquals(session, task.getSessionId());
        assertNull(task.getRecoveryDisposition());
        assertEquals(4, task.getDiscardedAttempt());
        // Receipt replay is harmless and cannot re-enable old evidence.
        work.progress(task.getId(), launchProgress(TaskStatus.running, session));
        assertNull(task.getRecoveryDisposition());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> work.recovery(task.getId(), 4)).getStatusCode().value());
        verify(recoveries, never()).findByTaskIdAndAttempt(any(), anyInt());
    }

    private TaskProgress launchProgress(TaskStatus status, UUID session) {
        return new TaskProgress(status, session, 5, 0, 0, null, null, null, null, null, null, null);
    }

    @Test
    void validatesOwnerProjectItemAndTaskMembership() {
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> work.restartCleanTask(UUID.randomUUID(), project, itemId, task.getId()))
                .getStatusCode().value());
        verifyNoInteractions(items, tasks);
        item.setProjectId(UUID.randomUUID());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> work.restartCleanTask(owner, project, itemId, task.getId())).getStatusCode().value());
        item.setProjectId(project);
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> work.restartCleanTask(owner, project, itemId, UUID.randomUUID())).getStatusCode().value());
        verify(tasks, never()).save(any());
    }

    @Test
    void rejectsEveryNonAttentionStatusAndSessionBoundAttention() {
        for (TaskStatus status : TaskStatus.values()) {
            if (status == TaskStatus.attention) continue;
            task.setStatus(status);
            assertEquals(409, assertThrows(ResponseStatusException.class,
                    () -> work.restartCleanTask(owner, project, itemId, task.getId())).getStatusCode().value());
        }
        task.setStatus(TaskStatus.attention);
        task.setSessionId(UUID.randomUUID());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> work.restartCleanTask(owner, project, itemId, task.getId())).getStatusCode().value());
        verify(tasks, never()).save(any());
    }

    @Test
    void retryRetainsRecoveryAndHandoff() {
        TaskRecovery recovery = new TaskRecovery();
        recovery.setTaskId(task.getId());
        recovery.setSnapshotValid(false);
        when(recoveries.findCurrentByItemId(itemId)).thenReturn(List.of(recovery));
        var payload = work.retryTask(owner, project, itemId, task.getId()).tasks().getFirst();
        assertNull(task.getRecoveryDisposition());
        assertNull(task.getDiscardedAttempt());
        assertEquals(new RecoveryDisposition("invalid", false, null), payload.recoveryDisposition());
        assertEquals("snapshot", payload.lastSnapshotRevision());
        assertEquals("requested", payload.contextHandoff());
        assertEquals(3, payload.segments());
    }

    @Test
    void legacyAndValidRecoveryHaveStructuredStatesWithoutChangingRows() {
        assertEquals("missing", work.payload(item).tasks().getFirst().recoveryDisposition().state());
        TaskRecovery recovery = new TaskRecovery();
        recovery.setTaskId(task.getId());
        recovery.setSnapshotValid(true);
        when(recoveries.findCurrentByItemId(itemId)).thenReturn(List.of(recovery));
        assertEquals("recoverable", work.payload(item).tasks().getFirst().recoveryDisposition().state());
        task.setAttempts(0);
        assertEquals("normal", work.payload(item).tasks().getFirst().recoveryDisposition().state());
        verify(tasks, never()).save(any());
    }
}
