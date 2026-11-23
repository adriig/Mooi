package dev.mooi.mic.features;

import java.time.Clock;
import java.time.OffsetDateTime;
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
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature: a project's software factory, the work behind its To-dos and Board views.
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

    @DeleteMapping("/me/projects/{projectId}/work/items/{itemId}/tasks/{taskId}")
    @Auth.Authenticated
    @Auth.ServiceCall
    public ItemPayload deleteTask(@PathVariable UUID projectId, @PathVariable UUID itemId, @PathVariable UUID taskId,
                                  Auth.Principal principal) {
        return work.deleteTask(principal.player().id(), projectId, itemId, taskId);
    }

    /** Hands the item to the runner, which acts for this very sign-in until the item ends. */
    @PostMapping("/me/projects/{projectId}/work/items/{itemId}/start")
    @Auth.Authenticated
    @Auth.ServiceCall
    public ItemPayload start(@PathVariable UUID projectId, @PathVariable UUID itemId, Auth.Principal principal) {
        return work.start(principal.player().id(), principal.sessionId(), projectId, itemId);
    }

    /** No task starts while paused; the ones already running finish their work. */
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

    @GetMapping("/internal/work/running")
    @Auth.ServiceOnly
    public RunningPayload running() {
        return work.running();
    }

    @PutMapping("/internal/work/tasks/{taskId}")
    @Auth.ServiceOnly
    public RunnerItem progress(@PathVariable UUID taskId, @Valid @RequestBody TaskProgress progress) {
        return work.progress(taskId, progress);
    }

    /** A failed QA run: the defect it found becomes a fix task, and the QA waits for it to run again. */
    @PostMapping("/internal/work/tasks/{taskId}/fix")
    @Auth.ServiceOnly
    public RunnerItem fix(@PathVariable UUID taskId, @Valid @RequestBody FixDraft draft) {
        return work.fix(taskId, draft);
    }

    @PutMapping("/internal/work/items/{itemId}/status")
    @Auth.ServiceOnly
    public RunnerItem itemStatus(@PathVariable UUID itemId, @Valid @RequestBody ItemStatusChange change) {
        return work.itemStatus(itemId, change.status());
    }

    // --- application ---

    @Service
    @RequiredArgsConstructor
    public static class WorkService {

        static final int MAX_TASKS = 80;
        private static final Set<TaskStatus> STARTED = Set.of(TaskStatus.running, TaskStatus.merging,
                TaskStatus.review, TaskStatus.done);

        private final WorkProjectRepository projects;
        private final WorkItemRepository items;
        private final WorkTaskRepository tasks;
        private final WorkSettingsRepository settings;
        private final Clock clock;
        private final ObjectMapper json = JsonMapper.shared();

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
        public ItemPayload deleteTask(UUID playerId, UUID projectId, UUID itemId, UUID taskId) {
            project(playerId, projectId);
            WorkItem item = lockedItem(projectId, itemId);
            List<WorkTask> all = new ArrayList<>(tasks.findByItemId(itemId));
            WorkTask task = find(all, taskId);
            Set<UUID> removed = new HashSet<>(Set.of(taskId));
            all.stream().filter(other -> taskId.equals(other.getQaOfId())).forEach(qa -> removed.add(qa.getId()));
            if (all.stream().anyMatch(other -> removed.contains(other.getId()) && isActive(other.getStatus()))) {
                throw conflict("This task is running: let it finish before removing it");
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
        public ItemPayload start(UUID playerId, UUID authSessionId, UUID projectId, UUID itemId) {
            project(playerId, projectId);
            WorkItem item = lockedItem(projectId, itemId);
            List<WorkTask> all = tasks.findByItemId(itemId);
            if (item.getStatus() == ItemStatus.done) {
                throw conflict("This work is already done");
            }
            if (all.isEmpty()) {
                throw conflict("Break it into tasks before starting it");
            }
            // Starting again retries what waits for the player without a session to answer in.
            all.stream().filter(task -> task.getStatus() == TaskStatus.attention && task.getSessionId() == null)
                    .forEach(task -> {
                        task.setStatus(TaskStatus.planned);
                        task.setAttempts(0);
                        task.setReport(null);
                    });
            tasks.saveAll(all);
            item.setStatus(ItemStatus.running);
            item.setRunnerAuthSessionId(authSessionId);
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
                items.save(item);
            }
            return toPayload(item, tasks.findByItemId(itemId));
        }

        @Transactional(readOnly = true)
        public RunningPayload running() {
            List<WorkItem> rows = items.findByStatus(ItemStatus.running);
            Map<UUID, List<WorkTask>> byItem = tasks.findByItemIdIn(rows.stream().map(WorkItem::getId).toList())
                    .stream().collect(Collectors.groupingBy(WorkTask::getItemId));
            List<UUID> projectIds = rows.stream().map(WorkItem::getProjectId).distinct().toList();
            Map<UUID, UUID> owners = projects.findAllById(projectIds)
                    .stream().collect(Collectors.toMap(WorkProject::getId, WorkProject::getPlayerId));
            Map<UUID, WorkSettingsPayload> limits = settings.findAllById(projectIds).stream()
                    .collect(Collectors.toMap(WorkSettings::getProjectId, WorkService::toPayload));
            return new RunningPayload(rows.stream()
                    .filter(item -> owners.containsKey(item.getProjectId()))
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
            return toPayload(settings.save(row));
        }

        private static WorkSettingsPayload toPayload(WorkSettings row) {
            return new WorkSettingsPayload(row.getQuotaLimit(), row.getMaxAttempts(), row.getMaxQaRounds(),
                    row.getMaxResolutions());
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
            List<WorkTask> all = tasks.findByItemId(item.getId());
            task = find(all, taskId);
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (progress.status() == TaskStatus.running && task.getStatus() != TaskStatus.running) {
                task.setStartedAt(now);
                task.setFinishedAt(null);
            }
            if (progress.status() == TaskStatus.done && task.getStatus() != TaskStatus.done) {
                task.setFinishedAt(now);
            }
            task.setStatus(progress.status());
            task.setSessionId(progress.sessionId());
            task.setAttempts(progress.attempts());
            task.setReport(progress.report());
            if (progress.assignment() != null) {
                assign(task, progress.assignment());
            }
            tasks.save(task);
            if (all.stream().allMatch(other -> other.getStatus() == TaskStatus.done)) {
                item.setStatus(ItemStatus.done);
                item.setFinishedAt(now);
            }
            items.save(touch(item));
            return runnerItem(item, all);
        }

        @Transactional
        public RunnerItem fix(UUID qaTaskId, FixDraft draft) {
            WorkTask found = tasks.findById(qaTaskId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
            WorkItem item = items.findLockedById(found.getItemId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
            List<WorkTask> all = new ArrayList<>(tasks.findByItemId(item.getId()));
            WorkTask qa = find(all, qaTaskId);
            if (qa.getQaOfId() == null) {
                throw badRequest("Only a QA task reports defects");
            }
            WorkTask tested = find(all, qa.getQaOfId());
            WorkTask fix = new WorkTask();
            fix.setId(UUID.randomUUID());
            fix.setItemId(item.getId());
            fix.setKey(uniqueKey("fix-" + tested.getKey(), keys(all)));
            fix.setTitle(requireText("Fix: " + draft.title(), "title"));
            fix.setCategory(TaskCategory.dev);
            fix.setDescription(draft.description() == null ? "" : draft.description().strip());
            fix.setAcceptance(qa.getAcceptance());
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
            fix.setDependsOn(new LinkedHashSet<>(Set.of(tested.getId())));
            all.add(fix);
            qa.getDependsOn().add(fix.getId());
            qa.setStatus(TaskStatus.planned);
            qa.setSessionId(null);
            qa.setStartedAt(null);
            qa.setFinishedAt(null);
            tasks.saveAll(all);
            item.setStatus(item.getStatus() == ItemStatus.done ? ItemStatus.running : item.getStatus());
            items.save(touch(item));
            return runnerItem(item, all);
        }

        @Transactional
        public RunnerItem itemStatus(UUID itemId, ItemStatus status) {
            WorkItem item = items.findLockedById(itemId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
            item.setStatus(status);
            if (status == ItemStatus.done) {
                item.setFinishedAt(OffsetDateTime.now(clock));
            }
            items.save(item);
            return runnerItem(item, tasks.findByItemId(itemId));
        }

        // --- shared with the issue side ---

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
            TaskDraft draft = new TaskDraft(item.getKind() == ItemKind.defect ? "fix" : "work",
                    issue.title(), TaskCategory.dev,
                    "GitHub issue #" + issue.number() + ": " + issue.title() + "\n\n" + issue.body(),
                    settings == null ? null : settings.acceptance(),
                    settings == null ? null : settings.assignment(),
                    settings == null ? null : settings.alternatives(),
                    settings == null ? null : settings.autoMerge(),
                    settings == null ? null : settings.tests(),
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

        private WorkItem touch(WorkItem item) {
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
                    item.getTitle(), item.getSpec(), item.getStatus(), item.getDependsOnId(), item.getStartedAt(),
                    item.getFinishedAt(), item.getCreatedAt(), item.getUpdatedAt(),
                    all.stream().sorted(Comparator.comparingInt(WorkTask::getPosition)
                                    .thenComparing(task -> task.getQaOfId() != null)
                                    .thenComparing(WorkTask::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                            .map(task -> new TaskPayload(task.getId(), task.getKey(), task.getTitle(), task.getCategory(),
                                    task.getDescription(), task.getAcceptance(),
                                    new Assignment(task.getProvider(), task.getConnectionId(), task.getModel(),
                                            task.getEffort()),
                                    readAlternatives(task.getAlternatives()), task.isAutoMerge(), task.isTests(),
                                    task.getQaOfId(), task.getFixOfId(), task.getStatus(), task.getSessionId(),
                                    task.getAttempts(), task.getReport(), List.copyOf(task.getDependsOn()),
                                    task.getStartedAt(), task.getFinishedAt()))
                            .toList());
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
        private final WorkGithubConnectionRepository connections;
        private final Github.OAuthClient github;
        private final Crypto.SecretBox secretBox;
        private final Clock clock;

        @Transactional(readOnly = true)
        public IssuesPayload list(UUID playerId, UUID projectId) {
            WorkProject project = work.project(playerId, projectId);
            String token = accessToken(playerId);
            Map<Integer, IssuePayload> byNumber = new LinkedHashMap<>();
            for (ItemKind kind : List.of(ItemKind.defect, ItemKind.debt)) {
                for (Github.Issue issue : github.listIssues(project.getOwner(), project.getName(), LABELS.get(kind)[0],
                        token)) {
                    byNumber.putIfAbsent(issue.number(), toPayload(issue, kind));
                }
            }
            return new IssuesPayload(byNumber.values().stream()
                    .sorted(Comparator.comparing((IssuePayload issue) -> "closed".equals(issue.state()))
                            .thenComparing(IssuePayload::number, Comparator.reverseOrder()))
                    .toList());
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
            return new IssueItemPayload(toPayload(issue, draft.kind()), work.payload(item));
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
            Optional<WorkItem> item = items.findByProjectIdAndIssueNumber(projectId, number);
            item.ifPresent(row -> {
                row.setTitle(WorkService.requireText(issue.title(), "title"));
                row.setSpec(issue.body());
                if ("closed".equals(issue.state()) && row.getStatus() != ItemStatus.running) {
                    row.setStatus(ItemStatus.done);
                    row.setFinishedAt(OffsetDateTime.now(clock));
                } else if ("open".equals(issue.state()) && row.getStatus() == ItemStatus.done) {
                    row.setStatus(ItemStatus.planned);
                    row.setFinishedAt(null);
                }
                items.save(row);
            });
            return new IssueItemPayload(toPayload(issue, kind), item.map(work::payload).orElse(null));
        }

        @Transactional
        public ItemPayload item(UUID playerId, UUID projectId, int number) {
            WorkProject project = work.project(playerId, projectId);
            Optional<WorkItem> existing = items.findByProjectIdAndIssueNumber(projectId, number);
            if (existing.isPresent()) {
                return work.payload(existing.get());
            }
            Github.Issue issue = github.fetchIssue(project.getOwner(), project.getName(), number, accessToken(playerId));
            WorkItem item = items.save(work.newItem(projectId, kindOf(issue), issue.number(), issue.title(),
                    issue.body()));
            work.issueTasks(item, issue, null);
            return work.payload(item);
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

        private static IssuePayload toPayload(Github.Issue issue, ItemKind kind) {
            return new IssuePayload(issue.number(), kind, issue.title(), issue.body(), issue.state(), issue.htmlUrl(),
                    issue.createdAt(), issue.closedAt());
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
        @Column(name = "kind", nullable = false, updatable = false)
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

        @Column(name = "depends_on_id")
        private UUID dependsOnId;

        @Column(name = "rank", nullable = false)
        private long rank;

        @Column(name = "runner_auth_session_id")
        private UUID runnerAuthSessionId;

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

        @Column(name = "attempts", nullable = false)
        private int attempts;

        @Column(name = "report", columnDefinition = "text")
        private String report;

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

    public interface WorkItemRepository extends JpaRepository<WorkItem, UUID> {

        List<WorkItem> findByProjectId(UUID projectId);

        List<WorkItem> findByStatus(ItemStatus status);

        Optional<WorkItem> findByProjectIdAndIssueNumber(UUID projectId, Integer issueNumber);

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
    }

    public interface WorkSettingsRepository extends JpaRepository<WorkSettings, UUID> {
    }

    public interface WorkTaskRepository extends JpaRepository<WorkTask, UUID> {

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

    /** {@code planned}: not started; {@code running}: the runner works on it; {@code paused}: nothing new starts. */
    public enum ItemStatus { planned, running, paused, done }

    /** UX/UI, development, QA, infrastructure and documentation, each with its own colour on the Board. */
    public enum TaskCategory { ux, dev, qa, infra, docs }

    /**
     * {@code planned}: waiting for its dependencies or the item's start; {@code queued}: unblocked, waiting
     * for a free session; {@code running}: its session works on it; {@code merging}: its work is being
     * merged; {@code review}: waiting for the player's merge; {@code attention}: the player must answer or
     * decide; {@code done}: merged, or for QA, its acceptance criteria are met.
     */
    public enum TaskStatus { planned, queued, running, merging, review, attention, done }

    public enum IssueState { open, closed }

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
                               @Size(max = 50_000) String report, @Valid Assignment assignment) {
    }

    public record FixDraft(@NotBlank @Size(max = 280) String title, @Size(max = 50_000) String description) {
    }

    public record ItemStatusChange(@NotNull ItemStatus status) {
    }

    public record TaskPayload(UUID id, String key, String title, TaskCategory category, String description,
                              String acceptance, Assignment assignment, List<Assignment> alternatives,
                              boolean autoMerge, boolean tests, UUID qaOf, UUID fixOf, TaskStatus status,
                              UUID sessionId, int attempts, String report, List<UUID> dependsOn,
                              OffsetDateTime startedAt, OffsetDateTime finishedAt) {
    }

    public record ItemPayload(UUID id, UUID projectId, ItemKind kind, Integer issueNumber, String title, String spec,
                              ItemStatus status, UUID dependsOn, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                              OffsetDateTime createdAt, OffsetDateTime updatedAt, List<TaskPayload> tasks) {
    }

    public record WorkPayload(List<ItemPayload> items) {
    }

    public record IssuePayload(int number, ItemKind kind, String title, String body, String state, String htmlUrl,
                               OffsetDateTime createdAt, OffsetDateTime closedAt) {
    }

    public record IssuesPayload(List<IssuePayload> issues) {
    }

    /** {@code item} is null while the issue was never configured or started. */
    public record IssueItemPayload(IssuePayload issue, ItemPayload item) {
    }

    /**
     * The limits the runner applies to a project's items. {@code quotaLimit}: the quota percent from
     * which an account is spared and a task's alternatives are tried; {@code maxAttempts}: sessions a
     * task may lose before it waits for the player; {@code maxQaRounds}: fix-and-check-again rounds
     * after a failed QA (0 hands every failure to the player); {@code maxResolutions}: agentic merge
     * attempts on conflicts (0 leaves conflicts to the player).
     */
    public record WorkSettingsPayload(@Min(10) @Max(100) int quotaLimit, @Min(1) @Max(10) int maxAttempts,
                                      @Min(0) @Max(10) int maxQaRounds, @Min(0) @Max(5) int maxResolutions) {

        static final WorkSettingsPayload DEFAULTS = new WorkSettingsPayload(85, 3, 3, 2);
    }

    public record RunnerItem(UUID playerId, UUID authSessionId, WorkSettingsPayload settings, ItemPayload item) {
    }

    public record RunningPayload(List<RunnerItem> items) {
    }
}
