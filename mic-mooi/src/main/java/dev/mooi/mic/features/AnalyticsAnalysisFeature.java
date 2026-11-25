package dev.mooi.mic.features;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import dev.mooi.mic.features.AnalyticsFeature.*;
import dev.mooi.mic.shared.Auth;
import jakarta.persistence.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Immutable, project-scoped evidence. Collection never invokes measurements or configuration writes. */
@RestController
@RequiredArgsConstructor
public class AnalyticsAnalysisFeature {
    private final AnalysisService service;

    @PostMapping("/me/projects/{projectId}/analytics/analyses")
    @Auth.Authenticated
    public AnalysisPayload create(@PathVariable UUID projectId, @Valid @RequestBody AnalysisRequest body,
                                  Auth.Principal principal) {
        return service.create(projectId, principal.player().id(), body);
    }

    @GetMapping("/me/projects/{projectId}/analytics/analyses")
    @Auth.Authenticated
    public List<AnalysisSummary> list(@PathVariable UUID projectId, @RequestParam(defaultValue = "20") int limit,
                                      Auth.Principal principal) {
        return service.list(projectId, principal.player().id(), limit);
    }

    @GetMapping("/me/projects/{projectId}/analytics/analyses/{id}")
    @Auth.Authenticated
    public AnalysisPayload get(@PathVariable UUID projectId, @PathVariable UUID id, Auth.Principal principal) {
        return service.get(projectId, principal.player().id(), id);
    }

    /** One source per commit: a lost request can resume without replacing earlier evidence. */
    @PostMapping("/me/projects/{projectId}/analytics/analyses/{id}/evidence/{source}")
    @Auth.Authenticated
    public AnalysisPayload collect(@PathVariable UUID projectId, @PathVariable UUID id,
                                   @PathVariable Source source, Auth.Principal principal) {
        return service.collect(projectId, principal.player().id(), id, source);
    }

    /** Generation is service-only; the next task supplies the headless agent and semantic validation. */
    @PutMapping("/internal/projects/{projectId}/analytics/analyses/{id}/generation")
    @Auth.ServiceOnly
    public AnalysisPayload generation(@PathVariable UUID projectId, @PathVariable UUID id,
                                      @RequestParam UUID playerId, @Valid @RequestBody GenerationUpdate body) {
        return service.generation(projectId, playerId, id, body);
    }

    @GetMapping("/internal/analytics/analyses/events")
    @Auth.ServiceOnly
    public List<AnalysisEvent> events() { return service.events(); }

    @DeleteMapping("/internal/analytics/analyses/events/{id}")
    @Auth.ServiceOnly
    public void acknowledge(@PathVariable UUID id, @RequestParam long revision) {
        service.acknowledge(id, revision);
    }

    public record AnalysisEvent(UUID id, UUID projectId, UUID playerId, GenerationStatus status, long revision) { }

    public enum Source { tracker, ga4, search_console, ai_referrals, geo_visibility, seo_geo_audits }
    public enum EvidenceStatus { available, partial, not_connected, empty, failed }
    public enum GenerationStatus { collecting, queued, running, completed, partial, no_data, failed,
        no_account, quota }
    public enum Priority { high, medium, low }

