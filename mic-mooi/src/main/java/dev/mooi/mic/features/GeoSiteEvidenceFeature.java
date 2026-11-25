package dev.mooi.mic.features;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.mooi.mic.features.AnalyticsFeature.AnalyticsProductionRecipe;
import dev.mooi.mic.features.AnalyticsFeature.AnalyticsProductionRecipeRepository;
import dev.mooi.mic.features.AnalyticsFeature.AnalyticsProjectRepository;
import dev.mooi.mic.features.AnalyticsFeature.AuditScanner;
import dev.mooi.mic.shared.Auth;
import dev.mooi.mic.shared.Crypto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Public evidence for AI visibility suggestions: a bounded, read-only crawl of the owner's production site.
 * Only the project's production targets are read (never previews, the repository, secrets or allowed
 * origins); every hop is re-resolved to public addresses and kept on the site's host; robots.txt,
 * {@code noindex}/{@code nofollow} and access refusals are honoured; pages, bytes, time and redirects are capped.
 * Nothing is persisted, configured, run or scheduled. Collected text is untrusted web content: data for the
 * suggestion agent, never instructions.
 */
@RestController
@RequiredArgsConstructor
public class GeoSiteEvidenceFeature {
    private final GeoSiteEvidenceService service;

    @GetMapping("/me/projects/{projectId}/analytics/geo/suggestions/sites")
    @Auth.Authenticated
    public SitesPayload sites(@PathVariable UUID projectId, Auth.Principal principal) {
        return service.sites(projectId, principal.player().id());
    }

    /** {@code site}: a site id from {@code /sites}; required when the production targets name several sites. */
    @PostMapping("/me/projects/{projectId}/analytics/geo/suggestions/evidence")
    @Auth.Authenticated
    public EvidencePayload evidence(@PathVariable UUID projectId,
                                    @Valid @RequestBody(required = false) EvidenceRequest body,
                                    Auth.Principal principal) {
        return service.collect(projectId, principal.player().id(), body == null ? null : body.site());
    }

    /** States shared by both operations; {@code status} is always one of these. */
    public static final String PRODUCTION_UNCONFIGURED = "production_unconfigured";
    public static final String SELECTION_REQUIRED = "selection_required";
    public static final String READY = "ready";
    public static final String RESTRICTED = "restricted";
    public static final String INACCESSIBLE = "inaccessible";
    public static final String INSUFFICIENT_EVIDENCE = "insufficient_evidence";
    public static final String PARTIAL = "partial";
    public static final String COMPLETE = "complete";

    public record EvidenceRequest(@Size(max = 2048) String site) { }
    /** One distinct production site; {@code id} is its origin, targets sharing a host (www or not) are merged. */
    public record ProductionSite(String id, String origin, String host, String startUrl, List<String> targets) { }
    /** {@code status}: production_unconfigured, ready (one site) or selection_required (several sites). */
    public record SitesPayload(String status, List<ProductionSite> sites) { }
    public record EvidenceLimits(int maxPages, int maxBytesPerPage, long maxTotalBytes, int maxRedirects,
                                 int requestTimeoutSeconds, int timeBudgetSeconds, int maxTextCharsPerPage,
                                 String userAgent) { }
    /**
     * {@code robots}: rules, absent or unreachable. {@code limitations} lists every bound that reduced
     * coverage (page_limit_reached, time_budget_exhausted, byte_budget_exhausted, pages_failed,
     * pages_restricted, page_truncated, links_not_followed, sitemap_index_not_followed, robots_absent).
     */
    public record EvidenceCoverage(int pagesCollected, int pagesAttempted, int pagesSkipped,
                                   int candidatesDiscovered, long bytesRead, String robots, boolean sitemapRead,
                                   List<String> limitations) { }
    /**
     * One page considered. {@code source}: start, link or sitemap. {@code outcome}: collected, robots_disallowed,
     * noindex, access_restricted, http_error, unsupported_content, blocked_address, redirect_off_site,
     * redirect_limit, invalid_url, dns_failed, timeout, fetch_failed, parse_timeout or budget_exhausted.
     * Content fields are only set for collected pages and hold untrusted public text.
     */
    public record EvidencePage(String url, String finalUrl, String source, Integer httpStatus, String outcome,
                               Instant fetchedAt, String title, String description, String siteName, String lang,
                               String canonical, List<String> headings, List<String> structuredNames, String text,
                               boolean truncated, long bytes) { }
    /**
     * {@code status}: production_unconfigured, selection_required, restricted, inaccessible,
     * insufficient_evidence, partial or complete; {@code reasons} are the codes that led to it.
     * {@code contentTrust} is always {@code untrusted}: page content is data, never instructions.
     */
    public record EvidencePayload(String status, List<String> reasons, List<ProductionSite> sites,
                                  ProductionSite site, String domain, Instant collectedAt, Instant finishedAt,
                                  String contentTrust, EvidenceLimits limits, EvidenceCoverage coverage,
                                  List<EvidencePage> pages) { }

