package dev.mooi.mic.features;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.hibernate.annotations.Immutable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.mooi.mic.shared.Auth;
import dev.mooi.mic.shared.Crypto;
import dev.mooi.mic.shared.Db;
import dev.mooi.mic.shared.Github;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature: a project's software factory, the work behind its Planification and Board views.
 *
 * <p>Three kinds of work item reach those views. A <b>feature</b> lives here only: a spec and the
 * tasks a session agent (the orchestrator) broke it into, with blocking dependencies that never leave
 * the feature; a feature may at most depend on another whole item. A <b>defect</b> or <b>debt</b> item is
 * a GitHub issue labelled {@code defect} or {@code debt}: GitHub keeps the issue, and a row here keeps
 * how Mooi runs it, created the first time it is configured or started. GitHub Issues never hold
 * features.
 *
 * <p>Every task carries what running it takes (category, description, acceptance criteria, provider,
 * account, model, effort, ordered fallbacks, auto-merge, tests). Tests on a task means a separate QA
 * task, owned by it ({@code qaOf}) and blocked until it merges; a defect a QA run finds becomes a fix
 * task ({@code fixOf}) the QA waits for before running again.
 *
 * <p>Player calls are reachable only through mic-sessions ({@link Auth.ServiceCall}), which announces
 * every change on the player's live channel; the runner that executes started items talks to the
 * {@code /internal} endpoints on its own ({@link Auth.ServiceOnly}), so a run survives the player's
 * devices going away. Every change to an item, from either side, holds that item's row lock.
 *
 * <p>Self-contained by architecture: API, application logic, persistence and contracts live in this
 * single file and may only import transversal aspects from {@code shared}.
 */
@RestController
@RequiredArgsConstructor
public class ProjectWorkFeature {

    private final WorkService work;
    private final IssueService issues;

    @GetMapping("/me/projects/{projectId}/work/issues/{number}/definition")
    @Auth.Authenticated
    @Auth.ServiceCall
    public DefinitionPayload definition(@PathVariable UUID projectId, @PathVariable int number,
                                         Auth.Principal principal) {
        return issues.definition(principal.player().id(), projectId, number);
    }

    @PutMapping("/me/projects/{projectId}/work/issues/{number}/definition/proposal")
    @Auth.Authenticated
    @Auth.ServiceCall
    public DefinitionResult propose(@PathVariable UUID projectId, @PathVariable int number,
                                      @Valid @RequestBody DefinitionProposal proposal, Auth.Principal principal) {
        return issues.propose(principal.player().id(), projectId, number, proposal);
    }

    @PostMapping("/me/projects/{projectId}/work/issues/{number}/definition/accept")
    @Auth.Authenticated
    @Auth.ServiceCall
    public DefinitionResult accept(@PathVariable UUID projectId, @PathVariable int number,
                                     @Valid @RequestBody DefinitionRevision revision, Auth.Principal principal) {
        return issues.accept(principal.player().id(), projectId, number, revision);
    }

    @PostMapping("/me/projects/{projectId}/work/issues/{number}/definition/reopen")
    @Auth.Authenticated
    @Auth.ServiceCall
    public DefinitionResult reopen(@PathVariable UUID projectId, @PathVariable int number,
                                     @Valid @RequestBody DefinitionRevision revision, Auth.Principal principal) {
        return issues.reopen(principal.player().id(), projectId, number, revision);
    }

    @GetMapping("/me/projects/{projectId}/work")
    @Auth.Authenticated
    @Auth.ServiceCall
    public WorkPayload items(@PathVariable UUID projectId, Auth.Principal principal) {
        return work.items(principal.player().id(), projectId);
    }

    @PostMapping("/me/projects/{projectId}/work/features")
    @Auth.Authenticated
    @Auth.ServiceCall
    @ResponseStatus(HttpStatus.CREATED)
    public ItemPayload createFeature(@PathVariable UUID projectId, @Valid @RequestBody FeatureDraft draft,
                                     Auth.Principal principal) {
        return work.createFeature(principal.player().id(), projectId, draft);
    }

    @PatchMapping("/me/projects/{projectId}/work/items/{itemId}")
    @Auth.Authenticated
    @Auth.ServiceCall
    public ItemPayload updateItem(@PathVariable UUID projectId, @PathVariable UUID itemId,
                                  @Valid @RequestBody ItemUpdate update, Auth.Principal principal) {
        return work.updateItem(principal.player().id(), projectId, itemId, update);
    }

    @DeleteMapping("/me/projects/{projectId}/work/items/{itemId}")
    @Auth.Authenticated
    @Auth.ServiceCall
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(@PathVariable UUID projectId, @PathVariable UUID itemId, Auth.Principal principal) {
        work.deleteItem(principal.player().id(), projectId, itemId);
    }

    @PostMapping("/me/projects/{projectId}/work/items/{itemId}/tasks")
    @Auth.Authenticated
    @Auth.ServiceCall
    public ItemPayload addTask(@PathVariable UUID projectId, @PathVariable UUID itemId,
                               @Valid @RequestBody TaskDraft draft, Auth.Principal principal) {
        return work.addTask(principal.player().id(), projectId, itemId, draft);
    }

    @PatchMapping("/me/projects/{projectId}/work/items/{itemId}/tasks/{taskId}")
    @Auth.Authenticated
    @Auth.ServiceCall
    public ItemPayload updateTask(@PathVariable UUID projectId, @PathVariable UUID itemId, @PathVariable UUID taskId,
                                  @Valid @RequestBody TaskUpdate update, Auth.Principal principal) {
        return work.updateTask(principal.player().id(), projectId, itemId, taskId, update);
    }

    @PostMapping("/me/projects/{projectId}/work/items/{itemId}/tasks/{taskId}/retry")
    @Auth.Authenticated
    @Auth.ServiceCall
    public ItemPayload retryTask(@PathVariable UUID projectId, @PathVariable UUID itemId, @PathVariable UUID taskId,
                                 Auth.Principal principal) {
        return work.retryTask(principal.player().id(), projectId, itemId, taskId);
    }

    @DeleteMapping("/me/projects/{projectId}/work/items/{itemId}/tasks/{taskId}")
    @Auth.Authenticated
    @Auth.ServiceCall
    public ItemPayload deleteTask(@PathVariable UUID projectId, @PathVariable UUID itemId, @PathVariable UUID taskId,
                                  Auth.Principal principal) {
        return work.deleteTask(principal.player().id(), projectId, itemId, taskId);
    }

    /**
     * Starts or resumes the item. The runner acts with the player's factory credential, not this
     * sign-in, so signing out of the device that pressed Start does not stop it.
     */
    @PostMapping("/me/projects/{projectId}/work/items/{itemId}/start")
    @Auth.Authenticated
    @Auth.ServiceCall
    public ItemPayload start(@PathVariable UUID projectId, @PathVariable UUID itemId, Auth.Principal principal) {
        return work.start(principal.player().id(), projectId, itemId);
    }

    /** No task starts or auto-merges while paused; live sessions still save their work and results. */
    @PostMapping("/me/projects/{projectId}/work/items/{itemId}/pause")
    @Auth.Authenticated
    @Auth.ServiceCall
    public ItemPayload pause(@PathVariable UUID projectId, @PathVariable UUID itemId, Auth.Principal principal) {
        return work.pause(principal.player().id(), projectId, itemId);
    }

    @GetMapping("/me/projects/{projectId}/work/issues")
    @Auth.Authenticated
    @Auth.ServiceCall
    public IssuesPayload issues(@PathVariable UUID projectId, Auth.Principal principal) {
        return issues.list(principal.player().id(), projectId);
    }

    /** Opens a defect or debt issue and, with it, the item that runs it. */
    @PostMapping("/me/projects/{projectId}/work/issues")
    @Auth.Authenticated
    @Auth.ServiceCall
    @ResponseStatus(HttpStatus.CREATED)
    public IssueItemPayload createIssue(@PathVariable UUID projectId, @Valid @RequestBody IssueDraft draft,
                                        Auth.Principal principal) {
        return issues.create(principal.player().id(), projectId, draft);
    }

    @PatchMapping("/me/projects/{projectId}/work/issues/{number}")
    @Auth.Authenticated
    @Auth.ServiceCall
    public IssueItemPayload updateIssue(@PathVariable UUID projectId, @PathVariable int number,
                                        @Valid @RequestBody IssueUpdate update, Auth.Principal principal) {
        return issues.update(principal.player().id(), projectId, number, update);
    }

    /** The item that runs an issue, created from the issue the first time it is asked for. */
    @PutMapping("/me/projects/{projectId}/work/issues/{number}/item")
    @Auth.Authenticated
    @Auth.ServiceCall
    public ItemPayload issueItem(@PathVariable UUID projectId, @PathVariable int number, Auth.Principal principal) {
        return issues.item(principal.player().id(), projectId, number);
    }

    /** How the project's factory runs: the limits the runner applies to every item of the project. */
    @GetMapping("/me/projects/{projectId}/work/settings")
    @Auth.Authenticated
    @Auth.ServiceCall
    public WorkSettingsPayload settings(@PathVariable UUID projectId, Auth.Principal principal) {
        return work.settings(principal.player().id(), projectId);
    }

    @PutMapping("/me/projects/{projectId}/work/settings")
    @Auth.Authenticated
    @Auth.ServiceCall
    public WorkSettingsPayload saveSettings(@PathVariable UUID projectId, @Valid @RequestBody WorkSettingsPayload settings,
                                            Auth.Principal principal) {
        return work.saveSettings(principal.player().id(), projectId, settings);
    }

    // --- the runner, acting on its own ---

    @GetMapping("/me/projects/{projectId}/work/items/{itemId}/change-requests")
    @Auth.Authenticated
    @Auth.ServiceCall
    public List<ChangeRequestPayload> changeRequests(@PathVariable UUID projectId, @PathVariable UUID itemId,
                                                     Auth.Principal principal) {
        return work.changeRequests(principal.player().id(), projectId, itemId);
    }

    @PostMapping("/me/projects/{projectId}/work/items/{itemId}/change-requests")
    @Auth.Authenticated
    @Auth.ServiceCall
    @ResponseStatus(HttpStatus.CREATED)
    public ItemPayload requestChange(@PathVariable UUID projectId, @PathVariable UUID itemId,
                                      @Valid @RequestBody ChangeRequestDraft draft, Auth.Principal principal) {
        return work.requestChange(principal.player().id(), projectId, itemId, draft);
    }

    @PostMapping("/me/projects/{projectId}/work/items/{itemId}/change-requests/{changeId}/decision")
    @Auth.Authenticated
    @Auth.ServiceCall
    public ItemPayload decideChange(@PathVariable UUID projectId, @PathVariable UUID itemId,
                                     @PathVariable UUID changeId, @Valid @RequestBody ChangeDecision decision,
                                     Auth.Principal principal) {
        return work.decideChange(principal.player().id(), projectId, itemId, changeId, decision);
    }

    @PutMapping("/internal/work/items/{itemId}/change-requests/{changeId}")
    @Auth.ServiceOnly
    public RunnerItem changeProgress(@PathVariable UUID itemId, @PathVariable UUID changeId,
                                      @Valid @RequestBody ChangeProgress progress) {
        return work.changeProgress(itemId, changeId, progress);
    }

    @PostMapping("/internal/work/items/{itemId}/change-requests/{changeId}/operations")
    @Auth.ServiceOnly
    public RunnerItem stageChange(@PathVariable UUID itemId, @PathVariable UUID changeId,
                                   @Valid @RequestBody ChangeOperationDraft draft) {
        return work.stageChange(itemId, changeId, draft);
    }

    @PostMapping("/internal/work/change-requests/reconcile")
    @Auth.ServiceOnly
    public RunningPayload reconcileChanges(@RequestBody Set<UUID> liveSessions,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Set<UUID> advancingItems) {
        return work.reconcileChanges(liveSessions, advancingItems == null ? Set.of() : advancingItems);
    }

    @GetMapping("/internal/work/running")
    @Auth.ServiceOnly
    public RunningPayload running() {
        return work.running();
    }

    @PostMapping("/internal/projects/{projectId}/work/deployment")
    @Auth.ServiceOnly
    public WorkPayload recordDeployment(@PathVariable UUID projectId,
                                        @Valid @RequestBody DeploymentOutcome outcome) {
        return work.recordDeployment(projectId, outcome);
    }

    @PutMapping("/internal/work/tasks/{taskId}")
    @Auth.ServiceOnly
    public RunnerItem progress(@PathVariable UUID taskId, @Valid @RequestBody TaskProgress progress) {
        return work.progress(taskId, progress);
    }

    /**
     * Durable, revisioned recovery metadata for the runner; blobs remain in mic-sessions' private
     * store. A missing task is 404; attempt mismatch or stale revision is 409; service-only callers
     * provide no owner identifiers, which are derived from the task's project relationship.
     */
    @PutMapping("/internal/work/tasks/{taskId}/recovery")
    @Auth.ServiceOnly
    public TaskRecoveryPayload saveRecovery(@PathVariable UUID taskId,
                                            @Valid @RequestBody TaskRecoveryUpdate update) {
        return work.saveRecovery(taskId, update);
    }

    /** Reads only the requested attempt; unknown tasks and absent attempts both return 404. */
    @GetMapping("/internal/work/tasks/{taskId}/recovery")
    @Auth.ServiceOnly
    public TaskRecoveryPayload recovery(@PathVariable UUID taskId,
                                        @org.springframework.web.bind.annotation.RequestParam int attempt) {
        return work.recovery(taskId, attempt);
    }

    /** A failed QA run: the defect it found becomes a fix task, and the QA waits for it to run again. */
    @PostMapping("/internal/work/tasks/{taskId}/fix")
    @Auth.ServiceOnly
    public RunnerItem fix(@PathVariable UUID taskId, @Valid @RequestBody FixDraft draft) {
        return work.reportDefect(taskId, draft);
    }

    @PutMapping("/internal/work/items/{itemId}/status")
    @Auth.ServiceOnly
    public RunnerItem itemStatus(@PathVariable UUID itemId, @Valid @RequestBody ItemStatusChange change) {
        return work.itemStatus(itemId, change.status(), change.pauseReason(), change.authSessionId());
    }

    // --- application ---

    @Service
    @RequiredArgsConstructor
    public static class WorkService {

        static final int MAX_TASKS = 80;
        private static final String ISSUE_CLOSED_REPORT =
                "Cancelled because the GitHub issue was closed before this task started.";
        private static final Set<TaskStatus> STARTED = Set.of(TaskStatus.running, TaskStatus.merging,
                TaskStatus.review, TaskStatus.done);

        private final WorkProjectRepository projects;
        private final WorkItemRepository items;
        private final WorkTaskRepository tasks;
        private final TaskRecoveryRepository recoveries;
        private final WorkQaDeliveryRepository qaDeliveries;
        private final WorkChangeRequestRepository changes;
        private final WorkSettingsRepository settings;
        private final IssueDefinitionRepository definitions;
        private final Auth.FactoryCredentials credentials;
        private final Clock clock;
        private final ObjectMapper json = JsonMapper.shared();

        @Transactional
        public List<ChangeRequestPayload> changeRequests(UUID playerId, UUID projectId, UUID itemId) {
            project(playerId, projectId);
            feature(lockedItem(projectId, itemId));
            return changes.findByItemIdOrderByCreatedAtAscIdAsc(itemId).stream().map(this::changePayload).toList();
        }

