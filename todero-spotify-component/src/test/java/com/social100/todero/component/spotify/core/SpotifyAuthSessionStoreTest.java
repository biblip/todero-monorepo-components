package com.social100.todero.component.spotify.core;

import com.social100.todero.common.runtime.auth.AuthorizationSession;
import com.social100.todero.common.runtime.auth.AuthorizationSessionStatus;
import com.social100.todero.common.runtime.auth.AuthorizationSecureEnvelope;
import com.social100.todero.component.testkit.TestStorage;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SpotifyAuthSessionStoreTest {

  @Test
  void saveAndReadActiveSessionLifecycle() {
    TestStorage storage = new TestStorage();
    SpotifyAuthSessionStore store = new SpotifyAuthSessionStore(storage);

    SpotifyAuthSessionStore.SessionEnvelope initial = buildEnvelope("sess-1", AuthorizationSessionStatus.PENDING);
    store.save(initial);

    Optional<SpotifyAuthSessionStore.SessionEnvelope> loaded = store.get("sess-1");
    assertTrue(loaded.isPresent());
    assertEquals("sess-1", loaded.get().session().sessionId());
    assertEquals(AuthorizationSessionStatus.PENDING, loaded.get().session().status());

    Optional<SpotifyAuthSessionStore.SessionEnvelope> active = store.getActive();
    assertTrue(active.isPresent());
    assertEquals("sess-1", active.get().session().sessionId());

    SpotifyAuthSessionStore.SessionEnvelope completed =
        store.updateSessionStatus(loaded.get(), AuthorizationSessionStatus.COMPLETED, 10_000L, null);
    assertEquals(AuthorizationSessionStatus.COMPLETED, completed.session().status());
    assertEquals(10_000L, completed.session().completedAtMs());

    SpotifyAuthSessionStore.SessionEnvelope reloaded = store.get("sess-1").orElseThrow();
    assertEquals(AuthorizationSessionStatus.COMPLETED, reloaded.session().status());
  }

  @Test
  void replayNonceRejectsWithinTtlAndAllowsAfterExpiry() {
    TestStorage storage = new TestStorage();
    SpotifyAuthSessionStore store = new SpotifyAuthSessionStore(storage);

    long now = 1_000L;
    assertTrue(store.registerReplayNonce("spotify-auth-complete", "n-1", 2_000L, now));
    assertFalse(store.registerReplayNonce("spotify-auth-complete", "n-1", 2_000L, now + 100));
    assertTrue(store.registerReplayNonce("spotify-auth-complete", "n-1", 4_000L, now + 2_500L));
  }

  @Test
  void envelopeSecretIsStableAfterFirstCreation() {
    TestStorage storage = new TestStorage();
    SpotifyAuthSessionStore store = new SpotifyAuthSessionStore(storage);

    String first = store.getOrCreateEnvelopeSecret();
    String second = store.getOrCreateEnvelopeSecret();

    assertNotNull(first);
    assertFalse(first.isBlank());
    assertEquals(first, second);
  }

  private static SpotifyAuthSessionStore.SessionEnvelope buildEnvelope(String sessionId, AuthorizationSessionStatus status) {
    AuthorizationSession session = new AuthorizationSession(
        sessionId,
        "spotify",
        "owner-test",
        "state-1",
        status,
        1_000L,
        5_000L,
        null,
        null
    );
    AuthorizationSecureEnvelope secureEnvelope = new AuthorizationSecureEnvelope(
        "env-1",
        sessionId,
        "nonce-1",
        300,
        "opaque-1",
        "integrity-1",
        "HMAC-SHA256",
        1_000L
    );
    return new SpotifyAuthSessionStore.SessionEnvelope(
        session,
        "http://127.0.0.1:34895/spotify/callback",
        "verifier-1",
        "scope-a scope-b",
        secureEnvelope
    );
  }
}
