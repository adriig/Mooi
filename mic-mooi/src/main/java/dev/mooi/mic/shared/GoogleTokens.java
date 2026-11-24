package dev.mooi.mic.shared;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Internal access to a player's Google grant; credentials must never be returned to the browser. */
public interface GoogleTokens {
    String accessToken(UUID playerId, UUID connectionId);

    class ReauthRequired extends ResponseStatusException {
        public ReauthRequired() {
            super(HttpStatus.CONFLICT, "google_reauth_required");
        }
    }
}