    /** Instants are [from,to); timezone is retained, not inferred from the server. */
    public record Period(@NotNull Instant from, @NotNull Instant to, @NotBlank @Size(max = 100) String timezone) { }
    public record AnalysisRequest(@NotNull UUID id, @NotNull @Valid Period period) { }
    public record Execution(UUID id, Instant createdAt, Instant startedAt, Instant finishedAt, String status) { }
    public record Coverage(String description, Integer rowLimit, Integer returnedRows, Boolean truncated,
                           Instant dataFetchedAt, String dataUntil, Long sampleSize, String sampleUnit,
                           List<String> limitations) { }
    public record Evidence(UUID id, Source source, EvidenceStatus status, Period requestedPeriod,
                           Period actualPeriod, Instant queriedAt, Coverage coverage, List<String> errors,
                           List<Execution> executions, JsonNode data) { }
    public record EvidenceReference(@NotNull UUID evidenceId, @NotNull @Valid Period period) { }
    public record Finding(@NotBlank @Size(max = 100) String id, @NotBlank @Size(max = 500) String title,
                          @NotNull Priority priority, @NotBlank @Size(max = 4000) String interpretation,
                          @NotNull @Size(min = 1, max = 30) List<@NotNull @Valid EvidenceReference> evidence,
                          @NotNull @Size(min = 1, max = 20) List<@NotBlank @Size(max = 2000) String> limitations) { }
    public record Recommendation(@NotNull Priority priority, @NotBlank @Size(max = 4000) String justification,
                                 @NotBlank @Size(max = 4000) String action,
                                 @NotBlank @Size(max = 4000) String verification,
                                 @NotNull @Size(min = 1, max = 30)
                                 List<@NotNull @Valid EvidenceReference> evidence) { }
    /** General suggestions never carry a claim to measured evidence. */
    public record AnalysisResult(@NotBlank @Size(max = 8000) String summary,
                                 @NotNull @Size(max = 50) List<@NotNull @Valid Finding> findings,
                                 @NotNull @Size(max = 50) List<@NotNull @Valid Recommendation> recommendations,
                                 @NotNull @Size(max = 30) List<@NotBlank @Size(max = 4000) String> generalSuggestions) { }
    public record GenerationUpdate(@Min(0) long revision, @NotNull GenerationStatus status,
                                   @Size(max = 100) String reason, @Valid AnalysisResult result) { }
    public record AnalysisPayload(int schemaVersion, UUID id, UUID projectId, Period period,
                                  GenerationStatus status, String reason, long revision,
                                  Instant createdAt, Instant updatedAt, List<Evidence> evidence,
                                  AnalysisResult result) { }
    public record AnalysisSummary(UUID id, UUID projectId, Period period, GenerationStatus status, String reason,
                                  long revision, Instant createdAt, Instant updatedAt) { }

    @Service
    @RequiredArgsConstructor
    public static class AnalysisService {
        private final AnalyticsService analytics;
        private final AnalyticsGoogleService google;
        private final AnalyticsSiteRepository sites;
        private final AnalyticsAuditRepository audits;
        private final GeoRunRepository runs;
        private final AnalysisStore store;
        private final ObjectMapper json = JsonMapper.shared();

        public AnalysisPayload create(UUID projectId, UUID playerId, AnalysisRequest request) {
            analytics.requireProject(projectId, playerId);
            validatePeriod(request.period());
            return store.create(projectId, playerId, request);
        }

        public AnalysisPayload get(UUID projectId, UUID playerId, UUID id) {
            analytics.requireProject(projectId, playerId);
            return store.get(projectId, playerId, id);
        }

        public List<AnalysisSummary> list(UUID projectId, UUID playerId, int limit) {
            analytics.requireProject(projectId, playerId);
            if (limit < 1 || limit > 50) throw bad("Limit must be from 1 to 50");
            return store.list(projectId, playerId, limit);
        }