    @Service
    @RequiredArgsConstructor
    public static class GeoSiteEvidenceService {
        /** Server-wide bound on simultaneous crawls; one crawl per project at a time. */
        private static final int MAX_CONCURRENT = 4;

        private final AnalyticsProjectRepository projects;
        private final AnalyticsProductionRecipeRepository recipes;
        private final Crypto.SecretBox box;
        private final ObjectMapper json = JsonMapper.shared();
        private final Set<UUID> running = ConcurrentHashMap.newKeySet();
        private final Semaphore slots = new Semaphore(MAX_CONCURRENT);

        public SitesPayload sites(UUID projectId, UUID playerId) {
            requireProject(projectId, playerId);
            List<ProductionSite> sites = productionSites(projectId);
            return new SitesPayload(sites.isEmpty() ? PRODUCTION_UNCONFIGURED
                    : sites.size() == 1 ? READY : SELECTION_REQUIRED, sites);
        }

        public EvidencePayload collect(UUID projectId, UUID playerId, String requested) {
            requireProject(projectId, playerId);
            List<ProductionSite> sites = productionSites(projectId);
            if (sites.isEmpty()) return unresolved(PRODUCTION_UNCONFIGURED, sites);
            ProductionSite site;
            if (requested == null || requested.isBlank()) {
                if (sites.size() > 1) return unresolved(SELECTION_REQUIRED, sites);
                site = sites.getFirst();
            } else {
                String id = requested.strip();
                site = sites.stream().filter(candidate -> candidate.id().equalsIgnoreCase(id)).findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Site is not one of the project's production sites"));
            }
            if (!running.add(projectId)) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "A site collection is already running for this project");
            }
            try {
                if (!slots.tryAcquire()) {
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                            "Too many site collections are running; try again shortly");
                }
                try {
                    return new Collector(site, sites, json).run();
                } finally {
                    slots.release();
                }
            } finally {
                running.remove(projectId);
            }
        }

        private void requireProject(UUID projectId, UUID playerId) {
            if (!projects.existsByIdAndPlayerId(projectId, playerId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
            }
        }

        private static EvidencePayload unresolved(String status, List<ProductionSite> sites) {
            Instant now = Instant.now();
            return new EvidencePayload(status, List.of(status), sites, null, null, now, now, "untrusted",
                    Collector.LIMITS, null, List.of());
        }

        private record Target(String label, String url) { }

        /** Distinct sites of the active production targets, in target order; https wins over http. */
        List<ProductionSite> productionSites(UUID projectId) {
            List<Target> targets;
            try {
                String stored = recipes.findById(projectId).map(AnalyticsProductionRecipe::getActiveTargets)
                        .map(box::decrypt).orElse(null);
                if (stored == null || stored.isBlank()) return List.of();
                targets = json.readValue(stored, new TypeReference<List<Target>>() { });
            } catch (RuntimeException e) {
                return List.of();
            }
            Map<String, ProductionSite> grouped = new LinkedHashMap<>();
            for (Target target : targets == null ? List.<Target>of() : targets) {
                if (target == null || target.url() == null) continue;
                URI uri;
                try {
                    uri = AuditScanner.validateUrl(target.url().strip());
                } catch (RuntimeException e) {
                    continue; // Legacy or malformed target: never a source.
                }
                String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
                String host = uri.getHost().toLowerCase(Locale.ROOT);
                int port = uri.getPort() == defaultPort(scheme) ? -1 : uri.getPort();
                String origin = scheme + "://" + host + (port == -1 ? "" : ":" + port);
                String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
                String start = origin + path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
                String key = bareHost(host) + (port == -1 ? "" : ":" + port);
                String label = target.label() == null || target.label().isBlank() ? null : clip(target.label().strip(), 120);
                ProductionSite existing = grouped.get(key);
                List<String> labels = new ArrayList<>(existing == null ? List.of() : existing.targets());
                if (label != null && !labels.contains(label)) labels.add(label);
                if (existing == null || existing.origin().startsWith("http:") && scheme.equals("https")) {
                    grouped.put(key, new ProductionSite(origin, origin, host, start, List.copyOf(labels)));
                } else {
                    grouped.put(key, new ProductionSite(existing.id(), existing.origin(), existing.host(),
                            existing.startUrl(), List.copyOf(labels)));
                }
            }
            return List.copyOf(grouped.values());
        }
    }

    static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    static String bareHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.startsWith("www.") ? lower.substring(4) : lower;
    }

    static String clip(String value, int max) {
        if (value == null || value.length() <= max) return value;
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end);
    }

    /** One crawl of one site; single-threaded apart from the bounded I/O calls. */
    static final class Collector {
        static final String AGENT = "MooiGeoCollector";
        static final int MAX_PAGES = 6;
        static final int MAX_PAGE_BYTES = 1024 * 1024;
        static final long MAX_TOTAL_BYTES = 4L * 1024 * 1024;
        static final int MAX_AUX_BYTES = 256 * 1024;
        static final int MAX_REDIRECTS = 3;
        static final int REQUEST_TIMEOUT_SECONDS = 5;
        static final int BUDGET_SECONDS = 25;
        static final int MAX_TEXT_CHARS = 3000;
        static final int MIN_EVIDENCE_CHARS = 200;
        static final EvidenceLimits LIMITS = new EvidenceLimits(MAX_PAGES, MAX_PAGE_BYTES, MAX_TOTAL_BYTES,
                MAX_REDIRECTS, REQUEST_TIMEOUT_SECONDS, BUDGET_SECONDS, MAX_TEXT_CHARS, AGENT + "/1.0");

        private static final ExecutorService IO = Executors.newVirtualThreadPerTaskExecutor();
        private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
        private static final Set<Integer> REFUSED = Set.of(401, 402, 403, 407, 451);
        private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
        private static final Pattern NON_VISIBLE = Pattern.compile(
                "(?is)<(script|style|template|noscript|svg|iframe|object|canvas)\\b[^>]*>.*?</\\1\\s*>");
        private static final Pattern HIDDEN = Pattern.compile(
                "(?is)<([a-z][a-z0-9]*)\\b[^>]*(?:\\shidden(?=\\s|=|>)|aria-hidden\\s*=\\s*['\"]true['\"])[^>]*>.*?</\\1\\s*>");
        private static final Pattern TITLE = Pattern.compile("(?is)<title\\b[^>]*>(.*?)</title\\s*>");
        private static final Pattern HEADING = Pattern.compile("(?is)<h([1-3])\\b[^>]*>(.*?)</h\\1\\s*>");
        private static final Pattern BODY = Pattern.compile("(?is)<body\\b[^>]*>(.*)");
        private static final Pattern SCRIPT = Pattern.compile(
                "(?is)<script\\b((?:[^>\"']++|\"[^\"]*+\"|'[^']*+')*+)>(.*?)</script\\s*>");
        private static final Pattern MARKUP = Pattern.compile("(?s)<[^>]*>");
        private static final Pattern ENTITY = Pattern.compile("&(#\\d{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});");
        private static final Pattern CHARSET = Pattern.compile("(?i)charset=\"?([\\w.:-]{1,40})");
        private static final Pattern SITEMAP_DECLARATION = Pattern.compile("(?im)^\\s*Sitemap:\\s*(\\S+)");
        private static final Pattern LOC = Pattern.compile("(?is)<loc>\\s*(.*?)\\s*</loc>");
        private static final Pattern SITEMAP_INDEX = Pattern.compile("(?is)<(?:[\\w-]+:)?sitemapindex\\b");
        private static final Pattern ASSET = Pattern.compile(
                "(?i).*\\.(?:png|jpe?g|gif|svg|webp|avif|ico|css|js|mjs|json|xml|txt|pdf|zip|gz|tgz|rar|mp4|webm|mp3|wav|woff2?|ttf|otf|eot|exe|dmg|apk)$");
        // Pages that are private, state-changing or empty for crawlers are never visited.
        private static final Pattern PRIVATE_PATH = Pattern.compile(
                "(?i).*/(?:log-?in|log-?out|sign-?in|sign-?out|sign-?up|register|auth|oauth|account|my-account|cart|checkout|admin|wp-admin|dashboard|settings)(?:/.*)?$");
        private static final Pattern KEY_PATH = Pattern.compile(
                "(?i).*/(?:about|about-us|company|product|products|features|pricing|plans|solutions|services|platform|faq|customers|use-cases|why|how-it-works|docs|documentation)/?$");
        private static final Pattern LEGAL_PATH = Pattern.compile(
                "(?i).*/(?:privacy|terms|cookies?|legal|imprint|impressum|gdpr)(?:[/-].*)?$");
        private static final Set<String> IDENTITY_TYPES = Set.of("organization", "corporation", "localbusiness",
                "website", "product", "softwareapplication", "webapplication", "brand", "service");

        private final ProductionSite site;
        private final List<ProductionSite> sites;
        private final ObjectMapper json;
        private final URI start;
        private final String siteKey;
        private final int sitePort;
        private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BUDGET_SECONDS);
        private final Instant collectedAt = Instant.now();
        private final List<EvidencePage> pages = new ArrayList<>();
        private final Set<String> limitations = new LinkedHashSet<>();
        private long bytesRead;
        private String robotsRules;
        private String robotsState = "absent";

        Collector(ProductionSite site, List<ProductionSite> sites, ObjectMapper json) {
            this.site = site;
            this.sites = sites;
            this.json = json;
            this.start = URI.create(site.startUrl());
            this.siteKey = bareHost(site.host());
            this.sitePort = start.getPort();
        }

        EvidencePayload run() {
            URI root = start.resolve("/");
            Response robots = fetch(root.resolve("/robots.txt"), MAX_AUX_BYTES);
            if (!robots.completed()) {
                // Without a robots.txt answer the site is unreachable or refused: no page is requested.
                robotsState = "unreachable";
                return robots.outcome().equals("blocked_address") ? finish(INACCESSIBLE, "blocked_address")
                        : finish(INACCESSIBLE, "robots_unreachable", robots.outcome());
            }
            if (robots.status() >= 500) {
                // RFC 9309: an unreachable robots.txt means the whole site is treated as disallowed.
                robotsState = "unreachable";
                return finish(INACCESSIBLE, "robots_unreachable");
            }
            String robotsBody = robots.text();
            if (robots.status() >= 200 && robots.status() < 300 && !robotsBody.stripLeading().startsWith("<")) {
                robotsRules = robotsBody;
                robotsState = "rules";
            } else {
                limitations.add("robots_absent");
            }

            Page home = visit(start, "start");
            if (!home.collected()) {
                String outcome = home.record().outcome();
                boolean restricted = List.of("robots_disallowed", "noindex", "access_restricted").contains(outcome);
                return finish(restricted ? RESTRICTED : INACCESSIBLE, outcome);
            }

            List<Candidate> candidates = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>(List.of(key(start), key(home.finalUri())));
            if (home.nofollow()) limitations.add("links_not_followed");
            else for (String link : home.links()) addCandidate(candidates, seen, home.finalUri(), link, "link");
            boolean sitemapRead = false;
            if (candidates.size() < MAX_PAGES - 1) sitemapRead = readSitemap(root, robotsRules, candidates, seen);
            candidates.sort(Comparator.comparingInt(Candidate::rank));

            int attempted = 1;
            int skipped = 0;
            int collected = 1;
            int index = 0;
            for (; index < candidates.size() && collected < MAX_PAGES; index++) {
                if (outOfBudget()) break;
                Page page = visit(candidates.get(index).uri(), candidates.get(index).source());
                String outcome = page.record().outcome();
                if (page.collected()) collected++;
                else if (List.of("robots_disallowed", "noindex", "access_restricted").contains(outcome)) {
                    limitations.add("pages_restricted");
                } else limitations.add("pages_failed");
                if (outcome.equals("robots_disallowed")) skipped++;
                else attempted++;
            }
            if (index < candidates.size()) limitations.add(outOfBudget() ? budgetLimitation() : "page_limit_reached");

            int evidence = pages.stream().filter(page -> page.outcome().equals("collected"))
                    .mapToInt(page -> length(page.text()) + length(page.description()) + length(page.title())
                            + page.headings().stream().mapToInt(String::length).sum()).sum();
            EvidenceCoverage coverage = new EvidenceCoverage(collected, attempted, skipped, candidates.size(),
                    bytesRead, robotsState, sitemapRead, List.copyOf(limitations));
            if (evidence < MIN_EVIDENCE_CHARS) return payload(INSUFFICIENT_EVIDENCE, List.of("empty_content"), coverage);
            List<String> partial = limitations.stream().filter(code -> !List.of("robots_absent",
                    "sitemap_index_not_followed").contains(code)).toList();
            return payload(partial.isEmpty() ? COMPLETE : PARTIAL, partial, coverage);
        }

        private EvidencePayload finish(String status, String... reasons) {
            return payload(status, List.of(reasons), new EvidenceCoverage(0, pages.size(), 0, 0, bytesRead,
                    robotsState, false, List.copyOf(limitations)));
        }

        private EvidencePayload payload(String status, List<String> reasons, EvidenceCoverage coverage) {
            return new EvidencePayload(status, List.copyOf(reasons), sites, site, siteKey, collectedAt, Instant.now(),
                    "untrusted", LIMITS, coverage, List.copyOf(pages));
        }

        private static int length(String value) { return value == null ? 0 : value.length(); }

        private boolean outOfBudget() {
            return System.nanoTime() >= deadline - TimeUnit.MILLISECONDS.toNanos(500) || bytesRead >= MAX_TOTAL_BYTES;
        }

        private String budgetLimitation() {
            return bytesRead >= MAX_TOTAL_BYTES ? "byte_budget_exhausted" : "time_budget_exhausted";
        }

        // --- candidates --------------------------------------------------------------------------

        private record Candidate(URI uri, String source, int rank) { }

        private void addCandidate(List<Candidate> candidates, Set<String> seen, URI base, String href, String source) {
            if (candidates.size() >= 200 || href == null || href.isBlank()) return;
            URI uri;
            try {
                URI resolved = base.resolve(href.strip());
                if (resolved.getRawQuery() != null || resolved.getUserInfo() != null) return;
                uri = new URI(resolved.getScheme(), null, resolved.getHost(), resolved.getPort(), resolved.getPath(), null, null);
                AuditScanner.validateUrl(uri.toString());
            } catch (Exception e) {
                return;
            }
            String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
            if (!onSite(uri) || ASSET.matcher(path).matches() || PRIVATE_PATH.matcher(path).matches()) return;
            if (!seen.add(key(uri))) return;
            int depth = (int) path.chars().filter(c -> c == '/').count();
            int rank = (KEY_PATH.matcher(path).matches() ? 0 : LEGAL_PATH.matcher(path).matches() ? 2000 : 1000)
                    + (source.equals("link") ? 0 : 500) + Math.min(depth, 20) * 10 + Math.min(candidates.size(), 9);
            candidates.add(new Candidate(uri, source, rank));
        }

        private static String key(URI uri) {
            String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            return bareHost(uri.getHost()) + ":" + (uri.getPort() == defaultPort(uri.getScheme()) ? -1 : uri.getPort()) + path;
        }

        private boolean readSitemap(URI root, String rules, List<Candidate> candidates, Set<String> seen) {
            LinkedHashSet<URI> declared = new LinkedHashSet<>();
            Matcher matcher = SITEMAP_DECLARATION.matcher(rules == null ? "" : rules);
            while (matcher.find() && declared.size() < 2) {
                try {
                    URI candidate = AuditScanner.validateUrl(root.resolve(matcher.group(1)).toString());
                    if (onSite(candidate)) declared.add(candidate);
                } catch (RuntimeException ignored) { /* Malformed or foreign declarations are not followed. */ }
            }
            if (declared.isEmpty()) declared.add(root.resolve("/sitemap.xml"));
            for (URI sitemap : declared) {
                if (outOfBudget() || !allowedByRobots(sitemap)) continue;
                Response response = fetch(sitemap, MAX_AUX_BYTES);
                if (!response.completed() || response.status() < 200 || response.status() >= 300) continue;
                String body = response.text();
                if (SITEMAP_INDEX.matcher(body).find()) {
                    limitations.add("sitemap_index_not_followed");
                    return true;
                }
                Matcher locs = LOC.matcher(guard(body));
                int read = 0;
                while (locs.find() && read++ < 50) {
                    addCandidate(candidates, seen, root, decode(locs.group(1)), "sitemap");
                }
                return true;
            }
            return false;
        }

        // --- pages -------------------------------------------------------------------------------

        private record Page(EvidencePage record, URI finalUri, List<String> links, boolean nofollow) {
            boolean collected() { return record.outcome().equals("collected"); }
        }

        private Page visit(URI uri, String source) {
            Instant at = Instant.now();
            if (!allowedByRobots(uri)) return skip(uri, uri, source, null, "robots_disallowed", at, 0);
            Response response = fetch(uri, MAX_PAGE_BYTES);
            if (!response.completed()) return skip(uri, response.finalUri(), source, null, response.outcome(), at, 0);
            long bytes = response.body().length;
            if (REFUSED.contains(response.status())) {
                return skip(uri, response.finalUri(), source, response.status(), "access_restricted", at, bytes);
            }
            if (response.status() < 200 || response.status() >= 300) {
                return skip(uri, response.finalUri(), source, response.status(), "http_error", at, bytes);
            }
            if (!allowedByRobots(response.finalUri())) {
                return skip(uri, response.finalUri(), source, response.status(), "robots_disallowed", at, bytes);
            }
            String type = response.contentType() == null ? "" : response.contentType().toLowerCase(Locale.ROOT);
            String html = response.text();
            if (!(type.contains("text/html") || type.contains("application/xhtml")
                    || type.isBlank() && html.stripLeading().startsWith("<"))) {
                return skip(uri, response.finalUri(), source, response.status(), "unsupported_content", at, bytes);
            }
            boolean[] header = robotsHeader(response.robotsTag());
            Extracted page;
            try {
                page = extract(html, response.finalUri());
            } catch (ParseTimeout e) {
                return skip(uri, response.finalUri(), source, response.status(), "parse_timeout", at, bytes);
            }
            if (header[0] || page.noindex()) {
                return skip(uri, response.finalUri(), source, response.status(), "noindex", at, bytes);
            }
            boolean truncated = response.truncated() || page.truncated();
            if (truncated) limitations.add("page_truncated");
            EvidencePage record = new EvidencePage(uri.toString(), response.finalUri().toString(), source,
                    response.status(), "collected", at, page.title(), page.description(), page.siteName(),
                    page.lang(), page.canonical(), page.headings(), page.structuredNames(), page.text(), truncated, bytes);
            pages.add(record);
            return new Page(record, response.finalUri(), page.links(), header[1] || page.nofollow());
        }

        private Page skip(URI uri, URI finalUri, String source, Integer status, String outcome, Instant at, long bytes) {
            EvidencePage record = new EvidencePage(uri.toString(), finalUri == null ? null : finalUri.toString(), source,
                    status, outcome, at, null, null, null, null, null, List.of(), List.of(), null, false, bytes);
            pages.add(record);
            return new Page(record, finalUri == null ? uri : finalUri, List.of(), true);
        }

        private boolean allowedByRobots(URI uri) {
            if (robotsRules == null) return true;
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            return !AuditScanner.blocked(robotsRules, AGENT, path);
        }

        /** X-Robots-Tag directives for every crawler or for this one: [noindex, nofollow]. */
        private static boolean[] robotsHeader(List<String> values) {
            boolean noindex = false, nofollow = false;
            for (String value : values) {
                String agent = null;
                for (String part : value.toLowerCase(Locale.ROOT).split(",")) {
                    String directive = part.strip();
                    int colon = directive.indexOf(':');
                    if (colon > 0 && !directive.startsWith("unavailable_after") && !directive.substring(0, colon).contains(" ")) {
                        agent = directive.substring(0, colon).strip();
                        directive = directive.substring(colon + 1).strip();
                    }
                    if (agent != null && !agent.equals(AGENT.toLowerCase(Locale.ROOT))) continue;
                    if (directive.equals("noindex") || directive.equals("none")) noindex = true;
                    if (directive.equals("nofollow") || directive.equals("none")) nofollow = true;
                }
            }
            return new boolean[] {noindex, nofollow};
        }

        private record Extracted(String title, String description, String siteName, String lang, String canonical,
                                 List<String> headings, List<String> structuredNames, String text, boolean truncated,
                                 List<String> links, boolean noindex, boolean nofollow) { }

        private Extracted extract(String html, URI base) {
            String markup = COMMENT.matcher(guard(html)).replaceAll(" ");
            String visible = NON_VISIBLE.matcher(guard(markup)).replaceAll(" ");
            Map<String, String> meta = new LinkedHashMap<>();
            List<String> links = new ArrayList<>();
            String lang = null;
            String canonical = null;
            Matcher tags = AuditScanner.TAG.matcher(guard(visible));
            while (tags.find()) {
                String name = tags.group(1).toLowerCase(Locale.ROOT);
                if (!Set.of("meta", "html", "link", "a").contains(name)) continue;
                Map<String, String> attrs = AuditScanner.attributes(tags.group(2));
                switch (name) {
                    case "meta" -> meta.putIfAbsent(attrs.getOrDefault("name", attrs.getOrDefault("property", ""))
                            .toLowerCase(Locale.ROOT), decode(attrs.getOrDefault("content", "")));
                    case "html" -> lang = attrs.get("lang");
                    case "link" -> {
                        if (canonical == null && List.of(attrs.getOrDefault("rel", "").toLowerCase(Locale.ROOT)
                                .split("\\s+")).contains("canonical")) canonical = attrs.get("href");
                    }
                    default -> {
                        if (links.size() < 300 && attrs.get("href") != null && !List.of(attrs.getOrDefault("rel", "")
                                .toLowerCase(Locale.ROOT).split("\\s+")).contains("nofollow")) {
                            links.add(decode(attrs.get("href")));
                        }
                    }
                }
            }
            String robots = (meta.getOrDefault("robots", "") + "," + meta.getOrDefault(AGENT.toLowerCase(Locale.ROOT), ""))
                    .toLowerCase(Locale.ROOT);
            boolean noindex = robots.matches(".*\\b(?:noindex|none)\\b.*");
            boolean nofollow = robots.matches(".*\\b(?:nofollow|none)\\b.*");

            Matcher titleMatch = TITLE.matcher(guard(visible));
            String title = titleMatch.find() ? text(titleMatch.group(1)) : "";
            if (title.isBlank()) title = clean(meta.getOrDefault("og:title", ""));
            String description = clean(meta.getOrDefault("description", meta.getOrDefault("og:description", "")));
            String siteName = clean(meta.getOrDefault("og:site_name", meta.getOrDefault("application-name", "")));

            Matcher bodyMatch = BODY.matcher(guard(visible));
            String body = bodyMatch.find() ? bodyMatch.group(1) : visible;
            body = HIDDEN.matcher(guard(body)).replaceAll(" ");
            List<String> headings = new ArrayList<>();
            Matcher heading = HEADING.matcher(guard(body));
            while (heading.find() && headings.size() < 20) {
                String value = clip(text(heading.group(2)), 200);
                if (!value.isBlank() && !headings.contains(value)) headings.add(value);
            }
            String text = text(body);
            boolean truncated = text.length() > MAX_TEXT_CHARS;

            List<String> names = new ArrayList<>();
            Matcher scripts = SCRIPT.matcher(guard(markup));
            int blocks = 0;
            while (scripts.find() && blocks < 10 && names.size() < 10) {
                if (!"application/ld+json".equalsIgnoreCase(AuditScanner.attributes(scripts.group(1)).get("type"))) continue;
                blocks++;
                if (scripts.group(2).length() > 100_000) continue;
                try {
                    collectNames(json.readTree(scripts.group(2)), names, 0);
                } catch (RuntimeException ignored) { /* Invalid JSON-LD is not evidence. */ }
            }
            return new Extracted(clip(title, 300), clip(description, 500), clip(siteName, 200),
                    lang == null ? null : clip(clean(lang), 35), canonical == null ? null : clip(clean(decode(canonical)), 2048),
                    List.copyOf(headings), List.copyOf(names), clip(text, MAX_TEXT_CHARS), truncated,
                    List.copyOf(links), noindex, nofollow);
        }

        private void collectNames(JsonNode node, List<String> names, int depth) {
            if (node == null || depth > 16 || names.size() >= 10) return;
            if (node.isObject()) {
                JsonNode type = node.path("@type");
                List<String> types = new ArrayList<>();
                if (type.isTextual()) types.add(type.asString());
                else if (type.isArray()) for (JsonNode value : type) if (value.isTextual()) types.add(value.asString());
                JsonNode name = node.path("name");
                for (String value : types) {
                    if (IDENTITY_TYPES.contains(value.toLowerCase(Locale.ROOT)) && name.isTextual()) {
                        String entry = clip(clean(value) + ": " + clean(name.asString()), 200);
                        if (!names.contains(entry)) names.add(entry);
                        break;
                    }
                }
                for (var property : node.properties()) collectNames(property.getValue(), names, depth + 1);
            } else if (node.isArray()) for (JsonNode child : node) collectNames(child, names, depth + 1);
        }

        private static String text(String html) {
            return clean(decode(MARKUP.matcher(html).replaceAll(" ")));
        }

        private static String decode(String value) {
            Matcher matcher = ENTITY.matcher(value);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                String entity = matcher.group(1);
                String replacement;
                try {
                    if (entity.startsWith("#x") || entity.startsWith("#X")) replacement = Character.toString(Integer.parseInt(entity.substring(2), 16));
                    else if (entity.startsWith("#")) replacement = Character.toString(Integer.parseInt(entity.substring(1)));
                    else replacement = switch (entity.toLowerCase(Locale.ROOT)) {
                        case "amp" -> "&"; case "lt" -> "<"; case "gt" -> ">"; case "quot" -> "\""; case "apos" -> "'";
                        case "nbsp" -> " "; case "ndash" -> "–"; case "mdash" -> "—"; case "hellip" -> "…";
                        case "copy" -> "©"; case "reg" -> "®"; case "trade" -> "™";
                        default -> " ";
                    };
                } catch (IllegalArgumentException e) {
                    replacement = " ";
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(out);
            return out.toString();
        }

        /** Drops control, format (zero-width, bidi overrides), private-use and unassigned characters. */
        private static String clean(String value) {
            StringBuilder out = new StringBuilder(value.length());
            value.codePoints().forEach(cp -> {
                int type = Character.getType(cp);
                if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) out.append(' ');
                else if (type != Character.CONTROL && type != Character.FORMAT && type != Character.PRIVATE_USE
                        && type != Character.UNASSIGNED && type != Character.SURROGATE) out.appendCodePoint(cp);
            });
            return out.toString().replaceAll(" {2,}", " ").strip();
        }

        // --- network -----------------------------------------------------------------------------

        /** {@code outcome} is ok when an HTTP response arrived (any status); otherwise the failure code. */
        private record Response(String outcome, int status, URI finalUri, String contentType, List<String> robotsTag,
                                byte[] body, boolean truncated) {
            boolean completed() { return outcome.equals("ok"); }
            String text() {
                Charset charset = StandardCharsets.UTF_8;
                if (contentType != null) {
                    Matcher match = CHARSET.matcher(contentType);
                    if (match.find()) {
                        try { charset = Charset.forName(match.group(1)); }
                        catch (RuntimeException ignored) { /* Unknown charsets fall back to UTF-8. */ }
                    }
                }
                return new String(body, charset);
            }
        }

        private static Response failure(String outcome, URI uri) {
            return new Response(outcome, 0, uri, null, List.of(), new byte[0], false);
        }

        private boolean onSite(URI uri) {
            if (uri.getHost() == null || !bareHost(uri.getHost()).equals(siteKey)) return false;
            int port = uri.getPort();
            return port == -1 || port == defaultPort(uri.getScheme()) || port == sitePort;
        }

        /** Every hop: validated URL on the site's host, fresh DNS answer with only public addresses pinned. */
        private Response fetch(URI initial, int maxBytes) {
            URI uri = initial;
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                if (outOfBudget()) return failure("budget_exhausted", uri);
                URI target;
                try {
                    target = AuditScanner.validateUrl(uri.toString());
                } catch (RuntimeException e) {
                    return failure("invalid_url", uri);
                }
                if (!onSite(target)) return failure("redirect_off_site", target);
                long requestDeadline = Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(REQUEST_TIMEOUT_SECONDS));
                InetAddress[] addresses;
                try {
                    addresses = bounded(() -> InetAddress.getAllByName(target.getHost()), requestDeadline);
                } catch (TimeoutException e) {
                    return failure("timeout", target);
                } catch (Exception e) {
                    return failure("dns_failed", target);
                }
                if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(address -> !AuditScanner.publicAddress(address))) {
                    return failure("blocked_address", target);
                }
                int limit = (int) Math.max(0, Math.min(maxBytes, MAX_TOTAL_BYTES - bytesRead));
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
                        .setDefaultRequestConfig(RequestConfig.custom()
                                .setResponseTimeout(Timeout.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                                .setConnectionRequestTimeout(Timeout.ofSeconds(REQUEST_TIMEOUT_SECONDS)).build()).build();
                try {
                    var request = new HttpGet(target);
                    request.setHeader("User-Agent", LIMITS.userAgent());
                    request.setHeader("Accept", "text/html,application/xhtml+xml,text/plain;q=0.8,application/xml;q=0.5");
                    request.setHeader("Accept-Encoding", "identity");
                    record Reply(int status, byte[] body, boolean truncated, String location, String type, List<String> robots) { }
                    Reply reply = bounded(() -> client.execute(request, response -> {
                        int status = response.getCode();
                        var location = response.getFirstHeader("Location");
                        var type = response.getFirstHeader("Content-Type");
                        List<String> robots = Arrays.stream(response.getHeaders("X-Robots-Tag")).map(h -> h.getValue()).toList();
                        byte[] bytes = new byte[0];
                        var entity = response.getEntity();
                        if (entity != null && !REDIRECTS.contains(status)) {
                            try (InputStream in = entity.getContent()) {
                                if (in != null) bytes = in.readNBytes(limit + 1);
                            }
                        }
                        boolean truncated = bytes.length > limit;
                        return new Reply(status, truncated ? Arrays.copyOf(bytes, limit) : bytes, truncated,
                                location == null ? null : location.getValue(), type == null ? null : type.getValue(), robots);
                    }), requestDeadline);
                    bytesRead += reply.body().length;
                    if (REDIRECTS.contains(reply.status()) && reply.location() != null) {
                        try {
                            uri = target.resolve(reply.location().strip());
                        } catch (IllegalArgumentException e) {
                            return failure("invalid_url", target);
                        }
                        continue;
                    }
                    if (reply.truncated() && bytesRead >= MAX_TOTAL_BYTES) limitations.add("byte_budget_exhausted");
                    return new Response("ok", reply.status(), target, reply.type(), reply.robots(), reply.body(), reply.truncated());
                } catch (TimeoutException e) {
                    return failure("timeout", target);
                } catch (Exception e) {
                    return failure("fetch_failed", target);
                } finally {
                    client.close(org.apache.hc.core5.io.CloseMode.IMMEDIATE);
                }
            }
            return failure("redirect_limit", uri);
        }

        private static <T> T bounded(Callable<T> work, long until) throws Exception {
            long remaining = until - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            var future = IO.submit(work);
            try {
                return future.get(remaining, TimeUnit.NANOSECONDS);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof Exception cause) throw cause;
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } finally {
                future.cancel(true);
            }
        }

        // --- bounded parsing ---------------------------------------------------------------------

        private static final class ParseTimeout extends RuntimeException {
            ParseTimeout() { super("Parsing exceeded the collection budget", null, false, false); }
        }

        /** Regexes over hostile markup cannot be interrupted; this view aborts them once the budget is spent. */
        private CharSequence guard(String value) {
            return new Guarded(value, 0, value.length(), deadline, new int[1]);
        }

        private record Guarded(String value, int from, int to, long deadline, int[] reads) implements CharSequence {
            public int length() { return to - from; }
            public char charAt(int index) {
                if ((++reads[0] & 0xffff) == 0 && System.nanoTime() > deadline) throw new ParseTimeout();
                return value.charAt(from + index);
            }
            public CharSequence subSequence(int start, int end) {
                return new Guarded(value, from + start, from + end, deadline, reads);
            }
            @Override public String toString() { return value.substring(from, to); }
        }
    }
}
