package dev.mooi.mic.features;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.util.Timeout;

import org.hibernate.annotations.Immutable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import dev.mooi.mic.shared.Auth;
import dev.mooi.mic.shared.Crypto;
import dev.mooi.mic.shared.GoogleTokens;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Project-scoped production analytics configuration and its cookieless public collection endpoint. */
@RestController
@RequiredArgsConstructor
public class AnalyticsFeature {
    private final AnalyticsService service;
    private final AnalyticsGoogleService google;

    @Bean
    WebMvcConfigurer analyticsCorsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/public/analytics/**")
                        .allowedOriginPatterns("*")
                        .allowedMethods("GET", "POST", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(false)
                        .maxAge(3600);
            }
        };
    }

    @GetMapping("/me/projects/{projectId}/analytics/site")
    @Auth.Authenticated
    public SitePayload getSite(@PathVariable UUID projectId, Auth.Principal principal) {
        return service.getSite(projectId, principal.player().id());
    }

    @PatchMapping("/me/projects/{projectId}/analytics/site")
    @Auth.Authenticated
    public SitePayload updateSite(@PathVariable UUID projectId, @Valid @RequestBody SiteUpdate body,
                                  Auth.Principal principal) {
        return service.updateSite(projectId, principal.player().id(), body);
    }

    @GetMapping("/me/projects/{projectId}/analytics/geo/settings")
    @Auth.Authenticated
    public GeoSettingsPayload geoSettings(@PathVariable UUID projectId, Auth.Principal principal) {
        return service.geoSettings(projectId, principal.player().id());
    }

    @PutMapping("/me/projects/{projectId}/analytics/geo/settings")
    @Auth.Authenticated
    public GeoSettingsPayload saveGeoSettings(@PathVariable UUID projectId,
                                              @Valid @RequestBody GeoSettingsUpdate body,
                                              Auth.Principal principal) {
        return service.saveGeoSettings(projectId, principal.player().id(), body);
    }

    @GetMapping("/me/projects/{projectId}/analytics/geo/prompts")
    @Auth.Authenticated
    public List<GeoPromptPayload> geoPrompts(@PathVariable UUID projectId, Auth.Principal principal) {
        return service.geoPrompts(projectId, principal.player().id());
    }

    @PostMapping("/me/projects/{projectId}/analytics/geo/prompts")
    @Auth.Authenticated
    public GeoPromptPayload createGeoPrompt(@PathVariable UUID projectId,
                                            @Valid @RequestBody GeoPromptCreate body,
                                            Auth.Principal principal) {
        return service.createGeoPrompt(projectId, principal.player().id(), body);
    }

    @PatchMapping("/me/projects/{projectId}/analytics/geo/prompts/{promptId}")
    @Auth.Authenticated
    public GeoPromptPayload updateGeoPrompt(@PathVariable UUID projectId, @PathVariable UUID promptId,
                                            @Valid @RequestBody GeoPromptUpdate body,
                                            Auth.Principal principal) {
        return service.updateGeoPrompt(projectId, principal.player().id(), promptId, body);
    }

    @DeleteMapping("/me/projects/{projectId}/analytics/geo/prompts/{promptId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Auth.Authenticated
    public void deleteGeoPrompt(@PathVariable UUID projectId, @PathVariable UUID promptId,
                                Auth.Principal principal) {
        service.deleteGeoPrompt(projectId, principal.player().id(), promptId);
    }

    @PostMapping("/me/projects/{projectId}/analytics/geo/runs")
    @Auth.Authenticated
    public GeoRunPayload queueGeoRun(@PathVariable UUID projectId, Auth.Principal principal) {
        return service.queueGeoRun(projectId, principal.player().id(), principal.sessionId());
    }

    @GetMapping("/me/projects/{projectId}/analytics/geo/runs")
    @Auth.Authenticated
    public List<GeoRunPayload> geoRuns(@PathVariable UUID projectId,
                                       @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
                                       Auth.Principal principal) {
        return service.geoRuns(projectId, principal.player().id(), limit);
    }

    @GetMapping("/me/projects/{projectId}/analytics/geo/runs/{runId}")
    @Auth.Authenticated
    public GeoRunDetail geoRun(@PathVariable UUID projectId, @PathVariable UUID runId,
                               Auth.Principal principal) {
        return service.geoRun(projectId, principal.player().id(), runId);
    }

    @GetMapping("/me/projects/{projectId}/analytics/geo/summary")
    @Auth.Authenticated
    public GeoSummary geoSummary(@PathVariable UUID projectId, @RequestParam(required = false) Instant from,
                                 @RequestParam(required = false) Instant to, Auth.Principal principal) {
        return service.geoSummary(projectId, principal.player().id(), from, to);
    }

    @PostMapping("/internal/analytics/geo/runs/claim")
    @Auth.ServiceOnly
    public List<GeoClaim> claimGeoRuns(@Valid @RequestBody(required = false) GeoClaimRequest body) {
        return service.claimGeoRuns(body == null || body.limit() == null ? 10 : body.limit(),
                body == null || body.active() == null ? List.of() : body.active());
    }

    @PutMapping("/internal/analytics/geo/runs/{runId}/status")
    @Auth.ServiceOnly
    public GeoRunPayload setGeoRunStatus(@PathVariable UUID runId, @Valid @RequestBody GeoRunStatusChange body) {
        return service.setGeoRunStatus(runId, body.status(), body.reason());
    }

    /** A weekly run the runner could not start (no eligible account): dropped and its schedule retried later. */
    @PostMapping("/internal/analytics/geo/runs/{runId}/postpone")
    @Auth.ServiceOnly
    public GeoSettingsPayload postponeGeoRun(@PathVariable UUID runId, @Valid @RequestBody GeoRunPostpone body) {
        return service.postponeGeoRun(runId, Duration.ofMinutes(body.minutes()));
    }

    @PostMapping("/internal/analytics/geo/runs/{runId}/results")
    @Auth.ServiceOnly
    @ResponseStatus(HttpStatus.CREATED)
    public GeoRunDetail postGeoResults(@PathVariable UUID runId, @Valid @RequestBody GeoResultsBatch body) {
        return service.postGeoResults(runId, body.results());
    }

    @PostMapping("/me/projects/{projectId}/analytics/site/key")
    @Auth.Authenticated
    public SitePayload regenerateKey(@PathVariable UUID projectId, Auth.Principal principal) {
        return service.regenerateKey(projectId, principal.player().id());
    }

    @PostMapping("/me/projects/{projectId}/analytics/audits")
    @Auth.Authenticated
    public AuditPayload audit(@PathVariable UUID projectId, @Valid @RequestBody(required = false) AuditRequest body,
                              Auth.Principal principal) {
        return service.audit(projectId, principal.player().id(), body == null ? null : body.url());
    }

    @GetMapping("/me/projects/{projectId}/analytics/audits")
    @Auth.Authenticated
    public List<AuditPayload> audits(@PathVariable UUID projectId, @RequestParam(defaultValue = "20") int limit,
                                    Auth.Principal principal) {
        return service.audits(projectId, principal.player().id(), limit);
    }

    @GetMapping("/me/projects/{projectId}/analytics/audits/{id}")
    @Auth.Authenticated
    public AuditPayload audit(@PathVariable UUID projectId, @PathVariable UUID id, Auth.Principal principal) {
        return service.audit(projectId, principal.player().id(), id);
    }

    public record AuditRequest(@Size(max = 2048) String url) { }
    public record AuditFinding(String id, String category, String severity, String title, String detail, String fix) { }
    public record AuditPayload(UUID id, String url, int score, List<AuditFinding> findings, Instant createdAt) { }

    @GetMapping("/me/projects/{projectId}/analytics/overview")
    @Auth.Authenticated
    public OverviewPayload overview(@PathVariable UUID projectId, @RequestParam String from,
            @RequestParam String to, @RequestParam(defaultValue = "UTC") String tz, Auth.Principal principal) {
        return service.overview(projectId, principal.player().id(), from, to, tz);
    }

    @GetMapping("/me/projects/{projectId}/analytics/timeseries")
    @Auth.Authenticated
    public List<SeriesPoint> timeseries(@PathVariable UUID projectId, @RequestParam String from,
            @RequestParam String to, @RequestParam(defaultValue = "UTC") String tz,
            @RequestParam String metric, @RequestParam(required = false) String interval, Auth.Principal principal) {
        return service.timeseries(projectId, principal.player().id(), from, to, tz, metric, interval);
    }

    @GetMapping("/me/projects/{projectId}/analytics/breakdown")
    @Auth.Authenticated
    public List<BreakdownRow> breakdown(@PathVariable UUID projectId, @RequestParam String from,
            @RequestParam String to, @RequestParam(defaultValue = "UTC") String tz,
            @RequestParam String dimension, @RequestParam(defaultValue = "20") int limit, Auth.Principal principal) {
        return service.breakdown(projectId, principal.player().id(), from, to, tz, dimension, limit);
    }

    @GetMapping("/me/projects/{projectId}/analytics/google/properties")
    @Auth.Authenticated
    public GooglePropertiesPayload googleProperties(@PathVariable UUID projectId,
            @RequestParam(required = false) UUID connectionId, Auth.Principal principal) {
        return google.properties(projectId, principal.player().id(), connectionId);
    }

    @GetMapping("/me/projects/{projectId}/analytics/search")
    @Auth.Authenticated
    public SearchReport search(@PathVariable UUID projectId, @RequestParam String from, @RequestParam String to,
            @RequestParam(defaultValue = "UTC") String tz, @RequestParam(defaultValue = "query") String dimension,
            @RequestParam(defaultValue = "25") int limit, Auth.Principal principal) {
        return google.search(projectId, principal.player().id(), from, to, tz, dimension, limit);
    }

    @GetMapping("/me/projects/{projectId}/analytics/ga4")
    @Auth.Authenticated
    public Ga4Report ga4(@PathVariable UUID projectId, @RequestParam String from, @RequestParam String to,
            @RequestParam(defaultValue = "UTC") String tz, @RequestParam(defaultValue = "summary") String report,
            @RequestParam(defaultValue = "20") int limit, Auth.Principal principal) {
        return google.ga4(projectId, principal.player().id(), from, to, tz, report, limit);
    }

    public record Ga4PropertyOption(String propertyId, String displayName, String accountName,
                                    List<String> streamUris, boolean suggested) { }
    public record SearchConsoleSiteOption(String siteUrl, String permissionLevel, boolean suggested) { }
    /** A source the connection cannot list carries its error code instead of failing the whole response. */
    public record GooglePropertiesPayload(UUID connectionId, List<Ga4PropertyOption> ga4Properties, String ga4Error,
                                          List<SearchConsoleSiteOption> searchConsoleSites, String searchConsoleError,
                                          String selectedGa4PropertyId, String selectedSearchConsoleSiteUrl,
                                          Instant fetchedAt) { }
    /** {@code ctr} is a 0..1 ratio and {@code position} an impression-weighted average, as Google reports them. */
    public record SearchRow(String key, double clicks, double impressions, double ctr, double position) { }
    public record SearchReport(String siteUrl, String dimension, String from, String to, String dataUntil,
                               SearchRow totals, List<SearchRow> rows, Instant fetchedAt) { }
    /** {@code bounceRate} is a 0..1 ratio and {@code averageSessionDuration} is in seconds, as GA4 reports them. */
    public record Ga4Metrics(long activeUsers, long sessions, long screenPageViews, long eventCount,
                             double bounceRate, double averageSessionDuration) { }
    public record Ga4Row(String key, Ga4Metrics metrics) { }
    public record Ga4Sampling(String samplesReadCount, String samplingSpaceSize) { }
    public record Ga4Coverage(String timeZone, Boolean subjectToThresholding, Boolean dataLossFromOtherRow,
                              List<Ga4Sampling> sampling, Long totalRows, String emptyReason) { }
    public record Ga4Report(String propertyId, String report, String from, String to, Ga4Metrics totals,
                            List<Ga4Row> rows, Instant fetchedAt, Ga4Coverage coverage) { }

    public record MetricValue(long value, Long previous, Double deltaPercent) { }
    public record OverviewPayload(MetricValue visitors, MetricValue sessions, MetricValue pageviews,
            MetricValue clicks, MetricValue bounceRate, MetricValue avgVisitDurationMs,
            MetricValue aiVisitors, long live) { }
    public record SeriesPoint(String bucket, long value) { }
    public record BreakdownRow(String key, long visitors, long pageviews, long clicks) { }