        public AnalysisPayload collect(UUID projectId, UUID playerId, UUID id, Source source) {
            AnalysisPayload analysis = get(projectId, playerId, id);
            if (analysis.evidence().stream().anyMatch(e -> e.source() == source)) return analysis;
            if (analysis.status() != GenerationStatus.collecting) throw conflict();
            Period p = analysis.period();
            Instant queried = Instant.now();
            UUID evidenceId = UUID.nameUUIDFromBytes((id + ":" + source).getBytes(StandardCharsets.UTF_8));
            Evidence evidence;
            try {
                AnalyticsSite site = sites.findByProjectId(projectId).orElse(null);
                if (site == null || (source == Source.ga4 && (site.getGoogleConnectionId() == null
                        || site.getGa4PropertyId() == null)) || (source == Source.search_console
                        && (site.getGoogleConnectionId() == null || site.getSearchConsoleSiteUrl() == null))) {
                    evidence = new Evidence(evidenceId, source, EvidenceStatus.not_connected, p, p, queried,
                            new Coverage("Source not configured", null, null, null, null, null, null, null,
                                    List.of("No measurement inferred from an unconfigured source")),
                            List.of(), List.of(), null);
                } else {
                    evidence = read(projectId, playerId, evidenceId, source, site, p, queried);
                }
            } catch (RuntimeException failure) {
                // Never persist provider bodies, tokens, URLs or SQL exceptions as error messages.
                String reason = failure instanceof ResponseStatusException error ? error.getReason() : null;
                String code = List.of("google_not_connected", "google_property_not_selected", "google_reauth_required",
                        "google_quota_exceeded", "google_permission_denied", "google_api_disabled", "google_bad_request")
                        .contains(reason == null ? "" : reason) ? reason : "source_unavailable";
                EvidenceStatus failedStatus = "google_not_connected".equals(code) || "google_property_not_selected".equals(code)
                        ? EvidenceStatus.not_connected : EvidenceStatus.failed;
                evidence = new Evidence(evidenceId, source, failedStatus, p, null, queried,
                        new Coverage("Collection failed", null, null, null, null, null, null, null,
                                List.of("Other sources remain independently usable")), List.of(code), List.of(), null);
            }
            analytics.requireProject(projectId, playerId);
            return store.append(projectId, playerId, id, evidence);
        }

