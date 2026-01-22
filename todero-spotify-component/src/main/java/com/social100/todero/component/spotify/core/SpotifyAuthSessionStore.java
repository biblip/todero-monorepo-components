package com.social100.todero.component.spotify.core;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.social100.todero.common.runtime.auth.AuthorizationSession;
import com.social100.todero.common.runtime.auth.AuthorizationSessionStatus;
import com.social100.todero.common.runtime.auth.AuthorizationSecureEnvelope;
import com.social100.todero.common.storage.Storage;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

final class SpotifyAuthSessionStore {
  private static final Gson GSON = new Gson();
  private static final String SESSION_PREFIX = "auth/session/";
  private static final String ACTIVE_SESSION_KEY = "auth/session/active";
  private static final String ENVELOPE_SECRET_KEY = "auth/envelope-secret";
  private static final String REPLAY_PREFIX = "auth/replay/";
  private static final SecureRandom RNG = new SecureRandom();

  private final Storage storage;

  SpotifyAuthSessionStore(Storage storage) {
    this.storage = storage;
  }

  void save(SessionEnvelope sessionEnvelope) {
    try {
      storage.putSecret(sessionKey(sessionEnvelope.session().sessionId()), GSON.toJson(sessionEnvelope));
      storage.putSecret(ACTIVE_SESSION_KEY, sessionEnvelope.session().sessionId());
    } catch (IOException e) {
      throw new RuntimeException("Failed to persist auth session.", e);
    }
  }

  Optional<SessionEnvelope> get(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return Optional.empty();
    }
    try {
      String raw = storage.getSecret(sessionKey(sessionId.trim()));
      if (raw == null || raw.isBlank()) {
        return Optional.empty();
      }
      return Optional.ofNullable(GSON.fromJson(raw, SessionEnvelope.class));
    } catch (IOException | JsonSyntaxException e) {
      return Optional.empty();
    }
  }

  Optional<SessionEnvelope> getActive() {
    try {
      String activeId = storage.getSecret(ACTIVE_SESSION_KEY);
      if (activeId == null || activeId.isBlank()) {
        return Optional.empty();
      }
      return get(activeId);
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  SessionEnvelope requireActive() {
    return getActive().orElseThrow(() -> new IllegalStateException("No active auth session."));
  }

  SessionEnvelope updateSessionStatus(SessionEnvelope envelope,
                                      AuthorizationSessionStatus status,
                                      Long completedAtMs,
                                      String errorCode) {
    AuthorizationSession updated = envelope.session().withStatus(status, completedAtMs, errorCode);
    SessionEnvelope next = new SessionEnvelope(updated, envelope.redirectUri(), envelope.codeVerifier(), envelope.requiredScopes(), envelope.envelope());
    save(next);
    return next;
  }

  boolean registerReplayNonce(String scope, String nonce, long expiresAtMs, long nowMs) {
    String normalizedScope = (scope == null || scope.isBlank()) ? "spotify-auth" : scope.trim();
    String normalizedNonce = nonce == null ? "" : nonce.trim();
    if (normalizedNonce.isEmpty()) {
      return false;
    }
    String key = REPLAY_PREFIX + normalizedScope + "/" + normalizedNonce;
    try {
      String existing = storage.getSecret(key);
      if (existing != null && !existing.isBlank()) {
        long existingExpiresAt = Long.parseLong(existing.trim());
        if (existingExpiresAt >= nowMs) {
          return false;
        }
      }
      storage.putSecret(key, Long.toString(expiresAtMs));
      return true;
    } catch (Exception e) {
      throw new RuntimeException("Failed to persist replay nonce.", e);
    }
  }

  String getOrCreateEnvelopeSecret() {
    try {
      String secret = storage.getSecret(ENVELOPE_SECRET_KEY);
      if (secret != null && !secret.isBlank()) {
        return secret;
      }
      byte[] bytes = new byte[32];
      RNG.nextBytes(bytes);
      String generated = Base64.getEncoder().encodeToString(bytes);
      storage.putSecret(ENVELOPE_SECRET_KEY, generated);
      return generated;
    } catch (IOException e) {
      throw new RuntimeException("Failed to load/create envelope secret.", e);
    }
  }

  private static String sessionKey(String sessionId) {
    return SESSION_PREFIX + sessionId;
  }

  static final class SessionEnvelope {
    private AuthorizationSession session;
    private String redirectUri;
    private String codeVerifier;
    private String requiredScopes;
    private AuthorizationSecureEnvelope envelope;

    SessionEnvelope() {
    }

    SessionEnvelope(AuthorizationSession session,
                    String redirectUri,
                    String codeVerifier,
                    String requiredScopes,
                    AuthorizationSecureEnvelope envelope) {
      this.session = session;
      this.redirectUri = redirectUri;
      this.codeVerifier = codeVerifier;
      this.requiredScopes = requiredScopes;
      this.envelope = envelope;
    }

    AuthorizationSession session() {
      return session;
    }

    String redirectUri() {
      return redirectUri;
    }

    String codeVerifier() {
      return codeVerifier;
    }

    String requiredScopes() {
      return requiredScopes;
    }

    AuthorizationSecureEnvelope envelope() {
      return envelope;
    }
  }
}
