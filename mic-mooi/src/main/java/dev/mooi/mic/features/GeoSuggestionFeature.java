package dev.mooi.mic.features;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.mooi.mic.features.AnalyticsFeature.AnalyticsService;
import dev.mooi.mic.features.AnalyticsFeature.AnalyticsSite;
import dev.mooi.mic.features.AnalyticsFeature.GeoCompetitor;
import dev.mooi.mic.features.AnalyticsFeature.GeoPrompt;
import dev.mooi.mic.features.AnalyticsFeature.GeoPromptPayload;
import dev.mooi.mic.features.AnalyticsFeature.GeoPromptRepository;
import dev.mooi.mic.features.AnalyticsFeature.GeoSettings;
import dev.mooi.mic.features.AnalyticsFeature.GeoSettingsPayload;
import dev.mooi.mic.features.AnalyticsFeature.GeoSettingsRepository;
import dev.mooi.mic.shared.Auth;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;
import jakarta.validation.Valid;
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
 * Saving reviewed AI visibility suggestions: the GEO configuration with its revision, and the additive accept.
 * <p>
 * The accept only appends the selected brand terms, competitors and prompts: existing entries, prompt ids,
 * order and enabled states, {@code weeklyEnabled} and {@code nextRunAt} are never touched, and nothing is run or
 * scheduled. It validates, deduplicates and checks the real limits (20 terms, 20 competitors, 50 prompts) before
 * writing anything, all in one transaction under the same site lock as every other GEO write. {@code baseRevision}
 * is the revision the reviewer read: any change since then is a 409 that asks to review the current version.
 * The request {@code id} makes retries idempotent: the same id and body replay the first outcome, never adding twice.
 */
@RestController
@RequiredArgsConstructor
public class GeoSuggestionFeature {
    private final GeoSuggestionService service;

    @GetMapping("/me/projects/{projectId}/analytics/geo/config")
    @Auth.Authenticated
    public GeoConfigPayload config(@PathVariable UUID projectId, Auth.Principal principal) {
        return service.config(projectId, principal.player().id());
    }

    @PostMapping("/me/projects/{projectId}/analytics/geo/suggestions/accept")
    @Auth.Authenticated
    public AcceptPayload accept(@PathVariable UUID projectId, @Valid @RequestBody AcceptRequest body,
                                Auth.Principal principal) {
        return service.accept(projectId, principal.player().id(), body);
    }

    /** {@code revision}: a digest of everything a reviewer edits (not {@code nextRunAt}, which the schedule moves). */
    public record GeoConfigPayload(GeoSettingsPayload settings, List<GeoPromptPayload> prompts, String revision) { }
    public record AcceptRequest(@NotNull UUID id, @NotBlank @Size(max = 64) String baseRevision,
                                @Size(max = 50) List<@NotNull @Size(max = 255) String> brandTerms,
                                @Size(max = 50) List<@NotNull @Valid GeoCompetitor> competitors,
                                @Size(max = 50) List<@NotNull @Size(max = 500) String> prompts) { }
    public record Added(List<String> brandTerms, List<GeoCompetitor> competitors, List<GeoPromptPayload> prompts) { }
    /** {@code kind}: brand_term, competitor or prompt; {@code reason}: already_saved or duplicate. */
    public record Skipped(String kind, String value, String reason) { }
    /** {@code status}: applied, or replayed for a retry of an id already applied (nothing added again). */
    public record AcceptPayload(String status, Added added, List<Skipped> skipped, GeoConfigPayload config) { }
    record Outcome(Added added, List<Skipped> skipped) { }