        private Evidence read(UUID project, UUID player, UUID id, Source source, AnalyticsSite site,
                              Period p, Instant queried) {
            String from = p.from().toString(), to = p.to().toString(), tz = p.timezone();
            List<String> errors = new ArrayList<>();
            List<String> limitations = new ArrayList<>();
            List<Execution> executions = new ArrayList<>();
            Object data;
            Period actual = p;
            Instant fetched = queried;
            String dataUntil = null;
            Long sample = null;
            String sampleUnit = null;
            Integer limit = null, count = null;
            Boolean knownTruncated = null;
            boolean empty = false;
            boolean partial = false;
            switch (source) {
                case tracker -> {
                    // No raw visitor/session hashes; only the same aggregate reports exposed to the owner.
                    OverviewPayload overview = analytics.overview(project, player, from, to, tz);
                    empty = overview.pageviews().value() == 0 && overview.clicks().value() == 0;
                    sample = overview.pageviews().value(); sampleUnit = "pageviews";
                    Map<String, Object> reports = new java.util.LinkedHashMap<>();
                    for (String dimension : List.of("page", "referrer", "click_target")) {
                        readPart(reports, dimension, () -> analytics.breakdown(project, player, from, to, tz,
                                dimension, 100), errors);
                    }
                    // The overview also exposes a live gauge and previous-period comparisons. They do
                    // not belong to this evidence period, so retain only the current selected metrics.
                    reports.put("overview", Map.of("visitors", overview.visitors().value(),
                            "sessions", overview.sessions().value(), "pageviews", overview.pageviews().value(),
                            "clicks", overview.clicks().value(), "bounceRate", overview.bounceRate().value(),
                            "avgVisitDurationMs", overview.avgVisitDurationMs().value(),
                            "aiVisitors", overview.aiVisitors().value()));
                    data = reports; limit = 100;
                    sample = overview.pageviews().value() + overview.clicks().value();
                    sampleUnit = "pageviews_and_clicks";
                    partial = !errors.isEmpty() || reports.values().stream()
                            .anyMatch(value -> value instanceof List<?> rows && rows.size() >= 100);
                    limitations.add("Breakdowns limited to top 100 per dimension; truncation unknown");
                    limitations.add("Tracker retention is 13 months; collection cannot establish earlier coverage");
                    limitations.add("Visitors use daily rotating hashes and cannot be deduplicated across days or sources");
                }
                case ai_referrals -> {
                    List<BreakdownRow> rows = analytics.breakdown(project, player, from, to, tz, "ai_source", 100);
                    data = rows; count = rows.size(); limit = 100; empty = rows.isEmpty();
                    limitations.add("Tracker referrer/UTM attribution, not prompt visibility or search ranking");
                }
                case ga4 -> {
                    Ga4Report report = google.ga4(project, player, from, to, tz, "pages", 100);
                    data = report;
                    actual = report.coverage().timeZone() == null ? null
                            : googlePeriod(report.from(), report.to(), report.coverage().timeZone());
                    fetched = report.fetchedAt(); limit = 100; count = report.rows().size();
                    empty = report.rows().isEmpty();
                    partial = actual == null || Boolean.TRUE.equals(report.coverage().subjectToThresholding())
                            || Boolean.TRUE.equals(report.coverage().dataLossFromOtherRow())
                            || !report.coverage().sampling().isEmpty();
                    if (report.coverage().totalRows() != null) knownTruncated = report.coverage().totalRows() > count;
                    if (!report.coverage().sampling().isEmpty()) {
                        String readCount = report.coverage().sampling().getFirst().samplesReadCount();
                        try { sample = Long.parseLong(readCount); sampleUnit = "sampled_events"; }
                        catch (RuntimeException ignored) { limitations.add("Sampling count unavailable; see saved provider metadata"); }
                    }
                    if (actual == null) limitations.add("Property timezone unavailable; actual period cannot be established or cited");
                    limitations.add("GA4 property timezone governs daily metrics; requested timezone only maps requested dates");
                    limitations.add("Sampling and thresholding metadata retained in coverage; missing fields mean unknown");
                    limitations.add("GA4 users must not be added to tracker visitors");
                }
                case search_console -> {
                    SearchReport report = google.search(project, player, from, to, tz, "query", 100);
                    Map<String, Object> reports = new java.util.LinkedHashMap<>();
                    reports.put("queries", report);
                    readPart(reports, "pages", () -> google.search(project, player, from, to, tz, "page", 100), errors);
                    data = reports; actual = googlePeriod(report.from(), report.to(), "America/Los_Angeles");
                    fetched = report.fetchedAt(); dataUntil = report.dataUntil(); limit = 100;
                    count = report.rows().size(); empty = report.totals().impressions() == 0;
                    partial = !errors.isEmpty() || dataUntil == null || dataUntil.compareTo(report.to()) < 0;
                    sample = (long) report.totals().impressions(); sampleUnit = "impressions";
                    limitations.add("Search Console dates use Pacific time; latest days may be unavailable");
                    limitations.add("Top queries/pages omit anonymized queries; totals and rows have different coverage");
                }
                case geo_visibility -> {
                    // Select historical runs by their real creation time. Never call queue/run operations.
                    List<GeoRun> selected = runs.findEvidenceRuns(site.getId(), p.from(), p.to(), PageRequest.of(0, 100));
                    List<GeoRunDetail> details = new ArrayList<>();
                    int bytes = 0;
                    for (GeoRun run : selected) {
                        executions.add(new Execution(run.getId(), run.getCreatedAt(), run.getStartedAt(),
                                run.getFinishedAt(), run.getStatus()));
                        try {
                            GeoRunDetail detail = analytics.geoEvidenceRun(project, player, run.getId());
                            int size = json.writeValueAsString(detail).getBytes(StandardCharsets.UTF_8).length;
                            if (bytes + size > 1_000_000) { errors.add("geo_snapshot_size_limit"); break; }
                            bytes += size; details.add(detail);
                            if (detail.results().size() >= 100) errors.add("geo_result_row_limit:" + run.getId());
                        } catch (RuntimeException failure) { errors.add("run_detail_unavailable:" + run.getId()); }
                    }
                    data = details; count = selected.size(); limit = 100;
                    empty = selected.isEmpty() || details.stream().allMatch(d -> d.results().isEmpty());
                    partial = !errors.isEmpty() || selected.stream().anyMatch(r -> !"finished".equals(r.getStatus()))
                            || details.stream().flatMap(d -> d.results().stream()).anyMatch(r -> r.error() != null);
                    details.stream().flatMap(d -> d.results().stream()).filter(r -> r.error() != null)
                            .forEach(r -> errors.add("geo_result_failed:" + r.id()));
                    sample = details.stream().flatMap(d -> d.results().stream()).filter(r -> r.error() == null).count();
                    sampleUnit = "answered_prompt_results";
                    limitations.add("Runs selected by createdAt in [from,to); point-in-time prompt measurements, not continuous traffic");
                    limitations.add("Prompt text and settings may have changed since the run; only saved run results are evidence");
                    limitations.add("At most 100 result rows per run, ordered by id; 1 MB total detail budget");
                }
                case seo_geo_audits -> {
                    List<AnalyticsAudit> selected = audits.findEvidenceAudits(site.getId(), p.from(), p.to(),
                            PageRequest.of(0, 100));
                    List<AuditPayload> details = new ArrayList<>();
                    int bytes = 0;
                    for (AnalyticsAudit audit : selected) {
                        executions.add(new Execution(audit.getId(), audit.getCreatedAt(), null, null, "recorded"));
                        try {
                            AuditPayload detail = analytics.audit(project, player, audit.getId());
                            int size = json.writeValueAsString(detail).getBytes(StandardCharsets.UTF_8).length;
                            if (bytes + size > 1_000_000) { errors.add("audit_snapshot_size_limit"); break; }
                            bytes += size; details.add(detail);
                        }
                        catch (RuntimeException failure) { errors.add("audit_detail_unavailable:" + audit.getId()); }
                    }
                    data = details; count = selected.size(); limit = 100; empty = selected.isEmpty();
                    partial = !errors.isEmpty() || details.stream().flatMap(a -> a.findings().stream())
                            .anyMatch(f -> f.title().startsWith("Check incomplete:"));
                    sample = details.stream().filter(a -> a.findings().stream()
                            .anyMatch(f -> !f.title().startsWith("Check incomplete:"))).count();
                    sampleUnit = "audits_with_completed_checks";
                    limitations.add("Point-in-time audit createdAt is stored; execution start/end are unavailable");
                    limitations.add("Audit coverage and interrupted scans are described by each saved finding");
                    limitations.add("1 MB total audit detail budget");
                }
                default -> throw bad("Unknown source");
            }
            Boolean truncated = knownTruncated != null ? knownTruncated
                    : limit != null && count != null ? (count >= limit ? null : false) : null;
            if (Boolean.TRUE.equals(truncated) || (limit != null && count != null && count >= limit)) partial = true;
            if (limit != null) limitations.add("Bounded report; a full row limit does not prove complete coverage");
            if (fetched != null && fetched.isBefore(queried)) limitations.add("Report may be cached; use dataFetchedAt for freshness");
            EvidenceStatus status = partial ? EvidenceStatus.partial : empty ? EvidenceStatus.empty : EvidenceStatus.available;
            return new Evidence(id, source, status, p, actual, queried,
                    new Coverage("Existing authorized project reports", limit, count, truncated, fetched, dataUntil,
                            sample, sampleUnit, limitations), errors, executions, json.valueToTree(data));
        }

