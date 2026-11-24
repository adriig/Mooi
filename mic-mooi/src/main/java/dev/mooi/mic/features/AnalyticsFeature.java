package dev.mooi.mic.features;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.hibernate.annotations.Immutable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;

import dev.mooi.mic.shared.Auth;
import dev.mooi.mic.shared.Crypto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.Valid;
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

    @PostMapping("/me/projects/{projectId}/analytics/site/key")
    @Auth.Authenticated
    public SitePayload regenerateKey(@PathVariable UUID projectId, Auth.Principal principal) {
        return service.regenerateKey(projectId, principal.player().id());
    }

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
        private final Crypto.SecretBox box;
        private final SecureRandom random = new SecureRandom();
        private final ObjectMapper json = JsonMapper.shared();
        private final ConcurrentHashMap<String, RateWindow> rateLimits = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, SessionState> activeSessions = new ConcurrentHashMap<>();
        private final DailySalt dailySalt = new DailySalt();
        private final String publicBaseUrl;

        AnalyticsService(AnalyticsSiteRepository sites, AnalyticsEventRepository events,
                         AnalyticsProjectRepository projects, AnalyticsProductionRecipeRepository recipes,
                         Crypto.SecretBox box,
                         @Value("${app.analytics.public-base-url:http://localhost:28471}") String publicBaseUrl) {
            this.sites = sites;
            this.events = events;
            this.projects = projects;
            this.recipes = recipes;
            this.box = box;
            this.publicBaseUrl = stripTrailingSlash(publicBaseUrl);
        }

        private void requireProject(UUID projectId, UUID playerId) {
            if (!projects.existsByIdAndPlayerId(projectId, playerId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
            }
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
