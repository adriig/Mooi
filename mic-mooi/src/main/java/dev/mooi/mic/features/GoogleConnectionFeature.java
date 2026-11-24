package dev.mooi.mic.features;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.mooi.mic.shared.Auth;
import dev.mooi.mic.shared.Crypto;
import dev.mooi.mic.shared.Db;
import dev.mooi.mic.shared.GoogleTokens;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Google reporting grants, separate from Google sign-in and from agent provider accounts. */
@RestController
@RequiredArgsConstructor
public class GoogleConnectionFeature {
    private final GoogleConnectionService service;

    @PostMapping("/me/google/connections/authorize")
    @Auth.Authenticated
    public Authorization authorize(@Valid @RequestBody(required = false) AuthorizeRequest request,
                                   Auth.Principal principal) {
        return service.authorize(principal.player().id(), request == null ? null : request.connectionId());
    }

    // Google redirects to the protected SPA route, which posts with the player's Mooi access token.
    @PostMapping("/me/google/connections/callback")
    @Auth.Authenticated
    public ConnectionPayload callback(@Valid @RequestBody CallbackRequest request, Auth.Principal principal) {
        return service.connect(principal.player().id(), request.code(), request.state());
    }

    @GetMapping("/me/google/connections")
    @Auth.Authenticated
    public List<ConnectionPayload> connections(Auth.Principal principal) {
        return service.list(principal.player().id());
    }