        private void readPart(Map<String, Object> reports, String name, Supplier<Object> read, List<String> errors) {
            try { reports.put(name, read.get()); }
            catch (RuntimeException failure) { errors.add(name + "_unavailable"); }
        }

        public AnalysisPayload generation(UUID project, UUID player, UUID id, GenerationUpdate update) {
            analytics.requireProject(project, player);
            return store.generation(project, player, id, update);
        }

        public List<AnalysisEvent> events() { return store.events(); }
        public void acknowledge(UUID id, long revision) { store.acknowledge(id, revision); }
    }

    /** Separate transactional boundary: external reads cannot roll back already persisted inputs. */
    @Service
    @RequiredArgsConstructor
    public static class AnalysisStore {
        private final AnalysisRepository repository;
        private final ObjectMapper json = JsonMapper.shared();

        @Transactional
        public AnalysisPayload create(UUID project, UUID player, AnalysisRequest request) {
            repository.insertIfAbsent(request.id(), project, player, json.writeValueAsString(request.period()), Instant.now());
            AnalysisPayload saved = payload(owned(project, player, request.id(), false));
            if (!saved.period().equals(request.period())) throw conflict();
            return saved;
        }

        @Transactional(readOnly = true)
        public AnalysisPayload get(UUID project, UUID player, UUID id) {
            return payload(owned(project, player, id, false));
        }