        @Transactional
        public ItemPayload requestChange(UUID playerId, UUID projectId, UUID itemId, ChangeRequestDraft draft) {
            project(playerId, projectId);
            WorkItem item = lockedItem(projectId, itemId);
            feature(item);
            requireOpen(item);
            WorkChangeRequest row = new WorkChangeRequest();
            row.setId(UUID.randomUUID());
            row.setItemId(itemId);
            row.setRequest(draft.request().strip());
            row.setStatus(ChangeStatus.queued);
            row.setProposal("{}");
            changes.save(row);
            return toPayload(touch(item), tasks.findByItemId(itemId));
        }

        @Transactional
        public ItemPayload decideChange(UUID playerId, UUID projectId, UUID itemId, UUID changeId,
                                         ChangeDecision decision) {
            project(playerId, projectId);
            WorkItem item = lockedItem(projectId, itemId);
            feature(item);
            WorkChangeRequest row = change(itemId, changeId);
            if (row.getStatus() != ChangeStatus.awaiting_confirmation) {
                throw conflict("This change is not waiting for a decision");
            }
            row.setStatus(decision.decision() == Decision.accept ? ChangeStatus.queued : ChangeStatus.rejected);
            row.setConfirmedAt(decision.decision() == Decision.accept ? OffsetDateTime.now(clock) : null);
            row.setFinishedAt(decision.decision() == Decision.reject ? OffsetDateTime.now(clock) : null);
            row.setSessionId(null);
            changes.save(row);
            List<WorkTask> all = tasks.findByItemId(itemId);
            completeIfReady(item, all);
            return toPayload(touch(item), all);
        }

        private void completeIfReady(WorkItem item, List<WorkTask> all) {
            if (!all.isEmpty() && all.stream().allMatch(task -> task.getStatus() == TaskStatus.done)
                    && !changes.existsByItemIdAndStatusIn(item.getId(), List.of(ChangeStatus.queued,
                            ChangeStatus.analyzing, ChangeStatus.awaiting_confirmation))) {
                item.setStatus(ItemStatus.done);
                item.setFinishedAt(OffsetDateTime.now(clock));
            }
        }

        @Transactional
        public RunningPayload reconcileChanges(Set<UUID> liveSessions, Set<UUID> advancingItems) {
            List<RunnerItem> changed = new ArrayList<>();
            // Include paused items and revoked callers: reconciliation needs no user's live grant.
            List<UUID> itemIds = changes.findItemIdsByStatus(ChangeStatus.analyzing);
            for (UUID itemId : itemIds) {
                // The concurrent runner may be between saving a lease and registering its session.
                // Its live-session snapshot cannot prove an in-flight item's analyst disappeared.
                if (advancingItems.contains(itemId)) continue;
                WorkItem item = items.findLockedById(itemId).orElse(null);
                if (item == null) continue;
                for (WorkChangeRequest row : changes.findByItemIdOrderByCreatedAtAscIdAsc(itemId)) {
                    if (row.getStatus() != ChangeStatus.analyzing || liveSessions.contains(row.getSessionId())) continue;
                    row.setStatus(ChangeStatus.queued);
                    row.setSessionId(null);
                    row.setAnalysis("Analyst session ended; queued for a fresh analysis");
                    changes.save(row);
                    changed.add(runnerItem(touch(item), tasks.findByItemId(itemId)));
                }
            }
            return new RunningPayload(changed);
        }

        private void feature(WorkItem item) {
            if (item.getKind() != ItemKind.feature) throw conflict("Only features accept change requests");
        }