    @GetMapping(value = "/public/analytics/t.js", produces = "application/javascript")
    public ResponseEntity<String> tracker() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .body(TRACKER);
    }

    @PostMapping(value = "/public/analytics/collect", consumes = MediaType.TEXT_PLAIN_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void collect(jakarta.servlet.http.HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > AnalyticsService.MAX_PAYLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Analytics payload exceeds 4 KB");
        }
        byte[] body = request.getInputStream().readNBytes(AnalyticsService.MAX_PAYLOAD_BYTES + 1);
        if (body.length > AnalyticsService.MAX_PAYLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Analytics payload exceeds 4 KB");
        }
        service.collect(new String(body, StandardCharsets.UTF_8), request);
    }

    /** Omitted fields stay unchanged; an empty string clears a Google selection. */
    public record SiteUpdate(@Size(max = 20) List<@Size(max = 512) String> allowedOrigins,
                            @Size(max = 255) String ga4PropertyId,
                            @Size(max = 2048) String searchConsoleSiteUrl,
                            @Size(max = 64) String googleConnectionId) { }

    public record SitePayload(String siteKey, List<String> allowedOrigins, String snippet,
                              UUID googleConnectionId, String ga4PropertyId, String searchConsoleSiteUrl) { }

    public record GeoCompetitor(@NotBlank @Size(max = 255) String name,
                                @NotBlank @Size(max = 255) String domain) { }
    public record GeoSettingsUpdate(@Size(max = 20) List<@NotBlank @Size(max = 255) String> brandTerms,
                                    @Size(max = 20) List<@NotNull @Valid GeoCompetitor> competitors,
                                    @NotNull Boolean weeklyEnabled) { }
    public record GeoSettingsPayload(List<String> brandTerms, List<GeoCompetitor> competitors,
                                     boolean weeklyEnabled, Instant nextRunAt) { }
    public record GeoPromptCreate(@NotBlank @Size(max = 500) String text,
                                  Boolean enabled, @Min(0) Integer position) { }
    public record GeoPromptUpdate(@Size(min = 1, max = 500) String text, Boolean enabled,
                                  @Min(0) Integer position) { }
    public record GeoPromptPayload(UUID id, String text, boolean enabled, int position) { }
    /** {@code reason} explains a failed run: {@code quota} (no account below the quota limit),
     *  {@code signin} (no live sign-in to run as), {@code interrupted} or {@code error}. */
    public record GeoRunPayload(UUID id, String trigger, String status, Instant createdAt,
                                Instant startedAt, Instant finishedAt, String reason) { }
    public record GeoResultPayload(UUID id, UUID promptId, String provider, String model,
                                   boolean mentioned, boolean cited, Integer position,
                                   List<String> citedUrls, List<String> competitorsMentioned,
                                   String excerpt, String error) { }
    public record GeoRunDetail(GeoRunPayload run, List<GeoResultPayload> results) { }
    /** {@code active}: the runs the runner is still executing; any other running run is abandoned. */
    public record GeoClaimRequest(@Min(1) @Max(100) Integer limit, @Size(max = 1000) List<@NotNull UUID> active) { }
    /** {@code authSessionId}: the sign-in the runner acts as (the one that queued a manual run, else the
     *  player's most recently used one); null when the player has none left. */
    public record GeoClaim(UUID runId, UUID siteId, UUID projectId, UUID playerId, UUID authSessionId,
                           String trigger, List<String> brandTerms, List<GeoCompetitor> competitors,
                           List<String> ownDomains, List<GeoPromptPayload> prompts) { }
    public record GeoRunStatusChange(@NotBlank String status, @Size(max = 32) String reason) { }
    public record GeoRunPostpone(@NotNull @Min(1) @Max(10080) Integer minutes) { }
    public record GeoResultInput(@NotNull UUID promptId, @NotBlank @Size(max = 80) String provider,
                                 @NotBlank @Size(max = 255) String model, boolean mentioned, boolean cited,
                                 @Min(1) Integer position, @Size(max = 50) List<@Size(max = 2048) String> citedUrls,
                                 @Size(max = 50) List<@Size(max = 255) String> competitorsMentioned,
                                 @Size(max = 2000) String excerpt, @Size(max = 2000) String error) { }
    public record GeoResultsBatch(@NotNull @Size(max = 500) List<@NotNull @Valid GeoResultInput> results) { }
    public record GeoVoice(String provider, String model, long mentionedPrompts, long answeredPrompts,
                           double shareOfVoice, long citedPrompts, double citationRate,
                           Double averageFirstMentionPosition, Map<String, Long> competitorMentions) { }
    public record GeoTrendPoint(UUID runId, Instant createdAt, String provider, String model,
                                long mentionedPrompts, long answeredPrompts, double shareOfVoice,
                                long citedPrompts, double citationRate) { }
    public record GeoSummary(Instant from, Instant to, List<GeoVoice> visibility,
                             List<GeoTrendPoint> trend) { }

    @Service
    public static class AnalyticsService {
        private static final int MAX_PAYLOAD_BYTES = 4096;
        private static final int RATE_LIMIT_PER_MINUTE = 60;
        private static final long SESSION_IDLE_MILLIS = 30 * 60 * 1000L;
        private static final String TRACKER_PATH = "/api/mooi/public/analytics/t.js";
        private static final Duration GEO_CLAIM_GRACE = Duration.ofMinutes(5);

        private final AnalyticsSiteRepository sites;
        private final AnalyticsEventRepository events;
        private final AnalyticsAuditRepository audits;
        private final AnalyticsProjectRepository projects;
        private final AnalyticsProductionRecipeRepository recipes;
        private final AnalyticsGoogleConnectionRepository googleConnections;
        private final GeoSettingsRepository geoSettings;
        private final GeoPromptRepository geoPrompts;
        private final GeoRunRepository geoRuns;
        private final GeoResultRepository geoResults;
        private final EntityManager entityManager;
        private final Crypto.SecretBox box;
        private final SecureRandom random = new SecureRandom();
        private final ObjectMapper json = JsonMapper.shared();
        private final ConcurrentHashMap<String, RateWindow> rateLimits = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, SessionState> activeSessions = new ConcurrentHashMap<>();
        private final DailySalt dailySalt = new DailySalt();
        private final String publicBaseUrl;

        AnalyticsService(AnalyticsSiteRepository sites, AnalyticsEventRepository events,
                         AnalyticsAuditRepository audits,
                         AnalyticsProjectRepository projects, AnalyticsProductionRecipeRepository recipes,
                         AnalyticsGoogleConnectionRepository googleConnections,
                         GeoSettingsRepository geoSettings, GeoPromptRepository geoPrompts,
                         GeoRunRepository geoRuns, GeoResultRepository geoResults,
                         EntityManager entityManager, Crypto.SecretBox box,
                         @Value("${app.analytics.public-base-url:http://localhost:28471}") String publicBaseUrl) {
            this.sites = sites;
            this.events = events;
            this.audits = audits;
            this.projects = projects;
            this.recipes = recipes;
            this.googleConnections = googleConnections;
            this.geoSettings = geoSettings;
            this.geoPrompts = geoPrompts;
            this.geoRuns = geoRuns;
            this.geoResults = geoResults;
            this.entityManager = entityManager;
            this.box = box;
            this.publicBaseUrl = stripTrailingSlash(publicBaseUrl);
        }

        void requireProject(UUID projectId, UUID playerId) {
            if (!projects.existsByIdAndPlayerId(projectId, playerId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
            }
        }

        private ReportRange range(UUID projectId, UUID playerId, String fromText, String toText, String tzText) {
            requireProject(projectId, playerId);
            try {
                Instant from = Instant.parse(fromText), to = Instant.parse(toText);
                ZoneId zone = ZoneId.of(tzText);
                long millis = Duration.between(from, to).toMillis();
                if (millis <= 0 || millis > Duration.ofDays(400).toMillis()) throw new IllegalArgumentException();
                return new ReportRange(sites.findByProjectId(projectId).map(AnalyticsSite::getId).orElse(null),
                        from, to, zone.getId());
            } catch (RuntimeException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date range or timezone", exception);
            }
        }

        @Transactional(readOnly = true)
        public OverviewPayload overview(UUID projectId, UUID playerId, String fromText, String toText, String tz) {
            ReportRange r = range(projectId, playerId, fromText, toText, tz);
            if (r.siteId() == null) return emptyOverview();
            long span = Duration.between(r.from(), r.to()).toMillis();
            Instant prevFrom = r.from().minusMillis(span), prevTo = r.from();
            Object[] now = aggregate(r.siteId(), r.from(), r.to());
            Object[] prev = aggregate(r.siteId(), prevFrom, prevTo);
            long live = number(entityManager.createNativeQuery("select count(distinct visitor_hash) from analytics_events where site_id=:site and occurred_at >= :since and kind='pageview'")
                    .setParameter("site", r.siteId()).setParameter("since", Instant.now().minusSeconds(300)).getSingleResult());
            return new OverviewPayload(metric(number(now[0]), number(prev[0])), metric(number(now[1]), number(prev[1])),
                    metric(number(now[2]), number(prev[2])), metric(number(now[3]), number(prev[3])),
                    metric(number(now[4]), number(prev[4])), metric(number(now[5]), number(prev[5])),
                    metric(number(now[6]), number(prev[6])), live);
        }

        private Object[] aggregate(UUID site, Instant from, Instant to) {
            return (Object[]) entityManager.createNativeQuery("with e as (select * from analytics_events where site_id=:site and occurred_at>=:from and occurred_at<:to), s as (select session_hash, count(*) filter(where kind='pageview') pv, count(*) filter(where kind='click') cl, max(duration_ms) filter(where kind='leave') dur from e group by session_hash) select (select count(distinct visitor_hash) from e where kind='pageview'), count(*), coalesce(sum(pv),0), coalesce(sum(cl),0), coalesce(100.0*count(*) filter(where pv=1 and cl=0)/nullif(count(*),0),0), coalesce(avg(dur),0), (select count(distinct visitor_hash) from e where kind='pageview' and (lower(coalesce(referrer_host,'') ) like '%chatgpt.com%' or lower(coalesce(referrer_host,'') ) like '%chat.openai.com%' or lower(coalesce(utm_source,'') ) like '%chatgpt%' or lower(coalesce(referrer_host,'') ) like '%perplexity.ai%' or lower(coalesce(utm_source,'') ) like '%perplexity%' or lower(coalesce(referrer_host,'') ) like '%gemini.google.com%' or lower(coalesce(referrer_host,'') ) like '%bard.google.com%' or lower(coalesce(utm_source,'') ) like '%gemini%' or lower(coalesce(referrer_host,'') ) like '%claude.ai%' or lower(coalesce(utm_source,'') ) like '%claude%' or lower(coalesce(referrer_host,'') ) like '%copilot.microsoft.com%' or lower(coalesce(utm_source,'') ) like '%copilot%')) from s")
                    .setParameter("site", site).setParameter("from", from).setParameter("to", to).getSingleResult();
        }

        private OverviewPayload emptyOverview() { MetricValue z = metric(0, 0); return new OverviewPayload(z,z,z,z,z,z,z,0); }
        private MetricValue metric(long value, long previous) {
            return new MetricValue(value, previous, previous == 0 ? (value == 0 ? 0d : null) : 100d * (value - previous) / previous);
        }
        private long number(Object value) { return value == null ? 0 : ((Number) value).longValue(); }

        @Transactional(readOnly = true)
        public List<SeriesPoint> timeseries(UUID projectId, UUID playerId, String fromText, String toText, String tz,
                String metric, String interval) {
            ReportRange r = range(projectId, playerId, fromText, toText, tz);
            if (!List.of("visitors","pageviews","clicks","ai_visitors").contains(metric)) bad("Invalid metric");
            String unit = interval == null || interval.isBlank() ? (Duration.between(r.from(),r.to()).toDays() <= 2 ? "hour" : "day") : interval;
            if (!List.of("hour","day").contains(unit)) bad("Invalid interval");
            if (r.siteId() == null) return List.of();
            String expression = switch(metric) { case "visitors" -> "count(distinct visitor_hash)"; case "pageviews" -> "count(*)"; case "clicks" -> "count(*)"; default -> "count(distinct visitor_hash)"; };
            String kind = metric.equals("pageviews") ? "pageview" : metric.equals("clicks") ? "click" : "pageview";
            String ai = metric.equals("ai_visitors") ? " and (lower(coalesce(referrer_host,'')) like '%chatgpt.com%' or lower(coalesce(referrer_host,'')) like '%chat.openai.com%' or lower(coalesce(utm_source,'')) like '%chatgpt%' or lower(coalesce(referrer_host,'')) like '%perplexity.ai%' or lower(coalesce(utm_source,'')) like '%perplexity%' or lower(coalesce(referrer_host,'')) like '%gemini.google.com%' or lower(coalesce(referrer_host,'')) like '%bard.google.com%' or lower(coalesce(utm_source,'')) like '%gemini%' or lower(coalesce(referrer_host,'')) like '%claude.ai%' or lower(coalesce(utm_source,'')) like '%claude%' or lower(coalesce(referrer_host,'')) like '%copilot.microsoft.com%' or lower(coalesce(utm_source,'')) like '%copilot%')" : "";
            String sql = "with bounds as (select date_trunc(:unit, cast(:from as timestamptz) at time zone :tz) as a, date_trunc(:unit, (cast(:to as timestamptz)-interval '1 microsecond') at time zone :tz) as b), buckets as (select generate_series(a,b,case when :unit='hour' then interval '1 hour' else interval '1 day' end) bucket from bounds), vals as (select date_trunc(:unit, occurred_at at time zone :tz) bucket,"+expression+" value from analytics_events where site_id=:site and occurred_at>=:from and occurred_at<:to and kind=:kind"+ai+" group by 1) select buckets.bucket, coalesce(vals.value,0) from buckets left join vals using(bucket) order by buckets.bucket";
            @SuppressWarnings("unchecked") List<Object[]> rows = entityManager.createNativeQuery(sql).setParameter("unit",unit).setParameter("from",r.from()).setParameter("to",r.to()).setParameter("tz",r.timezone()).setParameter("site",r.siteId()).setParameter("kind",kind).getResultList();
            return rows.stream().map(row -> new SeriesPoint(row[0].toString(), number(row[1]))).toList();
        }

        @Transactional(readOnly = true)
        public List<BreakdownRow> breakdown(UUID projectId, UUID playerId, String fromText, String toText, String tz,
                String dimension, int limit) {
            ReportRange r = range(projectId, playerId, fromText, toText, tz);
            if (limit < 1 || limit > 100) bad("Invalid limit");
            String expression = switch (dimension) {
                case "page" -> "path"; case "referrer" -> "referrer_host"; case "utm_source" -> "utm_source";
                case "utm_campaign" -> "utm_campaign"; case "country" -> "country"; case "device" -> "device";
                case "browser" -> "browser"; case "os" -> "os"; case "click_target" -> "target";
                case "outbound" -> "target"; case "ai_source" -> "case when lower(coalesce(referrer_host,'')) like '%chatgpt.com%' or lower(coalesce(referrer_host,'')) like '%chat.openai.com%' or lower(coalesce(utm_source,'')) like '%chatgpt%' then 'chatgpt' when lower(coalesce(referrer_host,'')) like '%perplexity.ai%' or lower(coalesce(utm_source,'')) like '%perplexity%' then 'perplexity' when lower(coalesce(referrer_host,'')) like '%gemini.google.com%' or lower(coalesce(referrer_host,'')) like '%bard.google.com%' or lower(coalesce(utm_source,'')) like '%gemini%' then 'gemini' when lower(coalesce(referrer_host,'')) like '%claude.ai%' or lower(coalesce(utm_source,'')) like '%claude%' then 'claude' when lower(coalesce(referrer_host,'')) like '%copilot.microsoft.com%' or lower(coalesce(utm_source,'')) like '%copilot%' then 'copilot' else null end";
                case "ai_landing" -> "path"; default -> { bad("Invalid dimension"); yield "path"; }
            };
            if (r.siteId() == null) return List.of();
            String kindFilter = List.of("click_target","outbound").contains(dimension) ? "click" : "pageview";
            String aiFilter = dimension.startsWith("ai_") ? " and (lower(coalesce(referrer_host,'')) like '%chatgpt.com%' or lower(coalesce(referrer_host,'')) like '%chat.openai.com%' or lower(coalesce(utm_source,'')) like '%chatgpt%' or lower(coalesce(referrer_host,'')) like '%perplexity.ai%' or lower(coalesce(utm_source,'')) like '%perplexity%' or lower(coalesce(referrer_host,'')) like '%gemini.google.com%' or lower(coalesce(referrer_host,'')) like '%bard.google.com%' or lower(coalesce(utm_source,'')) like '%gemini%' or lower(coalesce(referrer_host,'')) like '%claude.ai%' or lower(coalesce(utm_source,'')) like '%claude%' or lower(coalesce(referrer_host,'')) like '%copilot.microsoft.com%' or lower(coalesce(utm_source,'')) like '%copilot%')" : "";
            String outboundFilter = dimension.equals("outbound") ? " and target ~ '^https?://' and split_part(target,'/',3) <> split_part(coalesce(referrer_host,''),'/',1)" : "";
            String sql = "select coalesce("+expression+",'(unknown)') key, count(distinct visitor_hash) visitors, count(*) filter(where kind='pageview') pageviews, count(*) filter(where kind='click') clicks from analytics_events where site_id=:site and occurred_at>=:from and occurred_at<:to and kind=:kind"+aiFilter+outboundFilter+" group by key order by clicks desc, pageviews desc, visitors desc limit :lim";
            @SuppressWarnings("unchecked") List<Object[]> rows = entityManager.createNativeQuery(sql).setParameter("site",r.siteId()).setParameter("from",r.from()).setParameter("to",r.to()).setParameter("kind",kindFilter).setParameter("lim",limit).getResultList();
            return rows.stream().map(row -> new BreakdownRow((String)row[0],number(row[1]),number(row[2]),number(row[3]))).toList();
        }
        private void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
        private record ReportRange(UUID siteId, Instant from, Instant to, String timezone) { }

        @Transactional
        public AuditPayload audit(UUID projectId, UUID playerId, String requestedUrl) {
            requireProject(projectId, playerId);
            List<String> production = productionAuditUrls(projectId);
            AnalyticsSite site = sites.findByProjectId(projectId).orElseGet(() -> createSite(projectId, playerId));
            Set<String> hosts = new LinkedHashSet<>();
            for (String origin : production) hosts.add(URI.create(origin).getHost().toLowerCase(Locale.ROOT));
            for (String origin : readOrigins(site.getAllowedOrigins())) {
                hosts.add(URI.create(origin).getHost().toLowerCase(Locale.ROOT));
            }
            String url = requestedUrl == null || requestedUrl.isBlank()
                    ? production.stream().findFirst().orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Configure a production target or supply an allowed URL")) : requestedUrl.strip();
            URI uri = AuditScanner.validateUrl(url);
            if (!hosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL must use a project's production or allowed host");
            }
            List<AuditFinding> findings = new AuditScanner(json).scan(uri);
            AnalyticsAudit audit = new AnalyticsAudit();
            audit.setId(UUID.randomUUID());
            audit.setSiteId(site.getId());
            audit.setUrl(uri.toString());
            audit.setScore(Math.max(0, 100 - findings.stream().mapToInt(f -> switch (f.severity()) {
                case "critical" -> 15;
                case "warning" -> 4;
                default -> 0;
            }).sum()));
            audit.setFindings(json.writeValueAsString(findings));
            audit.setCreatedAt(Instant.now());
            return auditPayload(audits.save(audit));
        }

        @Transactional(readOnly = true)
        public List<AuditPayload> audits(UUID projectId, UUID playerId, int limit) {
            requireProject(projectId, playerId);
            if (limit < 1 || limit > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit must be 1–100");
            return sites.findByProjectId(projectId).map(site -> audits.findBySiteIdOrderByCreatedAtDesc(
                    site.getId(), PageRequest.of(0, limit)).stream().map(this::auditPayload).toList()).orElseGet(List::of);
        }

        @Transactional(readOnly = true)
        public AuditPayload audit(UUID projectId, UUID playerId, UUID id) {
            requireProject(projectId, playerId);
            AnalyticsSite site = sites.findByProjectId(projectId).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Audit not found"));
            return auditPayload(audits.findByIdAndSiteId(id, site.getId()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Audit not found")));
        }

        private AuditPayload auditPayload(AnalyticsAudit audit) {
            return new AuditPayload(audit.getId(), audit.getUrl(), audit.getScore(),
                    json.readValue(audit.getFindings(), new TypeReference<List<AuditFinding>>() { }), audit.getCreatedAt());
        }

        private List<String> productionAuditUrls(UUID projectId) {
            return recipes.findById(projectId).map(AnalyticsProductionRecipe::getActiveTargets).map(box::decrypt)
                    .map(targets -> {
                        try {
                            List<AnalyticsDeploymentTarget> parsed = json.readValue(targets,
                                    new TypeReference<List<AnalyticsDeploymentTarget>>() { });
                            List<String> urls = new ArrayList<>();
                            for (AnalyticsDeploymentTarget target : parsed) {
                                try { urls.add(AuditScanner.validateUrl(target.url()).toString()); }
                                catch (IllegalArgumentException | ResponseStatusException ignored) { /* Legacy target. */ }
                            }
                            return List.copyOf(urls);
                        } catch (RuntimeException e) { return List.<String>of(); }
                    }).orElseGet(List::of);
        }

        @Transactional
        public GeoSettingsPayload geoSettings(UUID projectId, UUID playerId) {
            AnalyticsSite site = ownedSite(projectId, playerId);
            return geoSettings.findById(site.getId()).map(this::settingsPayload)
                    .orElseGet(() -> new GeoSettingsPayload(List.of(), List.of(), false, null));
        }

        @Transactional
        public GeoSettingsPayload saveGeoSettings(UUID projectId, UUID playerId, GeoSettingsUpdate update) {
            AnalyticsSite site = lockedOwnedSite(projectId, playerId);
            GeoSettings row = geoSettings.findById(site.getId()).orElseGet(() -> {
                GeoSettings fresh = new GeoSettings();
                fresh.setSiteId(site.getId());
                fresh.setBrandTerms("[]");
                fresh.setCompetitors("[]");
                fresh.setWeeklyEnabled(false);
                return fresh;
            });
            List<String> terms = update.brandTerms() == null ? List.of()
                    : update.brandTerms().stream().map(String::strip).filter(value -> !value.isEmpty()).distinct().toList();
            List<GeoCompetitor> competitors = update.competitors() == null ? List.of()
                    : update.competitors().stream().map(value -> new GeoCompetitor(value.name().strip(),
                            value.domain().strip().toLowerCase(Locale.ROOT))).toList();
            boolean wasEnabled = row.isWeeklyEnabled();
            boolean enabled = update.weeklyEnabled();
            row.setBrandTerms(json.writeValueAsString(terms));
            row.setCompetitors(json.writeValueAsString(competitors));
            row.setWeeklyEnabled(enabled);
            if (!enabled) {
                row.setNextRunAt(null);
            } else if (!wasEnabled || row.getNextRunAt() == null) {
                row.setNextRunAt(Instant.now().plus(Duration.ofDays(7)));
            }
            return settingsPayload(geoSettings.save(row));
        }

        @Transactional
        public List<GeoPromptPayload> geoPrompts(UUID projectId, UUID playerId) {
            AnalyticsSite site = ownedSite(projectId, playerId);
            return geoPrompts.findBySiteIdOrderByPositionAscIdAsc(site.getId()).stream()
                    .map(this::promptPayload).toList();
        }

        @Transactional
        public GeoPromptPayload createGeoPrompt(UUID projectId, UUID playerId, GeoPromptCreate input) {
            AnalyticsSite site = lockedOwnedSite(projectId, playerId);
            List<GeoPrompt> existing = geoPrompts.findBySiteIdOrderByPositionAscIdAsc(site.getId());
            if (existing.size() >= 50) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most 50 prompts are supported");
            }
            GeoPrompt row = new GeoPrompt();
            row.setId(UUID.randomUUID());
            row.setSiteId(site.getId());
            row.setText(input.text().strip());
            row.setEnabled(input.enabled() == null || input.enabled());
            row.setPosition(input.position() == null ? existing.size() : input.position());
            geoPrompts.save(row);
            normalizePromptPositions(site.getId(), row.getId(), row.getPosition());
            return promptPayload(row);
        }

        @Transactional
        public GeoPromptPayload updateGeoPrompt(UUID projectId, UUID playerId, UUID promptId, GeoPromptUpdate input) {
            AnalyticsSite site = lockedOwnedSite(projectId, playerId);
            GeoPrompt row = ownedPrompt(site.getId(), promptId);
            if (input.text() != null) {
                if (input.text().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Prompt text cannot be blank");
                }
                row.setText(input.text().strip());
            }
            if (input.enabled() != null) row.setEnabled(input.enabled());
            if (input.position() != null) row.setPosition(input.position());
            geoPrompts.save(row);
            if (input.position() != null) normalizePromptPositions(site.getId(), row.getId(), row.getPosition());
            return promptPayload(row);
        }

        @Transactional
        public void deleteGeoPrompt(UUID projectId, UUID playerId, UUID promptId) {
            AnalyticsSite site = lockedOwnedSite(projectId, playerId);
            GeoPrompt row = ownedPrompt(site.getId(), promptId);
            geoPrompts.delete(row);
            normalizePromptPositions(site.getId(), null, 0);
        }

        @Transactional
        public GeoRunPayload queueGeoRun(UUID projectId, UUID playerId, UUID authSessionId) {
            AnalyticsSite site = lockedOwnedSite(projectId, playerId);
            if (geoRuns.existsBySiteIdAndStatusIn(site.getId(), List.of("queued", "running"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "An AI visibility run is already active");
            }
            GeoRun run = new GeoRun();
            run.setId(UUID.randomUUID());
            run.setSiteId(site.getId());
            run.setTrigger("manual");
            run.setStatus("queued");
            run.setAuthSessionId(authSessionId);
            run.setCreatedAt(Instant.now());
            return runPayload(geoRuns.save(run));
        }

        @Transactional
        public List<GeoRunPayload> geoRuns(UUID projectId, UUID playerId, int limit) {
            AnalyticsSite site = ownedSite(projectId, playerId);
            if (limit < 1 || limit > 100) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit must be from 1 to 100");
            }
            return geoRuns.findBySiteIdOrderByCreatedAtDesc(site.getId(), PageRequest.of(0, limit))
                    .stream().map(this::runPayload).toList();
        }

        @Transactional
        public GeoRunDetail geoRun(UUID projectId, UUID playerId, UUID runId) {
            AnalyticsSite site = ownedSite(projectId, playerId);
            GeoRun run = ownedRun(site.getId(), runId);
            return runDetail(run);
        }

        @Transactional(readOnly = true)
        public GeoRunDetail geoEvidenceRun(UUID projectId, UUID playerId, UUID runId) {
            requireProject(projectId, playerId);
            AnalyticsSite site = sites.findByProjectId(projectId).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Analytics site not found"));
            GeoRun run = ownedRun(site.getId(), runId);
            return new GeoRunDetail(runPayload(run), geoResults.findByRunIdOrderByIdAsc(runId, PageRequest.of(0, 100))
                    .stream().map(this::resultPayload).toList());
        }

        @Transactional
        public GeoSummary geoSummary(UUID projectId, UUID playerId, Instant requestedFrom, Instant requestedTo) {
            AnalyticsSite site = ownedSite(projectId, playerId);
            Instant to = requestedTo == null ? Instant.now() : requestedTo;
            Instant from = requestedFrom == null ? to.minus(Duration.ofDays(30)) : requestedFrom;
            if (!from.isBefore(to)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
            }
            List<GeoRun> runs = geoRuns.findBySiteIdAndCreatedAtBetweenOrderByCreatedAtAsc(site.getId(), from, to);
            Map<UUID, GeoRun> runById = new HashMap<>();
            runs.forEach(run -> runById.put(run.getId(), run));
            if (runs.isEmpty()) return new GeoSummary(from, to, List.of(), List.of());
            List<GeoResult> results = geoResults.findByRunIdIn(runById.keySet());
            Map<String, List<GeoResult>> byEngine = new HashMap<>();
            Map<UUID, Map<String, List<GeoResult>>> byRunAndEngine = new HashMap<>();
            for (GeoResult result : results) {
                if (result.getError() != null) continue;
                String key = engineKey(result.getProvider(), result.getModel());
                byEngine.computeIfAbsent(key, ignored -> new ArrayList<>()).add(result);
                byRunAndEngine.computeIfAbsent(result.getRunId(), ignored -> new HashMap<>())
                        .computeIfAbsent(key, ignored -> new ArrayList<>()).add(result);
            }
            List<GeoVoice> voice = byEngine.entrySet().stream().map(entry -> {
                List<GeoResult> rows = entry.getValue();
                GeoResult first = rows.getFirst();
                long mentioned = rows.stream().filter(GeoResult::isMentioned).count();
                long cited = rows.stream().filter(GeoResult::isCited).count();
                double positionTotal = rows.stream().filter(row -> row.isMentioned() && row.getPosition() != null)
                        .mapToInt(GeoResult::getPosition).sum();
                long positionCount = rows.stream().filter(row -> row.isMentioned() && row.getPosition() != null).count();
                Map<String, Long> competitors = new HashMap<>();
                rows.stream().filter(row -> row.getCompetitorsMentioned() != null)
                        .flatMap(row -> readStringList(row.getCompetitorsMentioned()).stream())
                        .forEach(name -> competitors.merge(name, 1L, Long::sum));
                return new GeoVoice(first.getProvider(), first.getModel(), mentioned, rows.size(),
                        ratio(mentioned, rows.size()), cited, ratio(cited, rows.size()),
                        positionCount == 0 ? null : positionTotal / positionCount, Map.copyOf(competitors));
            }).sorted(Comparator.comparing(GeoVoice::provider).thenComparing(GeoVoice::model)).toList();
            List<GeoTrendPoint> trend = new ArrayList<>();
            for (GeoRun run : runs) {
                Map<String, List<GeoResult>> engineRows = byRunAndEngine.getOrDefault(run.getId(), Map.of());
                engineRows.forEach((key, rows) -> {
                    GeoResult first = rows.getFirst();
                    long mentioned = rows.stream().filter(GeoResult::isMentioned).count();
                    long cited = rows.stream().filter(GeoResult::isCited).count();
                    trend.add(new GeoTrendPoint(run.getId(), run.getCreatedAt(), first.getProvider(),
                            first.getModel(), mentioned, rows.size(), ratio(mentioned, rows.size()),
                            cited, ratio(cited, rows.size())));
                });
            }
            return new GeoSummary(from, to, voice, trend);
        }

        @Transactional
        public List<GeoClaim> claimGeoRuns(int limit, List<UUID> active) {
            if (limit < 1 || limit > 100) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Claim limit must be from 1 to 100");
            }
            Instant now = Instant.now();
            abandonGeoRuns(java.util.Set.copyOf(active), now);
            @SuppressWarnings("unchecked")
            List<UUID> manualIds = entityManager.createNativeQuery(
                    "select id from analytics_geo_runs where trigger = 'manual' and status = 'queued' "
                            + "order by created_at asc limit :limit for update skip locked")
                    .setParameter("limit", limit).getResultList();
            List<GeoClaim> claimed = new ArrayList<>();
            for (GeoRun run : geoRuns.findAllById(manualIds)) {
                if (!"queued".equals(run.getStatus())) continue;
                run.setStatus("running");
                run.setStartedAt(now);
                claimed.add(claim(run));
            }
            int remaining = limit - claimed.size();
            if (remaining > 0) {
                @SuppressWarnings("unchecked")
                List<UUID> dueSiteIds = entityManager.createNativeQuery(
                        "select s.site_id from analytics_geo_settings s join analytics_sites a on a.id = s.site_id "
                                + "where s.weekly_enabled = true and s.next_run_at <= :now "
                                + "and not exists (select 1 from analytics_geo_runs r "
                                + "where r.site_id = s.site_id and r.status in ('queued', 'running')) "
                                + "order by s.next_run_at asc limit :limit for update of a, s skip locked")
                        .setParameter("now", now).setParameter("limit", remaining).getResultList();
                for (UUID siteId : dueSiteIds) {
                    GeoSettings schedule = geoSettings.findById(siteId).orElse(null);
                    if (schedule == null || !schedule.isWeeklyEnabled() || schedule.getNextRunAt() == null
                            || schedule.getNextRunAt().isAfter(now)) continue;
                    GeoRun run = new GeoRun();
                    run.setId(UUID.randomUUID());
                    run.setSiteId(siteId);
                    run.setTrigger("weekly");
                    run.setStatus("running");
                    run.setCreatedAt(now);
                    run.setStartedAt(now);
                    geoRuns.save(run);
                    schedule.setNextRunAt(schedule.getNextRunAt().plus(Duration.ofDays(7)));
                    claimed.add(claim(run));
                }
            }
            return claimed;
        }

        /** The runner keeps its runs in memory: a running run it no longer reports as active (it restarted
         *  mid-run) would hold the site's single active slot forever, so it fails as interrupted. */
        private void abandonGeoRuns(java.util.Set<UUID> active, Instant now) {
            List<GeoRun> running = entityManager.createQuery(
                            "select r from AnalyticsGeoRun r where r.status = 'running' and r.startedAt < :cutoff",
                            GeoRun.class)
                    .setParameter("cutoff", now.minus(GEO_CLAIM_GRACE))
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
            for (GeoRun run : running) {
                if (active.contains(run.getId())) continue;
                run.setStatus("failed");
                run.setReason("interrupted");
                run.setFinishedAt(now);
            }
        }

        /** A weekly run that could not start is dropped (it holds no results) and its site is due again
         *  after {@code delay} instead of a week later. */
        @Transactional
        public GeoSettingsPayload postponeGeoRun(UUID runId, Duration delay) {
            GeoRun run = lockedRun(runId);
            if (!"weekly".equals(run.getTrigger()) || !"running".equals(run.getStatus())
                    || geoResults.existsByRunId(runId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a weekly run that has not started can be postponed");
            }
            GeoSettings schedule = entityManager.find(GeoSettings.class, run.getSiteId(), LockModeType.PESSIMISTIC_WRITE);
            geoRuns.delete(run);
            if (schedule == null) {
                return new GeoSettingsPayload(List.of(), List.of(), false, null);
            }
            if (schedule.isWeeklyEnabled()) {
                schedule.setNextRunAt(Instant.now().plus(delay));
            }
            return new GeoSettingsPayload(readStringList(schedule.getBrandTerms()),
                    readCompetitors(schedule.getCompetitors()), schedule.isWeeklyEnabled(), schedule.getNextRunAt());
        }

        /** The sign-in a run acts as: the one that queued it while it lasts, else the player's latest one. */
        private UUID runnerAuthSession(GeoRun run, UUID playerId) {
            if (run.getAuthSessionId() != null) {
                @SuppressWarnings("unchecked")
                List<UUID> live = entityManager.createNativeQuery(
                                "select id from auth_sessions where id = :id and player_id = :player and revoked_at is null")
                        .setParameter("id", run.getAuthSessionId()).setParameter("player", playerId).getResultList();
                if (!live.isEmpty()) return live.getFirst();
            }
            @SuppressWarnings("unchecked")
            List<UUID> latest = entityManager.createNativeQuery(
                            "select id from auth_sessions where player_id = :player and revoked_at is null "
                                    + "order by last_used_at desc limit 1")
                    .setParameter("player", playerId).getResultList();
            return latest.isEmpty() ? null : latest.getFirst();
        }

        private List<String> ownDomains(AnalyticsSite site) {
            List<String> configured = readOrigins(site.getAllowedOrigins());
            List<String> origins = configured.isEmpty() ? productionOrigins(site.getProjectId()) : configured;
            LinkedHashSet<String> domains = new LinkedHashSet<>();
            for (String origin : origins) {
                try {
                    String host = URI.create(origin).getHost();
                    if (host == null || host.isBlank()) continue;
                    host = host.toLowerCase(Locale.ROOT);
                    domains.add(host.startsWith("www.") ? host.substring(4) : host);
                } catch (IllegalArgumentException ignored) {
                    // An unparsable origin names no domain.
                }
            }
            return List.copyOf(domains);
        }

        @Transactional
        public GeoRunPayload setGeoRunStatus(UUID runId, String status, String reason) {
            if (!List.of("running", "finished", "failed").contains(status)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Run status must be running, finished, or failed");
            }
            GeoRun run = lockedRun(runId);
            if (run.getFinishedAt() != null) {
                if (run.getStatus().equals(status)) return runPayload(run);
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Run is already complete");
            }
            Instant now = Instant.now();
            if ("running".equals(status)) {
                if ("queued".equals(run.getStatus())) {
                    run.setStatus(status);
                    run.setStartedAt(now);
                }
            } else {
                if (!"running".equals(run.getStatus())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a running run can finish");
                }
                run.setStatus(status);
                run.setFinishedAt(now);
                if ("failed".equals(status)) {
                    run.setReason(reason == null || reason.isBlank() ? "error" : reason.strip());
                }
            }
            return runPayload(run);
        }

        @Transactional
        public GeoRunDetail postGeoResults(UUID runId, List<GeoResultInput> inputs) {
            GeoRun run = lockedRun(runId);
            if (!"running".equals(run.getStatus()) || run.getFinishedAt() != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Results can only be added to a running run");
            }
            List<GeoPrompt> prompts = geoPrompts.findBySiteIdOrderByPositionAscIdAsc(run.getSiteId());
            var promptIds = prompts.stream().map(GeoPrompt::getId).collect(java.util.stream.Collectors.toSet());
            for (GeoResultInput input : inputs) {
                if (!promptIds.contains(input.promptId())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Result prompt does not belong to this site");
                }
                String provider = input.provider().strip();
                String model = input.model().strip();
                GeoResult row = geoResults.findByRunIdAndPromptIdAndProviderAndModel(runId, input.promptId(),
                        provider, model).orElseGet(() -> {
                            GeoResult fresh = new GeoResult();
                            fresh.setId(UUID.randomUUID());
                            fresh.setRunId(runId);
                            fresh.setPromptId(input.promptId());
                            fresh.setProvider(provider);
                            fresh.setModel(model);
                            return fresh;
                        });
                row.setMentioned(input.mentioned());
                row.setCited(input.cited());
                row.setPosition(input.position());
                row.setCitedUrls(json.writeValueAsString(input.citedUrls() == null ? List.of() : input.citedUrls()));
                row.setCompetitorsMentioned(json.writeValueAsString(
                        input.competitorsMentioned() == null ? List.of() : input.competitorsMentioned()));
                row.setExcerpt(cleanNullable(input.excerpt()));
                row.setError(cleanNullable(input.error()));
                geoResults.save(row);
            }
            return runDetail(run);
        }

        private AnalyticsSite ownedSite(UUID projectId, UUID playerId) {
            requireProject(projectId, playerId);
            return sites.findByProjectId(projectId).orElseGet(() -> createSite(projectId, playerId));
        }

        private AnalyticsSite lockedOwnedSite(UUID projectId, UUID playerId) {
            requireProject(projectId, playerId);
            AnalyticsSite site = sites.findByProjectIdForUpdate(projectId).orElse(null);
            if (site == null) {
                createSite(projectId, playerId);
                site = sites.findByProjectIdForUpdate(projectId).orElseThrow();
            }
            return site;
        }

        /** The owner's site, locked like every GEO write; for {@link GeoSuggestionFeature}'s atomic accept. */
        AnalyticsSite lockedGeoSite(UUID projectId, UUID playerId) {
            return lockedOwnedSite(projectId, playerId);
        }

        private GeoPrompt ownedPrompt(UUID siteId, UUID promptId) {
            GeoPrompt prompt = geoPrompts.findById(promptId).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Prompt not found"));
            if (!prompt.getSiteId().equals(siteId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Prompt not found");
            }
            return prompt;
        }

        private GeoRun ownedRun(UUID siteId, UUID runId) {
            GeoRun run = geoRuns.findById(runId).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
            if (!run.getSiteId().equals(siteId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found");
            }
            return run;
        }

        private GeoRun lockedRun(UUID runId) {
            GeoRun run = entityManager.find(GeoRun.class, runId, LockModeType.PESSIMISTIC_WRITE);
            if (run == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found");
            }
            return run;
        }

        private void normalizePromptPositions(UUID siteId, UUID movingId, int desiredPosition) {
            List<GeoPrompt> rows = new ArrayList<>(geoPrompts.findBySiteIdOrderByPositionAscIdAsc(siteId));
            rows.sort(Comparator.comparingInt(GeoPrompt::getPosition).thenComparing(GeoPrompt::getId));
            if (movingId != null) {
                GeoPrompt moving = rows.stream().filter(row -> row.getId().equals(movingId)).findFirst().orElse(null);
                if (moving != null) {
                    rows.remove(moving);
                    rows.add(Math.min(desiredPosition, rows.size()), moving);
                }
            }
            for (int i = 0; i < rows.size(); i++) rows.get(i).setPosition(i);
            geoPrompts.saveAll(rows);
        }

        private GeoSettingsPayload settingsPayload(GeoSettings row) {
            return new GeoSettingsPayload(readStringList(row.getBrandTerms()), readCompetitors(row.getCompetitors()),
                    row.isWeeklyEnabled(), row.getNextRunAt());
        }

        private List<GeoCompetitor> readCompetitors(String value) {
            if (value == null || value.isBlank()) return List.of();
            try {
                return json.readValue(value, new TypeReference<List<GeoCompetitor>>() { });
            } catch (RuntimeException exception) {
                return List.of();
            }
        }

        private List<String> readStringList(String value) {
            if (value == null || value.isBlank()) return List.of();
            try {
                return json.readValue(value, new TypeReference<List<String>>() { });
            } catch (RuntimeException exception) {
                return List.of();
            }
        }

        private GeoPromptPayload promptPayload(GeoPrompt row) {
            return new GeoPromptPayload(row.getId(), row.getText(), row.isEnabled(), row.getPosition());
        }

        private GeoRunPayload runPayload(GeoRun row) {
            return new GeoRunPayload(row.getId(), row.getTrigger(), row.getStatus(), row.getCreatedAt(),
                    row.getStartedAt(), row.getFinishedAt(), row.getReason());
        }

        private GeoResultPayload resultPayload(GeoResult row) {
            return new GeoResultPayload(row.getId(), row.getPromptId(), row.getProvider(), row.getModel(),
                    row.isMentioned(), row.isCited(), row.getPosition(), readStringList(row.getCitedUrls()),
                    readStringList(row.getCompetitorsMentioned()), row.getExcerpt(), row.getError());
        }

        private GeoRunDetail runDetail(GeoRun run) {
            return new GeoRunDetail(runPayload(run), geoResults.findByRunIdOrderByProviderAscModelAscPromptIdAsc(run.getId())
                    .stream().map(this::resultPayload).toList());
        }

        private GeoClaim claim(GeoRun run) {
            GeoSettings settings = geoSettings.findById(run.getSiteId()).orElse(null);
            AnalyticsSite site = sites.findById(run.getSiteId()).orElseThrow();
            List<String> terms = settings == null ? List.of() : readStringList(settings.getBrandTerms());
            List<GeoCompetitor> competitors = settings == null ? List.of() : readCompetitors(settings.getCompetitors());
            List<GeoPromptPayload> prompts = geoPrompts.findBySiteIdOrderByPositionAscIdAsc(run.getSiteId()).stream()
                    .filter(GeoPrompt::isEnabled).map(this::promptPayload).toList();
            return new GeoClaim(run.getId(), site.getId(), site.getProjectId(), site.getPlayerId(),
                    runnerAuthSession(run, site.getPlayerId()), run.getTrigger(), terms, competitors,
                    ownDomains(site), prompts);
        }

        private static String engineKey(String provider, String model) {
            return provider + "\u0000" + model;
        }

        private static double ratio(long numerator, long denominator) {
            return denominator == 0 ? 0 : (double) numerator / denominator;
        }

        @Transactional
        public SitePayload getSite(UUID projectId, UUID playerId) {
            requireProject(projectId, playerId);
            AnalyticsSite site = sites.findByProjectId(projectId).orElseGet(() -> createSite(projectId, playerId));
            return payload(site);
        }

        @Transactional
        public SitePayload updateSite(UUID projectId, UUID playerId, SiteUpdate update) {
            requireProject(projectId, playerId);
            AnalyticsSite site = sites.findByProjectId(projectId).orElseGet(() -> createSite(projectId, playerId));
            if (update.allowedOrigins() != null) {
                List<String> origins = normalizeOrigins(update.allowedOrigins());
                site.setAllowedOrigins(json.writeValueAsString(origins));
            }
            if (update.googleConnectionId() != null) {
                String value = cleanNullable(update.googleConnectionId());
                UUID connectionId = null;
                if (value != null) {
                    try {
                        connectionId = UUID.fromString(value);
                    } catch (IllegalArgumentException exception) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Google connection id");
                    }
                    if (!googleConnections.existsByIdAndPlayerId(connectionId, playerId)) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Google connection not found");
                    }
                }
                site.setGoogleConnectionId(connectionId);
            }
            if (update.ga4PropertyId() != null) {
                site.setGa4PropertyId(ga4PropertyId(update.ga4PropertyId()));
            }
            if (update.searchConsoleSiteUrl() != null) {
                site.setSearchConsoleSiteUrl(searchConsoleSiteUrl(update.searchConsoleSiteUrl()));
            }
            site.setUpdatedAt(Instant.now());
            return payload(sites.save(site));
        }

        @Transactional
        public SitePayload regenerateKey(UUID projectId, UUID playerId) {
            requireProject(projectId, playerId);
            AnalyticsSite site = sites.findByProjectId(projectId).orElseGet(() -> createSite(projectId, playerId));
            site.setSiteKey(newSiteKey());
            site.setUpdatedAt(Instant.now());
            return payload(sites.save(site));
        }

        private AnalyticsSite createSite(UUID projectId, UUID playerId) {
            Instant now = Instant.now();
            sites.insertIfAbsent(UUID.randomUUID(), projectId, playerId, newSiteKey(),
                    json.writeValueAsString(productionOrigins(projectId)), now);
            return sites.findByProjectId(projectId).orElseThrow();
        }

        private SitePayload payload(AnalyticsSite site) {
            List<String> configured = readOrigins(site.getAllowedOrigins());
            List<String> effective = configured.isEmpty() ? productionOrigins(site.getProjectId()) : configured;
            String snippet = "<script defer src=\"" + publicBaseUrl + TRACKER_PATH
                    + "\" data-site=\"" + site.getSiteKey() + "\"></script>";
            return new SitePayload(site.getSiteKey(), effective, snippet, site.getGoogleConnectionId(),
                    site.getGa4PropertyId(), site.getSearchConsoleSiteUrl());
        }

        /** GA4 properties are stored as their numeric id; {@code properties/123} is accepted too. */
        private static String ga4PropertyId(String value) {
            String cleaned = cleanNullable(value);
            if (cleaned == null) return null;
            String id = cleaned.startsWith("properties/") ? cleaned.substring("properties/".length()) : cleaned;
            if (!id.matches("\\d{1,20}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid GA4 property id");
            }
            return id;
        }

        /** Search Console properties are URL prefixes ({@code https://example.com/}) or {@code sc-domain:} hosts. */
        private static String searchConsoleSiteUrl(String value) {
            String cleaned = cleanNullable(value);
            if (cleaned == null) return null;
            boolean valid;
            if (cleaned.startsWith("sc-domain:")) {
                valid = cleaned.substring("sc-domain:".length()).matches("[A-Za-z0-9.-]{1,253}");
            } else {
                try {
                    URI uri = URI.create(cleaned);
                    valid = uri.getHost() != null && uri.getUserInfo() == null
                            && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()));
                } catch (IllegalArgumentException exception) {
                    valid = false;
                }
            }
            if (!valid) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Search Console property");
            }
            return cleaned;
        }

        /** Hosts of the production targets and configured origins, used to suggest matching Google properties. */
        @Transactional(readOnly = true)
        public List<String> siteHosts(UUID projectId) {
            LinkedHashSet<String> hosts = new LinkedHashSet<>();
            List<String> origins = new java.util.ArrayList<>(productionOrigins(projectId));
            sites.findByProjectId(projectId).ifPresent(site -> origins.addAll(readOrigins(site.getAllowedOrigins())));
            for (String origin : origins) {
                try {
                    String host = URI.create(origin).getHost();
                    if (host != null) hosts.add(host.toLowerCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // Origins are normalized on write; a malformed legacy value only loses its suggestion.
                }
            }
            return List.copyOf(hosts);
        }

        private List<String> productionOrigins(UUID projectId) {
            return recipes.findById(projectId).map(AnalyticsProductionRecipe::getActiveTargets)
                    .map(box::decrypt)
                    .map(this::parseTargets)
                    .orElseGet(List::of);
        }

        private List<String> parseTargets(String targetsJson) {
            if (targetsJson == null || targetsJson.isBlank()) return List.of();
            try {
                List<AnalyticsDeploymentTarget> targets =
                        json.readValue(targetsJson, new TypeReference<List<AnalyticsDeploymentTarget>>() { });
                LinkedHashSet<String> origins = new LinkedHashSet<>();
                for (AnalyticsDeploymentTarget target : targets) {
                    try {
                        origins.add(originOf(target.url()));
                    } catch (IllegalArgumentException ignored) {
                        // Ignore malformed legacy destinations rather than preventing analytics setup.
                    }
                }
                return List.copyOf(origins);
            } catch (RuntimeException exception) {
                return List.of();
            }
        }

        private List<String> readOrigins(String originsJson) {
            if (originsJson == null || originsJson.isBlank()) return List.of();
            try {
                return json.readValue(originsJson, new TypeReference<List<String>>() { });
            } catch (RuntimeException exception) {
                return List.of();
            }
        }

        private List<String> normalizeOrigins(List<String> origins) {
            if (origins.size() > 20) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most 20 allowed origins are supported");
            }
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String origin : origins) {
                try {
                    normalized.add(originOf(origin));
                } catch (IllegalArgumentException exception) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Allowed origins must be http(s) origins without a path", exception);
                }
            }
            return List.copyOf(normalized);
        }

        private String originOf(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Origin is required");
            URI uri;
            try {
                uri = URI.create(value.strip());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid origin", exception);
            }
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null || uri.getRawAuthority() == null || uri.getUserInfo() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getPort() < -1 || uri.getPort() > 65535)) {
                throw new IllegalArgumentException("Invalid origin");
            }
            String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if ((normalizedScheme.equals("https") && port == 443) || (normalizedScheme.equals("http") && port == 80)) {
                port = -1;
            }
            return normalizedScheme + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                    + (port < 0 ? "" : ":" + port);
        }

        public void collect(String body, jakarta.servlet.http.HttpServletRequest request) {
            if (body.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Analytics payload exceeds 4 KB");
            }
            Beacon beacon;
            try {
                beacon = parseBeacon(body);
            } catch (RuntimeException exception) {
                return;
            }
            if (beacon == null) return;
            AnalyticsSite site = sites.findBySiteKey(beacon.site()).orElse(null);
            if (site == null) return;

            String originHeader = request.getHeader(HttpHeaders.ORIGIN);
            if (originHeader == null) return;
            String origin;
            try {
                origin = originOf(originHeader);
            } catch (IllegalArgumentException exception) {
                return;
            }
            List<String> allowed = readOrigins(site.getAllowedOrigins());
            if (allowed.isEmpty()) allowed = productionOrigins(site.getProjectId());
            if (!allowed.contains(origin)) return;

            Instant now = Instant.now();
            String ip = Auth.readIpAddress(request);
            if (ip == null || !rateAllowed(site.getId(), ip, now)) return;
            String ua = Auth.readUserAgent(request);
            String visitorHash = sha256(dailySalt.get(now) + site.getId() + ip + (ua == null ? "" : ua));
            String sessionHash = sessionHash(site.getId(), visitorHash, now);

            AnalyticsEvent event = new AnalyticsEvent();
            event.setSiteId(site.getId());
            event.setOccurredAt(now);
            event.setKind(beacon.kind());
            event.setPath(limit(beacon.path(), 2048));
            event.setReferrerHost(referrerHost(beacon.referrer()));
            event.setUtmSource(limit(beacon.utmSource(), 255));
            event.setUtmMedium(limit(beacon.utmMedium(), 255));
            event.setUtmCampaign(limit(beacon.utmCampaign(), 255));
            event.setVisitorHash(visitorHash);
            event.setSessionHash(sessionHash);
            event.setDevice(device(ua));
            event.setBrowser(browser(ua));
            event.setOs(os(ua));
            event.setCountry(country(request.getHeader("CF-IPCountry")));
            event.setTarget(limit(beacon.target(), 2048));
            event.setDurationMs(beacon.durationMs());
            events.save(event);
        }

        private Beacon parseBeacon(String body) {
            var node = json.readTree(body);
            String site = string(node.path("site"), 128);
            String kind = string(node.path("kind"), 16);
            if (site == null || !kind.equals("pageview") && !kind.equals("click") && !kind.equals("leave")) {
                return null;
            }
            String path = string(node.path("path"), 2048);
            if (path == null || !path.startsWith("/") || path.startsWith("//")) return null;
            String referrer = string(node.path("referrer"), 2048);
            Long duration = node.path("duration").isNumber() ? node.path("duration").longValue() : null;
            if (duration != null) duration = Math.max(0, Math.min(duration, 86_400_000L));
            return new Beacon(site, kind, path, referrer,
                    string(node.path("utm_source"), 255), string(node.path("utm_medium"), 255),
                    string(node.path("utm_campaign"), 255), string(node.path("target"), 2048), duration);
        }

        private String string(tools.jackson.databind.JsonNode node, int maxLength) {
            if (node == null || !node.isTextual()) return null;
            String value = node.asString().strip();
            return value.isEmpty() ? null : limit(value, maxLength);
        }

        private boolean rateAllowed(UUID siteId, String ip, Instant now) {
            String key = siteId + ":" + ip;
            boolean[] allowed = { false };
            rateLimits.compute(key, (ignored, previous) -> {
                if (previous == null || now.toEpochMilli() - previous.startedAt() >= 60_000L) {
                    allowed[0] = true;
                    return new RateWindow(now.toEpochMilli(), 1);
                }
                if (previous.count() < RATE_LIMIT_PER_MINUTE) {
                    allowed[0] = true;
                    return new RateWindow(previous.startedAt(), previous.count() + 1);
                }
                return previous;
            });
            return allowed[0];
        }

        private String sessionHash(UUID siteId, String visitorHash, Instant now) {
            String key = siteId + ":" + visitorHash;
            long time = now.toEpochMilli();
            SessionState state = activeSessions.compute(key, (ignored, previous) -> {
                if (previous == null || time - previous.lastSeen() >= SESSION_IDLE_MILLIS) {
                    String nonce = UUID.randomUUID().toString();
                    return new SessionState(sha256(visitorHash + nonce), time);
                }
                return new SessionState(previous.hash(), time);
            });
            return state.hash();
        }

        private String referrerHost(String referrer) {
            if (referrer == null) return null;
            try {
                return limit(URI.create(referrer).getHost(), 255);
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        private String country(String value) {
            if (value == null) return null;
            String normalized = value.strip().toUpperCase(Locale.ROOT);
            return normalized.matches("[A-Z]{2}") && !normalized.equals("XX") ? normalized : null;
        }

        private String device(String ua) {
            if (ua == null) return "unknown";
            String lower = ua.toLowerCase(Locale.ROOT);
            if (lower.contains("ipad") || lower.contains("tablet")) return "tablet";
            if (lower.contains("mobi") || lower.contains("iphone") || lower.contains("android")) return "mobile";
            return "desktop";
        }

        private String browser(String ua) {
            if (ua == null) return "unknown";
            String lower = ua.toLowerCase(Locale.ROOT);
            if (lower.contains("edg/")) return "Edge";
            if (lower.contains("opr/") || lower.contains("opera")) return "Opera";
            if (lower.contains("chrome/") && !lower.contains("chromium")) return "Chrome";
            if (lower.contains("firefox/")) return "Firefox";
            if (lower.contains("safari/") && !lower.contains("chrome/")) return "Safari";
            return "other";
        }

        private String os(String ua) {
            if (ua == null) return "unknown";
            String lower = ua.toLowerCase(Locale.ROOT);
            if (lower.contains("windows")) return "Windows";
            if (lower.contains("android")) return "Android";
            if (lower.contains("iphone") || lower.contains("ipad") || lower.contains("ios")) return "iOS";
            if (lower.contains("mac os") || lower.contains("macintosh")) return "macOS";
            if (lower.contains("linux")) return "Linux";
            return "other";
        }

        private String newSiteKey() {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }

        private static String sha256(String value) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }

        private static String limit(String value, int max) {
            if (value == null) return null;
            return value.length() <= max ? value : value.substring(0, max);
        }

        private static String cleanNullable(String value) {
            if (value == null) return null;
            String normalized = value.strip();
            return normalized.isEmpty() ? null : normalized;
        }

        private static String stripTrailingSlash(String value) {
            if (value == null) return "";
            String result = value.strip().split(",", 2)[0].strip();
            while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
            return result;
        }

        @Scheduled(cron = "${app.analytics.purge-cron:0 15 4 * * *}")
        @Transactional
        public void purgeExpiredEvents() {
            events.deleteOlderThan(Instant.now().minus(java.time.Period.ofMonths(13)));
        }

        @Scheduled(fixedDelayString = "${app.analytics.memory-cleanup-ms:300000}")
        public void cleanInMemoryState() {
            long now = System.currentTimeMillis();
            rateLimits.entrySet().removeIf(entry -> now - entry.getValue().startedAt() > 120_000L);
            activeSessions.entrySet().removeIf(entry -> now - entry.getValue().lastSeen() > SESSION_IDLE_MILLIS);
        }

        private record Beacon(String site, String kind, String path, String referrer, String utmSource,
                              String utmMedium, String utmCampaign, String target, Long durationMs) { }
        private record RateWindow(long startedAt, int count) { }
        private record SessionState(String hash, long lastSeen) { }
        private record AnalyticsDeploymentTarget(String label, String url) { }

        private static final String TRACKER = "(()=>{const s=document.currentScript,k=s&&s.dataset.site;"
                + "if(!k||['1','yes'].includes(navigator.doNotTrack)||['1','yes'].includes(window.doNotTrack)"
                + "||navigator.globalPrivacyControl)return;"
                + "const u=new URL('collect',s.src).href;let visible=0,"
                + "shown=document.visibilityState==='visible'?Date.now():0,last=location.href;"
                + "const send=(kind,extra={})=>{const q=new URLSearchParams(location.search);"
                + "const d={site:k,kind,path:location.pathname,referrer:document.referrer,"
                + "utm_source:q.get('utm_source'),utm_medium:q.get('utm_medium'),utm_campaign:q.get('utm_campaign'),...extra};"
                + "navigator.sendBeacon(u,new Blob([JSON.stringify(d)],{type:'text/plain'}))};"
                + "send('pageview');const nav=()=>{if(last!==location.href){last=location.href;visible=0;"
                + "shown=document.visibilityState==='visible'?Date.now():0;send('pageview')}};"
                + "for(const m of ['pushState','replaceState']){const f=history[m];history[m]=function(...a){const r=f.apply(this,a);nav();return r}}"
                + "addEventListener('popstate',nav);document.addEventListener('visibilitychange',()=>{const n=Date.now();"
                + "if(document.visibilityState==='visible'&&!shown)shown=n;else if(document.visibilityState!=='visible'&&shown){"
                + "visible+=n-shown;shown=0}});addEventListener('click',e=>{const x=e.target.closest('a[href],button,[data-mooi-track]');"
                + "if(!x)return;const a=x.closest('a[href]');const v=x.dataset.mooiTrack||a?.href||x.innerText||x.getAttribute('aria-label')||'';"
                + "send('click',{target:v.trim().slice(0,80)})},true);"
                + "addEventListener('pagehide',()=>send('leave',{duration:visible+(shown?Date.now()-shown:0)}))})();";
    }

    /** No ambient proxy, DNS re-resolution, cookies or automatic redirects are used by the auditor.
     *  Its URL, address and robots checks are shared with {@link GeoSiteEvidenceFeature}. */
    static final class AuditScanner {
        private static final int MAX_BYTES = 2 * 1024 * 1024;
        private static final List<String> CRAWLERS = List.of("GPTBot", "OAI-SearchBot", "ChatGPT-User",
                "ClaudeBot", "Claude-SearchBot", "PerplexityBot", "Google-Extended", "Applebot-Extended", "CCBot");
        private static final java.util.concurrent.ExecutorService IO = Executors.newVirtualThreadPerTaskExecutor();
        static final Pattern TAG = Pattern.compile("(?is)<([a-z][a-z0-9:-]*)\\b((?:[^>\"']++|\"[^\"]*+\"|'[^']*+')*+)>");
        static final Pattern ATTR = Pattern.compile("([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
        private final ObjectMapper json;
        private final List<AuditFinding> findings = new ArrayList<>();
        private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);

        AuditScanner(ObjectMapper json) { this.json = json; }

        static URI validateUrl(String value) {
            try {
                URI uri = URI.create(value);
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                        || uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65535
                        || value.length() > 2048) throw new IllegalArgumentException();
                return uri;
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Audit URL must be an absolute http(s) URL without credentials or fragment");
            }
        }

        List<AuditFinding> scan(URI uri) {
            try {
                return bounded(() -> scanResources(uri), deadline);
            } catch (ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                // A single wall-clock deadline also covers DNS, parsing and all supporting resources.
                List<AuditFinding> incomplete = new ArrayList<>();
                List<String> checks = new ArrayList<>(List.of("https", "status", "title", "description", "canonical",
                        "lang", "open-graph", "h1", "headings", "server-content", "json-ld", "robots",
                        "llms-presence", "llms-format", "sitemap"));
                for (String crawler : CRAWLERS) checks.add("robots-" + crawler.toLowerCase(Locale.ROOT));
                for (String id : checks) incomplete.add(new AuditFinding(id,
                        id.startsWith("robots") || id.startsWith("llms") || id.equals("json-ld") || id.equals("server-content")
                                ? "geo" : "seo", "warning", "Check incomplete: " + id,
                        "The audit could not finish within its 20 s budget, or parsing was interrupted or failed.",
                        "Check production availability and run the audit again."));
                return List.copyOf(incomplete);
            }
        }

        private List<AuditFinding> scanResources(URI uri) {
            Fetch page = fetch(uri);
            check("https", "seo", "critical", "HTTPS", "https".equalsIgnoreCase(uri.getScheme()),
                    "Requested URL: " + uri, "Serve the production site over HTTPS.");
            check("status", "seo", "critical", "HTTP status", page.ok(), page.description(),
                    "Return a successful HTTP response for the production page.");
            inspectHtml(page.ok() ? page.body() : "");
            URI root = uri.resolve("/");
            Fetch robots = fetch(root.resolve("robots.txt"));
            boolean robotsOk = robots.ok() && !robots.body().stripLeading().startsWith("<");
            check("robots", "geo", "warning", "robots.txt", robotsOk, robots.description(),
                    "Publish a plain-text robots.txt at the origin root.");
            for (String crawler : CRAWLERS) {
                boolean blocked = robotsOk && blocked(robots.body(), crawler, uri.getRawPath());
                findings.add(new AuditFinding("robots-" + crawler.toLowerCase(Locale.ROOT), "geo",
                        blocked || !robotsOk ? "warning" : "info", crawler + " crawler access",
                        !robotsOk ? "Cannot determine access without a reachable robots.txt."
                                : blocked ? "robots.txt blocks this crawler for the audited path; this may be intentional."
                                : "robots.txt allows this crawler for the audited path.",
                        "Review this crawler's Allow/Disallow rules against your intended AI visibility policy."));
            }
            Fetch llms = fetch(root.resolve("llms.txt"));
            check("llms-presence", "geo", "warning", "llms.txt presence", llms.ok()
                            && !llms.body().stripLeading().startsWith("<"), llms.description(),
                    "Publish /llms.txt with a Markdown H1 title, summary and links to useful content.");
            check("llms-format", "geo", "warning", "llms.txt format", llms.ok()
                            && Pattern.compile("(?m)^#\\s+\\S+").matcher(llms.body()).find()
                            && Pattern.compile("(?m)^>\\s+\\S+").matcher(llms.body()).find()
                            && Pattern.compile("\\[[^]\\r\\n]+]\\(https?://[^)]+\\)").matcher(llms.body()).find(),
                    "Expected a Markdown H1, blockquote summary and HTTP(S) links.",
                    "Add # Site name, a > summary, and Markdown links to canonical documentation or pages.");
            // Try robots-advertised sitemaps before the conventional fallback; never fetch a foreign host.
            LinkedHashSet<URI> sitemaps = new LinkedHashSet<>();
            var advertised = Pattern.compile("(?im)^\\s*Sitemap:\\s*(\\S+)").matcher(robots.body());
            while (advertised.find() && sitemaps.size() < 3) {
                try {
                    URI candidate = validateUrl(root.resolve(advertised.group(1)).toString());
                    if (sameHost(root, candidate)) sitemaps.add(candidate);
                    else findings.add(new AuditFinding("sitemap-host-" + sitemaps.size(), "seo", "warning",
                            "External sitemap not fetched", "robots.txt advertises a sitemap on another host.",
                            "Expose a sitemap on the production host so it can be audited safely."));
                } catch (RuntimeException ignored) { /* Invalid sitemap declarations are not followed. */ }
            }
            sitemaps.add(root.resolve("sitemap.xml"));
            boolean sitemapOk = false;
            String sitemapDetail = "No reachable XML sitemap (urlset or sitemapindex) found.";
            for (URI candidate : sitemaps) {
                Fetch sitemap = fetch(candidate);
                if (sitemap.ok() && Pattern.compile("(?is)<(?:[\\w-]+:)?(?:urlset|sitemapindex)\\b")
                        .matcher(sitemap.body()).find()) {
                    sitemapOk = true;
                    sitemapDetail = "Reachable XML sitemap: " + candidate;
                    break;
                }
                sitemapDetail += " " + candidate + ": " + sitemap.description();
                if (System.nanoTime() >= deadline) break;
            }
            check("sitemap", "seo", "warning", "XML sitemap", sitemapOk, sitemapDetail,
                    "Publish sitemap.xml with canonical URLs and declare its URL in robots.txt.");
            return List.copyOf(findings);
        }

        private void check(String id, String category, String severity, String title, boolean passed,
                           String detail, String fix) {
            findings.add(new AuditFinding(id, category, passed ? "info" : severity, title,
                    (passed ? "Passed. " : "Needs attention. ") + detail, passed ? "" : fix));
        }

        private void inspectHtml(String html) {
            // Tokenize attributes without assuming their order or quoting. Strip executable/non-visible text first.
            String markup = html.replaceAll("(?is)<!--.*?-->", "");
            String visibleMarkup = markup.replaceAll("(?is)<(script|style|template|noscript)\\b[^>]*>.*?</\\1\\s*>", "");
            Map<String, String> meta = new LinkedHashMap<>();
            String canonical = "";
            String lang = "";
            int h1 = 0;
            int previousHeading = 0;
            boolean hierarchy = true;
            var tags = TAG.matcher(visibleMarkup);
            while (tags.find()) {
                String name = tags.group(1).toLowerCase(Locale.ROOT);
                Map<String, String> attrs = attributes(tags.group(2));
                if (name.equals("meta")) meta.put(attrs.getOrDefault("name", attrs.getOrDefault("property", ""))
                        .toLowerCase(Locale.ROOT), attrs.getOrDefault("content", "").strip());
                if (name.equals("html")) lang = attrs.getOrDefault("lang", "");
                if (name.equals("link") && List.of(attrs.getOrDefault("rel", "").toLowerCase(Locale.ROOT).split("\\s+"))
                        .contains("canonical")) canonical = attrs.getOrDefault("href", "");
                if (name.matches("h[1-6]")) {
                    int level = name.charAt(1) - '0';
                    if (level == 1) h1++;
                    if (level > previousHeading + 1) hierarchy = false;
                    previousHeading = level;
                }
            }
            var titleMatch = Pattern.compile("(?is)<title\\b[^>]*>(.*?)</title\\s*>").matcher(visibleMarkup);
            String title = titleMatch.find() ? text(titleMatch.group(1)) : "";
            check("title", "seo", "warning", "Page title", title.length() >= 10 && title.length() <= 60,
                    "Title length: " + title.length() + " characters (recommended 10–60).", "Set a descriptive, unique title of 10–60 characters.");
            String description = meta.getOrDefault("description", "");
            check("description", "seo", "warning", "Meta description", description.length() >= 50 && description.length() <= 160,
                    "Description length: " + description.length() + " characters (recommended 50–160).",
                    "Add a useful meta description of 50–160 characters.");
            boolean canonicalValid;
            try {
                URI candidate = URI.create(canonical);
                canonicalValid = ("http".equalsIgnoreCase(candidate.getScheme()) || "https".equalsIgnoreCase(candidate.getScheme()))
                        && candidate.getHost() != null;
            }
            catch (IllegalArgumentException e) { canonicalValid = false; }
            check("canonical", "seo", "warning", "Canonical URL", canonicalValid,
                    canonical.isBlank() ? "No canonical URL found." : "Canonical: " + canonical,
                    "Add a link rel=canonical with the absolute preferred production URL.");
            check("lang", "seo", "warning", "Document language", lang.matches("[a-zA-Z]{2,8}(-[a-zA-Z0-9]{1,8})*"),
                    "HTML lang: " + lang, "Set the html lang attribute to the document's BCP 47 language tag.");
            List<String> missingOg = List.of("og:title", "og:description", "og:url", "og:image", "og:type").stream()
                    .filter(key -> meta.getOrDefault(key, "").isBlank()).toList();
            check("open-graph", "seo", "warning", "Open Graph metadata", missingOg.isEmpty(),
                    missingOg.isEmpty() ? "Core Open Graph fields present." : "Missing: " + String.join(", ", missingOg),
                    "Add og:title, og:description, og:url, og:image and og:type metadata.");
            check("h1", "seo", "warning", "Single H1", h1 == 1, "H1 count: " + h1,
                    "Use one descriptive H1 for the main page topic.");
            check("headings", "seo", "warning", "Heading hierarchy", hierarchy && previousHeading > 0,
                    "Headings should start at H1 and descend without skipping levels.", "Use H1, then H2, then H3 for nested sections.");
            var body = Pattern.compile("(?is)<body\\b[^>]*>(.*?)(?:</body\\s*>|$)").matcher(visibleMarkup);
            String bodyMarkup = body.find() ? body.group(1) : "";
            bodyMarkup = bodyMarkup.replaceAll("(?is)<([a-z][a-z0-9]*)\\b[^>]*(?:\\shidden(?=\\s|=|>)|aria-hidden\\s*=\\s*['\"]true['\"])[^>]*>.*?</\\1\\s*>", "");
            int textLength = text(bodyMarkup).length();
            check("server-content", "geo", "critical", "Server-rendered content", textLength >= 500,
                    "Raw HTML body contains approximately " + textLength + " visible text characters; below 500 may indicate JavaScript-only rendering.",
                    "Render the main content on the server or prerender it so crawlers can read it without JavaScript.");
            Set<String> types = new LinkedHashSet<>();
            int jsonBlocks = 0;
            int invalidBlocks = 0;
            var scripts = Pattern.compile("(?is)<script\\b((?:[^>\"']++|\"[^\"]*+\"|'[^']*+')*+)>(.*?)</script\\s*>").matcher(markup);
            while (scripts.find()) {
                if (!"application/ld+json".equalsIgnoreCase(attributes(scripts.group(1)).get("type"))) continue;
                jsonBlocks++;
                try {
                    var node = json.readTree(scripts.group(2));
                    if (node == null || !(node.isObject() || node.isArray())) invalidBlocks++;
                    else collectTypes(node, types, 0);
                } catch (RuntimeException e) { invalidBlocks++; }
            }
            check("json-ld", "geo", "warning", "JSON-LD structured data", jsonBlocks > 0 && invalidBlocks == 0 && !types.isEmpty(),
                    "Blocks: " + jsonBlocks + "; invalid: " + invalidBlocks + "; types: " + String.join(", ", types),
                    "Add valid schema.org JSON-LD: Organization and WebSite for site identity; FAQPage, Product or Article where the visible content supports them.");
        }

        private void collectTypes(tools.jackson.databind.JsonNode node, Set<String> types, int depth) {
            if (depth > 64) return;
            if (node.isObject()) {
                var type = node.path("@type");
                if (type.isTextual()) types.add(type.asString());
                else if (type.isArray()) for (var value : type) if (value.isTextual()) types.add(value.asString());
                for (var property : node.properties()) collectTypes(property.getValue(), types, depth + 1);
            } else if (node.isArray()) for (var child : node) collectTypes(child, types, depth + 1);
        }

        static Map<String, String> attributes(String input) {
            Map<String, String> result = new LinkedHashMap<>();
            var matcher = ATTR.matcher(input);
            while (matcher.find()) result.put(matcher.group(1).toLowerCase(Locale.ROOT),
                    matcher.group(2) != null ? matcher.group(2) : matcher.group(3) != null ? matcher.group(3) : matcher.group(4));
            return result;
        }

        private static String text(String html) {
            return html.replaceAll("(?s)<[^>]*>", " ").replaceAll("&(?:nbsp|#160|#x[aA]0);", " ")
                    .replaceAll("&(?:[a-zA-Z]+|#\\d+|#x[0-9a-fA-F]+);", "x").replaceAll("\\s+", " ").strip();
        }

        private record RobotGroup(List<String> agents, List<String[]> rules) { }

        static boolean blocked(String robots, String crawler, String path) {
            List<RobotGroup> groups = new ArrayList<>();
            RobotGroup group = new RobotGroup(new ArrayList<>(), new ArrayList<>());
            boolean hasRules = false;
            for (String line : robots.split("\\R")) {
                String[] directive = line.split("#", 2)[0].split(":", 2);
                if (directive.length != 2) continue;
                String key = directive[0].strip().toLowerCase(Locale.ROOT);
                String value = directive[1].strip();
                if (key.equals("user-agent")) {
                    if (hasRules) { groups.add(group); group = new RobotGroup(new ArrayList<>(), new ArrayList<>()); hasRules = false; }
                    group.agents().add(value.toLowerCase(Locale.ROOT));
                } else if ((key.equals("allow") || key.equals("disallow")) && !group.agents().isEmpty()) {
                    hasRules = true;
                    if (!value.isEmpty()) group.rules().add(new String[] {key, value});
                }
            }
            groups.add(group);
            String agent = crawler.toLowerCase(Locale.ROOT);
            int specificity = groups.stream().flatMap(g -> g.agents().stream())
                    .filter(a -> a.equals("*") || agent.contains(a)).mapToInt(a -> a.equals("*") ? 0 : a.length()).max().orElse(-1);
            int longest = -1;
            boolean denied = false;
            for (RobotGroup candidate : groups) {
                if (candidate.agents().stream().noneMatch(a -> (a.equals("*") ? 0 : a.length()) == specificity
                        && (a.equals("*") || agent.contains(a)))) continue;
                for (String[] rule : candidate.rules()) {
                    String value = rule[1];
                    boolean end = value.endsWith("$");
                    String raw = end ? value.substring(0, value.length() - 1) : value;
                    int length = raw.replace("*", "").length();
                    if (robotPathMatches(raw, path == null || path.isEmpty() ? "/" : path, end)
                            && (length > longest || length == longest && rule[0].equals("allow"))) {
                        longest = length;
                        denied = rule[0].equals("disallow");
                    }
                }
            }
            return denied;
        }

        // Ordered literal segments avoid exponential regex backtracking on untrusted wildcard rules.
        private static boolean robotPathMatches(String pattern, String path, boolean end) {
            String[] parts = pattern.split("\\*", -1);
            if (!path.startsWith(parts[0])) return false;
            int offset = parts[0].length();
            for (int i = 1; i < parts.length; i++) {
                if (i == parts.length - 1 && end) return path.substring(offset).endsWith(parts[i]);
                int index = path.indexOf(parts[i], offset);
                if (index < 0) return false;
                offset = index + parts[i].length();
            }
            return !end || offset == path.length();
        }

        private record Fetch(int status, String body, String error) {
            boolean ok() { return error == null && status >= 200 && status < 300; }
            String description() { return error != null ? error : "HTTP " + status; }
        }

        private Fetch fetch(URI initial) {
            long requestDeadline = Math.min(deadline - TimeUnit.MILLISECONDS.toNanos(200),
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            try {
                URI uri = initial;
                for (int redirects = 0; redirects <= 3; redirects++) {
                    URI target = validateUrl(uri.toString());
                    InetAddress[] addresses = bounded(() -> InetAddress.getAllByName(target.getHost()), requestDeadline);
                    if (addresses.length == 0) throw new IOException("Host has no addresses");
                    for (InetAddress address : addresses) {
                        if (!publicAddress(address)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Audit refuses non-public, private or metadata addresses");
                    }
                    // Resolve once, validate every returned address, and pin the actual connection to that set.
                    var manager = PoolingHttpClientConnectionManagerBuilder.create()
                            .setDnsResolver(new org.apache.hc.client5.http.DnsResolver() {
                                public InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                                    if (!host.equalsIgnoreCase(target.getHost())) throw new java.net.UnknownHostException("Foreign host");
                                    return addresses;
                                }
                                public String resolveCanonicalHostname(String host) { return target.getHost(); }
                            }).build();
                    var client = HttpClients.custom().setConnectionManager(manager).disableRedirectHandling()
                            .disableAutomaticRetries().disableCookieManagement().disableContentCompression()
                            .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofSeconds(5))
                                    .setConnectionRequestTimeout(Timeout.ofSeconds(5)).build()).build();
                    try {
                        var request = new HttpGet(target);
                        request.setHeader("User-Agent", "MooiAudit/1.0");
                        request.setHeader("Accept-Encoding", "identity");
                        record Reply(int status, String body, String location) { }
                        Reply reply = bounded(() -> client.execute(request, response -> {
                            var entity = response.getEntity();
                            if (entity != null && entity.getContentLength() > MAX_BYTES) throw new IOException("Response exceeds 2 MB");
                            byte[] bytes = entity == null ? new byte[0] : entity.getContent().readNBytes(MAX_BYTES + 1);
                            if (bytes.length > MAX_BYTES) throw new IOException("Response exceeds 2 MB");
                            var location = response.getFirstHeader("Location");
                            return new Reply(response.getCode(), new String(bytes, StandardCharsets.UTF_8),
                                    location == null ? null : location.getValue());
                        }), requestDeadline);
                        if (Set.of(301, 302, 303, 307, 308).contains(reply.status()) && reply.location() != null) {
                            URI next = validateUrl(target.resolve(reply.location()).toString());
                            if (!sameHost(initial, next)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "Audit refuses redirects to another host");
                            uri = next;
                            continue;
                        }
                        return new Fetch(reply.status(), reply.body(), null);
                    } finally {
                        client.close(org.apache.hc.core5.io.CloseMode.IMMEDIATE);
                    }
                }
                return new Fetch(0, "", "Redirect limit exceeded");
            } catch (ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                return new Fetch(0, "", e instanceof TimeoutException ? "Audit time budget or 5 s request timeout exceeded"
                        : e instanceof IOException ? "Fetch failed: " + e.getMessage() : "Resource could not be fetched");
            }
        }

        private static <T> T bounded(java.util.concurrent.Callable<T> work, long until) throws Exception {
            long remaining = until - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            var future = IO.submit(work);
            try { return future.get(remaining, TimeUnit.NANOSECONDS); }
            catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof Exception cause) throw cause;
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } finally { future.cancel(true); }
        }

        private static boolean sameHost(URI first, URI second) {
            return first.getHost().equalsIgnoreCase(second.getHost());
        }

        static boolean publicAddress(InetAddress address) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
            byte[] bytes = address.getAddress();
            int a = Byte.toUnsignedInt(bytes[0]);
            if (bytes.length == 16) {
                // Only native globally routed IPv6; excludes ULA, mapped/compatible, NAT64, Teredo and 6to4.
                int b = Byte.toUnsignedInt(bytes[1]);
                return (a & 0xe0) == 0x20 && !(a == 0x20 && b == 0x02)
                        && !(a == 0x3f && (b & 0xf0) == 0xf0)
                        && !(a == 0x20 && b == 0x01 && (bytes[2] == 0 || (Byte.toUnsignedInt(bytes[2]) == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8)));
            }
            int b = Byte.toUnsignedInt(bytes[1]);
            int c = Byte.toUnsignedInt(bytes[2]);
            return !(a == 0 || a == 10 || a == 127 || a >= 224
                    || a == 168 && b == 63 && c == 129 && Byte.toUnsignedInt(bytes[3]) == 16
                    || a == 100 && b >= 64 && b <= 127 || a == 169 && b == 254
                    || a == 172 && b >= 16 && b <= 31 || a == 192 && (b == 168 || b == 0 || b == 2)
                    || a == 198 && (b == 18 || b == 19 || b == 51 && c == 100)
                    || a == 203 && b == 0 && c == 113);
        }
    }

    @Getter @Setter @NoArgsConstructor
    @Entity(name = "AnalyticsAudit")
    @Table(name = "analytics_audits")
    public static class AnalyticsAudit {
        @Id @Column(name = "id") private UUID id;
        @Column(name = "site_id", nullable = false) private UUID siteId;
        @Column(name = "url", nullable = false, length = 2048) private String url;
        @Column(name = "score", nullable = false) private int score;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
        @Column(name = "findings", nullable = false, columnDefinition = "jsonb") private String findings;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
    }

    public interface AnalyticsAuditRepository extends JpaRepository<AnalyticsAudit, UUID> {
        @Query("select a from AnalyticsAudit a where a.siteId = :site and a.createdAt >= :from and a.createdAt < :to order by a.createdAt desc, a.id desc")
        List<AnalyticsAudit> findEvidenceAudits(@Param("site") UUID site, @Param("from") Instant from,
                @Param("to") Instant to, Pageable page);
        List<AnalyticsAudit> findBySiteIdOrderByCreatedAtDesc(UUID siteId, org.springframework.data.domain.Pageable page);
        java.util.Optional<AnalyticsAudit> findByIdAndSiteId(UUID id, UUID siteId);
    }

    /**
     * GA4 and Search Console reports read through the player's Google connection. Credentials come only from
     * {@link GoogleTokens} and never leave the server; responses are cached in memory for 15 minutes.
     */
    @Service
    public static class AnalyticsGoogleService {
        private static final Duration CACHE_TTL = Duration.ofMinutes(15);
        private static final int CACHE_SIZE = 512;
        private static final int MAX_STREAM_LOOKUPS = 25;
        private static final int MAX_LIST_PAGES = 5;
        private static final List<String> SEARCH_DIMENSIONS = List.of("query", "page", "country", "device", "date");
        private static final List<String> GA4_REPORTS = List.of("summary", "pages", "sources", "timeseries");
        private static final List<String> GA4_METRICS = List.of("activeUsers", "sessions", "screenPageViews",
                "eventCount", "bounceRate", "averageSessionDuration");
        private static final Set<String> QUOTA_REASONS = Set.of("rateLimitExceeded", "userRateLimitExceeded",
                "quotaExceeded", "dailyLimitExceeded", "RATE_LIMIT_EXCEEDED");
        private static final Ga4Metrics NO_METRICS = new Ga4Metrics(0, 0, 0, 0, 0, 0);

        private final AnalyticsService analytics;
        private final AnalyticsSiteRepository sites;
        private final AnalyticsGoogleConnectionRepository connections;
        private final GoogleTokens tokens;
        private final Duration timeout;
        private final HttpClient client;
        private final String adminApiUri;
        private final String dataApiUri;
        private final String searchConsoleApiUri;
        private final ObjectMapper json = JsonMapper.shared();
        private final LinkedHashMap<String, CachedReport> cache = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedReport> eldest) {
                return size() > CACHE_SIZE;
            }
        };

        AnalyticsGoogleService(AnalyticsService analytics, AnalyticsSiteRepository sites,
                AnalyticsGoogleConnectionRepository connections, GoogleTokens tokens,
                @Value("${app.google-oauth.request-timeout}") String timeout,
                @Value("${app.analytics.google.admin-api-uri:https://analyticsadmin.googleapis.com/v1beta}") String adminApiUri,
                @Value("${app.analytics.google.data-api-uri:https://analyticsdata.googleapis.com/v1beta}") String dataApiUri,
                @Value("${app.analytics.google.search-console-api-uri:https://www.googleapis.com/webmasters/v3}") String searchConsoleApiUri) {
            this.analytics = analytics;
            this.sites = sites;
            this.connections = connections;
            this.tokens = tokens;
            this.timeout = DurationStyle.detectAndParse(timeout);
            this.client = HttpClient.newBuilder().connectTimeout(this.timeout)
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            this.adminApiUri = AnalyticsService.stripTrailingSlash(adminApiUri);
            this.dataApiUri = AnalyticsService.stripTrailingSlash(dataApiUri);
            this.searchConsoleApiUri = AnalyticsService.stripTrailingSlash(searchConsoleApiUri);
        }

        public GooglePropertiesPayload properties(UUID projectId, UUID playerId, UUID connectionId) {
            analytics.requireProject(projectId, playerId);
            AnalyticsSite site = sites.findByProjectId(projectId).orElse(null);
            UUID chosen = connectionId != null ? connectionId : site == null ? null : site.getGoogleConnectionId();
            if (chosen == null) throw notConnected();
            if (!connections.existsByIdAndPlayerId(chosen, playerId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Google connection not found");
            }
            List<String> hosts = analytics.siteHosts(projectId);
            String key = "properties|" + projectId + "|" + chosen + "|" + hosts;
            PropertyListing listing = cached(key, PropertyListing.class);
            if (listing == null) {
                listing = listProperties(token(playerId, chosen), hosts);
                // A partial listing is not cached, so a fixed permission or quota shows up on the next call.
                if (listing.ga4Error() == null && listing.searchConsoleError() == null) store(key, listing);
            }
            return new GooglePropertiesPayload(chosen, listing.ga4(), listing.ga4Error(),
                    listing.searchConsole(), listing.searchConsoleError(),
                    site == null ? null : site.getGa4PropertyId(),
                    site == null ? null : site.getSearchConsoleSiteUrl(), listing.fetchedAt());
        }

        public SearchReport search(UUID projectId, UUID playerId, String fromText, String toText, String tz,
                                   String dimension, int limit) {
            analytics.requireProject(projectId, playerId);
            if (!SEARCH_DIMENSIONS.contains(dimension)) bad("Invalid dimension");
            if (limit < 1 || limit > 1000) bad("Invalid limit");
            DateRange range = dates(fromText, toText, tz);
            Selection selection = selection(projectId, AnalyticsSite::getSearchConsoleSiteUrl);
            String key = "search|" + projectId + "|" + selection + "|" + dimension + "|" + range + "|" + limit;
            SearchReport report = cached(key, SearchReport.class);
            if (report != null) return report;

            String token = token(playerId, selection.connectionId());
            String uri = searchConsoleApiUri + "/sites/" + encode(selection.property()) + "/searchAnalytics/query";
            // The daily series yields both the totals and the last day Google has data for (it lags ~2 days).
            List<SearchRow> daily = searchRows(call(token, uri, searchQuery(range, "date", 1000)));
            double clicks = 0, impressions = 0, weightedPosition = 0;
            for (SearchRow row : daily) {
                clicks += row.clicks();
                impressions += row.impressions();
                weightedPosition += row.position() * row.impressions();
            }
            SearchRow totals = new SearchRow(null, clicks, impressions,
                    impressions == 0 ? 0 : clicks / impressions, impressions == 0 ? 0 : weightedPosition / impressions);
            String dataUntil = daily.stream().map(SearchRow::key).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
            List<SearchRow> rows = dimension.equals("date") ? daily
                    : searchRows(call(token, uri, searchQuery(range, dimension, limit)));
            report = new SearchReport(selection.property(), dimension, range.from().toString(), range.to().toString(),
                    dataUntil, totals, rows, Instant.now());
            store(key, report);
            return report;
        }

        public Ga4Report ga4(UUID projectId, UUID playerId, String fromText, String toText, String tz,
                             String report, int limit) {
            analytics.requireProject(projectId, playerId);
            if (!GA4_REPORTS.contains(report)) bad("Invalid report");
            if (limit < 1 || limit > 250) bad("Invalid limit");
            DateRange range = dates(fromText, toText, tz);
            Selection selection = selection(projectId, AnalyticsSite::getGa4PropertyId);
            String key = "ga4|" + projectId + "|" + selection + "|" + report + "|" + range + "|" + limit;
            Ga4Report result = cached(key, Ga4Report.class);
            if (result != null) return result;

            String dimension = switch (report) {
                case "pages" -> "pagePath";
                case "sources" -> "sessionSource";
                case "timeseries" -> "date";
                default -> null;
            };
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("dateRanges", List.of(Map.of("startDate", range.from().toString(),
                    "endDate", range.to().toString())));
            body.put("metrics", GA4_METRICS.stream().map(name -> Map.of("name", name)).toList());
            if (dimension != null) {
                body.put("dimensions", List.of(Map.of("name", dimension)));
                body.put("metricAggregations", List.of("TOTAL"));
                body.put("limit", report.equals("timeseries") ? 1000 : limit);
                body.put("orderBys", List.of(report.equals("timeseries")
                        ? Map.of("dimension", Map.of("dimensionName", "date"))
                        : Map.of("metric", Map.of("metricName", report.equals("pages") ? "screenPageViews" : "sessions"),
                                "desc", true)));
            }
            JsonNode response = call(token(playerId, selection.connectionId()),
                    dataApiUri + "/properties/" + selection.property() + ":runReport", body);
            List<String> headers = new ArrayList<>();
            for (JsonNode header : response.path("metricHeaders")) headers.add(text(header.path("name")));
            List<Ga4Row> rows = new ArrayList<>();
            for (JsonNode row : response.path("rows")) {
                rows.add(new Ga4Row(text(row.path("dimensionValues").path(0).path("value")),
                        ga4Metrics(headers, row.path("metricValues"))));
            }
            Ga4Metrics totals;
            if (dimension == null) {
                totals = rows.isEmpty() ? NO_METRICS : rows.get(0).metrics();
                rows = List.of();
            } else {
                JsonNode total = response.path("totals").path(0).path("metricValues");
                totals = total.isArray() ? ga4Metrics(headers, total) : NO_METRICS;
            }
            if (report.equals("timeseries")) rows = dailySeries(range, rows);
            JsonNode metadata = response.path("metadata");
            List<Ga4Sampling> sampling = new ArrayList<>();
            for (JsonNode sample : metadata.path("samplingMetadatas")) {
                sampling.add(new Ga4Sampling(text(sample.path("samplesReadCount")), text(sample.path("samplingSpaceSize"))));
            }
            Ga4Coverage coverage = new Ga4Coverage(text(metadata.path("timeZone")),
                    metadata.has("subjectToThresholding") ? metadata.path("subjectToThresholding").asBoolean() : null,
                    metadata.has("dataLossFromOtherRow") ? metadata.path("dataLossFromOtherRow").asBoolean() : null,
                    sampling, response.has("rowCount") ? response.path("rowCount").asLong() : null,
                    text(metadata.path("emptyReason")));
            result = new Ga4Report(selection.property(), report, range.from().toString(), range.to().toString(),
                    totals, List.copyOf(rows), Instant.now(), coverage);
            store(key, result);
            return result;
        }

        private PropertyListing listProperties(String token, List<String> hosts) {
            List<Ga4PropertyOption> ga4 = List.of();
            List<SearchConsoleSiteOption> searchConsole = List.of();
            GoogleApiError ga4Error = null, searchConsoleError = null;
            try {
                ga4 = ga4Properties(token, hosts);
            } catch (GoogleApiError error) {
                ga4Error = error;
            }
            try {
                searchConsole = searchConsoleSites(token, hosts);
            } catch (GoogleApiError error) {
                searchConsoleError = error;
            }
            // Only one declined or forbidden source is partial; both failing is an error for the whole request.
            if (ga4Error != null && searchConsoleError != null) throw ga4Error;
            return new PropertyListing(ga4, ga4Error == null ? null : ga4Error.getReason(),
                    searchConsole, searchConsoleError == null ? null : searchConsoleError.getReason(), Instant.now());
        }

        private List<Ga4PropertyOption> ga4Properties(String token, List<String> hosts) {
            List<Ga4PropertyOption> properties = new ArrayList<>();
            String pageToken = null;
            int pages = 0, lookups = 0;
            do {
                JsonNode body = call(token, adminApiUri + "/accountSummaries?pageSize=200"
                        + (pageToken == null ? "" : "&pageToken=" + encode(pageToken)), null);
                for (JsonNode account : body.path("accountSummaries")) {
                    String accountName = text(account.path("displayName"));
                    for (JsonNode property : account.path("propertySummaries")) {
                        String name = text(property.path("property"));
                        if (name == null || !name.matches("properties/\\d{1,20}")) continue;
                        String id = name.substring("properties/".length());
                        String displayName = text(property.path("displayName"));
                        List<String> streams = List.of();
                        if (!hosts.isEmpty() && lookups++ < MAX_STREAM_LOOKUPS) {
                            try {
                                streams = streamUris(token, id);
                            } catch (GoogleApiError ignored) {
                                // Streams only refine suggestions; the property stays selectable without them.
                            }
                        }
                        boolean suggested = streams.stream().anyMatch(uri -> hostMatches(hostOf(uri), hosts))
                                || (displayName != null && hosts.stream().anyMatch(host ->
                                        displayName.toLowerCase(Locale.ROOT).contains(bareHost(host))));
                        properties.add(new Ga4PropertyOption(id, displayName, accountName, streams, suggested));
                    }
                }
                pageToken = text(body.path("nextPageToken"));
            } while (pageToken != null && ++pages < MAX_LIST_PAGES);
            properties.sort(Comparator.comparing((Ga4PropertyOption option) -> !option.suggested()));
            return List.copyOf(properties);
        }

        private List<String> streamUris(String token, String propertyId) {
            JsonNode body = call(token, adminApiUri + "/properties/" + propertyId + "/dataStreams?pageSize=50", null);
            List<String> uris = new ArrayList<>();
            for (JsonNode stream : body.path("dataStreams")) {
                String uri = text(stream.path("webStreamData").path("defaultUri"));
                if (uri != null) uris.add(uri);
            }
            return List.copyOf(uris);
        }

        private List<SearchConsoleSiteOption> searchConsoleSites(String token, List<String> hosts) {
            JsonNode body = call(token, searchConsoleApiUri + "/sites", null);
            List<SearchConsoleSiteOption> result = new ArrayList<>();
            for (JsonNode entry : body.path("siteEntry")) {
                String siteUrl = text(entry.path("siteUrl"));
                String permission = text(entry.path("permissionLevel"));
                if (siteUrl == null || "siteUnverifiedUser".equals(permission)) continue;
                result.add(new SearchConsoleSiteOption(siteUrl, permission, searchConsoleMatches(siteUrl, hosts)));
            }
            result.sort(Comparator.comparing((SearchConsoleSiteOption option) -> !option.suggested()));
            return List.copyOf(result);
        }

        private static Map<String, Object> searchQuery(DateRange range, String dimension, int limit) {
            return Map.of("startDate", range.from().toString(), "endDate", range.to().toString(),
                    "dimensions", List.of(dimension), "rowLimit", limit, "type", "web");
        }

        private static List<SearchRow> searchRows(JsonNode body) {
            List<SearchRow> rows = new ArrayList<>();
            for (JsonNode row : body.path("rows")) {
                rows.add(new SearchRow(text(row.path("keys").path(0)), row.path("clicks").asDouble(),
                        row.path("impressions").asDouble(), row.path("ctr").asDouble(), row.path("position").asDouble()));
            }
            return List.copyOf(rows);
        }

        private static Ga4Metrics ga4Metrics(List<String> headers, JsonNode values) {
            Map<String, Double> metrics = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                String value = text(values.path(i).path("value"));
                try {
                    metrics.put(headers.get(i), value == null ? 0d : Double.parseDouble(value));
                } catch (NumberFormatException exception) {
                    metrics.put(headers.get(i), 0d);
                }
            }
            return new Ga4Metrics(Math.round(metrics.getOrDefault("activeUsers", 0d)),
                    Math.round(metrics.getOrDefault("sessions", 0d)),
                    Math.round(metrics.getOrDefault("screenPageViews", 0d)),
                    Math.round(metrics.getOrDefault("eventCount", 0d)),
                    metrics.getOrDefault("bounceRate", 0d), metrics.getOrDefault("averageSessionDuration", 0d));
        }

        /** GA4 omits days without data and keys them {@code yyyyMMdd}: return every day of the range, ISO keyed. */
        private static List<Ga4Row> dailySeries(DateRange range, List<Ga4Row> rows) {
            Map<String, Ga4Metrics> byDay = new HashMap<>();
            for (Ga4Row row : rows) {
                try {
                    byDay.put(LocalDate.parse(row.key(), DateTimeFormatter.BASIC_ISO_DATE).toString(), row.metrics());
                } catch (RuntimeException ignored) {
                    // GA4 always keys the date dimension yyyyMMdd; anything else is not a day of the series.
                }
            }
            List<Ga4Row> series = new ArrayList<>();
            for (LocalDate day = range.from(); !day.isAfter(range.to()); day = day.plusDays(1)) {
                series.add(new Ga4Row(day.toString(), byDay.getOrDefault(day.toString(), NO_METRICS)));
            }
            return series;
        }

        private Selection selection(UUID projectId, java.util.function.Function<AnalyticsSite, String> property) {
            AnalyticsSite site = sites.findByProjectId(projectId).orElse(null);
            if (site == null || site.getGoogleConnectionId() == null) throw notConnected();
            String value = property.apply(site);
            if (value == null) throw new GoogleApiError(HttpStatus.CONFLICT, "google_property_not_selected");
            return new Selection(site.getGoogleConnectionId(), value);
        }

        private String token(UUID playerId, UUID connectionId) {
            try {
                return tokens.accessToken(playerId, connectionId);
            } catch (GoogleTokens.ReauthRequired exception) {
                throw exception;
            } catch (ResponseStatusException exception) {
                // The connection was unlinked after it was chosen for the project.
                if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) throw notConnected();
                throw exception;
            }
        }

        private JsonNode call(String token, String uri, Object body) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri)).timeout(timeout)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json");
            if (body == null) {
                request.GET();
            } else {
                request.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            }
            HttpResponse<String> response;
            try {
                response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw unavailable();
            } catch (IOException exception) {
                throw unavailable();
            }
            JsonNode node;
            try {
                node = json.readTree(response.body());
            } catch (RuntimeException exception) {
                node = null;
            }
            if (node != null && !node.isObject()) node = null;
            if (response.statusCode() / 100 == 2) {
                if (node == null) throw unavailable();
                return node;
            }
            throw error(response.statusCode(), node);
        }

        /** Google's error bodies are never forwarded: they are reduced to one of the documented codes. */
        private static ResponseStatusException error(int status, JsonNode body) {
            Set<String> reasons = new HashSet<>();
            String rpcStatus = null;
            JsonNode error = body == null ? null : body.path("error");
            if (error != null && error.isObject()) {
                rpcStatus = text(error.path("status"));
                for (JsonNode item : error.path("errors")) reasons.add(text(item.path("reason")));
                for (JsonNode detail : error.path("details")) reasons.add(text(detail.path("reason")));
            }
            if (status == 401 || reasons.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT")) {
                return new GoogleApiError(HttpStatus.CONFLICT, "google_reauth_required");
            }
            if (status == 429 || "RESOURCE_EXHAUSTED".equals(rpcStatus)
                    || reasons.stream().anyMatch(QUOTA_REASONS::contains)) {
                return new GoogleApiError(HttpStatus.TOO_MANY_REQUESTS, "google_quota_exceeded");
            }
            if (reasons.contains("SERVICE_DISABLED") || reasons.contains("accessNotConfigured")) {
                return new GoogleApiError(HttpStatus.SERVICE_UNAVAILABLE, "google_api_disabled");
            }
            if (status == 403 || status == 404) {
                return new GoogleApiError(HttpStatus.FORBIDDEN, "google_permission_denied");
            }
            if (status == 400) {
                return new GoogleApiError(HttpStatus.BAD_REQUEST, "google_bad_request");
            }
            return unavailable();
        }

        private static ResponseStatusException unavailable() {
            return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Google is unavailable. Please try again");
        }

        private static GoogleApiError notConnected() {
            return new GoogleApiError(HttpStatus.CONFLICT, "google_not_connected");
        }

        private static DateRange dates(String fromText, String toText, String tz) {
            try {
                ZoneId zone = ZoneId.of(tz);
                LocalDate from = fromText.length() == 10 ? LocalDate.parse(fromText)
                        : Instant.parse(fromText).atZone(zone).toLocalDate();
                // Instants are an exclusive end, like the tracker reports; dates are inclusive.
                LocalDate to = toText.length() == 10 ? LocalDate.parse(toText)
                        : Instant.parse(toText).minusNanos(1).atZone(zone).toLocalDate();
                if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 500) throw new IllegalArgumentException();
                return new DateRange(from, to);
            } catch (RuntimeException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date range or timezone", exception);
            }
        }

        private static boolean searchConsoleMatches(String siteUrl, List<String> hosts) {
            if (siteUrl.startsWith("sc-domain:")) {
                String domain = siteUrl.substring("sc-domain:".length()).toLowerCase(Locale.ROOT);
                return hosts.stream().anyMatch(host -> host.equals(domain) || host.endsWith("." + domain));
            }
            return hostMatches(hostOf(siteUrl), hosts);
        }

        private static boolean hostMatches(String host, List<String> hosts) {
            if (host == null) return false;
            String bare = bareHost(host);
            return hosts.stream().map(AnalyticsGoogleService::bareHost).anyMatch(bare::equals);
        }

        private static String bareHost(String host) {
            String lower = host.toLowerCase(Locale.ROOT);
            return lower.startsWith("www.") ? lower.substring(4) : lower;
        }

        private static String hostOf(String uri) {
            try {
                return URI.create(uri.strip()).getHost();
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        private static String text(JsonNode node) {
            if (node == null || !node.isTextual()) return null;
            String value = node.asString();
            return value.isBlank() ? null : value;
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }

        private static void bad(String message) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }

        private <T> T cached(String key, Class<T> type) {
            synchronized (cache) {
                CachedReport entry = cache.get(key);
                if (entry == null) return null;
                if (entry.expiresAt().isBefore(Instant.now())) {
                    cache.remove(key);
                    return null;
                }
                return type.isInstance(entry.value()) ? type.cast(entry.value()) : null;
            }
        }

        private void store(String key, Object value) {
            synchronized (cache) {
                cache.put(key, new CachedReport(value, Instant.now().plus(CACHE_TTL)));
            }
        }

        @Scheduled(fixedDelayString = "${app.analytics.memory-cleanup-ms:300000}")
        public void purgeExpiredReports() {
            Instant now = Instant.now();
            synchronized (cache) {
                cache.values().removeIf(entry -> entry.expiresAt().isBefore(now));
            }
        }

        private record DateRange(LocalDate from, LocalDate to) { }
        private record Selection(UUID connectionId, String property) { }
        private record CachedReport(Object value, Instant expiresAt) { }
        private record PropertyListing(List<Ga4PropertyOption> ga4, String ga4Error,
                                       List<SearchConsoleSiteOption> searchConsole, String searchConsoleError,
                                       Instant fetchedAt) { }
    }

    /** A Google failure reduced to a stable code in the API error {@code message}. */
    static final class GoogleApiError extends ResponseStatusException {
        GoogleApiError(HttpStatus status, String code) {
            super(status, code);
        }
    }

    @Getter @Immutable @NoArgsConstructor
    @Entity(name = "AnalyticsGoogleConnection")
    @Table(name = "google_connections")
    public static class AnalyticsGoogleConnection {
        @Id @Column(name = "id") private UUID id;
        @Column(name = "player_id") private UUID playerId;
    }

    public interface AnalyticsGoogleConnectionRepository extends JpaRepository<AnalyticsGoogleConnection, UUID> {
        boolean existsByIdAndPlayerId(UUID id, UUID playerId);
    }

    @Getter @Setter @NoArgsConstructor
    @Entity(name = "AnalyticsSite")
    @Table(name = "analytics_sites")
    public static class AnalyticsSite {
        @Id @Column(name = "id") private UUID id;
        @Column(name = "project_id", nullable = false) private UUID projectId;
        @Column(name = "player_id", nullable = false) private UUID playerId;
        @Column(name = "site_key", nullable = false, length = 64) private String siteKey;
        @Column(name = "allowed_origins", columnDefinition = "text") private String allowedOrigins;
        @Column(name = "ga4_property_id", length = 255) private String ga4PropertyId;
        @Column(name = "search_console_site_url", length = 2048) private String searchConsoleSiteUrl;
        @Column(name = "google_connection_id") private UUID googleConnectionId;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    }

    @Getter @Setter @NoArgsConstructor
    @Entity(name = "AnalyticsEvent")
    @Table(name = "analytics_events")
    public static class AnalyticsEvent {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") private Long id;
        @Column(name = "site_id", nullable = false) private UUID siteId;
        @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
        @Column(name = "kind", nullable = false, length = 16) private String kind;
        @Column(name = "path", nullable = false, length = 2048) private String path;
        @Column(name = "referrer_host", length = 255) private String referrerHost;
        @Column(name = "utm_source", length = 255) private String utmSource;
        @Column(name = "utm_medium", length = 255) private String utmMedium;
        @Column(name = "utm_campaign", length = 255) private String utmCampaign;
        @Column(name = "visitor_hash", nullable = false, length = 64) private String visitorHash;
        @Column(name = "session_hash", nullable = false, length = 64) private String sessionHash;
        @Column(name = "device", length = 32) private String device;
        @Column(name = "browser", length = 64) private String browser;
        @Column(name = "os", length = 64) private String os;
        @Column(name = "country", length = 8) private String country;
        @Column(name = "target", length = 2048) private String target;
        @Column(name = "duration_ms") private Long durationMs;
    }

    @Getter @Setter @NoArgsConstructor
    @Entity(name = "AnalyticsGeoSettings")
    @Table(name = "analytics_geo_settings")
    public static class GeoSettings {
        @Id @Column(name = "site_id") private UUID siteId;
        @Column(name = "brand_terms", nullable = false, columnDefinition = "text") private String brandTerms;
        @Column(name = "competitors", nullable = false, columnDefinition = "text") private String competitors;
        @Column(name = "weekly_enabled", nullable = false) private boolean weeklyEnabled;
        @Column(name = "next_run_at") private Instant nextRunAt;
    }

    @Getter @Setter @NoArgsConstructor
    @Entity(name = "AnalyticsGeoPrompt")
    @Table(name = "analytics_geo_prompts")
    public static class GeoPrompt {
        @Id @Column(name = "id") private UUID id;
        @Column(name = "site_id", nullable = false) private UUID siteId;
        @Column(name = "text", nullable = false, length = 500) private String text;
        @Column(name = "enabled", nullable = false) private boolean enabled;
        @Column(name = "position", nullable = false) private int position;
    }

    @Getter @Setter @NoArgsConstructor
    @Entity(name = "AnalyticsGeoRun")
    @Table(name = "analytics_geo_runs")
    public static class GeoRun {
        @Id @Column(name = "id") private UUID id;
        @Column(name = "site_id", nullable = false) private UUID siteId;
        @Column(name = "trigger", nullable = false, length = 16) private String trigger;
        @Column(name = "status", nullable = false, length = 16) private String status;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        @Column(name = "started_at") private Instant startedAt;
        @Column(name = "finished_at") private Instant finishedAt;
        @Column(name = "reason", length = 32) private String reason;
        @Column(name = "auth_session_id") private UUID authSessionId;
    }

    @Getter @Setter @NoArgsConstructor
    @Entity(name = "AnalyticsGeoResult")
    @Table(name = "analytics_geo_results")
    public static class GeoResult {
        @Id @Column(name = "id") private UUID id;
        @Column(name = "run_id", nullable = false) private UUID runId;
        @Column(name = "prompt_id", nullable = false) private UUID promptId;
        @Column(name = "provider", nullable = false, length = 80) private String provider;
        @Column(name = "model", nullable = false, length = 255) private String model;
        @Column(name = "mentioned", nullable = false) private boolean mentioned;
        @Column(name = "cited", nullable = false) private boolean cited;
        @Column(name = "position") private Integer position;
        @Column(name = "cited_urls", nullable = false, columnDefinition = "text") private String citedUrls;
        @Column(name = "competitors_mentioned", nullable = false, columnDefinition = "text")
        private String competitorsMentioned;
        @Column(name = "excerpt", length = 2000) private String excerpt;
        @Column(name = "error", length = 2000) private String error;
    }

    @Getter @Immutable @NoArgsConstructor
    @Entity(name = "AnalyticsProject")
    @Table(name = "projects")
    public static class AnalyticsProject {
        @Id @Column(name = "id") private UUID id;
        @Column(name = "player_id") private UUID playerId;
    }

    @Getter @Immutable @NoArgsConstructor
    @Entity(name = "AnalyticsProductionRecipe")
    @Table(name = "production_recipes")
    public static class AnalyticsProductionRecipe {
        @Id @Column(name = "project_id") private UUID projectId;
        @Column(name = "active_targets", columnDefinition = "text") private String activeTargets;
    }

    public interface AnalyticsSiteRepository extends JpaRepository<AnalyticsSite, UUID> {
        java.util.Optional<AnalyticsSite> findByProjectId(UUID projectId);
        java.util.Optional<AnalyticsSite> findBySiteKey(String siteKey);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select s from AnalyticsSite s where s.projectId = :projectId")
        java.util.Optional<AnalyticsSite> findByProjectIdForUpdate(@Param("projectId") UUID projectId);

        @Modifying
        @Query(value = "insert into analytics_sites (id, project_id, player_id, site_key, allowed_origins, created_at, updated_at) "
                + "values (?1, ?2, ?3, ?4, ?5, ?6, ?6) on conflict (project_id) do nothing", nativeQuery = true)
        int insertIfAbsent(UUID id, UUID projectId, UUID playerId, String siteKey, String allowedOrigins, Instant now);
    }

    public interface AnalyticsEventRepository extends JpaRepository<AnalyticsEvent, Long> {
        @Modifying
        @Query("delete from AnalyticsEvent e where e.occurredAt < :cutoff")
        int deleteOlderThan(Instant cutoff);
    }

    public interface AnalyticsProjectRepository extends JpaRepository<AnalyticsProject, UUID> {
        boolean existsByIdAndPlayerId(UUID id, UUID playerId);
    }

    public interface AnalyticsProductionRecipeRepository
            extends JpaRepository<AnalyticsProductionRecipe, UUID> { }

    public interface GeoSettingsRepository extends JpaRepository<GeoSettings, UUID> { }

    public interface GeoPromptRepository extends JpaRepository<GeoPrompt, UUID> {
        List<GeoPrompt> findBySiteIdOrderByPositionAscIdAsc(UUID siteId);
    }

    public interface GeoRunRepository extends JpaRepository<GeoRun, UUID> {
        @Query("select r from AnalyticsGeoRun r where r.siteId = :site and r.createdAt >= :from and r.createdAt < :to order by r.createdAt desc, r.id desc")
        List<GeoRun> findEvidenceRuns(@Param("site") UUID site, @Param("from") Instant from,
                @Param("to") Instant to, Pageable page);
        boolean existsBySiteIdAndStatusIn(UUID siteId, List<String> statuses);
        List<GeoRun> findBySiteIdOrderByCreatedAtDesc(UUID siteId, Pageable pageable);
        List<GeoRun> findBySiteIdAndCreatedAtBetweenOrderByCreatedAtAsc(UUID siteId, Instant from, Instant to);
    }

    public interface GeoResultRepository extends JpaRepository<GeoResult, UUID> {
        List<GeoResult> findByRunIdOrderByIdAsc(UUID runId, Pageable page);
        List<GeoResult> findByRunIdOrderByProviderAscModelAscPromptIdAsc(UUID runId);
        List<GeoResult> findByRunIdIn(java.util.Collection<UUID> runIds);
        java.util.Optional<GeoResult> findByRunIdAndPromptIdAndProviderAndModel(
                UUID runId, UUID promptId, String provider, String model);
        boolean existsByRunId(UUID runId);
    }

    private static final String TRACKER = AnalyticsService.TRACKER;

    private static final class DailySalt {
        private final SecureRandom random = new SecureRandom();
        private LocalDate day;
        private byte[] salt;

        synchronized String get(Instant now) {
            LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
            if (!today.equals(day)) {
                day = today;
                salt = new byte[32];
                random.nextBytes(salt);
            }
            return Base64.getEncoder().encodeToString(salt);
        }
    }
}