        @Transactional(readOnly = true)
        public List<AnalysisSummary> list(UUID project, UUID player, int limit) {
            return repository.findByProjectIdAndPlayerIdOrderByCreatedAtDescIdDesc(project, player,
                    PageRequest.of(0, limit)).stream().map(row -> new AnalysisSummary(row.getId(), row.getProjectId(),
                            json.readValue(row.getPeriod(), Period.class), GenerationStatus.valueOf(row.getStatus()),
                            row.getReason(), row.getRevision(), row.getCreatedAt(), row.getUpdatedAt())).toList();
        }

        @Transactional
        public AnalysisPayload append(UUID project, UUID player, UUID id, Evidence evidence) {
            Analysis row = owned(project, player, id, true);
            AnalysisPayload current = payload(row);
            List<Evidence> inputs = new ArrayList<>(current.evidence());
            if (inputs.stream().anyMatch(e -> e.source() == evidence.source())) return current;
            if (current.status() != GenerationStatus.collecting) throw conflict();
            inputs.add(evidence);
            row.setEvidence(json.writeValueAsString(inputs));
            if (inputs.size() == Source.values().length) {
                row.setStatus(inputs.stream().anyMatch(AnalyticsAnalysisFeature::usable)
                        ? GenerationStatus.queued.name() : GenerationStatus.no_data.name());
            }
            touch(row);
            return payload(row);
        }

        @Transactional
        public AnalysisPayload generation(UUID project, UUID player, UUID id, GenerationUpdate update) {
            Analysis row = owned(project, player, id, true);
            AnalysisPayload current = payload(row);
            if (current.revision() != update.revision()) throw conflict();
            boolean terminal = update.status() == GenerationStatus.completed || update.status() == GenerationStatus.partial;
            boolean allowed = switch (current.status()) {
                case queued -> List.of(GenerationStatus.running, GenerationStatus.failed,
                        GenerationStatus.no_account, GenerationStatus.quota).contains(update.status());
                case running -> List.of(GenerationStatus.completed, GenerationStatus.partial, GenerationStatus.failed,
                        GenerationStatus.no_account, GenerationStatus.quota, GenerationStatus.queued).contains(update.status());
                case failed, no_account, quota -> List.of(GenerationStatus.queued, GenerationStatus.running).contains(update.status());
                default -> false;
            };
            if (!allowed) throw conflict();
            if (terminal != (update.result() != null)) throw bad("Only completed/partial generations require a result");
            if (terminal) {
                validateResult(current, update.result());
                row.setResult(json.writeValueAsString(update.result()));
            }
            row.setStatus(update.status().name()); row.setReason(update.reason()); touch(row);
            return payload(row);
        }

        private void validateResult(AnalysisPayload analysis, AnalysisResult result) {
            if (analysis.evidence().stream().noneMatch(AnalyticsAnalysisFeature::usable)) throw bad("No measured evidence");
            if (result.findings().stream().map(Finding::id).distinct().count() != result.findings().size()) {
                throw bad("Duplicate finding id");
            }
            for (Finding finding : result.findings()) validateReferences(analysis, finding.evidence());
            for (Recommendation recommendation : result.recommendations()) validateReferences(analysis, recommendation.evidence());
        }

        private void validateReferences(AnalysisPayload analysis, List<EvidenceReference> references) {
            for (EvidenceReference reference : references) {
                Evidence evidence = analysis.evidence().stream().filter(e -> e.id().equals(reference.evidenceId()))
                        .findFirst().orElseThrow(() -> bad("Evidence reference not in saved inputs"));
                if (!usable(evidence) || evidence.actualPeriod() == null || !evidence.actualPeriod().equals(reference.period())) {
                    throw bad("Evidence reference must identify usable evidence and its actual period");
                }
            }
        }

        private Analysis owned(UUID project, UUID player, UUID id, boolean lock) {
            Analysis row = (lock ? repository.lockById(id) : repository.findById(id)).orElseThrow(AnalyticsAnalysisFeature::missing);
            if (!row.getProjectId().equals(project) || !row.getPlayerId().equals(player)) throw missing();
            return row;
        }