    @DeleteMapping("/me/google/connections/{id}")
    @Auth.Authenticated
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable UUID id, Auth.Principal principal) {
        service.disconnect(principal.player().id(), id);
    }

    @Service
    @RequiredArgsConstructor
    public static class GoogleConnectionService implements GoogleTokens {
        private final GoogleConnectionRepository repository;
        private final GoogleOAuth oauth;
        private final Crypto.SecretBox secretBox;
        private final Clock clock;

        @Transactional(readOnly = true)
        public Authorization authorize(UUID playerId, UUID connectionId) {
            oauth.requireConfigured();
            GoogleConnection existing = connectionId == null ? null : require(playerId, connectionId);
            String verifier = oauth.random();
            // Authenticated encryption hides the verifier and binds state to player, expiry and relink target.
            String state = secretBox.encrypt(playerId + "|" + clock.instant().plus(oauth.stateTtl).getEpochSecond()
                    + "|" + verifier + "|" + (connectionId == null ? "" : connectionId));
            return new Authorization(oauth.authorizeUrl(state, verifier,
                    existing == null ? null : existing.getGoogleEmail()));
        }

        @Transactional
        public ConnectionPayload connect(UUID playerId, String code, String state) {
            oauth.requireConfigured();
            String[] parts;
            UUID relinkId;
            try {
                parts = secretBox.decrypt(state).split("\\|", -1);
                if (parts.length != 4 || !parts[0].equals(playerId.toString())
                        || Long.parseLong(parts[1]) <= clock.instant().getEpochSecond()) {
                    throw new IllegalArgumentException();
                }
                relinkId = parts[3].isEmpty() ? null : UUID.fromString(parts[3]);
            } catch (IllegalStateException | IllegalArgumentException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired Google authorization state");
            }
            GoogleConnection relink = relinkId == null ? null : repository.lockOwned(relinkId, playerId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Google connection not found"));
            JsonNode grant = oauth.token(Map.of("grant_type", "authorization_code", "code", code,
                    "redirect_uri", oauth.redirectUri, "code_verifier", parts[2]), false);
            JsonNode identity = oauth.identity(grant.path("access_token").asText());
            String subject = identity.path("sub").asText("");
            String email = identity.path("email").asText("");
            if (subject.isBlank() || email.isBlank() || !identity.path("email_verified").asBoolean(false)) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Google did not return a verified email");
            }
            if (relink != null && !relink.getGoogleSubject().equals(subject)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Choose the Google account you are re-linking");
            }
            GoogleConnection connection = relink != null ? relink
                    : repository.findByPlayerIdAndGoogleSubject(playerId, subject).orElseGet(() -> {
                        GoogleConnection created = new GoogleConnection();
                        created.setPlayerId(playerId);
                        created.setGoogleSubject(subject);
                        return created;
                    });
            if (grant.path("refresh_token").asText("").isBlank()
                    && (connection.getRefreshToken() == null || connection.isReauthRequired())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Google did not grant offline access. Please link again and allow access");
            }
            connection.setGoogleEmail(email);
            connection.setScopes(grant.path("scope").asText(""));
            applyGrant(connection, grant);
            connection.setReauthRequired(false);
            return payload(repository.saveAndFlush(connection));
        }

        @Transactional(readOnly = true)
        public List<ConnectionPayload> list(UUID playerId) {
            return repository.findByPlayerIdOrderByCreatedAtAsc(playerId).stream()
                    .map(GoogleConnectionService::payload).toList();
        }

        // The flag must commit even when the caller's reporting transaction aborts on this exception.
        @Override
        @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = GoogleTokens.ReauthRequired.class)
        public String accessToken(UUID playerId, UUID connectionId) {
            GoogleConnection connection = repository.lockOwned(connectionId, playerId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Google connection not found"));
            if (connection.isReauthRequired()) {
                throw new GoogleTokens.ReauthRequired();
            }
            if (!connection.getAccessExpiresAt().isAfter(OffsetDateTime.now(clock).plus(oauth.refreshSkew))) {
                try {
                    JsonNode grant = oauth.token(Map.of("grant_type", "refresh_token",
                            "refresh_token", secretBox.decrypt(connection.getRefreshToken())), true);
                    applyGrant(connection, grant);
                    if (grant.hasNonNull("scope")) {
                        connection.setScopes(grant.path("scope").asText());
                    }
                } catch (GoogleTokens.ReauthRequired exception) {
                    connection.setReauthRequired(true);
                    repository.saveAndFlush(connection);
                    throw exception;
                }
                repository.saveAndFlush(connection);
            }
            return secretBox.decrypt(connection.getAccessToken());
        }

        @Transactional
        public void disconnect(UUID playerId, UUID connectionId) {
            GoogleConnection connection = repository.lockOwned(connectionId, playerId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Google connection not found"));
            // A transient Google failure leaves the row available to retry instead of hiding an active grant.
            oauth.revoke(secretBox.decrypt(connection.getRefreshToken()));
            repository.delete(connection);
        }

        private GoogleConnection require(UUID playerId, UUID id) {
            return repository.findByIdAndPlayerId(id, playerId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Google connection not found"));
        }

        private void applyGrant(GoogleConnection connection, JsonNode grant) {
            connection.setAccessToken(secretBox.encrypt(grant.path("access_token").asText()));
            connection.setAccessExpiresAt(OffsetDateTime.now(clock).plusSeconds(grant.path("expires_in").asLong()));
            String refresh = grant.path("refresh_token").asText("");
            // Google usually omits the refresh token on renewal: preserve the existing encrypted value.
            if (!refresh.isBlank()) {
                connection.setRefreshToken(secretBox.encrypt(refresh));
            }
        }

        private static ConnectionPayload payload(GoogleConnection connection) {
            return new ConnectionPayload(connection.getId(), connection.getGoogleEmail(),
                    Arrays.stream(connection.getScopes().split("\\s+")).filter(s -> !s.isBlank()).toList(),
                    connection.getCreatedAt(), connection.isReauthRequired());
        }
    }

    /** Only protocol mechanics; no tokens or provider response bodies are logged or exposed on errors. */
    @Component
    public static class GoogleOAuth {
        private final String clientId;
        private final String clientSecret;
        private final String redirectUri;
        private final String authorizeUri;
        private final String tokenUri;
        private final String revokeUri;
        private final String userinfoUri;
        private final String scopes;
        private final Duration stateTtl;
        private final Duration refreshSkew;
        private final Duration timeout;
        private final HttpClient client;
        private final JsonMapper json = JsonMapper.builder().build();
        private final SecureRandom random = new SecureRandom();

        GoogleOAuth(@Value("${app.google-oauth.client-id}") String clientId,
                    @Value("${app.google-oauth.client-secret}") String clientSecret,
                    @Value("${app.google-oauth.redirect-uri}") String redirectUri,
                    @Value("${app.google-oauth.authorize-uri}") String authorizeUri,
                    @Value("${app.google-oauth.token-uri}") String tokenUri,
                    @Value("${app.google-oauth.revoke-uri}") String revokeUri,
                    @Value("${app.google-oauth.userinfo-uri}") String userinfoUri,
                    @Value("${app.google-oauth.scopes}") String scopes,
                    @Value("${app.google-oauth.state-expires-in}") String stateTtl,
                    @Value("${app.google-oauth.token-refresh-skew}") String refreshSkew,
                    @Value("${app.google-oauth.request-timeout}") String timeout) {
            this.clientId = clientId;
            this.clientSecret = clientSecret;
            this.redirectUri = redirectUri;
            this.authorizeUri = authorizeUri;
            this.tokenUri = tokenUri;
            this.revokeUri = revokeUri;
            this.userinfoUri = userinfoUri;
            this.scopes = scopes;
            this.stateTtl = DurationStyle.detectAndParse(stateTtl);
            this.refreshSkew = DurationStyle.detectAndParse(refreshSkew);
            this.timeout = DurationStyle.detectAndParse(timeout);
            this.client = HttpClient.newBuilder().connectTimeout(this.timeout)
                    .followRedirects(HttpClient.Redirect.NEVER).build();
        }

        void requireConfigured() {
            if (clientId.isBlank() || clientSecret.isBlank() || redirectUri.isBlank()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Google Analytics linking is not configured on this Mooi instance");
            }
        }

        String random() {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }

        String authorizeUrl(String state, String verifier, String email) {
            try {
                String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
                return authorizeUri + "?" + form(Map.of("client_id", clientId, "redirect_uri", redirectUri,
                        "response_type", "code", "scope", scopes, "state", state, "access_type", "offline",
                        "prompt", "consent select_account", "code_challenge", challenge, "code_challenge_method", "S256"))
                        + (email == null ? "" : "&login_hint=" + encode(email));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }

        JsonNode token(Map<String, String> values, boolean refreshing) {
            requireConfigured();
            String body = form(values) + "&client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret);
            HttpResponse<String> response = send(post(tokenUri, body));
            JsonNode grant = read(response);
            if (response.statusCode() == 400 && "invalid_grant".equals(grant.path("error").asText())) {
                if (refreshing) {
                    throw new GoogleTokens.ReauthRequired();
                }
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Google authorization expired or was refused. Please link again");
            }
            if (response.statusCode() / 100 != 2 || grant.path("access_token").asText("").isBlank()
                    || grant.path("expires_in").asLong(0) <= 0
                    || !"Bearer".equalsIgnoreCase(grant.path("token_type").asText())) {
                throw unavailable();
            }
            return grant;
        }

        JsonNode identity(String accessToken) {
            HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(userinfoUri)).timeout(timeout)
                    .header("Authorization", "Bearer " + accessToken).GET().build());
            if (response.statusCode() / 100 != 2) {
                throw unavailable();
            }
            return read(response);
        }

        void revoke(String refreshToken) {
            HttpResponse<String> response = send(post(revokeUri, form(Map.of("token", refreshToken))));
            if (response.statusCode() / 100 == 2) {
                return;
            }
            if (response.statusCode() == 400 && "invalid_token".equals(read(response).path("error").asText())) {
                return; // Already revoked is the requested outcome.
            }
            throw unavailable();
        }

        private HttpRequest post(String uri, String body) {
            return HttpRequest.newBuilder(URI.create(uri)).timeout(timeout)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        }

        private HttpResponse<String> send(HttpRequest request) {
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw unavailable();
            } catch (IOException exception) {
                throw unavailable();
            }
        }

        private JsonNode read(HttpResponse<String> response) {
            try {
                JsonNode body = json.readTree(response.body());
                if (body == null || !body.isObject()) {
                    throw unavailable();
                }
                return body;
            } catch (RuntimeException exception) {
                throw unavailable();
            }
        }

        private static ResponseStatusException unavailable() {
            return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Google is unavailable. Please try again");
        }

        private static String form(Map<String, String> values) {
            return values.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                    .collect(Collectors.joining("&"));
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @Entity(name = "GoogleConnection")
    @Table(name = "google_connections")
    public static class GoogleConnection extends Db.Auditable {
        @Id @GeneratedValue
        private UUID id;
        @Column(name = "player_id", nullable = false, updatable = false)
        private UUID playerId;
        @Column(name = "google_subject", nullable = false, updatable = false, length = 255)
        private String googleSubject;
        @Column(name = "google_email", nullable = false, length = 320)
        private String googleEmail;
        @Column(name = "scopes", nullable = false, columnDefinition = "text")
        private String scopes;
        @Column(name = "refresh_token", nullable = false, columnDefinition = "text")
        private String refreshToken;
        @Column(name = "access_token", nullable = false, columnDefinition = "text")
        private String accessToken;
        @Column(name = "access_expires_at", nullable = false)
        private OffsetDateTime accessExpiresAt;
        @Column(name = "reauth_required", nullable = false)
        private boolean reauthRequired;
    }

    public interface GoogleConnectionRepository extends JpaRepository<GoogleConnection, UUID> {
        List<GoogleConnection> findByPlayerIdOrderByCreatedAtAsc(UUID playerId);
        Optional<GoogleConnection> findByIdAndPlayerId(UUID id, UUID playerId);
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        Optional<GoogleConnection> findByPlayerIdAndGoogleSubject(UUID playerId, String googleSubject);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select c from GoogleConnection c where c.id = :id and c.playerId = :playerId")
        Optional<GoogleConnection> lockOwned(UUID id, UUID playerId);
    }

    public record Authorization(String authorizeUrl) {}
    public record AuthorizeRequest(UUID connectionId) {}
    public record CallbackRequest(@NotBlank @Size(max = 8192) String code,
                                  @NotBlank @Size(max = 4096) String state) {}
    public record ConnectionPayload(UUID id, String email, List<String> scopes, OffsetDateTime linkedAt,
                                    boolean reauthRequired) {}
}