    @Service
    @RequiredArgsConstructor
    public static class GeoSuggestionService {
        static final int MAX_TERMS = 20;
        static final int MAX_COMPETITORS = 20;
        static final int MAX_PROMPTS = 50;
        private static final Duration RETENTION = Duration.ofDays(30);
        private static final Pattern UNSAFE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Co}\\p{Cs}]");
        private static final Pattern SPACES = Pattern.compile("\\s+");
        private static final Pattern HOST = Pattern.compile(
                "^(?=.{4,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:[a-z]{2,63}|xn--[a-z0-9-]{1,59})$");

        private final AnalyticsService analytics;
        private final GeoSettingsRepository geoSettings;
        private final GeoPromptRepository geoPrompts;
        private final AcceptanceRepository acceptances;
        private final EntityManager entityManager;
        private final ObjectMapper json = JsonMapper.shared();

        @Transactional
        public GeoConfigPayload config(UUID projectId, UUID playerId) {
            GeoSettingsPayload settings = analytics.geoSettings(projectId, playerId);
            List<GeoPromptPayload> prompts = analytics.geoPrompts(projectId, playerId);
            return new GeoConfigPayload(settings, prompts, revision(settings, prompts));
        }

        @Transactional
        public AcceptPayload accept(UUID projectId, UUID playerId, AcceptRequest request) {
            if (nullToEmpty(request.brandTerms()).isEmpty() && nullToEmpty(request.competitors()).isEmpty()
                    && nullToEmpty(request.prompts()).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one suggestion to save");
            }
            AnalyticsSite site = analytics.lockedGeoSite(projectId, playerId);
            String requestHash = digest(json.writeValueAsString(List.of(request.baseRevision().strip(),
                    nullToEmpty(request.brandTerms()), nullToEmpty(request.competitors()), nullToEmpty(request.prompts()))));
            Acceptance previous = acceptances.findById(request.id()).orElse(null);
            if (previous != null) {
                if (!previous.getSiteId().equals(site.getId()) || !previous.getPlayerId().equals(playerId)
                        || !previous.getRequestHash().equals(requestHash)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "This acceptance id was already used for a different selection; generate a new one");
                }
                Outcome outcome = json.readValue(previous.getResult(), new TypeReference<Outcome>() { });
                return new AcceptPayload("replayed", outcome.added(), outcome.skipped(), config(projectId, playerId));
            }

            GeoSettings settings = entityManager.find(GeoSettings.class, site.getId(), LockModeType.PESSIMISTIC_WRITE);
            List<GeoPrompt> existingPrompts = geoPrompts.findBySiteIdOrderByPositionAscIdAsc(site.getId());
            List<String> existingTerms = settings == null ? List.of() : readList(settings.getBrandTerms(), new TypeReference<>() { });
            List<GeoCompetitor> existingCompetitors = settings == null ? List.of()
                    : readList(settings.getCompetitors(), new TypeReference<>() { });
            GeoSettingsPayload current = new GeoSettingsPayload(existingTerms, existingCompetitors,
                    settings != null && settings.isWeeklyEnabled(), settings == null ? null : settings.getNextRunAt());
            List<GeoPromptPayload> currentPrompts = existingPrompts.stream().map(row ->
                    new GeoPromptPayload(row.getId(), row.getText(), row.isEnabled(), row.getPosition())).toList();
            if (!revision(current, currentPrompts).equals(request.baseRevision().strip())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "The AI visibility configuration changed since it was read; review the current version before saving again");
            }

            List<Skipped> skipped = new ArrayList<>();
            List<String> terms = new ArrayList<>();
            Set<String> seenTerms = new LinkedHashSet<>();
            existingTerms.forEach(term -> seenTerms.add(term.strip().toLowerCase(Locale.ROOT)));
            for (String raw : nullToEmpty(request.brandTerms())) {
                String term = text(raw, 255, "Brand term");
                String key = term.toLowerCase(Locale.ROOT);
                if (existingTerms.stream().anyMatch(value -> value.strip().equalsIgnoreCase(term))) {
                    skipped.add(new Skipped("brand_term", term, "already_saved"));
                } else if (!seenTerms.add(key)) {
                    skipped.add(new Skipped("brand_term", term, "duplicate"));
                } else {
                    terms.add(term);
                }
            }

            List<GeoCompetitor> competitors = new ArrayList<>();
            Set<String> savedDomains = new LinkedHashSet<>();
            Set<String> savedNames = new LinkedHashSet<>();
            for (GeoCompetitor competitor : existingCompetitors) {
                savedDomains.add(bareHost(competitor.domain()));
                savedNames.add(competitor.name().strip().toLowerCase(Locale.ROOT));
            }
            Set<String> newDomains = new LinkedHashSet<>();
            Set<String> newNames = new LinkedHashSet<>();
            for (GeoCompetitor raw : nullToEmpty(request.competitors())) {
                String name = text(raw.name(), 255, "Competitor name");
                String domain = domain(raw.domain());
                String nameKey = name.toLowerCase(Locale.ROOT);
                if (savedDomains.contains(domain) || savedNames.contains(nameKey)) {
                    skipped.add(new Skipped("competitor", name + " (" + domain + ")", "already_saved"));
                } else if (!newDomains.add(domain) | !newNames.add(nameKey)) {
                    skipped.add(new Skipped("competitor", name + " (" + domain + ")", "duplicate"));
                } else {
                    competitors.add(new GeoCompetitor(name, domain));
                }
            }

            List<String> promptTexts = new ArrayList<>();
            Set<String> seenPrompts = new LinkedHashSet<>();
            existingPrompts.forEach(row -> seenPrompts.add(promptKey(row.getText())));
            Set<String> savedPrompts = Set.copyOf(seenPrompts);
            for (String raw : nullToEmpty(request.prompts())) {
                String prompt = text(raw, 500, "Prompt");
                String key = promptKey(prompt);
                if (savedPrompts.contains(key)) {
                    skipped.add(new Skipped("prompt", prompt, "already_saved"));
                } else if (!seenPrompts.add(key)) {
                    skipped.add(new Skipped("prompt", prompt, "duplicate"));
                } else {
                    promptTexts.add(prompt);
                }
            }

            if (existingTerms.size() + terms.size() > MAX_TERMS) {
                throw limit("brand terms", MAX_TERMS, existingTerms.size());
            }
            if (existingCompetitors.size() + competitors.size() > MAX_COMPETITORS) {
                throw limit("competitors", MAX_COMPETITORS, existingCompetitors.size());
            }
            if (existingPrompts.size() + promptTexts.size() > MAX_PROMPTS) {
                throw limit("prompts", MAX_PROMPTS, existingPrompts.size());
            }

            // Only now, with everything valid, write: settings are appended in place, never rebuilt.
            if (!terms.isEmpty() || !competitors.isEmpty()) {
                GeoSettings row = settings;
                if (row == null) {
                    row = new GeoSettings();
                    row.setSiteId(site.getId());
                    row.setWeeklyEnabled(false);
                }
                List<String> nextTerms = new ArrayList<>(existingTerms);
                nextTerms.addAll(terms);
                List<GeoCompetitor> nextCompetitors = new ArrayList<>(existingCompetitors);
                nextCompetitors.addAll(competitors);
                row.setBrandTerms(json.writeValueAsString(nextTerms));
                row.setCompetitors(json.writeValueAsString(nextCompetitors));
                geoSettings.save(row);
            }
            int next = existingPrompts.stream().mapToInt(GeoPrompt::getPosition).max().orElse(-1) + 1;
            List<GeoPromptPayload> addedPrompts = new ArrayList<>();
            for (String prompt : promptTexts) {
                GeoPrompt row = new GeoPrompt();
                row.setId(UUID.randomUUID());
                row.setSiteId(site.getId());
                row.setText(prompt);
                row.setEnabled(true);
                row.setPosition(next++);
                geoPrompts.save(row);
                addedPrompts.add(new GeoPromptPayload(row.getId(), row.getText(), row.isEnabled(), row.getPosition()));
            }

            Outcome outcome = new Outcome(new Added(terms, competitors, addedPrompts), List.copyOf(skipped));
            Acceptance record = new Acceptance();
            record.setId(request.id());
            record.setSiteId(site.getId());
            record.setPlayerId(playerId);
            record.setRequestHash(requestHash);
            record.setResult(json.writeValueAsString(outcome));
            record.setCreatedAt(Instant.now());
            acceptances.save(record);
            entityManager.flush();
            return new AcceptPayload("applied", outcome.added(), outcome.skipped(), config(projectId, playerId));
        }

        /** Idempotency records only need to outlive a client's retries. */
        @Scheduled(fixedDelay = 6, timeUnit = java.util.concurrent.TimeUnit.HOURS)
        @Transactional
        public void forgetOldAcceptances() {
            acceptances.deleteOlderThan(Instant.now().minus(RETENTION));
        }

        String revision(GeoSettingsPayload settings, List<GeoPromptPayload> prompts) {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("brandTerms", settings.brandTerms());
            canonical.put("competitors", settings.competitors());
            canonical.put("weeklyEnabled", settings.weeklyEnabled());
            canonical.put("prompts", prompts);
            return digest(json.writeValueAsString(canonical)).substring(0, 32);
        }

        private static ResponseStatusException limit(String kind, int max, int existing) {
            return new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most " + max + " " + kind
                    + " are supported and " + existing + " are already saved; select fewer");
        }

        private static String text(String raw, int max, String label) {
            String value = raw == null ? "" : SPACES.matcher(UNSAFE.matcher(raw).replaceAll(" ")).replaceAll(" ").strip();
            if (value.isEmpty() || value.length() > max) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " must have 1 to " + max + " characters");
            }
            return value;
        }

        /** A bare lowercase public host name: no scheme, path, port or {@code www.}. */
        private static String domain(String raw) {
            String host = bareHost(raw == null ? "" : raw);
            if (!HOST.matcher(host).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Competitor domain is not a valid host name");
            }
            return host;
        }

        private static String bareHost(String raw) {
            String value = raw.strip().toLowerCase(Locale.ROOT).replaceFirst("^[a-z][a-z0-9+.-]*://", "");
            int end = value.length();
            for (char stop : new char[] {'/', '?', '#', ':'}) {
                int at = value.indexOf(stop);
                if (at >= 0) end = Math.min(end, at);
            }
            value = value.substring(0, end);
            while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
            return value.startsWith("www.") ? value.substring(4) : value;
        }

        private static String promptKey(String text) {
            return SPACES.matcher(text.strip().toLowerCase(Locale.ROOT)).replaceAll(" ").replaceAll("[?.!¿¡\\s]+$", "");
        }

        private <T> List<T> readList(String value, TypeReference<List<T>> type) {
            if (value == null || value.isBlank()) return List.of();
            try {
                return json.readValue(value, type);
            } catch (RuntimeException exception) {
                return List.of();
            }
        }

        private static <T> List<T> nullToEmpty(List<T> values) {
            return values == null ? List.of() : values;
        }

        private static String digest(String value) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    @Getter @Setter @NoArgsConstructor
    @Entity(name = "AnalyticsGeoSuggestionAcceptance")
    @Table(name = "analytics_geo_suggestion_acceptances")
    public static class Acceptance {
        @Id @Column(name = "id") private UUID id;
        @Column(name = "site_id", nullable = false) private UUID siteId;
        @Column(name = "player_id", nullable = false) private UUID playerId;
        @Column(name = "request_hash", nullable = false, length = 64) private String requestHash;
        @Column(name = "result", nullable = false, columnDefinition = "text") private String result;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
    }

    public interface AcceptanceRepository extends JpaRepository<Acceptance, UUID> {
        @Modifying
        @Query("delete from AnalyticsGeoSuggestionAcceptance a where a.createdAt < :before")
        int deleteOlderThan(@Param("before") Instant before);
    }
}