        @Transactional(readOnly = true)
        public List<AnalysisEvent> events() {
            return repository.findByEventPendingTrueOrderByUpdatedAtAscIdAsc(PageRequest.of(0, 100)).stream()
                    .map(row -> new AnalysisEvent(row.getId(), row.getProjectId(), row.getPlayerId(),
                            GenerationStatus.valueOf(row.getStatus()), row.getRevision())).toList();
        }

        @Transactional
        public void acknowledge(UUID id, long revision) { repository.acknowledge(id, revision); }

        private void touch(Analysis row) {
            row.setRevision(row.getRevision() + 1); row.setUpdatedAt(Instant.now()); row.setEventPending(true);
        }

        private AnalysisPayload payload(Analysis row) {
            return new AnalysisPayload(1, row.getId(), row.getProjectId(), json.readValue(row.getPeriod(), Period.class),
                    GenerationStatus.valueOf(row.getStatus()), row.getReason(), row.getRevision(), row.getCreatedAt(),
                    row.getUpdatedAt(), json.readValue(row.getEvidence(), new TypeReference<List<Evidence>>() { }),
                    row.getResult() == null ? null : json.readValue(row.getResult(), AnalysisResult.class));
        }
    }

    private static boolean usable(Evidence evidence) {
        return evidence.data() != null && evidence.actualPeriod() != null
                && (evidence.coverage().sampleSize() == null || evidence.coverage().sampleSize() > 0)
                && (evidence.status() == EvidenceStatus.available
                || evidence.status() == EvidenceStatus.partial);
    }

    private static Period googlePeriod(String from, String to, String timezone) {
        ZoneId zone = ZoneId.of(timezone);
        return new Period(LocalDate.parse(from).atStartOfDay(zone).toInstant(),
                LocalDate.parse(to).plusDays(1).atStartOfDay(zone).toInstant(), timezone);
    }

    private static void validatePeriod(Period p) {
        try {
            ZoneId.of(p.timezone());
            if (!p.from().isBefore(p.to()) || Duration.between(p.from(), p.to()).compareTo(Duration.ofDays(400)) > 0) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException failure) { throw bad("Invalid date range or timezone"); }
    }

    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "Analysis revision or state conflict"); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis not found"); }

    @Getter @Setter @NoArgsConstructor
    @Entity(name = "AnalyticsAnalysis") @Table(name = "analytics_analyses")
    public static class Analysis {
        @Id private UUID id;
        @Column(name = "project_id", nullable = false) private UUID projectId;
        @Column(name = "player_id", nullable = false) private UUID playerId;
        @Column(nullable = false, columnDefinition = "text") private String period;
        @Column(nullable = false, columnDefinition = "text") private String evidence;
        @Column(columnDefinition = "text") private String result;
        @Column(nullable = false, length = 32) private String status;
        @Column(length = 100) private String reason;
        @Column(nullable = false) private long revision;
        @Column(name = "event_pending", nullable = false) private boolean eventPending;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    }

    public interface AnalysisRepository extends JpaRepository<Analysis, UUID> {
        @Modifying
        @Query(value = "insert into analytics_analyses (id, project_id, player_id, period, evidence, status, created_at, updated_at) "
                + "values (?1, ?2, ?3, ?4, '[]', 'collecting', ?5, ?5) on conflict (id) do nothing", nativeQuery = true)
        int insertIfAbsent(UUID id, UUID project, UUID player, String period, Instant now);
        List<Analysis> findByProjectIdAndPlayerIdOrderByCreatedAtDescIdDesc(UUID projectId, UUID playerId,
                                                                        org.springframework.data.domain.Pageable page);
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select a from AnalyticsAnalysis a where a.id = :id")
        Optional<Analysis> lockById(UUID id);
        List<Analysis> findByEventPendingTrueOrderByUpdatedAtAscIdAsc(org.springframework.data.domain.Pageable page);
        @Modifying
        @Query("update AnalyticsAnalysis a set a.eventPending = false where a.id = :id and a.revision = :revision")
        int acknowledge(UUID id, long revision);
    }
}