        private WorkChangeRequest change(UUID itemId, UUID changeId) {
            return changes.findById(changeId).filter(row -> row.getItemId().equals(itemId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Change request not found"));
        }

        private Map<String, Object> proposal(WorkChangeRequest row) {
            return new LinkedHashMap<>(json.readValue(row.getProposal(), new TypeReference<Map<String, Object>>() {}));
        }

        private List<ChangeOperation> operations(WorkChangeRequest row) {
            Object value = proposal(row).getOrDefault("operations", List.of());
            return json.convertValue(value, new TypeReference<List<ChangeOperation>>() {});
        }

        private void editable(List<WorkTask> all, UUID taskId) {
            WorkTask task = find(all, taskId);
            if (task.getStatus() != TaskStatus.planned && task.getStatus() != TaskStatus.queued) {
                throw conflict("An analyst may only change planned or queued tasks; add a delta task instead");
            }
        }

        private void validateOperation(List<WorkTask> all, ChangeOperation op) {
            switch (op.kind()) {
                case spec -> {
                    if (op.item() == null || op.item().spec() == null || op.item().title() != null
                            || op.item().dependsOn() != null) throw badRequest("Provide only the spec");
                }
                case add_task -> {
                    if (op.draft() == null) throw badRequest("A task draft is required");
                }
                case update_task, delete_task -> {
                    if (op.taskId() == null) throw badRequest("A task id is required");
                    editable(all, op.taskId());
                    if (op.kind() == ChangeOperationKind.update_task && op.task() == null) {
                        throw badRequest("A task update is required");
                    }
                    WorkTask owner = find(all, op.taskId());
                    if (op.kind() == ChangeOperationKind.delete_task && owner.getQaOfId() != null) {
                        editable(all, owner.getQaOfId());
                    }
                    if (op.kind() == ChangeOperationKind.delete_task
                            || (op.task() != null && Boolean.FALSE.equals(op.task().tests()) && owner.isTests())) {
                        Set<UUID> removed = new HashSet<>(Set.of(op.taskId()));
                        all.stream().filter(task -> op.taskId().equals(task.getQaOfId()))
                                .forEach(task -> { editable(all, task.getId()); removed.add(task.getId()); });
                        for (WorkTask task : all) {
                            if (!removed.contains(task.getId())
                                    && task.getDependsOn().stream().anyMatch(removed::contains)) editable(all, task.getId());
                        }
                    }
                }
            }
        }

        @Transactional
        public RunnerItem stageChange(UUID itemId, UUID changeId, ChangeOperationDraft draft) {
            WorkItem item = items.findLockedById(itemId).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
            WorkChangeRequest row = change(itemId, changeId);
            if (row.getStatus() != ChangeStatus.analyzing || !draft.sessionId().equals(row.getSessionId())) {
                throw conflict("This session is not the active analyst");
            }
            List<WorkTask> all = tasks.findByItemId(itemId);
            validateOperation(all, draft.operation());
            List<ChangeOperation> ops = new ArrayList<>(operations(row));
            if (ops.size() >= 160) throw badRequest("Too many proposed operations");
            ops.add(draft.operation());
            Map<String, Object> proposal = proposal(row);
            proposal.put("operations", ops);
            row.setProposal(json.writeValueAsString(proposal));
            changes.save(row);
            return runnerItem(touch(item), all);
        }

        @Transactional
        public RunnerItem changeProgress(UUID itemId, UUID changeId, ChangeProgress progress) {
            WorkItem item = items.findLockedById(itemId).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
            feature(item);
            WorkChangeRequest row = change(itemId, changeId);
            List<WorkTask> all = tasks.findByItemId(itemId);
            if ((progress.status() == ChangeStatus.applied || progress.status() == ChangeStatus.awaiting_confirmation)
                    && row.getStatus() == progress.status() && progress.sessionId().equals(row.getSessionId())) {
                return runnerItem(item, all); // A lost HTTP response must not apply the proposal twice.
            }
            if (progress.status() == ChangeStatus.analyzing) {
                requireOpen(item);
                if (item.getStatus() != ItemStatus.running || row.getStatus() != ChangeStatus.queued
                        || changes.existsByItemIdAndStatus(itemId, ChangeStatus.analyzing)
                        || all.stream().anyMatch(task -> task.getStatus() == TaskStatus.merging)) {
                    throw conflict("The feature is not at a safe analysis point");
                }
                row.setSessionId(progress.sessionId());
                Map<String, Object> proposal = proposal(row);
                proposal.put("operations", List.of());
                row.setProposal(json.writeValueAsString(proposal));
            } else {
                if (row.getStatus() != ChangeStatus.analyzing
                        || !progress.sessionId().equals(row.getSessionId())) {
                    throw conflict("This session is no longer the active analyst");
                }
                if (progress.status() == ChangeStatus.applied) {
                    UUID playerId = projects.findById(item.getProjectId()).orElseThrow().getPlayerId();
                    // All operations share this transaction and item lock, including validation against
                    // current task state. A failure rolls the entire proposal back.
                    for (ChangeOperation op : operations(row)) {
                        validateOperation(tasks.findByItemId(itemId), op);
                        switch (op.kind()) {
                            case spec -> updateItem(playerId, item.getProjectId(), itemId, op.item());
                            case add_task -> addTask(playerId, item.getProjectId(), itemId, op.draft());
                            case update_task -> updateTask(playerId, item.getProjectId(), itemId, op.taskId(), op.task());
                            case delete_task -> deleteTask(playerId, item.getProjectId(), itemId, op.taskId());
                        }
                    }
                    row.setFinishedAt(OffsetDateTime.now(clock));
                } else if (progress.status() != ChangeStatus.queued
                        && progress.status() != ChangeStatus.awaiting_confirmation) {
                    throw badRequest("Invalid analyst transition");
                }
                if (progress.analysis() != null) row.setAnalysis(progress.analysis().strip());
                if (progress.proposalText() != null) {
                    Map<String, Object> proposal = proposal(row);
                    proposal.put("text", progress.proposalText());
                    row.setProposal(json.writeValueAsString(proposal));
                }
                // A new scope proposal always requires a fresh explicit decision.
                if (progress.status() == ChangeStatus.awaiting_confirmation) row.setConfirmedAt(null);
                if (progress.status() == ChangeStatus.queued) row.setSessionId(null);
            }
            row.setStatus(progress.status());
            changes.save(row);
            all = tasks.findByItemId(itemId);
            completeIfReady(item, all);
            return runnerItem(touch(item), all);
        }

        private ChangeRequestPayload changePayload(WorkChangeRequest row) {
            return new ChangeRequestPayload(row.getId(), row.getItemId(), row.getRequest(), row.getStatus(),
                    row.getAnalysis(), proposal(row), row.getSessionId(), row.getConfirmedAt(), row.getCreatedAt(),
                    row.getUpdatedAt(), row.getFinishedAt());
        }

        @Transactional
        public WorkPayload recordDeployment(UUID projectId, DeploymentOutcome outcome) {
            if (!projects.existsById(projectId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
            }
            List<ItemPayload> changed = new ArrayList<>();
            // Stable lock order also serializes overlapping reconciliation requests.
            for (UUID id : items.findIdsByProjectId(projectId)) {
                WorkItem item = lockedItem(projectId, id);
                List<WorkTask> all = tasks.findByItemId(id);
                for (WorkTask task : all) {
                    if (task.getMergeSha() == null) continue;
                    boolean contained = outcome.containedTaskIds().contains(task.getId());
                    task.setInCurrentRelease(contained);
                    if (task.getQaOfId() == null && task.getStatus() == TaskStatus.done
                            && contained && task.getDeployedVersion() == null) {
                        task.setDeployedVersion(outcome.releaseTag());
                        task.setDeployedAt(outcome.deployedAt());
                    }
                }
                tasks.saveAll(all);
                recomputeDeployment(item, all);
                item.setUpdatedAt(OffsetDateTime.now(clock));
                items.save(item);
                changed.add(toPayload(item, all));
            }
            return new WorkPayload(changed);
        }

        private static void recomputeDeployment(WorkItem item, List<WorkTask> all) {
            List<WorkTask> development = all.stream().filter(task -> task.getQaOfId() == null).toList();
            item.setInCurrentRelease(development.isEmpty()
                    || development.stream().anyMatch(task -> task.getInCurrentRelease() == null) ? null
                    : development.stream().allMatch(task -> Boolean.TRUE.equals(task.getInCurrentRelease())));
            boolean deployed = item.getStatus() == ItemStatus.done && !development.isEmpty()
                    && development.stream().allMatch(task -> task.getDeployedVersion() != null);
            WorkTask latest = deployed ? development.stream()
                    .max(Comparator.comparing(WorkTask::getDeployedAt).thenComparing(WorkTask::getId)).orElseThrow() : null;
            item.setDeployedVersion(latest == null ? null : latest.getDeployedVersion());
            item.setDeployedAt(latest == null ? null : latest.getDeployedAt());
        }

        private static DeploymentPayload deployment(WorkItem item, List<WorkTask> all) {
            String state = item.getStatus() != ItemStatus.done ? "none"
                    : all.stream().anyMatch(task -> task.getQaOfId() == null
                            && task.getStatus() == TaskStatus.done && task.getMergeSha() == null) ? "unknown"
                    : item.getDeployedVersion() != null ? "deployed" : "pending";
            return new DeploymentPayload(state, item.getDeployedVersion(), item.getDeployedAt(), item.getInCurrentRelease());
        }

        @Transactional(readOnly = true)
        public WorkPayload items(UUID playerId, UUID projectId) {
            project(playerId, projectId);
            List<WorkItem> rows = items.findByProjectId(projectId);
            Map<UUID, List<WorkTask>> byItem = tasks.findByItemIdIn(rows.stream().map(WorkItem::getId).toList())
                    .stream().collect(Collectors.groupingBy(WorkTask::getItemId));
            return new WorkPayload(rows.stream()
                    .sorted(Comparator.comparing((WorkItem item) -> item.getStatus() == ItemStatus.done)
                            .thenComparing(WorkItem::getRank, Comparator.reverseOrder()))
                    .map(item -> toPayload(item, byItem.getOrDefault(item.getId(), List.of())))
                    .toList());
        }

        @Transactional
        public ItemPayload createFeature(UUID playerId, UUID projectId, FeatureDraft draft) {
            project(playerId, projectId);
            WorkItem item = newItem(projectId, ItemKind.feature, null, draft.title(), draft.spec());
            if (draft.dependsOn() != null) {
                item.setDependsOnId(otherItem(projectId, item.getId(), draft.dependsOn()));
            }
            item = items.save(item);
            List<WorkTask> created = new ArrayList<>();
            Map<String, WorkTask> byKey = new LinkedHashMap<>();
            for (TaskDraft task : draft.tasks()) {
                WorkTask row = newTask(item.getId(), task, byKey.keySet(), created.size());
                byKey.put(row.getKey(), row);
                created.add(row);
            }
            if (created.size() > MAX_TASKS) {
                throw badRequest("A feature holds at most " + MAX_TASKS + " tasks");
            }
            // Dependencies name keys, so a draft may point at a task written after it.
            for (int index = 0; index < draft.tasks().size(); index++) {
                created.get(index).setDependsOn(resolve(draft.tasks().get(index).dependsOn(), created,
                        created.get(index)));
            }
            List<WorkTask> all = new ArrayList<>(created);
            for (int index = 0; index < draft.tasks().size(); index++) {
                if (Boolean.TRUE.equals(draft.tasks().get(index).tests())) {
                    all.add(newQa(created.get(index), draft.tasks().get(index).qa(), all));
                }
            }
            requireAcyclic(all);
            tasks.saveAll(all);
            return toPayload(item, all);
        }

        @Transactional
        public ItemPayload updateItem(UUID playerId, UUID projectId, UUID itemId, ItemUpdate update) {
            project(playerId, projectId);
            WorkItem item = lockedItem(projectId, itemId);
            if (update.title() != null) {
                item.setTitle(requireText(update.title(), "title"));
            }
            if (update.spec() != null) {
                item.setSpec(update.spec().strip());
            }
            if (update.dependsOn() != null) {
                item.setDependsOnId(update.dependsOn().isBlank() ? null
                        : otherItem(projectId, itemId, parseId(update.dependsOn())));
            }
            items.save(item);
            return toPayload(item, tasks.findByItemId(itemId));
        }

        @Transactional
        public void deleteItem(UUID playerId, UUID projectId, UUID itemId) {
            project(playerId, projectId);
            WorkItem item = lockedItem(projectId, itemId);
            if (item.getStatus() == ItemStatus.running
                    || tasks.findByItemId(itemId).stream().anyMatch(task -> isActive(task.getStatus()))) {
                throw conflict("Pause it and let its running tasks finish before removing it");
            }
            items.delete(item);
        }

        @Transactional
        public ItemPayload addTask(UUID playerId, UUID projectId, UUID itemId, TaskDraft draft) {
            project(playerId, projectId);
            WorkItem item = lockedItem(projectId, itemId);
            requireOpen(item);
            List<WorkTask> all = new ArrayList<>(tasks.findByItemId(itemId));
            if (all.size() >= MAX_TASKS) {
                throw badRequest("A feature holds at most " + MAX_TASKS + " tasks");
            }
            WorkTask row = newTask(itemId, draft, keys(all), nextPosition(all));
            all.add(row);
            row.setDependsOn(resolve(draft.dependsOn(), all, row));
            if (Boolean.TRUE.equals(draft.tests())) {
                all.add(newQa(row, draft.qa(), all));
            }
            requireAcyclic(all);
            tasks.saveAll(all);
            return toPayload(touch(item), all);
        }

        @Transactional
        public ItemPayload updateTask(UUID playerId, UUID projectId, UUID itemId, UUID taskId, TaskUpdate update) {
            project(playerId, projectId);
            WorkItem item = lockedItem(projectId, itemId);
            List<WorkTask> all = new ArrayList<>(tasks.findByItemId(itemId));
            WorkTask task = find(all, taskId);
            if (STARTED.contains(task.getStatus())) {
                throw conflict("This task already started: its settings can no longer change");
            }
            if (update.title() != null) {
                task.setTitle(requireText(update.title(), "title"));
            }
            if (update.category() != null) {
                if ((update.category() == TaskCategory.qa) != (task.getQaOfId() != null)) {
                    throw badRequest("Only the QA task of a task with tests is a QA task");
                }
                task.setCategory(update.category());
            }
            if (update.description() != null) {
                task.setDescription(update.description().strip());
            }
            if (update.acceptance() != null) {
                task.setAcceptance(update.acceptance().strip());
            }
            if (update.assignment() != null) {
                assign(task, update.assignment());
            }
            if (update.alternatives() != null) {
                task.setAlternatives(writeAlternatives(update.alternatives()));
            }
            if (update.autoMerge() != null) {
                task.setAutoMerge(update.autoMerge());
            }
            if (update.dependsOn() != null) {
                task.setDependsOn(resolve(update.dependsOn(), all, task));
            }
            if (update.tests() != null && update.tests() != task.isTests()) {
                if (task.getQaOfId() != null) {
                    throw badRequest("A QA task is the test itself");
                }
                task.setTests(update.tests());
                Optional<WorkTask> qa = all.stream().filter(other -> taskId.equals(other.getQaOfId())).findFirst();
                if (update.tests() && qa.isEmpty()) {
                    all.add(newQa(task, null, all));
                } else if (!update.tests() && qa.isPresent()) {
                    if (STARTED.contains(qa.get().getStatus())) {
                        throw conflict("Its QA task already started");
                    }
                    all.remove(qa.get());
                    all.forEach(other -> other.getDependsOn().remove(qa.get().getId()));
                    tasks.delete(qa.get());
                }
            }
            requireAcyclic(all);
            tasks.saveAll(all);
            return toPayload(touch(item), all);
        }

        @Transactional
        public ItemPayload retryTask(UUID playerId, UUID projectId, UUID itemId, UUID taskId) {
            project(playerId, projectId);
            WorkItem item = lockedItem(projectId, itemId);
            requireOpen(item);
            List<WorkTask> all = new ArrayList<>(tasks.findByItemId(itemId));
            WorkTask task = find(all, taskId);
            if (task.getStatus() != TaskStatus.attention || task.getSessionId() != null) {
                throw conflict("Only a task waiting to retry can be restarted");
            }
            task.setStatus(TaskStatus.planned);
            task.setSessionId(null);
            task.setAttemptsBase(task.getAttempts());
            task.setReport(null);
            task.setStartedAt(null);
            task.setFinishedAt(null);
            tasks.save(task);
            return toPayload(touch(item), all);
        }

        @Transactional
        public ItemPayload deleteTask(UUID playerId, UUID projectId, UUID itemId, UUID taskId) {
            project(playerId, projectId);
            WorkItem item = lockedItem(projectId, itemId);
            List<WorkTask> all = new ArrayList<>(tasks.findByItemId(itemId));
            WorkTask task = find(all, taskId);
            Set<UUID> removed = new HashSet<>(Set.of(taskId));
            all.stream().filter(other -> taskId.equals(other.getQaOfId())).forEach(qa -> removed.add(qa.getId()));
            if (all.stream().anyMatch(other -> removed.contains(other.getId()) && STARTED.contains(other.getStatus()))) {
                throw conflict("A task that already started cannot be removed");
            }
            if (task.getQaOfId() != null) {
                all.stream().filter(owner -> owner.getId().equals(task.getQaOfId()))
                        .forEach(owner -> owner.setTests(false));
            }
            List<WorkTask> kept = all.stream().filter(other -> !removed.contains(other.getId())).toList();
            kept.forEach(other -> other.getDependsOn().removeAll(removed));
            tasks.saveAll(kept);
            tasks.deleteAll(all.stream().filter(other -> removed.contains(other.getId())).toList());
            return toPayload(touch(item), kept);
        }

        @Transactional
        public ItemPayload start(UUID playerId, UUID projectId, UUID itemId) {
            project(playerId, projectId);
            // Issued on every start and resume, so a revoked credential is replaced by a live one. First,
            // because issuing it clears the persistence context; a refused start rolls it back.
            UUID credential = credentials.factoryCredential(playerId);
            WorkItem item = lockedItem(projectId, itemId);
            List<WorkTask> all = tasks.findByItemId(itemId);
            if (definitionState(item) != DefinitionState.DEFINED || !executable(item)) {
                throw conflict("Accept this issue's definition before starting it");
            }
            if (item.getStatus() == ItemStatus.done) {
                throw conflict("This work is already done");
            }
            if (all.isEmpty()) {
                throw conflict("Break it into tasks before starting it");
            }
            // Only an explicit Start revives tasks cancelled by closing and reopening an issue.
            all.stream().filter(task -> task.getSessionId() == null
                            && (task.getStatus() == TaskStatus.attention
                                || (task.getStatus() == TaskStatus.done && ISSUE_CLOSED_REPORT.equals(task.getReport()))))
                    .forEach(task -> {
                        task.setStatus(TaskStatus.planned);
                        task.setAttemptsBase(task.getAttempts());
                        task.setReport(null);
                        task.setStartedAt(null);
                        task.setFinishedAt(null);
                    });
            tasks.saveAll(all);
            item.setStatus(ItemStatus.running);
            item.setPauseReason(null);
            item.setRunnerAuthSessionId(credential);
            item.setRunnerPlayerId(playerId);
            if (item.getStartedAt() == null) {
                item.setStartedAt(OffsetDateTime.now(clock));
            }
            items.save(item);
            return toPayload(item, all);
        }

        @Transactional
        public ItemPayload pause(UUID playerId, UUID projectId, UUID itemId) {
            project(playerId, projectId);
            WorkItem item = lockedItem(projectId, itemId);
            if (item.getStatus() == ItemStatus.running) {
                item.setStatus(ItemStatus.paused);
                item.setPauseReason(null);
                items.save(item);
            }
            return toPayload(item, tasks.findByItemId(itemId));
        }

        @Transactional(readOnly = true)
        public RunningPayload running() {
            // Paused items still need session adoption and durable result reconciliation after restarts.
            List<WorkItem> rows = items.findByStatusIn(List.of(ItemStatus.running, ItemStatus.paused));
            Map<UUID, List<WorkTask>> byItem = tasks.findByItemIdIn(rows.stream().map(WorkItem::getId).toList())
                    .stream().collect(Collectors.groupingBy(WorkTask::getItemId));
            List<UUID> projectIds = rows.stream().map(WorkItem::getProjectId).distinct().toList();
            Map<UUID, UUID> owners = projects.findAllById(projectIds)
                    .stream().collect(Collectors.toMap(WorkProject::getId, WorkProject::getPlayerId));
            Map<UUID, WorkSettingsPayload> limits = settings.findAllById(projectIds).stream()
                    .collect(Collectors.toMap(WorkSettings::getProjectId, WorkService::toPayload));
            return new RunningPayload(rows.stream()
                    .filter(item -> owners.containsKey(item.getProjectId()))
                    .filter(item -> definitionState(item) == DefinitionState.DEFINED && executable(item))
                    .map(item -> new RunnerItem(owners.get(item.getProjectId()), item.getRunnerAuthSessionId(),
                            limits.getOrDefault(item.getProjectId(), WorkSettingsPayload.DEFAULTS),
                            toPayload(item, byItem.getOrDefault(item.getId(), List.of()))))
                    .toList());
        }

        @Transactional(readOnly = true)
        public WorkSettingsPayload settings(UUID playerId, UUID projectId) {
            project(playerId, projectId);
            return settings.findById(projectId).map(WorkService::toPayload).orElse(WorkSettingsPayload.DEFAULTS);
        }

        @Transactional
        public WorkSettingsPayload saveSettings(UUID playerId, UUID projectId, WorkSettingsPayload payload) {
            project(playerId, projectId);
            WorkSettings row = settings.findById(projectId).orElseGet(() -> {
                WorkSettings created = new WorkSettings();
                created.setProjectId(projectId);
                return created;
            });
            row.setQuotaLimit(payload.quotaLimit());
            row.setMaxAttempts(payload.maxAttempts());
            row.setMaxQaRounds(payload.maxQaRounds());
            row.setMaxResolutions(payload.maxResolutions());
            row.setContextHandoffPercent(payload.contextHandoffPercent());
            row.setStallTurns(payload.stallTurns());
            return toPayload(settings.save(row));
        }

        private static WorkSettingsPayload toPayload(WorkSettings row) {
            return new WorkSettingsPayload(row.getQuotaLimit(), row.getMaxAttempts(), row.getMaxQaRounds(),
                    row.getMaxResolutions(), row.getContextHandoffPercent(), row.getStallTurns());
        }

        /**
         * The runner's account of one task. Timestamps follow the status; an item whose every task is
         * done is done too.
         */
        @Transactional
        public RunnerItem progress(UUID taskId, TaskProgress progress) {
            WorkTask task = tasks.findById(taskId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
            WorkItem item = items.findLockedById(task.getItemId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
            task = tasks.findLockedById(taskId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
            List<WorkTask> all = tasks.findByItemId(item.getId());
            if (definitionState(item) != DefinitionState.DEFINED || !executable(item)) {
                throw conflict("Accept this issue's definition before running its tasks");
            }
            if (item.getStatus() == ItemStatus.done) {
                return runnerItem(item, all);
            }
            task = find(all, taskId);
            // A stale runner snapshot must not launch a paused item or revive a cancelled task.
            if (task.getStatus() == TaskStatus.done
                    || (task.getSessionId() == null && (item.getStatus() != ItemStatus.running
                        || changes.existsByItemIdAndStatus(item.getId(), ChangeStatus.analyzing)))) {
                return runnerItem(item, all);
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (progress.status() == TaskStatus.running && task.getStatus() != TaskStatus.running) {
                task.setStartedAt(now);
                task.setFinishedAt(null);
            }
            if (progress.status() == TaskStatus.done && task.getStatus() != TaskStatus.done) {
                task.setFinishedAt(now);
            }
            UUID previousSessionId = task.getSessionId();
            task.setStatus(progress.status());
            task.setSessionId(progress.sessionId());
            // A stale runner snapshot must not rewind the attempt its recovery evidence is keyed by.
            task.setAttempts(Math.max(task.getAttempts(), progress.attempts()));
            task.setReport(progress.report());
            if (progress.status() == TaskStatus.running && task.getQaOfId() != null
                    && !java.util.Objects.equals(previousSessionId, progress.sessionId())) {
                task.setQaReport(null);
            } else if (progress.qaReport() != null) {
                task.setQaReport(json.writeValueAsString(progress.qaReport()));
            }
            // Only a successful development merge supplies this; later progress must never clear it.
            if (progress.status() == TaskStatus.done && task.getQaOfId() == null
                    && task.getMergeSha() == null && progress.mergeSha() != null) {
                task.setMergeSha(progress.mergeSha());
            }
            if (progress.status() == TaskStatus.done && task.getQaOfId() == null
                    && progress.qaHandoff() != null && task.getQaHandoff() == null) {
                task.setQaHandoff(json.writeValueAsString(progress.qaHandoff()));
            }
            if (progress.status() == TaskStatus.done) {
                for (TaskRecovery recovery : recoveries.findByTaskId(taskId)) {
                    recovery.setSnapshotValid(false);
                    recovery.setValidityReason("Task finalized; recovery bundle retired");
                    recovery.setUpdatedAt(now);
                }
                recoveries.flush();
            }
            if (progress.assignment() != null) {
                assign(task, progress.assignment());
            }
            tasks.save(task);
            if (all.stream().allMatch(other -> other.getStatus() == TaskStatus.done)
                    && !changes.existsByItemIdAndStatusIn(item.getId(), List.of(ChangeStatus.queued,
                            ChangeStatus.analyzing, ChangeStatus.awaiting_confirmation))) {
                item.setStatus(ItemStatus.done);
                item.setFinishedAt(now);
            }
            items.save(touch(item));
            return runnerItem(item, all);
        }

        /**
         * Store one task attempt's compact handoff/result only after validating the task/item/project
         * chain. A compare-and-set revision prevents a delayed session from replacing newer recovery
         * evidence. The bundle itself is never sent to this service or persisted in the repository.
         */
        @Transactional
        public TaskRecoveryPayload saveRecovery(UUID taskId, TaskRecoveryUpdate update) {
            WorkTask task = tasks.findLockedById(taskId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
            WorkItem item = items.findLockedById(task.getItemId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
            WorkProject project = projects.findById(item.getProjectId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
            if (task.getStatus() == TaskStatus.done) {
                throw conflict("A completed task cannot accept new recovery evidence");
            }
            if (update.attempt() != task.getAttempts()) {
                throw conflict("Recovery attempt does not match the task's current attempt");
            }
            if (update.files() != null && update.files().stream().anyMatch(path ->
                    path == null || path.isBlank() || path.startsWith("/") || path.startsWith("-")
                            || path.contains("\\") || path.contains("\0")
                            || List.of(path.split("/")).stream().anyMatch(part ->
                                    part.isBlank() || part.equals(".") || part.equals("..") || part.equals(".git")))) {
                throw badRequest("Recovery contains an unsafe file path");
            }
            TaskRecovery row = recoveries.findLockedByTaskIdAndAttempt(taskId, update.attempt()).orElse(null);
            String currentRevision = row == null ? null : row.getSnapshotRevision();
            if (row != null && currentRevision.equals(update.snapshotRevision())) {
                TaskRecoveryPayload existing = toRecoveryPayload(row);
                if (sameRecovery(existing, update)) {
                    return existing;
                }
                if (java.util.Objects.equals(currentRevision, update.previousRevision())
                        && sameSnapshot(existing, update)) {
                    row.setReportedOutcome(update.reportedOutcome());
                    row.setReportedTitle(update.reportedTitle());
                    row.setReportedSummary(update.reportedSummary());
                    row.setReportedPreparation(update.reportedPreparation());
                    row.setUpdatedAt(OffsetDateTime.now(clock));
                    return toRecoveryPayload(recoveries.save(row));
                }
                throw conflict("Recovery revision already exists with different metadata");
            }
            if (!java.util.Objects.equals(currentRevision, update.previousRevision())) {
                throw conflict("Recovery evidence changed; reload its current revision before saving");
            }
            if (row == null) {
                row = new TaskRecovery();
                row.setId(UUID.randomUUID());
                row.setTaskId(taskId);
                row.setAttempt(update.attempt());
            }
            row.setPlayerId(project.getPlayerId());
            row.setProjectId(project.getId());
            row.setItemId(item.getId());
            row.setBranch(update.branch());
            row.setBaseCommit(update.baseCommit());
            row.setHeadCommit(update.headCommit());
            row.setSnapshotRevision(update.snapshotRevision());
            row.setSummary(update.summary());
            row.setNextStep(update.nextStep());
            row.setFiles(json.writeValueAsString(update.files() == null ? List.of() : update.files()));
            row.setVerifications(json.writeValueAsString(update.verifications() == null ? List.of() : update.verifications()));
            row.setObstacles(json.writeValueAsString(update.obstacles() == null ? List.of() : update.obstacles()));
            row.setReportedOutcome(update.reportedOutcome());
            row.setReportedTitle(update.reportedTitle());
            row.setReportedSummary(update.reportedSummary());
            row.setReportedPreparation(update.reportedPreparation());
            row.setSnapshotValid(update.snapshotValid());
            row.setValidityReason(update.validityReason());
            row.setUpdatedAt(OffsetDateTime.now(clock));
            return toRecoveryPayload(recoveries.save(row));
        }

        @Transactional(readOnly = true)
        public TaskRecoveryPayload recovery(UUID taskId, int attempt) {
            if (attempt < 0) {
                throw badRequest("Recovery attempt must not be negative");
            }
            // Checking the task first avoids exposing whether a recovery row exists for an unknown id.
            tasks.findById(taskId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
            return recoveries.findByTaskIdAndAttempt(taskId, attempt).map(WorkService::toRecoveryPayload)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recovery evidence not found"));
        }

        private static boolean sameRecovery(TaskRecoveryPayload existing, TaskRecoveryUpdate update) {
            return existing.attempt() == update.attempt()
                    && existing.branch().equals(update.branch())
                    && existing.baseCommit().equals(update.baseCommit())
                    && existing.headCommit().equals(update.headCommit())
                    && existing.summary().equals(update.summary() == null ? "" : update.summary())
                    && existing.nextStep().equals(update.nextStep() == null ? "" : update.nextStep())
                    && existing.files().equals(update.files() == null ? List.of() : update.files())
                    && existing.verifications().equals(update.verifications() == null ? List.of() : update.verifications())
                    && existing.obstacles().equals(update.obstacles() == null ? List.of() : update.obstacles())
                    && java.util.Objects.equals(existing.reportedOutcome(), update.reportedOutcome())
                    && java.util.Objects.equals(existing.reportedTitle(), update.reportedTitle())
                    && java.util.Objects.equals(existing.reportedSummary(), update.reportedSummary())
                    && java.util.Objects.equals(existing.reportedPreparation(), update.reportedPreparation())
                    && existing.snapshotValid() == update.snapshotValid()
                    && java.util.Objects.equals(existing.validityReason(), update.validityReason());
        }

        private static boolean sameSnapshot(TaskRecoveryPayload existing, TaskRecoveryUpdate update) {
            return existing.attempt() == update.attempt()
                    && existing.branch().equals(update.branch())
                    && existing.baseCommit().equals(update.baseCommit())
                    && existing.headCommit().equals(update.headCommit())
                    && existing.snapshotRevision().equals(update.snapshotRevision())
                    && existing.summary().equals(update.summary())
                    && existing.nextStep().equals(update.nextStep())
                    && existing.files().equals(update.files() == null ? List.of() : update.files())
                    && existing.verifications().equals(update.verifications() == null ? List.of() : update.verifications())
                    && existing.obstacles().equals(update.obstacles() == null ? List.of() : update.obstacles())
                    && existing.snapshotValid() == update.snapshotValid()
                    && java.util.Objects.equals(existing.validityReason(), update.validityReason());
        }

        private static TaskRecoveryPayload toRecoveryPayload(TaskRecovery row) {
            return new TaskRecoveryPayload(row.getTaskId(), row.getAttempt(), row.getBranch(), row.getBaseCommit(),
                    row.getHeadCommit(), row.getSnapshotRevision(), row.getSummary(), row.getNextStep(),
                    readStringList(row.getFiles()), readStringList(row.getVerifications()),
                    readStringList(row.getObstacles()), row.getReportedOutcome(), row.getReportedTitle(),
                    row.getReportedSummary(), row.getReportedPreparation(), row.isSnapshotValid(),
                    row.getValidityReason(), row.getUpdatedAt());
        }

        private static List<String> readStringList(String value) {
            try {
                return JsonMapper.shared().readValue(value == null ? "[]" : value, new TypeReference<List<String>>() {});
            } catch (RuntimeException exception) {
                return List.of();
            }
        }

        @Transactional
        public RunnerItem reportDefect(UUID qaTaskId, FixDraft draft) {
            WorkTask found = tasks.findById(qaTaskId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
            WorkItem item = items.findLockedById(found.getItemId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
            List<WorkTask> all = new ArrayList<>(tasks.findByItemId(item.getId()));
            if (item.getStatus() == ItemStatus.done) {
                return runnerItem(item, all);
            }
            WorkTask qa = find(all, qaTaskId);
            if (qa.getQaOfId() == null) {
                throw badRequest("Only a QA task reports defects");
            }
            String serialized = json.writeValueAsString(draft);
            QaOutcome outcome = draft.outcome() == null ? QaOutcome.defect : draft.outcome();
            String deliveryId = draft.deliveryId() == null
                    ? UUID.nameUUIDFromBytes((qaTaskId + "|" + outcome + "|" + serialized)
                            .getBytes(StandardCharsets.UTF_8)).toString()
                    : draft.deliveryId();
            if (qaDeliveries.existsByQaTaskIdAndDeliveryId(qaTaskId, deliveryId)) {
                return runnerItem(item, all);
            }
            WorkTask tested = find(all, qa.getQaOfId());
            String summary = draft.summary();
            if (summary == null || summary.isBlank()) {
                summary = draft.blockerCause() != null ? draft.blockerCause()
                        : draft.description() != null ? draft.description()
                        : draft.findings() == null ? "QA completed" : draft.findings().stream()
                                .map(FindingDraft::title).filter(value -> value != null && !value.isBlank())
                                .collect(Collectors.joining("; "));
            }
            summary = requireText(summary, "summary");
            qa.setQaReport(serialized);
            qa.setReport(outcome == QaOutcome.blocked
                    ? "QA blocked (" + (draft.blockerCategory() == null ? "unspecified" : draft.blockerCategory())
                            + "): " + (draft.blockerCause() == null ? summary : draft.blockerCause())
                    : summary);
            WorkQaDelivery delivery = new WorkQaDelivery();
            delivery.setId(UUID.randomUUID());
            delivery.setQaTaskId(qaTaskId);
            delivery.setDeliveryId(deliveryId);
            delivery.setOutcome(outcome);
            delivery.setPayload(serialized);
            qaDeliveries.save(delivery);

            OffsetDateTime now = OffsetDateTime.now(clock);
            if (outcome == QaOutcome.blocked) {
                qa.setStatus(TaskStatus.attention);
                qa.setSessionId(null);
                qa.setFinishedAt(null);
            } else if (outcome == QaOutcome.passed) {
                qa.setStatus(TaskStatus.done);
                qa.setSessionId(null);
                qa.setFinishedAt(now);
                if (all.stream().allMatch(task -> task.getStatus() == TaskStatus.done || task == qa)
                        && !changes.existsByItemIdAndStatusIn(item.getId(), List.of(ChangeStatus.queued,
                                ChangeStatus.analyzing, ChangeStatus.awaiting_confirmation))) {
                    item.setStatus(ItemStatus.done);
                    item.setFinishedAt(now);
                }
            } else {
                int maxQaRounds = settings.findById(item.getProjectId()).map(WorkSettings::getMaxQaRounds)
                        .orElse(WorkSettingsPayload.DEFAULTS.maxQaRounds());
                if (qa.getQaRounds() >= maxQaRounds) {
                    qa.setStatus(TaskStatus.attention);
                    qa.setSessionId(null);
                    qa.setFinishedAt(null);
                    qa.setReport("QA still fails after " + qa.getQaRounds() + " correction rounds: " + summary);
                } else {
                    List<FindingDraft> findings = draft.findings() == null ? List.of() : draft.findings();
                    if (findings.isEmpty()) {
                        findings = List.of(new FindingDraft("legacy", requireText(draft.title(), "title"),
                                FindingPriority.high, qa.getAcceptance().isBlank() ? summary : qa.getAcceptance(),
                                draft.description() == null ? summary : draft.description(),
                                qa.getAcceptance().isBlank() ? "The reported behaviour matches the acceptance criteria"
                                        : qa.getAcceptance(),
                                summary, ""));
                    }
                    if (findings.stream().map(FindingDraft::id).distinct().count() != findings.size()) {
                        throw badRequest("Finding ids must be unique within a delivery");
                    }
                    if (all.size() + findings.size() > MAX_TASKS) {
                        qa.setStatus(TaskStatus.attention);
                        qa.setSessionId(null);
                        qa.setFinishedAt(null);
                        qa.setReport("QA found " + findings.size()
                                + " in-scope defects, but the feature task limit prevents creating all fixes");
                        tasks.saveAll(all);
                        items.save(touch(item));
                        return runnerItem(item, all);
                    }
                    Set<String> existingKeys = keys(all);
                    UUID previousFixId = tested.getId();
                    for (FindingDraft finding : findings.stream()
                            .sorted(Comparator.comparingInt(finding -> finding.priority().ordinal())).toList()) {
                        String keySuffix = UUID.nameUUIDFromBytes((deliveryId + "|" + finding.id())
                                .getBytes(StandardCharsets.UTF_8)).toString().replace("-", "").substring(0, 12);
                        WorkTask fix = new WorkTask();
                        fix.setId(UUID.randomUUID());
                        fix.setItemId(item.getId());
                        fix.setKey(uniqueKey(("fix-" + tested.getKey() + "-" + keySuffix).substring(0, 64), existingKeys));
                        existingKeys.add(fix.getKey());
                        fix.setTitle(requireText("Fix: " + finding.title(), "title"));
                        fix.setCategory(TaskCategory.dev);
                        fix.setDescription(findingDescription(finding));
                        fix.setFindingId(finding.id());
                        fix.setAcceptance(findingAcceptance(finding));
                        fix.setProvider(tested.getProvider());
                        fix.setConnectionId(tested.getConnectionId());
                        fix.setModel(tested.getModel());
                        fix.setEffort(tested.getEffort());
                        fix.setAlternatives(tested.getAlternatives());
                        fix.setAutoMerge(tested.isAutoMerge());
                        fix.setTests(false);
                        fix.setFixOfId(qa.getId());
                        fix.setStatus(TaskStatus.planned);
                        fix.setPosition(qa.getPosition());
                        fix.setDependsOn(new LinkedHashSet<>(Set.of(previousFixId)));
                        all.add(fix);
                        qa.getDependsOn().add(fix.getId());
                        previousFixId = fix.getId();
                    }
                    qa.setQaRounds(qa.getQaRounds() + 1);
                    qa.setStatus(TaskStatus.planned);
                    qa.setSessionId(null);
                    qa.setStartedAt(null);
                    qa.setFinishedAt(null);
                }
            }
            tasks.saveAll(all);
            items.save(touch(item));
            return runnerItem(item, all);
        }

        private static String findingAcceptance(FindingDraft finding) {
            return "Acceptance criterion:\n" + finding.criterion() + "\n\n"
                    + "Expected:\n" + finding.expected() + "\n\n"
                    + "Reproduction:\n" + finding.reproduction();
        }

        private static String findingDescription(FindingDraft finding) {
            return "QA finding (" + finding.priority() + ")\n\n"
                    + "Acceptance criterion:\n" + finding.criterion() + "\n\n"
                    + "Reproduction:\n" + finding.reproduction() + "\n\n"
                    + "Expected:\n" + finding.expected() + "\n\n"
                    + "Observed:\n" + finding.observed() + "\n\n"
                    + "Redacted evidence:\n" + (finding.evidence() == null ? "" : finding.evidence());
        }

        @Transactional
        public RunnerItem itemStatus(UUID itemId, ItemStatus status, String pauseReason, UUID authSessionId) {
            WorkItem item = items.findLockedById(itemId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
            if (item.getStatus() == ItemStatus.done
                    || (authSessionId != null && !authSessionId.equals(item.getRunnerAuthSessionId()))) {
                return runnerItem(item, tasks.findByItemId(itemId));
            }
            if (status == ItemStatus.running && (definitionState(item) != DefinitionState.DEFINED || !executable(item))) {
                throw conflict("Accept this issue's definition before running it");
            }
            item.setStatus(status);
            item.setPauseReason(status == ItemStatus.paused && pauseReason != null && !pauseReason.isBlank()
                    ? pauseReason.strip() : null);
            if (status == ItemStatus.done) {
                item.setFinishedAt(OffsetDateTime.now(clock));
            }
            recomputeDeployment(item, tasks.findByItemId(itemId));
            items.save(item);
            return runnerItem(item, tasks.findByItemId(itemId));
        }

        // --- shared with the issue side ---

        /** Closing is terminal for pending work, including rows left behind by older versions. */
        @Transactional
        public ItemPayload reconcileIssue(UUID itemId, Github.Issue issue) {
            return reconcileIssue(itemId, issue, false);
        }

        @Transactional
        public ItemPayload reconcileIssue(UUID itemId, Github.Issue issue, boolean explicitlyReopened) {
            WorkItem item = items.findLockedById(itemId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
            List<WorkTask> all = tasks.findByItemId(itemId);
            item.setTitle(requireText(issue.title(), "title"));
            item.setSpec(issue.body());
            if ("closed".equals(issue.state())) {
                OffsetDateTime now = OffsetDateTime.now(clock);
                item.setIssueClosed(true);
                item.setStatus(ItemStatus.done);
                if (item.getFinishedAt() == null) {
                    item.setFinishedAt(issue.closedAt() == null ? now : issue.closedAt());
                }
                for (WorkTask task : all) {
                    // Attached sessions finish normally; their late reports cannot revive this item.
                    if (task.getSessionId() == null && (task.getStatus() == TaskStatus.planned
                            || task.getStatus() == TaskStatus.queued || task.getStatus() == TaskStatus.attention)) {
                        task.setStatus(TaskStatus.done);
                        task.setReport(ISSUE_CLOSED_REPORT);
                        task.setFinishedAt(now);
                    }
                }
                tasks.saveAll(all);
            } else if (item.isIssueClosed() || (explicitlyReopened && item.getStatus() == ItemStatus.done)) {
                item.setIssueClosed(false);
                item.setStatus(ItemStatus.planned);
                item.setFinishedAt(null);
                // Pending tasks stay cancelled until the player explicitly starts this item.
            }
            recomputeDeployment(item, all);
            return toPayload(items.save(item), all);
        }

        public WorkItem newItem(UUID projectId, ItemKind kind, Integer issueNumber, String title, String spec) {
            WorkItem item = new WorkItem();
            item.setId(UUID.randomUUID());
            item.setProjectId(projectId);
            item.setKind(kind);
            item.setIssueNumber(issueNumber);
            item.setTitle(requireText(title, "title"));
            item.setSpec(spec == null ? "" : spec.strip());
            item.setStatus(ItemStatus.planned);
            item.setRank(items.findTopByProjectIdOrderByRankDesc(projectId).map(WorkItem::getRank).orElse(0L) + 1);
            return item;
        }

        /** The one development task an issue runs as, unless the draft names its settings. */
        public List<WorkTask> issueTasks(WorkItem item, Github.Issue issue, TaskSettings settings) {
            // Manually created defects and debt should proceed without merge review or a separate QA
            // task by default. Explicit issue settings still take precedence.
            Boolean autoMerge = settings == null || settings.autoMerge() == null ? true : settings.autoMerge();
            Boolean tests = settings == null || settings.tests() == null ? false : settings.tests();
            TaskDraft draft = new TaskDraft(item.getKind() == ItemKind.defect ? "fix" : "work",
                    issue.title(), TaskCategory.dev,
                    "GitHub issue #" + issue.number() + ": " + issue.title() + "\n\n" + issue.body(),
                    settings == null ? null : settings.acceptance(),
                    settings == null ? null : settings.assignment(),
                    settings == null ? null : settings.alternatives(),
                    autoMerge,
                    tests,
                    settings == null ? null : settings.qa(), null);
            List<WorkTask> all = new ArrayList<>();
            WorkTask task = newTask(item.getId(), draft, Set.of(), 0);
            all.add(task);
            if (task.isTests()) {
                all.add(newQa(task, draft.qa(), all));
            }
            tasks.saveAll(all);
            return all;
        }

        public ItemPayload payload(WorkItem item) {
            return toPayload(item, tasks.findByItemId(item.getId()));
        }

        public WorkProject project(UUID playerId, UUID projectId) {
            return projects.findByIdAndPlayerId(projectId, playerId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        }

        // --- internals ---

        private WorkItem lockedItem(UUID projectId, UUID itemId) {
            return items.findLockedById(itemId).filter(item -> item.getProjectId().equals(projectId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
        }

        private UUID otherItem(UUID projectId, UUID itemId, UUID dependsOn) {
            if (dependsOn.equals(itemId)) {
                throw badRequest("An item cannot depend on itself");
            }
            WorkItem other = items.findById(dependsOn).filter(item -> item.getProjectId().equals(projectId))
                    .orElseThrow(() -> badRequest("The item it depends on is not part of this project"));
            // Items chain at most one hop each way: following the other one back must never reach this one.
            Set<UUID> seen = new HashSet<>();
            for (WorkItem step = other; step != null && seen.add(step.getId());
                 step = step.getDependsOnId() == null ? null : items.findById(step.getDependsOnId()).orElse(null)) {
                if (step.getId().equals(itemId)) {
                    throw badRequest("These items would depend on each other");
                }
            }
            return dependsOn;
        }

        private void requireOpen(WorkItem item) {
            if (item.getStatus() == ItemStatus.done) {
                throw conflict("This work is already done");
            }
        }

        private WorkTask newTask(UUID itemId, TaskDraft draft, Set<String> taken, int position) {
            if (draft.category() == TaskCategory.qa) {
                throw badRequest("QA tasks are added by turning tests on for the task they check");
            }
            WorkTask task = new WorkTask();
            task.setId(UUID.randomUUID());
            task.setItemId(itemId);
            String key = draft.key() == null || draft.key().isBlank() ? slug(draft.title()) : slug(draft.key());
            if (draft.key() != null && !draft.key().isBlank() && taken.contains(key)) {
                throw badRequest("Two tasks share the key \"" + key + "\"");
            }
            task.setKey(uniqueKey(key, taken));
            task.setTitle(requireText(draft.title(), "title"));
            task.setCategory(draft.category());
            task.setDescription(draft.description() == null ? "" : draft.description().strip());
            task.setAcceptance(draft.acceptance() == null ? "" : draft.acceptance().strip());
            if (draft.assignment() != null) {
                assign(task, draft.assignment());
            }
            task.setAlternatives(writeAlternatives(draft.alternatives() == null ? List.of() : draft.alternatives()));
            task.setAutoMerge(draft.autoMerge() == null || draft.autoMerge());
            task.setTests(Boolean.TRUE.equals(draft.tests()));
            task.setStatus(TaskStatus.planned);
            task.setPosition(position);
            task.setDependsOn(new LinkedHashSet<>());
            return task;
        }

        /** The QA task of {@code tested}: right after it, blocked by it, checking its acceptance criteria. */
        private WorkTask newQa(WorkTask tested, QaDraft draft, List<WorkTask> all) {
            WorkTask qa = new WorkTask();
            qa.setId(UUID.randomUUID());
            qa.setItemId(tested.getItemId());
            qa.setKey(uniqueKey(tested.getKey() + "-qa", keys(all)));
            qa.setTitle(requireText("QA: " + tested.getTitle(), "title"));
            qa.setCategory(TaskCategory.qa);
            qa.setDescription(draft != null && draft.description() != null && !draft.description().isBlank()
                    ? draft.description().strip()
                    : "Check \"" + tested.getTitle() + "\" on the default branch, with everything merged, through Playwright.");
            qa.setAcceptance(draft != null && draft.acceptance() != null && !draft.acceptance().isBlank()
                    ? draft.acceptance().strip() : tested.getAcceptance());
            if (draft != null && draft.assignment() != null) {
                assign(qa, draft.assignment());
            }
            qa.setAlternatives(writeAlternatives(List.of()));
            qa.setAutoMerge(false);
            qa.setTests(false);
            qa.setQaOfId(tested.getId());
            qa.setStatus(TaskStatus.planned);
            qa.setPosition(tested.getPosition());
            qa.setDependsOn(new LinkedHashSet<>(Set.of(tested.getId())));
            return qa;
        }

        private static void assign(WorkTask task, Assignment assignment) {
            task.setProvider(blankToNull(assignment.provider()));
            task.setConnectionId(blankToNull(assignment.connectionId()));
            task.setModel(blankToNull(assignment.model()));
            task.setEffort(blankToNull(assignment.effort()));
        }

        /** Names are task ids or keys, both unique inside the item; anything else is refused. */
        private static Set<UUID> resolve(List<String> names, List<WorkTask> all, WorkTask task) {
            Set<UUID> resolved = new LinkedHashSet<>();
            if (names == null) {
                return resolved;
            }
            for (String name : names) {
                WorkTask target = all.stream()
                        .filter(other -> other.getId().toString().equals(name) || other.getKey().equals(name))
                        .findFirst()
                        .orElseThrow(() -> badRequest("\"" + name + "\" is not a task of this item"));
                if (target.getId().equals(task.getId())) {
                    throw badRequest("A task cannot depend on itself");
                }
                resolved.add(target.getId());
            }
            if (task.getQaOfId() != null) {
                resolved.add(task.getQaOfId());
            }
            return resolved;
        }

        private static void requireAcyclic(List<WorkTask> all) {
            Map<UUID, WorkTask> byId = all.stream().collect(Collectors.toMap(WorkTask::getId, Function.identity()));
            Map<UUID, Integer> state = new HashMap<>();
            for (WorkTask root : all) {
                if (state.containsKey(root.getId())) {
                    continue;
                }
                Deque<Map.Entry<UUID, Boolean>> stack = new ArrayDeque<>();
                stack.push(Map.entry(root.getId(), false));
                while (!stack.isEmpty()) {
                    Map.Entry<UUID, Boolean> entry = stack.pop();
                    UUID id = entry.getKey();
                    if (entry.getValue()) {
                        state.put(id, 2);
                        continue;
                    }
                    Integer seen = state.get(id);
                    if (seen != null) {
                        if (seen == 1) {
                            throw badRequest("These dependencies form a cycle");
                        }
                        continue;
                    }
                    state.put(id, 1);
                    stack.push(Map.entry(id, true));
                    WorkTask task = byId.get(id);
                    if (task == null) {
                        continue;
                    }
                    for (UUID next : task.getDependsOn()) {
                        Integer nextState = state.get(next);
                        if (nextState != null && nextState == 1) {
                            throw badRequest("These dependencies form a cycle");
                        }
                        if (nextState == null) {
                            stack.push(Map.entry(next, false));
                        }
                    }
                }
            }
        }

        private String writeAlternatives(List<Assignment> alternatives) {
            if (alternatives.size() > 5) {
                throw badRequest("A task keeps at most 5 alternatives");
            }
            return json.writeValueAsString(alternatives);
        }

        private List<Assignment> readAlternatives(String value) {
            try {
                return json.readValue(value == null || value.isBlank() ? "[]" : value,
                        new TypeReference<List<Assignment>>() { });
            } catch (RuntimeException exception) {
                return List.of();
            }
        }

        private Map<String, Object> readQaReport(String value) {
            try {
                return value == null || value.isBlank() ? null
                        : json.readValue(value, new TypeReference<Map<String, Object>>() { });
            } catch (RuntimeException exception) {
                return null;
            }
        }

        private Map<String, Object> readQaHandoff(String value) {
            try {
                return value == null || value.isBlank() ? null
                        : json.readValue(value, new TypeReference<Map<String, Object>>() { });
            } catch (RuntimeException exception) {
                return null;
            }
        }

        private WorkItem touch(WorkItem item) {
            recomputeDeployment(item, tasks.findByItemId(item.getId()));
            item.setUpdatedAt(OffsetDateTime.now(clock));
            return item;
        }

        private RunnerItem runnerItem(WorkItem item, List<WorkTask> all) {
            UUID playerId = projects.findById(item.getProjectId()).map(WorkProject::getPlayerId).orElse(null);
            WorkSettingsPayload limits = settings.findById(item.getProjectId()).map(WorkService::toPayload)
                    .orElse(WorkSettingsPayload.DEFAULTS);
            return new RunnerItem(playerId, item.getRunnerAuthSessionId(), limits, toPayload(item, all));
        }

        private ItemPayload toPayload(WorkItem item, List<WorkTask> all) {
            return new ItemPayload(item.getId(), item.getProjectId(), item.getKind(), item.getIssueNumber(),
                    item.getTitle(), item.getSpec(), item.getStatus(),
                    item.getStatus() == ItemStatus.paused ? item.getPauseReason() : null, item.getDependsOnId(),
                    item.getStartedAt(),
                    item.getFinishedAt(), item.getCreatedAt(), item.getUpdatedAt(), deployment(item, all),
                    definitionState(item),
                    all.stream().sorted(Comparator.comparingInt(WorkTask::getPosition)
                                    .thenComparing(task -> task.getQaOfId() != null)
                                    .thenComparing(WorkTask::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                            .map(task -> new TaskPayload(task.getId(), task.getKey(), task.getTitle(), task.getCategory(),
                                    task.getDescription(), task.getAcceptance(),
                                    new Assignment(task.getProvider(), task.getConnectionId(), task.getModel(),
                                            task.getEffort()),
                                     readAlternatives(task.getAlternatives()), task.isAutoMerge(), task.isTests(),
                                     task.getQaOfId(), task.getFixOfId(), task.getStatus(), task.getSessionId(),
                                     task.getAttempts(), task.getAttemptsBase(), task.getReport(),
                                     readQaReport(task.getQaReport()),
                                     task.getFindingId(),
                                     task.getQaRounds(), task.getMergeSha(), task.getDeployedVersion(), task.getDeployedAt(),
                                     readQaHandoff(task.getQaHandoff()),
                                     List.copyOf(task.getDependsOn()),
                                     task.getStartedAt(), task.getFinishedAt()))
                            .toList(), changes.findByItemIdOrderByCreatedAtAscIdAsc(item.getId()).stream()
                                    .map(this::changePayload).toList());
        }

        private DefinitionState definitionState(WorkItem item) {
            return item.getKind() == ItemKind.feature ? DefinitionState.DEFINED
                    : definitions.findByProjectIdAndIssueNumber(item.getProjectId(), item.getIssueNumber())
                            .map(IssueDefinition::getState).orElse(DefinitionState.NEW);
        }

        private boolean executable(WorkItem item) {
            if (item.getKind() == ItemKind.feature) return true;
            return definitions.findByProjectIdAndIssueNumber(item.getProjectId(), item.getIssueNumber())
                    .map(row -> row.getAccepted() == null || json.readValue(row.getAccepted(), DefinitionProposal.class)
                            .executable()).orElse(false);
        }

        private static boolean isActive(TaskStatus status) {
            return status == TaskStatus.running || status == TaskStatus.merging || status == TaskStatus.review;
        }

        private static WorkTask find(List<WorkTask> all, UUID taskId) {
            return all.stream().filter(task -> task.getId().equals(taskId)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
        }

        private static Set<String> keys(List<WorkTask> all) {
            return all.stream().map(WorkTask::getKey).collect(Collectors.toSet());
        }

        private static int nextPosition(List<WorkTask> all) {
            return all.stream().mapToInt(WorkTask::getPosition).max().orElse(-1) + 1;
        }

        static String slug(String value) {
            String slug = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
            if (slug.isEmpty()) {
                slug = "task";
            }
            return slug.length() > 48 ? slug.substring(0, 48).replaceAll("-+$", "") : slug;
        }

        private static String uniqueKey(String key, Set<String> taken) {
            String candidate = key;
            for (int suffix = 2; taken.contains(candidate); suffix++) {
                candidate = key + "-" + suffix;
            }
            return candidate;
        }

        private static UUID parseId(String value) {
            try {
                return UUID.fromString(value.strip());
            } catch (IllegalArgumentException exception) {
                throw badRequest("Not an item id: " + value);
            }
        }

        static String requireText(String value, String field) {
            String text = value == null ? "" : value.strip().replaceAll("\\s+", " ");
            if (text.isEmpty()) {
                throw badRequest("The " + field + " cannot be blank");
            }
            return text.length() > 300 ? text.substring(0, 297) + "..." : text;
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }

        static ResponseStatusException badRequest(String reason) {
            return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
        }

        static ResponseStatusException conflict(String reason) {
            return new ResponseStatusException(HttpStatus.CONFLICT, reason);
        }
    }

    /**
     * Defects and debt: GitHub issues labelled {@code defect} or {@code debt}, read and written with the
     * player's own grant, and the items that run them.
     */
    @Service
    @RequiredArgsConstructor
    public static class IssueService {

        private static final Map<ItemKind, String[]> LABELS = Map.of(
                ItemKind.defect, new String[]{"defect", "d73a4a", "Something does not work as it should"},
                ItemKind.debt, new String[]{"debt", "8b5cf6", "Technical debt to pay back"});

        private final WorkService work;
        private final WorkItemRepository items;
        private final WorkTaskRepository tasks;
        private final WorkProjectRepository projects;
        private final IssueDefinitionRepository definitions;
        private final DefinitionProposalRepository proposals;
        private final WorkGithubConnectionRepository connections;
        private final Github.OAuthClient github;
        private final Crypto.SecretBox secretBox;
        private final Clock clock;

        private final ObjectMapper json = JsonMapper.shared();

        @Transactional(readOnly = true)
        public DefinitionPayload definition(UUID playerId, UUID projectId, int number) {
            WorkProject project = work.project(playerId, projectId);
            // Even metadata-free issues must exist in this owner's project and have a factory label.
            kindOf(github.fetchIssue(project.getOwner(), project.getName(), number, accessToken(playerId)));
            return definitionPayload(definitions.findByProjectIdAndIssueNumber(projectId, number).orElse(null));
        }

        @Transactional
        public DefinitionResult propose(UUID playerId, UUID projectId, int number, DefinitionProposal proposal) {
            WorkProject project = lockProject(playerId, projectId);
            Github.Issue issue = github.fetchIssue(project.getOwner(), project.getName(), number, accessToken(playerId));
            kindOf(issue);
            IssueDefinition row = definitionRow(projectId, number);
            Optional<DefinitionProposalRow> replay = proposals.findByDefinitionIdAndProposalId(row.getId(), proposal.proposalId());
            if (replay.isPresent()) {
                if (!json.readValue(replay.get().getContent(), DefinitionProposal.class).equals(proposal)) {
                    throw WorkService.conflict("Proposal id already has different content");
                }
                return definitionSnapshot(projectId, issue, row);
            }
            if (row.getProposal() != null) {
                DefinitionProposal current = json.readValue(row.getProposal(), DefinitionProposal.class);
                if (!current.proposalId().equals(proposal.previousProposalId())) {
                    throw WorkService.conflict("Definition proposal has changed; reload it before proposing");
                }
            } else if (proposal.previousProposalId() != null) {
                throw WorkService.conflict("There is no previous proposal");
            }
            if (row.getState() != DefinitionState.NEW || !"open".equals(issue.state())) {
                throw WorkService.conflict("Reopen the definition of an open issue before proposing changes");
            }
            validateProposal(proposal);
            requireIdle(projectId, number);
            row.setProposal(json.writeValueAsString(proposal));
            definitions.save(row);
            saveProposal(row, proposal);
            return definitionSnapshot(projectId, issue, row);
        }

        @Transactional
        public DefinitionResult reopen(UUID playerId, UUID projectId, int number, DefinitionRevision revision) {
            WorkProject project = lockProject(playerId, projectId);
            Github.Issue issue = github.fetchIssue(project.getOwner(), project.getName(), number, accessToken(playerId));
            kindOf(issue);
            IssueDefinition row = definitionRow(projectId, number);
            requireRevision(row, revision);
            requireIdle(projectId, number);
            row.setState(DefinitionState.NEW);
            definitions.save(row);
            return definitionSnapshot(projectId, issue, row);
        }

        @Transactional
        public DefinitionResult accept(UUID playerId, UUID projectId, int number, DefinitionRevision revision) {
            WorkProject project = lockProject(playerId, projectId);
            IssueDefinition row = definitionRow(projectId, number);
            Optional<DefinitionProposalRow> replay = revision.proposalId() == null ? Optional.empty()
                    : proposals.findByDefinitionIdAndProposalId(row.getId(), revision.proposalId());
            if (replay.isPresent() && replay.get().getResult() != null) {
                return refreshResult(project, row, json.readValue(replay.get().getResult(), DefinitionResult.class),
                        accessToken(playerId));
            }
            requireRevision(row, revision);
            if (row.getProposal() == null) throw WorkService.conflict("There is no proposal to accept");
            DefinitionProposal proposal = json.readValue(row.getProposal(), DefinitionProposal.class);
            if (row.getState() != DefinitionState.NEW) throw WorkService.conflict("Definition is already accepted");
            validateProposal(proposal);
            requireIdle(projectId, number);
            String token = accessToken(playerId);
            Github.Issue source = github.fetchIssue(project.getOwner(), project.getName(), number, token);
            kindOf(source);
            List<IssuePayload> changedIssues = new ArrayList<>();
            List<ItemPayload> changedItems = new ArrayList<>();
            List<Integer> numbers = new ArrayList<>();
            row.setFeatureId(null);
            if (proposal.feature() != null) {
                ItemPayload feature = work.createFeature(playerId, projectId, proposal.feature());
                row.setFeatureId(feature.id());
                changedItems.add(feature);
            } else if (Boolean.TRUE.equals(proposal.valid()) && proposal.breakdown() != null && !proposal.breakdown().isEmpty()) {
                int index = 0;
                for (DefinitionPart part : proposal.breakdown()) {
                    String marker = "<!-- mooi-definition:" + projectId + ":" + proposal.proposalId() + ":" + index++ + " -->";
                    // GitHub cannot join our transaction. A stable marker recovers a create whose
                    // response was lost, including a previous partially completed acceptance.
                    Github.Issue child = github.findIssueByBodyMarker(project.getOwner(), project.getName(), marker, token)
                            .orElseGet(() -> {
                                ensureLabel(project, part.kind(), token);
                                return github.createIssue(project.getOwner(), project.getName(), part.title(),
                                        part.description() + "\n\n" + marker, List.of(LABELS.get(part.kind())[0]), token);
                            });
                    IssueDefinition childRow = definitionRow(projectId, child.number());
                    requireIdle(projectId, child.number());
                    if (childRow.getProposal() != null) {
                        throw WorkService.conflict("A split issue has since been defined separately; review the proposal");
                    }
                    DefinitionProposal childProposal = new DefinitionProposal(proposal.proposalId(), null,
                            part.title(), part.description(), true, part.acceptance(), part.kind(), part.category(), null, null);
                    childRow.setState(DefinitionState.DEFINED);
                    childRow.setProposal(json.writeValueAsString(childProposal));
                    childRow.setAccepted(json.writeValueAsString(childProposal));
                    definitions.save(childRow);
                    DefinitionProposalRow childRevision = saveProposal(childRow, childProposal);
                    ItemPayload item = applyDefinition(projectId, child, childProposal);
                    changedItems.add(item);
                    changedIssues.add(toPayload(projectId, child, part.kind()));
                    numbers.add(child.number());
                    childRevision.setResult(json.writeValueAsString(new DefinitionResult(definitionPayload(childRow),
                            List.of(toPayload(projectId, child, part.kind())), List.of(item))));
                    proposals.save(childRevision);
                }
            }
            ensureLabel(project, proposal.kind(), token);
            List<String> labels = new ArrayList<>(source.labels().stream()
                    .filter(label -> !label.equals("defect") && !label.equals("debt")).toList());
            labels.add(LABELS.get(proposal.kind())[0]);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("title", proposal.title());
            fields.put("body", proposal.description());
            fields.put("labels", labels);
            if (!proposal.executable()) fields.put("state", "closed");
            source = github.updateIssue(project.getOwner(), project.getName(), number, fields, token);
            row.setState(DefinitionState.DEFINED);
            row.setAccepted(row.getProposal());
            row.setIssueNumbers(json.writeValueAsString(numbers));
            definitions.save(row);
            if (proposal.executable()) {
                changedItems.add(applyDefinition(projectId, source, proposal));
            } else {
                Github.Issue acceptedSource = source;
                items.findLockedByProjectIdAndIssueNumber(projectId, number).ifPresent(item -> {
                    item.setKind(proposal.kind());
                    changedItems.add(work.reconcileIssue(item.getId(), acceptedSource));
                });
            }
            changedIssues.add(toPayload(projectId, source, proposal.kind()));
            DefinitionResult result = new DefinitionResult(definitionPayload(row), changedIssues, changedItems);
            DefinitionProposalRow acceptedRevision = proposals.findByDefinitionIdAndProposalId(row.getId(), proposal.proposalId())
                    .orElseThrow(() -> WorkService.conflict("The saved proposal revision is missing"));
            acceptedRevision.setResult(json.writeValueAsString(result));
            proposals.save(acceptedRevision);
            return result;
        }

        private DefinitionProposalRow saveProposal(IssueDefinition row, DefinitionProposal proposal) {
            DefinitionProposalRow revision = new DefinitionProposalRow();
            revision.setId(UUID.randomUUID());
            revision.setDefinitionId(row.getId());
            revision.setProposalId(proposal.proposalId());
            revision.setContent(json.writeValueAsString(proposal));
            return proposals.save(revision);
        }

        private DefinitionResult refreshResult(WorkProject project, IssueDefinition row, DefinitionResult result, String token) {
            List<IssuePayload> currentIssues = result.issues().stream().map(issue -> {
                Github.Issue current = github.fetchIssue(project.getOwner(), project.getName(), issue.number(), token);
                return toPayload(project.getId(), current, kindOf(current));
            }).toList();
            List<ItemPayload> currentItems = result.items().stream().map(item -> items.findById(item.id()))
                    .flatMap(Optional::stream).map(work::payload).toList();
            return new DefinitionResult(definitionPayload(row), currentIssues, currentItems);
        }

        private WorkProject lockProject(UUID playerId, UUID projectId) {
            return projects.findLockedByIdAndPlayerId(projectId, playerId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        }

        private void requireIdle(UUID projectId, int number) {
            items.findLockedByProjectIdAndIssueNumber(projectId, number).ifPresent(item -> {
                if (item.getStatus() == ItemStatus.running || tasks.findByItemId(item.getId()).stream()
                        .anyMatch(task -> WorkService.isActive(task.getStatus())
                                || (task.getSessionId() != null && task.getStatus() != TaskStatus.done))) {
                    throw WorkService.conflict("Finish the active task sessions before defining this issue");
                }
            });
        }

        private IssueDefinition definitionRow(UUID projectId, int number) {
            return definitions.findByProjectIdAndIssueNumber(projectId, number).orElseGet(() -> {
                IssueDefinition row = new IssueDefinition();
                row.setId(UUID.randomUUID());
                row.setProjectId(projectId);
                row.setIssueNumber(number);
                row.setState(DefinitionState.NEW);
                row.setIssueNumbers("[]");
                return row;
            });
        }

        private void requireRevision(IssueDefinition row, DefinitionRevision revision) {
            UUID current = row.getProposal() == null ? null
                    : json.readValue(row.getProposal(), DefinitionProposal.class).proposalId();
            if (!java.util.Objects.equals(current, revision.proposalId())) {
                throw WorkService.conflict("Definition proposal has changed; reload it before accepting or reopening");
            }
        }

        private static void validateProposal(DefinitionProposal proposal) {
            if (proposal.kind() == ItemKind.feature || proposal.category() == TaskCategory.qa) {
                throw WorkService.badRequest("An issue must be defect or debt with a development category");
            }
            boolean split = proposal.breakdown() != null && !proposal.breakdown().isEmpty();
            if ((split && proposal.feature() != null)
                    || (!proposal.valid() && (split || proposal.feature() != null))) {
                throw WorkService.badRequest("Choose one resolution: invalid, one issue, split issues or feature");
            }
            if (proposal.valid() && (proposal.description().isBlank() || proposal.acceptance().isBlank())) {
                throw WorkService.badRequest("Valid definitions need a description and verifiable acceptance criteria");
            }
            if (split && proposal.breakdown().stream().anyMatch(part -> part.kind() == ItemKind.feature
                    || part.category() == TaskCategory.qa)) {
                throw WorkService.badRequest("Split issues must be defects or debt with development categories");
            }
        }

        private void ensureLabel(WorkProject project, ItemKind kind, String token) {
            String[] label = LABELS.get(kind);
            github.ensureLabel(project.getOwner(), project.getName(), label[0], label[1], label[2], token);
        }

        private ItemPayload applyDefinition(UUID projectId, Github.Issue issue, DefinitionProposal proposal) {
            WorkItem item = items.findLockedByProjectIdAndIssueNumber(projectId, issue.number()).orElseGet(() ->
                    items.save(work.newItem(projectId, proposal.kind(), issue.number(), issue.title(), issue.body())));
            item.setKind(proposal.kind());
            List<WorkTask> all = tasks.findByItemId(item.getId());
            if (all.isEmpty()) all = work.issueTasks(item, issue, null);
            for (WorkTask task : all) {
                if (task.getQaOfId() == null && task.getFixOfId() == null) {
                    task.setTitle(proposal.title());
                    task.setDescription(proposal.description());
                    task.setCategory(proposal.category());
                    task.setAcceptance(proposal.acceptance());
                }
            }
            tasks.saveAll(all);
            return work.reconcileIssue(item.getId(), issue);
        }

        private DefinitionPayload definitionPayload(IssueDefinition row) {
            return row == null ? new DefinitionPayload(DefinitionState.NEW, null, null, List.of(), null)
                    : new DefinitionPayload(row.getState(),
                            row.getProposal() == null ? null : json.readValue(row.getProposal(), DefinitionProposal.class),
                            row.getAccepted() == null ? null : json.readValue(row.getAccepted(), DefinitionProposal.class),
                            json.readValue(row.getIssueNumbers(), new TypeReference<List<Integer>>() { }), row.getFeatureId());
        }

        private DefinitionResult definitionSnapshot(UUID projectId, Github.Issue issue, IssueDefinition row) {
            List<ItemPayload> existing = items.findLockedByProjectIdAndIssueNumber(projectId, issue.number())
                    .map(item -> List.of(work.payload(item))).orElse(List.of());
            return new DefinitionResult(definitionPayload(row), List.of(toPayload(projectId, issue, kindOf(issue))), existing);
        }

        public IssuesPayload list(UUID playerId, UUID projectId) {
            WorkProject project = work.project(playerId, projectId);
            String token = accessToken(playerId);
            Map<Integer, IssuePayload> byNumber = new LinkedHashMap<>();
            Map<Integer, Github.Issue> observed = new HashMap<>();
            for (ItemKind kind : List.of(ItemKind.defect, ItemKind.debt)) {
                for (Github.Issue issue : github.listIssues(project.getOwner(), project.getName(), LABELS.get(kind)[0],
                        token)) {
                    byNumber.putIfAbsent(issue.number(), toPayload(projectId, issue, kind));
                    observed.putIfAbsent(issue.number(), issue);
                }
            }
            List<ItemPayload> reconciled = new ArrayList<>();
            for (WorkItem item : items.findByProjectId(projectId)) {
                if (item.getIssueNumber() == null) {
                    continue;
                }
                // The listing is bounded and labels can change: always synchronize tracked issues too.
                Github.Issue issue = observed.containsKey(item.getIssueNumber())
                        ? observed.get(item.getIssueNumber())
                        : github.fetchIssue(project.getOwner(), project.getName(), item.getIssueNumber(), token);
                byNumber.putIfAbsent(issue.number(), toPayload(projectId, issue, item.getKind()));
                reconciled.add(work.reconcileIssue(item.getId(), issue));
            }
            return new IssuesPayload(byNumber.values().stream()
                    .sorted(Comparator.comparing((IssuePayload issue) -> "closed".equals(issue.state()))
                            .thenComparing(IssuePayload::number, Comparator.reverseOrder()))
                    .toList(), reconciled);
        }

        @Transactional
        public IssueItemPayload create(UUID playerId, UUID projectId, IssueDraft draft) {
            WorkProject project = work.project(playerId, projectId);
            if (draft.kind() == ItemKind.feature) {
                throw WorkService.badRequest("Features never become GitHub issues");
            }
            String token = accessToken(playerId);
            String[] label = LABELS.get(draft.kind());
            github.ensureLabel(project.getOwner(), project.getName(), label[0], label[1], label[2], token);
            Github.Issue issue = github.createIssue(project.getOwner(), project.getName(),
                    WorkService.requireText(draft.title(), "title"), draft.body() == null ? "" : draft.body().strip(),
                    List.of(label[0]), token);
            WorkItem item = items.save(work.newItem(projectId, draft.kind(), issue.number(), issue.title(),
                    issue.body()));
            work.issueTasks(item, issue, draft.task());
            return new IssueItemPayload(toPayload(projectId, issue, draft.kind()), work.payload(item));
        }

        @Transactional
        public IssueItemPayload update(UUID playerId, UUID projectId, int number, IssueUpdate update) {
            WorkProject project = work.project(playerId, projectId);
            Map<String, Object> fields = new LinkedHashMap<>();
            if (update.title() != null) {
                fields.put("title", WorkService.requireText(update.title(), "title"));
            }
            if (update.body() != null) {
                fields.put("body", update.body().strip());
            }
            if (update.state() != null) {
                fields.put("state", update.state() == IssueState.closed ? "closed" : "open");
            }
            Github.Issue issue = github.updateIssue(project.getOwner(), project.getName(), number, fields,
                    accessToken(playerId));
            ItemKind kind = kindOf(issue);
            Optional<WorkItem> item = items.findLockedByProjectIdAndIssueNumber(projectId, number);
            return new IssueItemPayload(toPayload(projectId, issue, kind),
                    item.map(row -> work.reconcileIssue(row.getId(), issue, update.state() == IssueState.open))
                            .orElse(null));
        }

        @Transactional
        public ItemPayload item(UUID playerId, UUID projectId, int number) {
            WorkProject project = work.project(playerId, projectId);
            Github.Issue issue = github.fetchIssue(project.getOwner(), project.getName(), number, accessToken(playerId));
            Optional<WorkItem> existing = items.findLockedByProjectIdAndIssueNumber(projectId, number);
            if (existing.isPresent()) {
                return work.reconcileIssue(existing.get().getId(), issue);
            }
            WorkItem item = items.save(work.newItem(projectId, kindOf(issue), issue.number(), issue.title(),
                    issue.body()));
            work.issueTasks(item, issue, null);
            return work.reconcileIssue(item.getId(), issue);
        }

        private static ItemKind kindOf(Github.Issue issue) {
            if (issue.labels().contains(LABELS.get(ItemKind.defect)[0])) {
                return ItemKind.defect;
            }
            if (issue.labels().contains(LABELS.get(ItemKind.debt)[0])) {
                return ItemKind.debt;
            }
            throw WorkService.badRequest("Issue #" + issue.number() + " is labelled neither defect nor debt");
        }

        private IssuePayload toPayload(UUID projectId, Github.Issue issue, ItemKind kind) {
            return new IssuePayload(issue.number(), kind, issue.title(), issue.body(), issue.state(), issue.htmlUrl(),
                    issue.createdAt(), issue.closedAt(), definitions.findByProjectIdAndIssueNumber(projectId, issue.number())
                            .map(IssueDefinition::getState).orElse(DefinitionState.NEW));
        }

        /** Read-only like every non-owning feature: mic-sessions renews the grant before calling here. */
        private String accessToken(UUID playerId) {
            WorkGithubConnection account = connections.findByPlayerId(playerId)
                    .orElseThrow(Github.GithubException::reauthorize);
            OffsetDateTime expiresAt = account.getAccessTokenExpiresAt();
            if (expiresAt != null && !expiresAt.isAfter(OffsetDateTime.now(clock))) {
                throw Github.GithubException.reauthorize();
            }
            return secretBox.decrypt(account.getAccessToken());
        }
    }

    // --- persistence ---

    /** Definition metadata is independent of executable items: proposing creates no work. */
    @Getter
    @Setter
    @NoArgsConstructor
    @Entity(name = "IssueDefinition")
    @Table(name = "work_issue_definitions")
    public static class IssueDefinition extends Db.Auditable {
        @Id
        private UUID id;
        @Column(name = "project_id", nullable = false, updatable = false)
        private UUID projectId;
        @Column(name = "issue_number", nullable = false, updatable = false)
        private Integer issueNumber;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false)
        private DefinitionState state;
        @Column(columnDefinition = "text")
        private String proposal;
        @Column(columnDefinition = "text")
        private String accepted;
        @Column(name = "issue_numbers", nullable = false, columnDefinition = "text")
        private String issueNumbers;
        @Column(name = "feature_id")
        private UUID featureId;
    }

    public interface IssueDefinitionRepository extends JpaRepository<IssueDefinition, UUID> {
        Optional<IssueDefinition> findByProjectIdAndIssueNumber(UUID projectId, Integer issueNumber);
    }

    /** Immutable proposal content plus its acceptance receipt, retained across later revisions. */
    @Getter
    @Setter
    @NoArgsConstructor
    @Entity(name = "DefinitionProposalRow")
    @Table(name = "work_definition_proposals")
    public static class DefinitionProposalRow extends Db.Auditable {
        @Id
        private UUID id;
        @Column(name = "definition_id", nullable = false, updatable = false)
        private UUID definitionId;
        @Column(name = "proposal_id", nullable = false, updatable = false)
        private UUID proposalId;
        @Column(nullable = false, updatable = false, columnDefinition = "text")
        private String content;
        @Column(columnDefinition = "text")
        private String result;
    }

    public interface DefinitionProposalRepository extends JpaRepository<DefinitionProposalRow, UUID> {
        Optional<DefinitionProposalRow> findByDefinitionIdAndProposalId(UUID definitionId, UUID proposalId);
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @Entity(name = "WorkChangeRequest")
    @Table(name = "work_change_requests")
    public static class WorkChangeRequest extends Db.Auditable {
        @Id
        private UUID id;
        @Column(name = "item_id", nullable = false, updatable = false)
        private UUID itemId;
        @Column(name = "request", nullable = false, columnDefinition = "text")
        private String request;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false)
        private ChangeStatus status;
        @Column(columnDefinition = "text")
        private String analysis;
        @Column(nullable = false, columnDefinition = "text")
        private String proposal;
        @Column(name = "session_id")
        private UUID sessionId;
        @Column(name = "confirmed_at")
        private OffsetDateTime confirmedAt;
        @Column(name = "finished_at")
        private OffsetDateTime finishedAt;
    }

    public interface WorkChangeRequestRepository extends JpaRepository<WorkChangeRequest, UUID> {
        List<WorkChangeRequest> findByItemIdOrderByCreatedAtAscIdAsc(UUID itemId);
        @Query("select distinct request.itemId from WorkChangeRequest request where request.status = :status order by request.itemId")
        List<UUID> findItemIdsByStatus(@Param("status") ChangeStatus status);
        boolean existsByItemIdAndStatus(UUID itemId, ChangeStatus status);
        boolean existsByItemIdAndStatusIn(UUID itemId, List<ChangeStatus> statuses);
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @Entity(name = "WorkItem")
    @Table(name = "work_items")
    public static class WorkItem extends Db.Auditable {

        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id;

        @Column(name = "project_id", nullable = false, updatable = false)
        private UUID projectId;

        @Enumerated(EnumType.STRING)
        @Column(name = "kind", nullable = false)
        private ItemKind kind;

        @Column(name = "issue_number", updatable = false)
        private Integer issueNumber;

        @Column(name = "title", nullable = false)
        private String title;

        @Column(name = "spec", nullable = false, columnDefinition = "text")
        private String spec;

        @Enumerated(EnumType.STRING)
        @Column(name = "status", nullable = false)
        private ItemStatus status;

        /** Last observed GitHub closure; distinguishes a reopen from work just completed locally. */
        @Column(name = "issue_closed", nullable = false)
        private boolean issueClosed;

        @Column(name = "deployed_version")
        private String deployedVersion;

        @Column(name = "deployed_at")
        private OffsetDateTime deployedAt;

        @Column(name = "in_current_release")
        private Boolean inCurrentRelease;

        @Column(name = "depends_on_id")
        private UUID dependsOnId;

        @Column(name = "rank", nullable = false)
        private long rank;

        /** The player's factory credential the runner acts with (see {@link Auth.FactoryCredentials}). */
        @Column(name = "runner_auth_session_id")
        private UUID runnerAuthSessionId;

        @Column(name = "pause_reason", length = 300)
        private String pauseReason;

        @Column(name = "runner_player_id")
        private UUID runnerPlayerId;

        @Column(name = "started_at")
        private OffsetDateTime startedAt;

        @Column(name = "finished_at")
        private OffsetDateTime finishedAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @Entity(name = "WorkTask")
    @Table(name = "work_tasks")
    public static class WorkTask extends Db.Auditable {

        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id;

        @Column(name = "item_id", nullable = false, updatable = false)
        private UUID itemId;

        @Column(name = "key", nullable = false)
        private String key;

        @Column(name = "title", nullable = false)
        private String title;

        @Enumerated(EnumType.STRING)
        @Column(name = "category", nullable = false)
        private TaskCategory category;

        @Column(name = "description", nullable = false, columnDefinition = "text")
        private String description;

        @Column(name = "acceptance", nullable = false, columnDefinition = "text")
        private String acceptance;

        @Column(name = "provider")
        private String provider;

        @Column(name = "connection_id")
        private String connectionId;

        @Column(name = "model")
        private String model;

        @Column(name = "effort")
        private String effort;

        /** JSON list of {@link Assignment}s, tried in order when the chosen account runs low. */
        @Column(name = "alternatives", nullable = false, columnDefinition = "text")
        private String alternatives;

        @Column(name = "auto_merge", nullable = false)
        private boolean autoMerge;

        @Column(name = "tests", nullable = false)
        private boolean tests;

        @Column(name = "qa_of_id", updatable = false)
        private UUID qaOfId;

        @Column(name = "fix_of_id", updatable = false)
        private UUID fixOfId;

        @Enumerated(EnumType.STRING)
        @Column(name = "status", nullable = false)
        private TaskStatus status;

        @Column(name = "session_id")
        private UUID sessionId;

        /** Monotonic: recovery snapshots are keyed by attempt, so Retry and Start never rewind it. */
        @Column(name = "attempts", nullable = false)
        private int attempts;

        /** {@code attempts} at the latest Retry or Start; the session limit counts attempts beyond it. */
        @Column(name = "attempts_base", nullable = false)
        private int attemptsBase;

        @Column(name = "report", columnDefinition = "text")
        private String report;

        /** Latest structured QA delivery, retained for the Board, Planification and chat API. */
        @Column(name = "qa_report", columnDefinition = "text")
        private String qaReport;

        /** Number of failed QA deliveries that actually created correction tasks. */
        @Column(name = "qa_rounds", nullable = false)
        private int qaRounds;

        @Column(name = "finding_id", length = 100)
        private String findingId;

        /** Full commit from a successful development merge; absent for QA and other completion paths. */
        @Column(name = "merge_sha", length = 40)
        private String mergeSha;

        @Column(name = "deployed_version")
        private String deployedVersion;

        @Column(name = "deployed_at")
        private OffsetDateTime deployedAt;

        @Column(name = "in_current_release")
        private Boolean inCurrentRelease;

        /** Compact, reported development evidence for its independent QA task; never a diff or repository. */
        @Column(name = "qa_handoff", columnDefinition = "text")
        private String qaHandoff;

        @Column(name = "position", nullable = false)
        private int position;

        @Column(name = "started_at")
        private OffsetDateTime startedAt;

        @Column(name = "finished_at")
        private OffsetDateTime finishedAt;

        @ElementCollection(fetch = FetchType.EAGER)
        @CollectionTable(name = "work_task_dependencies", joinColumns = @JoinColumn(name = "task_id"))
        @Column(name = "depends_on_id", nullable = false)
        private Set<UUID> dependsOn = new LinkedHashSet<>();
    }

    /** Durable metadata for one attempt. Recovery objects are kept only by the isolated sessions service. */
    @Getter
    @Setter
    @NoArgsConstructor
    @Entity(name = "WorkTaskRecovery")
    @Table(name = "work_task_recovery")
    public static class TaskRecovery {

        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id;

        @Column(name = "player_id", nullable = false, updatable = false)
        private UUID playerId;

        @Column(name = "project_id", nullable = false, updatable = false)
        private UUID projectId;

        @Column(name = "item_id", nullable = false, updatable = false)
        private UUID itemId;

        @Column(name = "task_id", nullable = false, updatable = false)
        private UUID taskId;

        @Column(name = "attempt", nullable = false, updatable = false)
        private int attempt;

        @Column(name = "branch", nullable = false, length = 120)
        private String branch;

        @Column(name = "base_commit", nullable = false, length = 64)
        private String baseCommit;

        @Column(name = "head_commit", nullable = false, length = 64)
        private String headCommit;

        @Column(name = "snapshot_revision", nullable = false, length = 64)
        private String snapshotRevision;

        @Column(name = "summary", nullable = false, columnDefinition = "text")
        private String summary;

        @Column(name = "next_step", nullable = false, columnDefinition = "text")
        private String nextStep;

        @Column(name = "files", nullable = false, columnDefinition = "text")
        private String files;

        @Column(name = "verifications", nullable = false, columnDefinition = "text")
        private String verifications;

        @Column(name = "obstacles", nullable = false, columnDefinition = "text")
        private String obstacles;

        @Column(name = "reported_outcome", length = 16)
        private String reportedOutcome;

        @Column(name = "reported_title", length = 200)
        private String reportedTitle;

        @Column(name = "reported_summary", columnDefinition = "text")
        private String reportedSummary;

        @Column(name = "reported_preparation", columnDefinition = "text")
        private String reportedPreparation;

        @Column(name = "snapshot_valid", nullable = false)
        private boolean snapshotValid;

        @Column(name = "validity_reason", columnDefinition = "text")
        private String validityReason;

        @Column(name = "updated_at", nullable = false)
        private OffsetDateTime updatedAt;
    }

    public interface TaskRecoveryRepository extends JpaRepository<TaskRecovery, UUID> {

        Optional<TaskRecovery> findByTaskIdAndAttempt(UUID taskId, int attempt);

        List<TaskRecovery> findByTaskId(UUID taskId);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select recovery from WorkTaskRecovery recovery where recovery.taskId = :taskId and recovery.attempt = :attempt")
        Optional<TaskRecovery> findLockedByTaskIdAndAttempt(@Param("taskId") UUID taskId, @Param("attempt") int attempt);
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @Entity(name = "WorkQaDelivery")
    @Table(name = "work_qa_deliveries")
    public static class WorkQaDelivery {

        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id;

        @Column(name = "qa_task_id", nullable = false, updatable = false)
        private UUID qaTaskId;

        @Column(name = "delivery_id", nullable = false, updatable = false, length = 100)
        private String deliveryId;

        @Enumerated(EnumType.STRING)
        @Column(name = "outcome", nullable = false, updatable = false, length = 16)
        private QaOutcome outcome;

        @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "text")
        private String payload;
    }

    public interface WorkQaDeliveryRepository extends JpaRepository<WorkQaDelivery, UUID> {

        boolean existsByQaTaskIdAndDeliveryId(UUID qaTaskId, String deliveryId);
    }

    public interface WorkItemRepository extends JpaRepository<WorkItem, UUID> {

        List<WorkItem> findByProjectId(UUID projectId);

        @Query("select item.id from WorkItem item where item.projectId = :projectId order by item.id")
        List<UUID> findIdsByProjectId(@Param("projectId") UUID projectId);

        List<WorkItem> findByStatusIn(List<ItemStatus> statuses);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        Optional<WorkItem> findLockedByProjectIdAndIssueNumber(UUID projectId, Integer issueNumber);

        Optional<WorkItem> findTopByProjectIdOrderByRankDesc(UUID projectId);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        Optional<WorkItem> findLockedById(UUID id);
    }

    /** A project's factory limits; no row means the defaults. */
    @Getter
    @Setter
    @NoArgsConstructor
    @Entity(name = "WorkSettings")
    @Table(name = "work_settings")
    public static class WorkSettings {

        @Id
        @Column(name = "project_id", nullable = false, updatable = false)
        private UUID projectId;

        @Column(name = "quota_limit", nullable = false)
        private int quotaLimit;

        @Column(name = "max_attempts", nullable = false)
        private int maxAttempts;

        @Column(name = "max_qa_rounds", nullable = false)
        private int maxQaRounds;

        @Column(name = "max_resolutions", nullable = false)
        private int maxResolutions;

        @Column(name = "context_handoff_percent", nullable = false)
        private int contextHandoffPercent;

        @Column(name = "stall_turns", nullable = false)
        private int stallTurns;
    }

    public interface WorkSettingsRepository extends JpaRepository<WorkSettings, UUID> {
    }

    public interface WorkTaskRepository extends JpaRepository<WorkTask, UUID> {

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select task from WorkTask task where task.id = :id")
        Optional<WorkTask> findLockedById(@Param("id") UUID id);

        List<WorkTask> findByItemId(UUID itemId);

        List<WorkTask> findByItemIdIn(List<UUID> itemIds);
    }

    /** A read-only view of the projects table owned by {@code ProjectFeature}: whose it is and where it lives. */
    @Getter
    @Immutable
    @NoArgsConstructor
    @Entity(name = "WorkProject")
    @Table(name = "projects")
    public static class WorkProject {

        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id;

        @Column(name = "player_id", nullable = false, updatable = false)
        private UUID playerId;

        @Column(name = "owner", nullable = false)
        private String owner;

        @Column(name = "name", nullable = false)
        private String name;
    }

    public interface WorkProjectRepository extends JpaRepository<WorkProject, UUID> {

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        Optional<WorkProject> findLockedByIdAndPlayerId(UUID id, UUID playerId);

        /** Scoped by player on purpose: an id alone must never be enough to reach somebody's work. */
        Optional<WorkProject> findByIdAndPlayerId(UUID id, UUID playerId);
    }

    /** A read-only view of the connection table owned by {@code GithubConnectionFeature}. */
    @Getter
    @Immutable
    @NoArgsConstructor
    @Entity(name = "WorkGithubConnection")
    @Table(name = "github_connections")
    public static class WorkGithubConnection {

        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id;

        @Column(name = "player_id", nullable = false, updatable = false)
        private UUID playerId;

        @Column(name = "access_token", nullable = false)
        private String accessToken;

        @Column(name = "access_token_expires_at")
        private OffsetDateTime accessTokenExpiresAt;
    }

    public interface WorkGithubConnectionRepository extends JpaRepository<WorkGithubConnection, UUID> {

        Optional<WorkGithubConnection> findByPlayerId(UUID playerId);
    }

    // --- contracts ---

    public enum ItemKind { feature, defect, debt }

    public enum DefinitionState { NEW, DEFINED }

    /** The client owns the revision id; retries must send identical content and the same id. */
    public record DefinitionProposal(@NotNull UUID proposalId, UUID previousProposalId,
                                      @NotBlank @Size(max = 300) String title,
                                      @NotNull @Size(max = 50_000) String description,
                                      @NotNull Boolean valid,
                                      @NotNull @Size(max = 20_000) String acceptance,
                                      @NotNull ItemKind kind, @NotNull TaskCategory category,
                                      @Size(max = 20) List<@Valid @NotNull DefinitionPart> breakdown,
                                      @Valid FeatureDraft feature) {
        boolean executable() {
            return Boolean.TRUE.equals(valid) && feature == null && (breakdown == null || breakdown.isEmpty());
        }
    }

    public record DefinitionPart(@NotBlank @Size(max = 300) String title,
                                  @NotBlank @Size(max = 50_000) String description,
                                  @NotBlank @Size(max = 20_000) String acceptance,
                                  @NotNull ItemKind kind, @NotNull TaskCategory category) { }

    /** proposalId is null only when reopening an issue that has no proposal yet. */
    public record DefinitionRevision(UUID proposalId) { }

    public record DefinitionPayload(DefinitionState state, DefinitionProposal proposal,
                                     DefinitionProposal accepted, List<Integer> issueNumbers, UUID featureId) { }

    public record DefinitionResult(DefinitionPayload definition, List<IssuePayload> issues,
                                    List<ItemPayload> items) { }

    /**
     * {@code planned}: not started; {@code running}: the runner works on it;
     * {@code paused}: live tasks reconcile, but no task starts or auto-merges until Resume.
     */
    public enum ItemStatus { planned, running, paused, done }

    /** UX/UI, development, QA, infrastructure and documentation, each with its own colour on the Board. */
    public enum TaskCategory { ux, dev, qa, infra, docs }

    /**
     * {@code planned}: waiting for its dependencies or the item's start; {@code queued}: unblocked, waiting
     * for a free session; {@code running}: its session works on it; {@code merging}: its work is being
     * merged; {@code review}: waiting for the player's merge or Resume; {@code attention}: the player must
     * answer or decide; {@code done}: merged, or for QA, its acceptance criteria are met.
     */
    public enum TaskStatus { planned, queued, running, merging, review, attention, done }

    public enum IssueState { open, closed }

    public enum ChangeStatus { queued, analyzing, awaiting_confirmation, applied, rejected }

    public enum Decision { accept, reject }

    public enum ChangeOperationKind { spec, add_task, update_task, delete_task }

    public record ChangeRequestDraft(@NotBlank @Size(max = 20_000) String request) { }

    public record ChangeDecision(@NotNull Decision decision) { }

    public record ChangeProgress(@NotNull ChangeStatus status, @NotNull UUID sessionId,
                                  @Size(max = 20_000) String analysis,
                                  @Size(max = 20_000) String proposalText) { }

    public record ChangeOperation(@NotNull ChangeOperationKind kind, UUID taskId,
                                   @Valid ItemUpdate item, @Valid TaskDraft draft, @Valid TaskUpdate task) { }

    public record ChangeOperationDraft(@NotNull UUID sessionId, @NotNull @Valid ChangeOperation operation) { }

    public record ChangeRequestPayload(UUID id, UUID itemId, String request, ChangeStatus status, String analysis,
                                        Map<String, Object> proposal, UUID sessionId, OffsetDateTime confirmedAt,
                                        OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime finishedAt) { }

    /** Who runs a task. Any field may be empty: the platform fills it with the player's defaults at launch. */
    public record Assignment(@Size(max = 32) String provider, @Size(max = 64) String connectionId,
                             @Size(max = 128) String model, @Size(max = 32) String effort) {
    }

    /** The QA task a task with tests gets; empty fields fall back to the task's own criteria. */
    public record QaDraft(@Size(max = 20_000) String description, @Size(max = 20_000) String acceptance,
                          @Valid Assignment assignment) {
    }

    /** {@code dependsOn} names other tasks of the same item by key (or id once saved). */
    public record TaskDraft(@Size(max = 64) String key,
                            @NotBlank @Size(max = 300) String title,
                            @NotNull TaskCategory category,
                            @Size(max = 50_000) String description,
                            @Size(max = 20_000) String acceptance,
                            @Valid Assignment assignment,
                            @Size(max = 5) List<@Valid @NotNull Assignment> alternatives,
                            Boolean autoMerge,
                            Boolean tests,
                            @Valid QaDraft qa,
                            @Size(max = 100) List<@NotBlank String> dependsOn) {
    }

    public record FeatureDraft(@NotBlank @Size(max = 300) String title,
                               @Size(max = 50_000) String spec,
                               UUID dependsOn,
                               @NotNull @Size(min = 1, max = WorkService.MAX_TASKS) List<@Valid @NotNull TaskDraft> tasks) {
    }

    /** Null leaves a field as it is; an empty {@code dependsOn} removes the item's dependency. */
    public record ItemUpdate(@Size(max = 300) String title, @Size(max = 50_000) String spec,
                             @Size(max = 64) String dependsOn) {
    }

    /** Null leaves a field as it is; {@code dependsOn} replaces the whole list. */
    public record TaskUpdate(@Size(max = 300) String title,
                             TaskCategory category,
                             @Size(max = 50_000) String description,
                             @Size(max = 20_000) String acceptance,
                             @Valid Assignment assignment,
                             @Size(max = 5) List<@Valid @NotNull Assignment> alternatives,
                             Boolean autoMerge,
                             Boolean tests,
                             @Size(max = 100) List<@NotBlank String> dependsOn) {
    }

    /** How an issue item runs; null fields take the defaults. */
    public record TaskSettings(@Size(max = 20_000) String acceptance,
                               @Valid Assignment assignment,
                               @Size(max = 5) List<@Valid @NotNull Assignment> alternatives,
                               Boolean autoMerge,
                               Boolean tests,
                               @Valid QaDraft qa) {
    }

    public record IssueDraft(@NotNull ItemKind kind, @NotBlank @Size(max = 300) String title,
                             @Size(max = 60_000) String body, @Valid TaskSettings task) {
    }

    public record IssueUpdate(@Size(max = 300) String title, @Size(max = 60_000) String body, IssueState state) {
    }

    public record TaskProgress(@NotNull TaskStatus status, UUID sessionId, @Min(0) int attempts,
                               @Size(max = 50_000) String report, Map<String, Object> qaReport,
                               @Valid Assignment assignment,
                               @Pattern(regexp = "[0-9a-f]{40}") String mergeSha,
                               Map<String, Object> qaHandoff) {
    }

    /**
     * v1 recovery metadata; the content-addressed bundle is external to this API. previousRevision is
     * a compare-and-set guard (null only for the first revision). Invalid snapshots remain reportable
     * but are never offered as restore candidates.
     */
    public record TaskRecoveryUpdate(@Min(0) int attempt,
                                     @NotBlank @Size(max = 120) @Pattern(regexp = "[A-Za-z0-9._/-]+") String branch,
                                     @NotBlank @Size(max = 64) @Pattern(regexp = "[0-9a-f]{40,64}") String baseCommit,
                                     @NotBlank @Size(max = 64) @Pattern(regexp = "[0-9a-f]{40,64}") String headCommit,
                                     @NotBlank @Size(max = 64) @Pattern(regexp = "[0-9a-f]{64}") String snapshotRevision,
                                     @Size(max = 64) @Pattern(regexp = "[0-9a-f]{64}") String previousRevision,
                                      @NotNull @Size(max = 20_000) String summary,
                                      @NotNull @Size(max = 20_000) String nextStep,
                                     @Size(max = 500) List<@Size(max = 1024) String> files,
                                     @Size(max = 200) List<@Size(max = 2_000) String> verifications,
                                     @Size(max = 200) List<@Size(max = 2_000) String> obstacles,
                                      @Pattern(regexp = "done|blocked|passed|defect") String reportedOutcome,
                                      @Size(max = 200) String reportedTitle,
                                      @Size(max = 20_000) String reportedSummary,
                                      @Size(max = 20_000) String reportedPreparation,
                                      boolean snapshotValid,
                                      @Size(max = 2_000) String validityReason) {
    }

    public record TaskRecoveryPayload(UUID taskId, int attempt, String branch, String baseCommit, String headCommit,
                                       String snapshotRevision, String summary, String nextStep, List<String> files,
                                       List<String> verifications, List<String> obstacles, String reportedOutcome,
                                       String reportedTitle, String reportedSummary, String reportedPreparation,
                                       boolean snapshotValid,
                                       String validityReason,
                                       OffsetDateTime updatedAt) {
    }

    public enum QaOutcome { passed, defect, blocked }

    public enum QaBlockerCategory { access, credentials, infrastructure, capacity }

    public enum FindingPriority { critical, high, medium, low }

    public record FindingDraft(@NotBlank @Size(max = 100) String id,
                              @NotBlank @Size(max = 200) String title,
                              @NotNull FindingPriority priority,
                              @NotBlank @Size(max = 4_000) String criterion,
                              @NotBlank @Size(max = 6_000) String reproduction,
                              @NotBlank @Size(max = 4_000) String expected,
                              @NotBlank @Size(max = 4_000) String observed,
                              @Size(max = 4_000) String evidence) {
    }

    /** Also accepts the former {title, description} single-defect report. */
    public record FixDraft(@Size(max = 100) String deliveryId,
                           QaOutcome outcome,
                           @Size(max = 280) String title,
                           @Size(max = 50_000) String description,
                           @Size(max = 20_000) String summary,
                           @Size(max = 20) List<@Valid @NotNull FindingDraft> findings,
                           QaBlockerCategory blockerCategory,
                           @Size(max = 4_000) String blockerCause) {
    }

    /**
     * {@code pauseReason} is kept only with {@code paused}: why the runner had to stop the item. With
     * {@code authSessionId} the change applies only while the item still runs with that credential, so
     * a revocation noticed late never pauses an item a Resume already gave a new one.
     */
    public record ItemStatusChange(@NotNull ItemStatus status, @Size(max = 300) String pauseReason,
                                   UUID authSessionId) {
    }

    public record TaskPayload(UUID id, String key, String title, TaskCategory category, String description,
                               String acceptance, Assignment assignment, List<Assignment> alternatives,
                               boolean autoMerge, boolean tests, UUID qaOf, UUID fixOf, TaskStatus status,
                               UUID sessionId, int attempts, int attemptsBase, String report,
                               Map<String, Object> qaReport,
                               String findingId, int qaRounds, String mergeSha,
                               String deployedVersion, OffsetDateTime deployedAt, Map<String, Object> qaHandoff,
                               List<UUID> dependsOn,
                               OffsetDateTime startedAt, OffsetDateTime finishedAt) {
    }

    /** {@code pauseReason}: why the runner paused the item on its own; null for the player's own pause. */
    public record ItemPayload(UUID id, UUID projectId, ItemKind kind, Integer issueNumber, String title, String spec,
                              ItemStatus status, String pauseReason, UUID dependsOn, OffsetDateTime startedAt,
                              OffsetDateTime finishedAt,
                              OffsetDateTime createdAt, OffsetDateTime updatedAt, DeploymentPayload deployment,
                              DefinitionState definitionState,
                              List<TaskPayload> tasks, List<ChangeRequestPayload> changeRequests) {
    }

    public record DeploymentPayload(String state, String version, OffsetDateTime deployedAt, Boolean inCurrentRelease) { }

    public record DeploymentOutcome(@NotBlank @Size(max = 255) String releaseTag,
                                    @NotNull @Pattern(regexp = "[0-9a-f]{40}") String releaseSha,
                                    @NotNull OffsetDateTime deployedAt,
                                    @NotNull Set<@NotNull UUID> containedTaskIds) { }

    public record WorkPayload(List<ItemPayload> items) {
    }

    public record IssuePayload(int number, ItemKind kind, String title, String body, String state, String htmlUrl,
                               OffsetDateTime createdAt, OffsetDateTime closedAt, DefinitionState definitionState) {
    }

    public record IssuesPayload(List<IssuePayload> issues, List<ItemPayload> items) {
    }

    /** {@code item} is null while the issue was never configured or started. */
    public record IssueItemPayload(IssuePayload issue, ItemPayload item) {
    }

    /**
     * The limits the runner applies to a project's items. {@code quotaLimit}: the quota percent from
     * which an account is spared and a task's alternatives are tried; {@code maxAttempts}: sessions a
     * task may lose before it waits for the player; {@code maxQaRounds}: fix-and-check-again rounds
     * after a failed QA (0 hands every failure to the player); {@code maxResolutions}: agentic merge
     * attempts on conflicts (0 leaves conflicts to the player); {@code contextHandoffPercent}: the
     * context share at which a task session checkpoints and continues in a fresh session;
     * {@code stallTurns}: consecutive segments or turns without progress before a task waits for the
     * player.
     */
    public record WorkSettingsPayload(@Min(10) @Max(100) int quotaLimit, @Min(1) @Max(10) int maxAttempts,
                                      @Min(0) @Max(10) int maxQaRounds, @Min(0) @Max(5) int maxResolutions,
                                      @Min(20) @Max(90) int contextHandoffPercent, @Min(1) @Max(10) int stallTurns) {

        static final WorkSettingsPayload DEFAULTS = new WorkSettingsPayload(85, 3, 3, 2, 60, 3);
    }

    public record RunnerItem(UUID playerId, UUID authSessionId, WorkSettingsPayload settings, ItemPayload item) {
    }

    public record RunningPayload(List<RunnerItem> items) {
    }
}
