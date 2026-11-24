package dev.mooi.mic.features;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.hibernate.annotations.Immutable;
import org.springframework.beans.factory.annotation.Value;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Project-scoped production analytics configuration and its cookieless public collection endpoint. */
@RestController
@RequiredArgsConstructor
public class AnalyticsFeature {
    private final AnalyticsService service;

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
        return service.queueGeoRun(projectId, principal.player().id());
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
        return service.claimGeoRuns(body == null || body.limit() == null ? 10 : body.limit());
    }

    @PutMapping("/internal/analytics/geo/runs/{runId}/status")
    @Auth.ServiceOnly
    public GeoRunPayload setGeoRunStatus(@PathVariable UUID runId, @Valid @RequestBody GeoRunStatusChange body) {
        return service.setGeoRunStatus(runId, body.status());
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

    public record SiteUpdate(@Size(max = 20) List<@Size(max = 512) String> allowedOrigins,
                            @Size(max = 255) String ga4PropertyId,
                            @Size(max = 2048) String searchConsoleSiteUrl) { }

    public record SitePayload(String siteKey, List<String> allowedOrigins, String snippet,
                              String ga4PropertyId, String searchConsoleSiteUrl) { }

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
    public record GeoRunPayload(UUID id, String trigger, String status, Instant createdAt,
                                Instant startedAt, Instant finishedAt) { }
    public record GeoResultPayload(UUID id, UUID promptId, String provider, String model,
                                   boolean mentioned, boolean cited, Integer position,
                                   List<String> citedUrls, List<String> competitorsMentioned,
                                   String excerpt, String error) { }
    public record GeoRunDetail(GeoRunPayload run, List<GeoResultPayload> results) { }
    public record GeoClaimRequest(@Min(1) @Max(100) Integer limit) { }
    public record GeoClaim(UUID runId, UUID siteId, UUID projectId, String trigger,
                           List<String> brandTerms, List<GeoCompetitor> competitors,
                           List<GeoPromptPayload> prompts) { }
    public record GeoRunStatusChange(@NotBlank String status) { }
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

        private final AnalyticsSiteRepository sites;
        private final AnalyticsEventRepository events;
        private final AnalyticsProjectRepository projects;
        private final AnalyticsProductionRecipeRepository recipes;
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
                         AnalyticsProjectRepository projects, AnalyticsProductionRecipeRepository recipes,
                         GeoSettingsRepository geoSettings, GeoPromptRepository geoPrompts,
                         GeoRunRepository geoRuns, GeoResultRepository geoResults,
                         EntityManager entityManager, Crypto.SecretBox box,
                         @Value("${app.analytics.public-base-url:http://localhost:28471}") String publicBaseUrl) {
            this.sites = sites;
            this.events = events;
            this.projects = projects;
            this.recipes = recipes;
            this.geoSettings = geoSettings;
            this.geoPrompts = geoPrompts;
            this.geoRuns = geoRuns;
            this.geoResults = geoResults;
            this.entityManager = entityManager;
            this.box = box;
            this.publicBaseUrl = stripTrailingSlash(publicBaseUrl);
        }

        private void requireProject(UUID projectId, UUID playerId) {
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
        public GeoRunPayload queueGeoRun(UUID projectId, UUID playerId) {
            AnalyticsSite site = lockedOwnedSite(projectId, playerId);
            if (geoRuns.existsBySiteIdAndStatusIn(site.getId(), List.of("queued", "running"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "An AI visibility run is already active");
            }
            GeoRun run = new GeoRun();
            run.setId(UUID.randomUUID());
            run.setSiteId(site.getId());
            run.setTrigger("manual");
            run.setStatus("queued");
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
        public List<GeoClaim> claimGeoRuns(int limit) {
            if (limit < 1 || limit > 100) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Claim limit must be from 1 to 100");
            }
            Instant now = Instant.now();
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

        @Transactional
        public GeoRunPayload setGeoRunStatus(UUID runId, String status) {
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
                    row.getStartedAt(), row.getFinishedAt());
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
            return new GeoClaim(run.getId(), site.getId(), site.getProjectId(), run.getTrigger(),
                    terms, competitors, prompts);
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
            if (update.ga4PropertyId() != null) {
                site.setGa4PropertyId(cleanNullable(update.ga4PropertyId()));
            }
            if (update.searchConsoleSiteUrl() != null) {
                site.setSearchConsoleSiteUrl(cleanNullable(update.searchConsoleSiteUrl()));
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
            return new SitePayload(site.getSiteKey(), effective, snippet,
                    site.getGa4PropertyId(), site.getSearchConsoleSiteUrl());
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
        boolean existsBySiteIdAndStatusIn(UUID siteId, List<String> statuses);
        List<GeoRun> findBySiteIdOrderByCreatedAtDesc(UUID siteId, Pageable pageable);
        List<GeoRun> findBySiteIdAndCreatedAtBetweenOrderByCreatedAtAsc(UUID siteId, Instant from, Instant to);
    }

    public interface GeoResultRepository extends JpaRepository<GeoResult, UUID> {
        List<GeoResult> findByRunIdOrderByProviderAscModelAscPromptIdAsc(UUID runId);
        List<GeoResult> findByRunIdIn(java.util.Collection<UUID> runIds);
        java.util.Optional<GeoResult> findByRunIdAndPromptIdAndProviderAndModel(
                UUID runId, UUID promptId, String provider, String model);
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
