package dev.mooi.mic.features;

import java.io.Serializable;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.mooi.mic.shared.Agents;
import dev.mooi.mic.shared.Auth;
import dev.mooi.mic.shared.Crypto;
import dev.mooi.mic.shared.Db;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature: agent-provider credentials linked to a player.
 *
 * <p>A connection hands an agent-provider credential (a Claude OAuth access token, a Codex
 * {@code auth.json}, an Antigravity credential bundle, an OpenCode API key or an OpenCode account sign-in) to {@code mic-sessions} on request; the browser
 * never holds it. Claude is linked by the Claude Code sign-in (OAuth2 + PKCE, the code pasted back from
 * Anthropic's page), whose short-lived token this feature renews: it is the only holder of the refresh
 * token, so concurrent sessions of one account never race to rotate it. Tokens pasted from {@code claude
 * setup-token} keep working. Codex and Antigravity are signed in by their own binaries inside {@code
 * mic-sessions}, which posts the result here ({@link Agents.Mode#agentOwned() agent-owned} modes); the
 * binary renews it in-session and the session writes the renewed copy back, so this feature never renews
 * them. It keeps every Claude grant renewed in the background, so an account stays signed in for as long
 * as Anthropic keeps the grant alive, exactly like Claude Code. Every mode is recorded in the same table,
 * distinguished only by {@link Agents.Mode}.
 *
 * <p>Self-contained by architecture: API, application logic, persistence and contracts live in this
 * single file and may only import transversal aspects from {@code shared}.
 */
@RestController
@RequiredArgsConstructor
public class AgentConnectionFeature {

    private final AgentConnectionService agentConnectionService;

    @GetMapping("/me/agents/providers")
    @Auth.Authenticated
    public AgentProvidersResponse providers() {
        return agentConnectionService.providers();
    }

    /** Minting a state has an effect and must never be replayed from a cache, hence POST. */
    @PostMapping("/me/agents/{provider}/authorization")
    @Auth.Authenticated
    public AuthorizationPayload startAuthorization(@PathVariable String provider, Auth.Principal principal) {
        return agentConnectionService.startAuthorization(principal.player().id(), provider);
    }

    /**
     * The code is redeemed for the caller on this request, never for whoever the state claims. It may
     * arrive as Anthropic's callback page shows it, {@code code#state}. With a {@code connectionId} the
     * grant repairs that existing account (a sign-in again) instead of adding one.
     */
    @PostMapping("/me/agents/{provider}/connection")
    @Auth.Authenticated
    public AgentConnectionPayload connectOauth(@PathVariable String provider,
            @Valid @RequestBody OauthConnectRequest request, Auth.Principal principal) {
        return agentConnectionService.connectOauth(principal.player().id(), provider, request.code(), request.state(),
                request.name(), request.connectionId());
    }

    /** Records the provider's pasted credential mode: a setup token or an API key. */
    @PutMapping("/me/agents/{provider}/connection")
    @Auth.Authenticated
    public AgentConnectionPayload connectToken(@PathVariable String provider,
            @Valid @RequestBody TokenConnectRequest request, Auth.Principal principal) {
        return agentConnectionService.connectToken(principal.player().id(), provider, request.token(), request.name());
    }

    @GetMapping("/me/agents/connections")
    @Auth.Authenticated
    public AgentConnectionsResponse connections(Auth.Principal principal) {
        return new AgentConnectionsResponse(agentConnectionService.list(principal.player().id()));
    }

    @GetMapping("/me/agents/favorite-models")
    @Auth.Authenticated
    public FavoriteModelsResponse favoriteModels(Auth.Principal principal) {
        return agentConnectionService.favoriteModels(principal.player().id());
    }

    @GetMapping("/me/agents/{provider}/favorite-models")
    @Auth.Authenticated
    public ProviderFavoriteModels favoriteModels(@PathVariable String provider, Auth.Principal principal) {
        return agentConnectionService.favoriteModels(principal.player().id(), provider);
    }

    /** Both the player's identity and the service secret are required; no caller-supplied player id. */
    @GetMapping("/internal/me/agents/favorite-models")
    @Auth.Authenticated
    @Auth.ServiceCall
    public FavoriteModelsResponse internalFavoriteModels(Auth.Principal principal) {
        return agentConnectionService.favoriteModels(principal.player().id());
    }

    /**
     * mic-sessions validates membership in the caller's connected-provider catalogs before writing.
     * Like the factory APIs, the player reaches this operation through that service, so a browser
     * cannot bypass catalog validation by calling the persistence API directly.
     */
    @PutMapping("/me/agents/{provider}/favorite-models")
    @Auth.Authenticated
    @Auth.ServiceCall
    public ProviderFavoriteModels replaceFavoriteModels(@PathVariable String provider,
            @Valid @RequestBody FavoriteModelsUpdate update, Auth.Principal principal) {
        return agentConnectionService.replaceFavoriteModels(principal.player().id(), provider, update);
    }

    /** Always 204, linked or not: disconnecting is stated as an outcome, not as a transaction. */
    @DeleteMapping("/me/agents/connections/{connectionId}")
    @Auth.Authenticated
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable UUID connectionId, Auth.Principal principal) {
        agentConnectionService.disconnect(principal.player().id(), connectionId);
    }

    @PutMapping("/me/agents/connections/{connectionId}/name")
    @Auth.Authenticated
    public AgentConnectionPayload rename(@PathVariable UUID connectionId,
            @Valid @RequestBody RenameRequest request, Auth.Principal principal) {
        return agentConnectionService.rename(principal.player().id(), connectionId, request.name());
    }

    @GetMapping("/admin/agents/defaults")
    @Auth.RequireRole(Auth.Role.ADMIN)
    public List<EngineDefaultsPayload> engineDefaults() {
        return agentConnectionService.engineDefaults();
    }

    @PutMapping("/me/agents/default-account")
    @Auth.Authenticated
    public AgentConnectionsResponse setDefaultAccount(@RequestBody DefaultAccountRequest request, Auth.Principal principal) {
        return new AgentConnectionsResponse(agentConnectionService.setDefaultAccount(principal.player().id(), request.connectionId()));
    }

    @PutMapping("/admin/agents/{provider}/defaults")
    @Auth.RequireRole(Auth.Role.ADMIN)
    public EngineDefaultsPayload setDefaultModels(@PathVariable String provider,
            @Valid @RequestBody DefaultModelsRequest request) {
        return agentConnectionService.setDefaultModels(provider, request);
    }

    /** A browser holding a valid access token is not enough here: only another internal service may call this. */
    @GetMapping("/me/agents/connections/{connectionId}/credential")
    @Auth.Authenticated
    @Auth.ServiceCall
    public CredentialPayload credential(@PathVariable UUID connectionId, Auth.Principal principal) {
        return agentConnectionService.credential(principal.player().id(), connectionId);
    }

    @PostMapping("/me/agents/codex/device-connection")
    @Auth.Authenticated
    @Auth.ServiceCall
    public AgentConnectionPayload connectDevice(@Valid @RequestBody AgentOwnedConnectRequest request,
                                                Auth.Principal principal) {
        return agentConnectionService.connectAgentOwned(principal.player().id(), "codex", request);
    }

    /** The bundle {@code agy acp --login} wrote in a temporary home of {@code mic-sessions}, for the caller. */
    @PostMapping("/me/agents/antigravity/agent-connection")
    @Auth.Authenticated
    @Auth.ServiceCall
    public AgentConnectionPayload connectAntigravity(@Valid @RequestBody AgentOwnedConnectRequest request,
                                                     Auth.Principal principal) {
        return agentConnectionService.connectAgentOwned(principal.player().id(), "antigravity", request);
    }

    /** The sign-in {@code opencode auth login} produced in a temporary profile of {@code mic-sessions}, for the caller. */
    @PostMapping("/me/agents/opencode/account-connection")
    @Auth.Authenticated
    @Auth.ServiceCall
    public AgentConnectionPayload connectOpenCodeAccount(@Valid @RequestBody AgentOwnedConnectRequest request,
                                                         Auth.Principal principal) {
        return agentConnectionService.connectAgentOwned(principal.player().id(), "opencode", request);
    }

    @PutMapping("/me/agents/{provider}/credential")
    @Auth.Authenticated
    @Auth.ServiceCall
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void refreshCredential(@PathVariable String provider,
                                  @Valid @RequestBody RefreshCredentialRequest request, Auth.Principal principal) {
        agentConnectionService.refreshCredential(principal.player().id(), provider, request);
    }

    /**
     * A session reports that the provider ended a sign-in it renews itself (Codex): the account page
     * then asks the player to sign in again, instead of the next session failing unexplained.
     */
    @PutMapping("/me/agents/{provider}/credential/expired")
    @Auth.Authenticated
    @Auth.ServiceCall
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void expireCredential(@PathVariable String provider,
                                 @Valid @RequestBody ExpiredCredentialRequest request, Auth.Principal principal) {
        agentConnectionService.expireCredential(principal.player().id(), provider, request.connectionId());
    }

    // --- application ---

    /**
     * Everything a connection does: linking a provider by either mode, listing what is linked, and
     * dropping a link.
     *
     * <p>Token material is sealed the moment it arrives and opened only for the single call that
     * needs it, so no caller is ever handed a usable agent-provider credential from here. A failed
     * renewal (added in the OAuth flow) never destroys data: a stale link the player can repair with
     * one click is strictly better than a row deleted because the provider answered badly.
     */
    @Service
    @RequiredArgsConstructor
    public static class AgentConnectionService {

        private final AgentConnectionRepository agentConnectionRepository;
        private final EngineDefaultsRepository engineDefaultsRepository;
        private final AccountPreferenceRepository accountPreferenceRepository;
        private final ModelFavoritesRepository modelFavoritesRepository;
        private final Agents.Settings settings;
        private final Agents.StateCodec stateCodec;
        private final Agents.OAuthClient oauthClient;
        private final Crypto.SecretBox secretBox;
        private final Clock clock;

        private static final Pattern ADAPTER_ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,31}$");
        /** A Codex {@code auth.json}: a few kilobytes of JSON. */
        private static final int MAX_DEVICE_OAUTH_LENGTH = 65536;
        private static final int MAX_ACCOUNT_LABEL_LENGTH = 255;

        /** One grant, whichever mode produced it. Fields absent for a mode are simply {@code null}. */
        private record Grant(String accessToken, OffsetDateTime accessTokenExpiresAt, String refreshToken,
                             String scope, String accountLabel) {
        }

        /** Every mode of a provider is always offered: the SPA signs in when OAuth is usable, else asks for a token. */
        public AgentProvidersResponse providers() {
            return new AgentProvidersResponse(settings.all().stream()
                    .map(provider -> new ProviderPayload(provider.id(), provider.label(),
                            provider.modes().stream().map(Agents.Mode::wire).toList(), provider.signInAvailable()))
                    .toList());
        }

        public List<AgentConnectionPayload> list(UUID playerId) {
            UUID defaultId = defaultAccountId(playerId);
            return agentConnectionRepository.findByPlayerIdOrderByConnectedAtDesc(playerId).stream()
                    .map(connection -> toPayload(connection, defaultId))
                    .toList();
        }

        /** Missing rows are empty preferences, including for accounts created before this feature. */
        @Transactional(readOnly = true)
        public FavoriteModelsResponse favoriteModels(UUID playerId) {
            Map<String, List<String>> favorites = new LinkedHashMap<>();
            settings.all().forEach(provider -> favorites.put(provider.id(), List.of()));
            modelFavoritesRepository.findByPlayerId(playerId).forEach(row -> {
                if (favorites.containsKey(row.getProvider())) {
                    favorites.put(row.getProvider(), readFavoriteModels(row));
                }
            });
            return new FavoriteModelsResponse(favorites);
        }

        @Transactional(readOnly = true)
        public ProviderFavoriteModels favoriteModels(UUID playerId, String provider) {
            settings.find(provider).orElseThrow(Agents.AgentsException::unknownProvider);
            return new ProviderFavoriteModels(provider, modelFavoritesRepository.findByPlayerIdAndProvider(playerId, provider)
                    .map(this::readFavoriteModels).orElse(List.of()));
        }

        @Transactional
        public ProviderFavoriteModels replaceFavoriteModels(UUID playerId, String provider, FavoriteModelsUpdate update) {
            settings.find(provider).orElseThrow(Agents.AgentsException::unknownProvider);
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (Object value : update.modelIds()) {
                if (!(value instanceof String modelId) || modelId.length() > 255 || modelId.strip().isEmpty()
                        || modelId.strip().codePoints().anyMatch(codePoint -> Character.isWhitespace(codePoint)
                                || Character.isSpaceChar(codePoint) || Character.isISOControl(codePoint))) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Model ids must be non-empty strings of at most 255 characters without whitespace or controls");
                }
                normalized.add(modelId.strip());
            }
            List<String> modelIds = List.copyOf(normalized);
            // Clearing is allowed even after disconnecting the last account of this provider.
            if (!modelIds.isEmpty() && !agentConnectionRepository.existsByPlayerIdAndProvider(playerId, provider)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No account linked for this provider");
            }
            modelFavoritesRepository.replace(playerId, provider, JsonMapper.shared().writeValueAsString(modelIds));
            return new ProviderFavoriteModels(provider, modelIds);
        }

        private List<String> readFavoriteModels(ModelFavorites row) {
            return JsonMapper.shared().readValue(row.getModelIds(), new TypeReference<List<String>>() { });
        }

        /**
         * The URL is assembled here rather than in the SPA so the client id and the registered
         * redirect URI stay configuration of the API, and so the PKCE {@code state} is signed by the
         * very party that will later verify it.
         */
        public AuthorizationPayload startAuthorization(UUID playerId, String provider) {
            Agents.Provider agentProvider = settings.find(provider).orElseThrow(Agents.AgentsException::unknownProvider);
            if (!agentProvider.signInAvailable()) {
                throw Agents.AgentsException.oauthNotConfigured();
            }
            Agents.StateCodec.Issued issued = stateCodec.issue(playerId, agentProvider.id());
            return new AuthorizationPayload(Agents.authorizeUrl(agentProvider, issued.state(), issued.codeChallenge()),
                    issued.state());
        }

        /**
         * Closes the loop opened by {@link #startAuthorization(UUID, String)}.
         *
         * <p>The state is checked against the caller and the provider, not merely against our own
         * signature: a perfectly valid state minted for somebody else, or for a different provider,
         * is exactly the attack this parameter exists to stop, and only this request knows both.
         *
         * <p>Anthropic's page shows {@code code#state}, and that pasted state wins over the one the
         * client sends: it is the one the authorization was issued for, signed and bound to this caller.
         * A phone that reloaded the app while the player signed in (an installed PWA coming back from
         * Safari) has minted a new attempt by then, and its code must still redeem.
         *
         * <p>With {@code connectionId} the grant replaces the credential of that account rather than
         * adding one, so its sessions, title and default selection carry on.
         */
        @Transactional
        public AgentConnectionPayload connectOauth(UUID playerId, String provider, String code, String state, String name,
                                                   UUID connectionId) {
            Agents.Provider agentProvider = settings.find(provider).orElseThrow(Agents.AgentsException::unknownProvider);
            if (!agentProvider.signInAvailable()) {
                throw Agents.AgentsException.oauthNotConfigured();
            }
            String pasted = code.strip();
            int separator = pasted.indexOf('#');
            String pastedState = separator >= 0 ? pasted.substring(separator + 1).strip() : "";
            String attemptState = !pastedState.isEmpty() ? pastedState : state;
            if (attemptState == null || attemptState.isBlank()) {
                throw Agents.AgentsException.invalidState();
            }
            Agents.StateCodec.StateClaims claims = stateCodec.verify(attemptState);
            if (!claims.playerId().equals(playerId) || !claims.provider().equals(provider)) {
                throw Agents.AgentsException.invalidState();
            }
            String authorizationCode = separator >= 0 ? pasted.substring(0, separator).strip() : pasted;
            if (authorizationCode.isEmpty()) {
                throw Agents.AgentsException.invalidToken();
            }
            AgentConnection existing = null;
            if (connectionId != null) {
                existing = agentConnectionRepository.findLockedByPlayerIdAndId(playerId, connectionId)
                        .filter(connection -> connection.getProvider().equals(agentProvider.id()))
                        .orElseThrow(Agents.AgentsException::reauthorize);
            } else if (cleanName(name) == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account title is required");
            }
            Grant grant = toGrant(oauthClient.exchangeCode(agentProvider, authorizationCode, attemptState,
                    claims.codeVerifier()));
            AgentConnection saved = existing == null
                    ? create(playerId, agentProvider, Agents.Mode.OAUTH, grant, name)
                    : relink(existing, grant, name);
            Agents.LOG.info("Player {} {} agent provider {} ({})", playerId, existing == null ? "linked" : "signed in again to",
                    agentProvider.id(), Agents.Mode.OAUTH.wire());
            return toPayload(saved);
        }

        /**
         * Hands out a usable credential to another internal service. For {@code setup_token} and the
         * agent-owned modes ({@code device_oauth}, {@code agent_oauth}) the stored token is handed out as is:
         * it never expires here and has no refresh token this platform could use, the provider's binary
         * renews it in-session. For {@code oauth} it is renewed first when it is within the refresh margin of
         * expiring, so a caller is never handed a credential that dies mid-flight. A refresh token is never
         * handed out.
         *
         * <p>The row is read under a write lock, held for this short transaction: the refresh token is
         * single-use, so two sessions asking at once must not both spend it — the second one simply
         * finds the renewed token. A failed renewal commits the {@code stale} mark it leaves behind.
         */
        @Transactional(noRollbackFor = Agents.AgentsException.class)
        public CredentialPayload credential(UUID playerId, UUID connectionId) {
            AgentConnection connection = agentConnectionRepository.findLockedByPlayerIdAndId(playerId, connectionId)
                    .orElseThrow(Agents.AgentsException::reauthorize);
            Agents.Provider agentProvider = settings.find(connection.getProvider()).orElseThrow(Agents.AgentsException::unknownProvider);
            if (Agents.Mode.fromWire(connection.getMode()) == Agents.Mode.OAUTH) {
                connection = ensureFreshToken(connection, agentProvider);
            }
            EngineDefaults defaults = engineDefaultsRepository.findById(agentProvider.id()).orElseThrow();
            return new CredentialPayload(agentProvider.id(), connection.getMode(),
                    secretBox.decrypt(connection.getAccessToken()), connection.getAccessTokenExpiresAt(),
                    connection.getCredentialId(), defaults.getSessionModel(), defaults.getSessionEffort(),
                    defaults.getSessionAdapter());
        }

        /**
         * Records a pasted credential: a {@code claude setup-token} token or an OpenCode API key, as the provider's {@link
         * Agents.Provider#pastedMode()}. Token formats are the provider's to change, but values that are
         * unmistakably URLs are rejected before encryption: they are commonly copied from the browser beside
         * the key and can never authenticate a provider.
         */
        @Transactional
        public AgentConnectionPayload connectToken(UUID playerId, String provider, String token, String name) {
            Agents.Provider agentProvider = settings.find(provider).orElseThrow(Agents.AgentsException::unknownProvider);
            Agents.Mode mode = agentProvider.pastedMode().orElseThrow(Agents.AgentsException::invalidToken);
            String cleaned = token == null ? "" : token.strip();
            if (cleaned.isEmpty() || agentProvider.id().equals("opencode") && looksLikeUrl(cleaned)) {
                throw Agents.AgentsException.invalidToken();
            }
            AgentConnection saved = create(playerId, agentProvider, mode, new Grant(cleaned, null, null, null, null), name);
            Agents.LOG.info("Player {} linked agent provider {} ({})", playerId, agentProvider.id(), mode.wire());
            return toPayload(saved);
        }

        private static boolean looksLikeUrl(String value) {
            return value.regionMatches(true, 0, "https://", 0, 8)
                    || value.regionMatches(true, 0, "http://", 0, 7);
        }

        /**
         * Records what a provider's own binary signed in with inside {@code mic-sessions} (a Codex {@code
         * auth.json}, an Antigravity bundle), always for the caller, as the provider's agent-owned mode. Only
         * the shape is checked; the token never reaches a log. With {@code connectionId} it signs that
         * existing account in again: title and default selection stay, and the credential id changes so the
         * sessions still holding the ended sign-in stop renewing it over the new one and ask for a new session.
         */
        @Transactional
        public AgentConnectionPayload connectAgentOwned(UUID playerId, String provider, AgentOwnedConnectRequest request) {
            Agents.Provider agentProvider = settings.find(provider).orElseThrow(Agents.AgentsException::unknownProvider);
            Agents.Mode mode = agentProvider.agentOwnedMode().orElseThrow(Agents.AgentsException::unknownProvider);
            String accountLabel = request.accountLabel() == null || request.accountLabel().isBlank()
                    ? null : request.accountLabel().strip();
            if (!validAgentOwned(mode, request.token())
                    || accountLabel != null && accountLabel.length() > MAX_ACCOUNT_LABEL_LENGTH) {
                throw Agents.AgentsException.invalidToken();
            }
            Grant grant = new Grant(request.token(), null, null, null, accountLabel);
            if (request.connectionId() == null) {
                AgentConnection saved = create(playerId, agentProvider, mode, grant, request.name());
                Agents.LOG.info("Player {} linked agent provider {} ({})", playerId, agentProvider.id(), mode.wire());
                return toPayload(saved);
            }
            AgentConnection connection = agentConnectionRepository.findLockedByPlayerIdAndId(playerId, request.connectionId())
                    .filter(existing -> existing.getProvider().equals(agentProvider.id()))
                    .orElseThrow(Agents.AgentsException::reauthorize);
            connection.setCredentialId(UUID.randomUUID());
            connection.setMode(mode.wire());
            applyGrant(connection, grant);
            Agents.LOG.info("Player {} signed in again to agent provider {} ({})", playerId, agentProvider.id(),
                    mode.wire());
            return toPayload(agentConnectionRepository.save(connection));
        }

        /** Idempotent: an account already flagged, relinked meanwhile or gone is left as it is. */
        @Transactional
        public void expireCredential(UUID playerId, String provider, UUID credentialId) {
            if (agentConnectionRepository.markStale(playerId, provider, credentialId) == 1) {
                Agents.LOG.warn("Agent provider {} ended a sign-in of player {}; it needs a new sign-in", provider, playerId);
            }
        }

        /**
         * Writes back the copy a session's binary renewed. The connection must still be the caller's, of this
         * provider and mode, with the credential id the session was handed: a relinked or dropped account
         * answers 409, never overwrites another link.
         */
        @Transactional
        public void refreshCredential(UUID playerId, String provider, RefreshCredentialRequest request) {
            Agents.Mode mode = settings.find(provider).flatMap(Agents.Provider::agentOwnedMode)
                    .orElseThrow(Agents.AgentsException::reauthorize);
            if (!validAgentOwned(mode, request.token())) {
                throw Agents.AgentsException.invalidToken();
            }
            if (agentConnectionRepository.refreshAgentOwned(playerId, provider, mode.wire(), request.connectionId(),
                    secretBox.encrypt(request.token())) != 1) {
                throw Agents.AgentsException.reauthorize();
            }
        }

        private static boolean validAgentOwned(Agents.Mode mode, String token) {
            if (mode == Agents.Mode.ACCOUNT) {
                return Agents.OpenCodeAccount.isValid(token);
            }
            return mode == Agents.Mode.AGENT_OAUTH ? Agents.AgentOAuthBundle.isValid(token)
                    : token != null && !token.isBlank() && token.length() <= MAX_DEVICE_OAUTH_LENGTH;
        }

        /** Idempotent: disconnecting an account that was never linked is not an error. */
        @Transactional
        public void disconnect(UUID playerId, UUID connectionId) {
            agentConnectionRepository.findByPlayerIdAndId(playerId, connectionId).ifPresent(connection -> {
                agentConnectionRepository.delete(connection);
                Agents.LOG.info("Player {} unlinked agent connection {}", playerId, connectionId);
            });
        }

        @Transactional
        public AgentConnectionPayload rename(UUID playerId, UUID connectionId, String name) {
            AgentConnection connection = agentConnectionRepository.findByPlayerIdAndId(playerId, connectionId)
                    .orElseThrow(Agents.AgentsException::reauthorize);
            connection.setName(cleanName(name));
            return toPayload(agentConnectionRepository.save(connection));
        }

        public List<EngineDefaultsPayload> engineDefaults() {
            return engineDefaultsRepository.findAll().stream().map(this::toDefaultsPayload).toList();
        }

        @Transactional
        public EngineDefaultsPayload setDefaultModels(String provider, DefaultModelsRequest request) {
            settings.find(provider).orElseThrow(Agents.AgentsException::unknownProvider);
            EngineDefaults defaults = engineDefaultsRepository.findById(provider).orElseThrow();
            defaults.setSessionModel(cleanModel(request.sessionModel()));
            defaults.setSessionEffort(cleanEffort(request.sessionEffort()));
            defaults.setSessionAdapter(cleanAdapter(request.sessionAdapter()));
            return toDefaultsPayload(engineDefaultsRepository.save(defaults));
        }

        @Transactional
        public List<AgentConnectionPayload> setDefaultAccount(UUID playerId, UUID connectionId) {
            if (connectionId == null) {
                accountPreferenceRepository.deleteById(playerId);
            } else {
                agentConnectionRepository.findByPlayerIdAndId(playerId, connectionId)
                        .orElseThrow(Agents.AgentsException::reauthorize);
                accountPreferenceRepository.selectAccount(playerId, connectionId);
            }
            return list(playerId);
        }

        private UUID defaultAccountId(UUID playerId) {
            return accountPreferenceRepository.findById(playerId).map(AccountPreference::getConnectionId).orElse(null);
        }

        private EngineDefaultsPayload toDefaultsPayload(EngineDefaults defaults) {
            return new EngineDefaultsPayload(defaults.getProvider(), defaults.getSessionModel(), defaults.getSessionEffort(),
                    defaults.getSessionAdapter());
        }

        /**
         * Adapters are registered in mic-sessions, so only the shape is checked here: an id it does not
         * know (or no longer knows) simply falls back to the provider's first adapter there.
         */
        private String cleanAdapter(String value) {
            if (value == null || value.isBlank()) return null;
            String cleaned = value.strip();
            if (!ADAPTER_ID.matcher(cleaned).matches())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid adapter");
            return cleaned;
        }

        private String cleanEffort(String value) {
            if (value == null || value.isBlank()) return null;
            String cleaned = value.strip();
            if (cleaned.length() > 32) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid effort");
            return cleaned;
        }

        private String cleanModel(String value) {
            if (value == null || value.isBlank()) return null;
            String cleaned = value.strip();
            if (cleaned.length() > 120 || cleaned.equalsIgnoreCase("default"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid model");
            return cleaned;
        }

        /** Creates a separate account link for every completed authorization. */
        private AgentConnection create(UUID playerId, Agents.Provider provider, Agents.Mode mode, Grant grant, String name) {
            AgentConnection connection = new AgentConnection();
            connection.setPlayerId(playerId);
            connection.setProvider(provider.id());
            connection.setConnectedAt(OffsetDateTime.now(clock));
            connection.setCredentialId(UUID.randomUUID());
            connection.setName(cleanName(name));
            connection.setMode(mode.wire());
            connection.setAccountLabel(grant.accountLabel());
            connection.setAccessToken(secretBox.encrypt(grant.accessToken()));
            connection.setAccessTokenExpiresAt(grant.accessTokenExpiresAt());
            connection.setRefreshToken(secretBox.encrypt(grant.refreshToken()));
            connection.setScope(grant.scope());
            connection.setStale(false);
            return agentConnectionRepository.save(connection);
        }

        private String cleanName(String name) {
            if (name == null || name.isBlank()) return null;
            String cleaned = name.strip();
            if (cleaned.length() > 100) throw Agents.AgentsException.invalidToken();
            return cleaned;
        }

        /** The OAuth connections whose token is due for renewal, soonest first, for the keeper. */
        public List<UUID> renewalDue(int limit) {
            return agentConnectionRepository.findRenewalDue(Agents.Mode.OAUTH.wire(),
                    OffsetDateTime.now(clock).plus(settings.getTokenRefreshSkew()), Pageable.ofSize(limit));
        }

        /**
         * Renews one connection in the background, under the same row lock as {@link #credential}, and
         * only if it is still due once locked: another instance, or a session asking for it, may have
         * renewed it meanwhile. A failure is already recorded by {@link #renew} and retried next run.
         */
        @Transactional(noRollbackFor = Agents.AgentsException.class)
        public void renewIfDue(UUID connectionId) {
            agentConnectionRepository.findLockedById(connectionId)
                    .filter(connection -> Agents.Mode.OAUTH.wire().equals(connection.getMode()) && !connection.isStale())
                    .filter(this::renewalDue)
                    .ifPresent(connection -> settings.find(connection.getProvider()).ifPresent(provider -> {
                        try {
                            renew(connection, provider);
                        } catch (Agents.AgentsException exception) {
                            // Logged and recorded by renew; the next run retries an outage.
                        }
                    }));
        }

        private boolean renewalDue(AgentConnection connection) {
            OffsetDateTime expiresAt = connection.getAccessTokenExpiresAt();
            return expiresAt != null && !expiresAt.isAfter(OffsetDateTime.now(clock).plus(settings.getTokenRefreshSkew()));
        }

        /**
         * Renews an OAuth credential shortly before it expires, so nothing downstream is handed a
         * token that dies mid-flight. Claude Code's own rule: a sign-in ends only when the provider
         * refuses the refresh token, never because renewal failed for a while.
         *
         * <p>A token that still works is handed out whatever renewal answered — the keeper retries an
         * outage, and a refused grant is already flagged for the player to sign in again. Only a token
         * past its expiry fails the call: with {@link Agents.AgentsException#reauthorize()} when the
         * grant is refused, with the outage itself (retryable) otherwise.
         */
        private AgentConnection ensureFreshToken(AgentConnection connection, Agents.Provider provider) {
            if (!renewalDue(connection)) {
                return connection;
            }
            try {
                return renew(connection, provider);
            } catch (Agents.AgentsException exception) {
                if (connection.getAccessTokenExpiresAt().isAfter(OffsetDateTime.now(clock))) {
                    return connection;
                }
                throw exception;
            }
        }

        /**
         * Spends the refresh token for a new grant. The refresh token rotates on every renewal, so a
         * grant renewed regularly never lapses. A definitive refusal marks the row {@code stale} and
         * throws {@link Agents.AgentsException#reauthorize()} rather than destroying it: the link is
         * still a fact, and signing in again repairs it. An outage leaves the row as it was.
         */
        private AgentConnection renew(AgentConnection connection, Agents.Provider provider) {
            String refreshToken = secretBox.decrypt(connection.getRefreshToken());
            if (refreshToken == null) {
                Agents.LOG.warn("Agent connection {} of player {} has no refresh token to renew with",
                        connection.getId(), connection.getPlayerId());
                markStale(connection);
                throw Agents.AgentsException.reauthorize();
            }
            try {
                Agents.Tokens renewed = oauthClient.refresh(provider, refreshToken, connection.getScope());
                // Some providers omit fields on renewal rather than rotating them: keep what was there.
                applyGrant(connection, new Grant(renewed.accessToken(), renewed.accessTokenExpiresAt(),
                        renewed.refreshToken() != null ? renewed.refreshToken() : refreshToken,
                        renewed.scope() != null ? renewed.scope() : connection.getScope(),
                        renewed.accountLabel() != null ? renewed.accountLabel() : connection.getAccountLabel()));
                return agentConnectionRepository.save(connection);
            } catch (Agents.AgentsException exception) {
                if (!exception.isDefinitive()) {
                    Agents.LOG.warn("Agent connection {} of player {} could not be renewed yet: {}",
                            connection.getId(), connection.getPlayerId(), exception.getReason());
                    throw exception;
                }
                Agents.LOG.warn("Agent connection {} of player {} was refused renewal ({}); it needs a new sign-in",
                        connection.getId(), connection.getPlayerId(), exception.getOauthError());
                markStale(connection);
                throw Agents.AgentsException.reauthorize();
            }
        }

        /**
         * A new sign-in into an existing account. Signing in to a different account than before
         * changes its credential id, so live sessions see the account change instead of silently
         * switching subscription mid-conversation.
         */
        private AgentConnection relink(AgentConnection connection, Grant grant, String name) {
            String previous = connection.getAccountLabel();
            if (previous != null && grant.accountLabel() != null && !previous.equalsIgnoreCase(grant.accountLabel())) {
                connection.setCredentialId(UUID.randomUUID());
            }
            if (cleanName(name) != null) {
                connection.setName(cleanName(name));
            }
            connection.setMode(Agents.Mode.OAUTH.wire());
            applyGrant(connection, grant);
            return agentConnectionRepository.save(connection);
        }

        private void applyGrant(AgentConnection connection, Grant grant) {
            connection.setAccessToken(secretBox.encrypt(grant.accessToken()));
            connection.setAccessTokenExpiresAt(grant.accessTokenExpiresAt());
            connection.setRefreshToken(secretBox.encrypt(grant.refreshToken()));
            connection.setScope(grant.scope());
            if (grant.accountLabel() != null) {
                connection.setAccountLabel(grant.accountLabel());
            }
            connection.setStale(false);
        }

        private void markStale(AgentConnection connection) {
            connection.setStale(true);
            agentConnectionRepository.save(connection);
        }

        private static Grant toGrant(Agents.Tokens tokens) {
            return new Grant(tokens.accessToken(), tokens.accessTokenExpiresAt(), tokens.refreshToken(),
                    tokens.scope(), tokens.accountLabel());
        }

        private AgentConnectionPayload toPayload(AgentConnection connection) {
            return toPayload(connection, defaultAccountId(connection.getPlayerId()));
        }

        private AgentConnectionPayload toPayload(AgentConnection connection, UUID defaultId) {
            String label = settings.find(connection.getProvider())
                    .map(Agents.Provider::label)
                    .orElse(connection.getProvider());
            return new AgentConnectionPayload(connection.getId(), connection.getProvider(), label, connection.getName(), connection.getMode(),
                    connection.getAccountLabel(), connection.getScope(), connection.getConnectedAt(),
                    connection.getAccessTokenExpiresAt(), connection.isStale(), connection.getId().equals(defaultId));
        }
    }

    /**
     * Keeps every Claude sign-in alive, the way a Claude Code that is always open does: each grant is
     * renewed shortly before its access token expires, whether or not a session is running, so the
     * refresh token keeps rotating and never lapses from disuse. Sessions then always find a fresh token,
     * and a grant the provider revoked is flagged on the account page before anyone tries to use it.
     */
    @Component
    @RequiredArgsConstructor
    public static class AgentTokenKeeper {

        /** Bounds one run; a larger backlog simply carries over to the next one. */
        private static final int BATCH = 200;

        private final AgentConnectionService agentConnectionService;

        @Scheduled(cron = "${app.agents.token-renewal-cron}")
        public void renewDueTokens() {
            for (UUID connectionId : agentConnectionService.renewalDue(BATCH)) {
                try {
                    agentConnectionService.renewIfDue(connectionId);
                } catch (RuntimeException exception) {
                    Agents.LOG.warn("Background renewal of agent connection {} failed", connectionId, exception);
                }
            }
        }
    }

    // --- persistence ---

    /**
     * One agent-provider credential linked to one player. A player may link several accounts for
     * the same provider.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @Entity(name = "AgentConnection")
    @Table(name = "agent_connections")
    public static class AgentConnection extends Db.Auditable {

        @Id
        @GeneratedValue
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id;

        @Column(name = "player_id", nullable = false, updatable = false)
        private UUID playerId;

        @Column(name = "provider", nullable = false, length = 32, updatable = false)
        private String provider;

        @Column(name = "mode", nullable = false, length = 16)
        private String mode;

        @Column(name = "account_label", length = 255)
        private String accountLabel;

        @Column(name = "name", length = 100)
        private String name;

        /**
         * AES-256-GCM ciphertext produced by {@code Crypto.SecretBox}, never a usable credential.
         * The plaintext exists only inside the request that needs it.
         */
        @Column(name = "credential_id", nullable = false)
        private UUID credentialId;

        @Column(name = "access_token", nullable = false, columnDefinition = "text")
        private String accessToken;

        /** NULL for a {@code setup_token} credential: it does not expire. */
        @Column(name = "access_token_expires_at")
        private OffsetDateTime accessTokenExpiresAt;

        /** Ciphertext, as above. NULL for a {@code setup_token} credential: there is nothing to renew with. */
        @Column(name = "refresh_token", length = 2048)
        private String refreshToken;

        @Column(name = "scope", length = 512)
        private String scope;

        /** True once an OAuth credential expired and renewal failed. Cleared by any fresh grant. */
        @Column(name = "stale", nullable = false)
        private boolean stale;

        /** When the player first linked this provider; survives a relink or a token renewal. */
        @Column(name = "connected_at", nullable = false)
        private OffsetDateTime connectedAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @Entity(name = "EngineDefaults")
    @Table(name = "agent_engine_defaults")
    public static class EngineDefaults {
        @Id
        @Column(length = 32)
        private String provider;
        @Column(name = "session_model", length = 120)
        private String sessionModel;
        @Column(name = "session_effort", length = 32)
        private String sessionEffort;
        @Column(name = "session_adapter", length = 32)
        private String sessionAdapter;
    }

    public interface EngineDefaultsRepository extends JpaRepository<EngineDefaults, String> { }

    @Getter
    @NoArgsConstructor
    @Entity(name = "AccountPreference")
    @Table(name = "agent_account_preferences")
    public static class AccountPreference {
        @Id
        @Column(name = "player_id")
        private UUID playerId;
        @Column(name = "connection_id")
        private UUID connectionId;
    }

    public interface AccountPreferenceRepository extends JpaRepository<AccountPreference, UUID> {
        @Modifying
        @Query(value = "insert into agent_account_preferences (player_id, connection_id) values (:playerId, :connectionId) "
                + "on conflict (player_id) do update set connection_id = excluded.connection_id", nativeQuery = true)
        void selectAccount(UUID playerId, UUID connectionId);
    }

    /** Preferences survive disconnecting an individual account; ownership is player + provider. */
    @Getter
    @NoArgsConstructor
    @Entity(name = "AgentModelFavorites")
    @Table(name = "agent_model_favorites")
    @IdClass(ModelFavoritesId.class)
    public static class ModelFavorites {
        @Id
        @Column(name = "player_id", nullable = false, updatable = false)
        private UUID playerId;
        @Id
        @Column(name = "provider", nullable = false, updatable = false, length = 32)
        private String provider;
        @Column(name = "model_ids", nullable = false, columnDefinition = "text")
        private String modelIds;
    }

    @Getter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class ModelFavoritesId implements Serializable {
        private UUID playerId;
        private String provider;
    }

    public interface ModelFavoritesRepository extends JpaRepository<ModelFavorites, ModelFavoritesId> {
        List<ModelFavorites> findByPlayerId(UUID playerId);

        Optional<ModelFavorites> findByPlayerIdAndProvider(UUID playerId, String provider);

        /** Atomic replacement also handles simultaneous first writes from different devices. */
        @Modifying
        @Query(value = "insert into agent_model_favorites (player_id, provider, model_ids) "
                + "values (:playerId, :provider, :modelIds) on conflict (player_id, provider) "
                + "do update set model_ids = excluded.model_ids", nativeQuery = true)
        void replace(UUID playerId, String provider, String modelIds);
    }

    public interface AgentConnectionRepository extends JpaRepository<AgentConnection, UUID> {

        List<AgentConnection> findByPlayerIdOrderByConnectedAtDesc(UUID playerId);

        boolean existsByPlayerIdAndProvider(UUID playerId, String provider);

        Optional<AgentConnection> findByPlayerIdAndId(UUID playerId, UUID id);

        /** Serializes renewals of one connection: see {@code AgentConnectionService#credential}. */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select c from AgentConnection c where c.playerId = :playerId and c.id = :id")
        Optional<AgentConnection> findLockedByPlayerIdAndId(UUID playerId, UUID id);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select c from AgentConnection c where c.id = :id")
        Optional<AgentConnection> findLockedById(UUID id);

        /** Live grants expiring before {@code threshold}; a stale one waits for its player to sign in again. */
        @Query("select c.id from AgentConnection c where c.mode = :mode and c.stale = false "
                + "and c.accessTokenExpiresAt < :threshold order by c.accessTokenExpiresAt")
        List<UUID> findRenewalDue(String mode, OffsetDateTime threshold, Pageable page);

        // The identity check and update must be atomic with a concurrent disconnect/relink.
        @Modifying
        @Query("update AgentConnection c set c.accessToken = :token, c.stale = false "
                + "where c.playerId = :playerId and c.provider = :provider "
                + "and c.mode = :mode and c.credentialId = :connectionId")
        int refreshAgentOwned(UUID playerId, String provider, String mode, UUID connectionId, String token);

        @Modifying
        @Query("update AgentConnection c set c.stale = true where c.playerId = :playerId "
                + "and c.provider = :provider and c.credentialId = :credentialId and c.stale = false")
        int markStale(UUID playerId, String provider, UUID credentialId);
    }

    // --- contracts ---

    /**
     * What the SPA is told about a connection. No token material of any kind: the client never needs
     * an agent-provider credential, so it is never given one. {@code stale} is {@code true} when an
     * OAuth credential expired and renewal failed — the player repairs it with one click, the row is
     * not deleted.
     */
    public record AgentConnectionPayload(UUID id, String provider, String label, String name, String mode, String accountLabel,
                                         String scope, OffsetDateTime connectedAt, OffsetDateTime expiresAt,
                                         boolean stale, boolean defaultAccount) {
    }

    /**
     * Where the browser sends the player to authorize, and the state to send back with the pasted code.
     * The state is signed and carries the PKCE verifier, so the API keeps nothing between the two calls.
     */
    public record AuthorizationPayload(String authorizeUrl, String state) {
    }

    /**
     * A usable credential, handed only to another internal service. The single place, across every
     * payload in this feature, where a raw token is ever serialized. {@code connectionId} is the
     * connection's credential id, the one a write-back must name.
     */
    public record CredentialPayload(String provider, String mode, String token, OffsetDateTime expiresAt, UUID connectionId,
                                    String sessionModel, String sessionEffort, String sessionAdapter) {
    }

    /** One provider this application knows how to link, and whether OAuth is actually usable for it. */
    public record ProviderPayload(String id, String label, List<String> modes, boolean oauthEnabled) {
    }

    public record AgentProvidersResponse(List<ProviderPayload> providers) {
    }

    public record AgentConnectionsResponse(List<AgentConnectionPayload> connections) {
    }

    /** Provider ids map to lists of exact, case-sensitive model ids; empty means no preference. */
    public record FavoriteModelsResponse(Map<String, List<String>> favorites) {
    }

    public record ProviderFavoriteModels(String provider, List<String> modelIds) {
    }

    /** Wildcard elements intentionally avoid Jackson coercing JSON numbers/booleans into strings. */
    public record FavoriteModelsUpdate(@NotNull @Size(max = 100) List<?> modelIds) {
    }

    /**
     * {@code state} may be omitted when {@code code} is pasted as {@code code#state}. {@code name} is
     * required for a new account; {@code connectionId} signs an existing account in again instead.
     */
    public record OauthConnectRequest(@NotBlank String code, String state, String name, UUID connectionId) {
    }

    /**
     * A credential a provider's own binary produced; {@code accountLabel} is the account e-mail it reported.
     * {@code connectionId} signs that existing account in again instead of adding one.
     */
    public record AgentOwnedConnectRequest(@NotBlank String token, String accountLabel, @NotBlank String name,
                                           UUID connectionId) { }

    /** The credential id a session was handed ({@code CredentialPayload.connectionId}). */
    public record ExpiredCredentialRequest(@jakarta.validation.constraints.NotNull UUID connectionId) { }

    public record RefreshCredentialRequest(@NotBlank String token,
            @jakarta.validation.constraints.NotNull UUID connectionId) { }

    public record TokenConnectRequest(@NotBlank String token, @NotBlank String name) {
    }

    public record RenameRequest(@NotBlank String name) {
    }

    public record DefaultAccountRequest(UUID connectionId) { }

    public record EngineDefaultsPayload(String provider, String sessionModel, String sessionEffort,
                                        String sessionAdapter) { }

    public record DefaultModelsRequest(String sessionModel, String sessionEffort, String sessionAdapter) {
    }
}
