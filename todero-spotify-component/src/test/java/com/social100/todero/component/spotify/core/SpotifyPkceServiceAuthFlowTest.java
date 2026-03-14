package com.social100.todero.component.spotify.core;

import com.social100.todero.common.runtime.auth.AuthorizationErrorCode;
import com.social100.todero.common.runtime.auth.AuthorizationSessionStatus;
import com.social100.todero.common.runtime.auth.AuthorizationSecureEnvelope;
import com.social100.todero.common.runtime.auth.AuthorizationValidationException;
import com.social100.todero.component.testkit.TestStorage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class SpotifyPkceServiceAuthFlowTest {

  private HttpServer tokenServer;

  @AfterEach
  void cleanup() {
    if (tokenServer != null) {
      tokenServer.stop(0);
      tokenServer = null;
    }
  }

  @Test
  void fullBeginCompleteFlowStoresTokenAndCompletesSession() throws Exception {
    startTokenServer(200, tokenResponseWithScopes(), 0);

    TestStorage storage = new TestStorage();
    SpotifyPkceService service = newService(storage, tokenServerUri());

    SpotifyPkceService.AuthBeginResult begin = service.authBegin("console", null, "owner-a");
    assertTrue(begin.ok());
    assertNotNull(begin.session());
    assertEquals(AuthorizationSessionStatus.PENDING, begin.session().status());

    SpotifyPkceService.AuthCompleteResult complete = service.authComplete(
        new SpotifyPkceService.AuthCompleteRequest(
            begin.session().sessionId(),
            begin.session().state(),
            "oauth-code-123",
            null,
            begin.secureEnvelope()
        )
    );

    assertTrue(complete.ok());
    assertNotNull(complete.session());
    assertEquals(AuthorizationSessionStatus.COMPLETED, complete.session().status());
    assertNotNull(storage.getSecret("auth/token"));

    SpotifyPkceService.AuthSessionResult session = service.authSession(begin.session().sessionId());
    assertTrue(session.ok());
    assertEquals(AuthorizationSessionStatus.COMPLETED, session.session().status());
  }

  @Test
  void authCompleteRejectsTamperedEnvelope() throws Exception {
    startTokenServer(200, tokenResponseWithScopes(), 0);

    TestStorage storage = new TestStorage();
    SpotifyPkceService service = newService(storage, tokenServerUri());
    SpotifyPkceService.AuthBeginResult begin = service.authBegin("console", null, "owner-a");

    AuthorizationSecureEnvelope tampered = new AuthorizationSecureEnvelope(
        begin.secureEnvelope().envelopeId(),
        begin.secureEnvelope().sessionId(),
        begin.secureEnvelope().nonce(),
        begin.secureEnvelope().ttlSec(),
        begin.secureEnvelope().opaquePayload(),
        begin.secureEnvelope().integrity() + "tampered",
        begin.secureEnvelope().algorithm(),
        begin.secureEnvelope().issuedAtMs()
    );

    SpotifyPkceService.AuthCompleteResult complete = service.authComplete(
        new SpotifyPkceService.AuthCompleteRequest(
            begin.session().sessionId(),
            begin.session().state(),
            "oauth-code-123",
            null,
            tampered
        )
    );

    assertFalse(complete.ok());
    assertEquals(AuthorizationErrorCode.AUTH_ENVELOPE_INVALID, complete.errorCode());
  }

  @Test
  void authCompleteRejectsExpiredEnvelope() throws Exception {
    startTokenServer(200, tokenResponseWithScopes(), 0);

    TestStorage storage = new TestStorage();
    SpotifyPkceService service = newService(storage, tokenServerUri());
    SpotifyPkceService.AuthBeginResult begin = service.authBegin("console", null, "owner-a");

    AuthorizationSecureEnvelope expired = new AuthorizationSecureEnvelope(
        begin.secureEnvelope().envelopeId(),
        begin.secureEnvelope().sessionId(),
        begin.secureEnvelope().nonce(),
        1,
        begin.secureEnvelope().opaquePayload(),
        begin.secureEnvelope().integrity(),
        begin.secureEnvelope().algorithm(),
        begin.secureEnvelope().issuedAtMs() - 10_000
    );

    SpotifyPkceService.AuthCompleteResult complete = service.authComplete(
        new SpotifyPkceService.AuthCompleteRequest(
            begin.session().sessionId(),
            begin.session().state(),
            "oauth-code-123",
            null,
            expired
        )
    );

    assertFalse(complete.ok());
    assertEquals(AuthorizationErrorCode.AUTH_ENVELOPE_EXPIRED, complete.errorCode());
  }

  @Test
  void authCompleteRejectsReplayOnSecondAttempt() throws Exception {
    startTokenServer(200, tokenResponseWithScopes(), 0);

    TestStorage storage = new TestStorage();
    SpotifyPkceService service = newService(storage, tokenServerUri());
    SpotifyPkceService.AuthBeginResult begin = service.authBegin("console", null, "owner-a");

    SpotifyPkceService.AuthCompleteRequest req = new SpotifyPkceService.AuthCompleteRequest(
        begin.session().sessionId(),
        begin.session().state(),
        "oauth-code-123",
        null,
        begin.secureEnvelope()
    );

    SpotifyPkceService.AuthCompleteResult first = service.authComplete(req);
    assertTrue(first.ok());

    SpotifyPkceService.AuthCompleteResult second = service.authComplete(req);
    assertFalse(second.ok());
    assertEquals(AuthorizationErrorCode.AUTH_SESSION_ALREADY_COMPLETED, second.errorCode());
  }

  @Test
  void authCompleteRejectsStateMismatch() throws Exception {
    startTokenServer(200, tokenResponseWithScopes(), 0);

    TestStorage storage = new TestStorage();
    SpotifyPkceService service = newService(storage, tokenServerUri());
    SpotifyPkceService.AuthBeginResult begin = service.authBegin("console", null, "owner-a");

    SpotifyPkceService.AuthCompleteResult complete = service.authComplete(
        new SpotifyPkceService.AuthCompleteRequest(
            begin.session().sessionId(),
            "wrong-state",
            "oauth-code-123",
            null,
            begin.secureEnvelope()
        )
    );

    assertFalse(complete.ok());
    assertEquals(AuthorizationErrorCode.AUTH_STATE_MISMATCH, complete.errorCode());
  }

  @Test
  void authCompleteRejectsMismatchedSessionBinding() throws Exception {
    startTokenServer(200, tokenResponseWithScopes(), 0);

    TestStorage storage = new TestStorage();
    SpotifyPkceService service = newService(storage, tokenServerUri());
    SpotifyPkceService.AuthBeginResult begin = service.authBegin("console", null, "owner-a");

    AuthorizationSecureEnvelope mismatched = new AuthorizationSecureEnvelope(
        begin.secureEnvelope().envelopeId(),
        "another-session",
        begin.secureEnvelope().nonce(),
        begin.secureEnvelope().ttlSec(),
        begin.secureEnvelope().opaquePayload(),
        begin.secureEnvelope().integrity(),
        begin.secureEnvelope().algorithm(),
        begin.secureEnvelope().issuedAtMs()
    );

    SpotifyPkceService.AuthCompleteResult complete = service.authComplete(
        new SpotifyPkceService.AuthCompleteRequest(
            begin.session().sessionId(),
            begin.session().state(),
            "oauth-code-123",
            null,
            mismatched
        )
    );

    assertFalse(complete.ok());
    assertEquals(AuthorizationErrorCode.AUTH_ENVELOPE_INVALID, complete.errorCode());
  }

  @Test
  void authBeginUsesProfileSpecificRedirectUrisAndBuildsAuthCtaWebview() {
    TestStorage storage = new TestStorage();
    SpotifyConfig config = SpotifyConfig.builder()
        .clientId("client-test")
        .redirectUrlApp("https://auth.shellaia.com/oauth2/component/callback?provider=spotify")
        .redirectUrlConsole("http://127.0.0.1:34895/spotify/callback")
        .redirectAllowlist("https://auth.shellaia.com/oauth2/component/callback?provider=spotify,http://127.0.0.1:34895/spotify/callback")
        .build();
    SpotifyPkceService service = new SpotifyPkceService(config, storage, URI.create("http://127.0.0.1/token"));

    SpotifyPkceService.AuthBeginResult appBegin = service.authBegin("app", null, "owner-a");
    assertTrue(appBegin.authorizeUrl().contains("redirect_uri=https%3A%2F%2Fauth.shellaia.com%2Foauth2%2Fcomponent%2Fcallback%3Fprovider%3Dspotify"));

    SpotifyPkceService.AuthBeginResult consoleBegin = service.authBegin("console", null, "owner-a");
    assertTrue(consoleBegin.authorizeUrl().contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A34895%2Fspotify%2Fcallback"));

    String html = appBegin.ctaHtml();
    assertTrue(html.contains("Authorize Spotify"));
    assertTrue(html.contains("href="));
  }

  @Test
  void authBeginExplicitRejectsRedirectOutsideAllowlist() {
    TestStorage storage = new TestStorage();
    SpotifyConfig config = SpotifyConfig.builder()
        .clientId("client-test")
        .redirectUrlApp("https://auth.shellaia.com/oauth2/component/callback?provider=spotify")
        .redirectUrlConsole("http://127.0.0.1:34895/spotify/callback")
        .redirectAllowlist("https://auth.shellaia.com/oauth2/component/callback?provider=spotify")
        .build();
    SpotifyPkceService service = new SpotifyPkceService(config, storage, URI.create("http://127.0.0.1/token"));

    AuthorizationValidationException ex = assertThrows(
        AuthorizationValidationException.class,
        () -> service.authBegin("explicit", "http://127.0.0.1:34895/spotify/callback", "owner-a")
    );
    assertEquals(AuthorizationErrorCode.AUTH_REDIRECT_URI_DISALLOWED, ex.code());
  }

  private static SpotifyPkceService newService(TestStorage storage, URI tokenUri) {
    SpotifyConfig config = SpotifyConfig.builder()
        .clientId("client-test")
        .redirectUrlApp("https://auth.shellaia.com/oauth2/component/callback?provider=spotify")
        .redirectUrlConsole("http://127.0.0.1:34895/spotify/callback")
        .redirectAllowlist("https://auth.shellaia.com/oauth2/component/callback?provider=spotify,http://127.0.0.1:34895/spotify/callback")
        .build();
    return new SpotifyPkceService(config, storage, tokenUri);
  }

  @Test
  void authBeginAppRejectsMissingProviderMarker() {
    TestStorage storage = new TestStorage();
    SpotifyConfig config = SpotifyConfig.builder()
        .clientId("client-test")
        .redirectUrlApp("https://auth.shellaia.com/oauth2/component/callback")
        .redirectUrlConsole("http://127.0.0.1:34895/spotify/callback")
        .redirectAllowlist("https://auth.shellaia.com/oauth2/component/callback,http://127.0.0.1:34895/spotify/callback")
        .build();
    SpotifyPkceService service = new SpotifyPkceService(config, storage, URI.create("http://127.0.0.1/token"));

    AuthorizationValidationException ex = assertThrows(
        AuthorizationValidationException.class,
        () -> service.authBegin("app", null, "owner-a")
    );
    assertEquals(AuthorizationErrorCode.AUTH_REDIRECT_URI_DISALLOWED, ex.code());
  }

  private void startTokenServer(int statusCode, String responseBody, long responseDelayMs) throws IOException {
    tokenServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    tokenServer.createContext("/token", exchange -> handle(exchange, statusCode, responseBody, responseDelayMs));
    tokenServer.setExecutor(Executors.newCachedThreadPool());
    tokenServer.start();
  }

  private URI tokenServerUri() {
    return URI.create("http://127.0.0.1:" + tokenServer.getAddress().getPort() + "/token");
  }

  private static void handle(HttpExchange exchange, int statusCode, String responseBody, long responseDelayMs) throws IOException {
    try {
      if (responseDelayMs > 0) {
        try {
          Thread.sleep(responseDelayMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(statusCode, body.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(body);
      }
    } finally {
      exchange.close();
    }
  }

  private static String tokenResponseWithScopes() {
    return "{"
        + "\"access_token\":\"access-1\"," 
        + "\"refresh_token\":\"refresh-1\"," 
        + "\"expires_in\":3600,"
        + "\"scope\":\"user-read-playback-state user-modify-playback-state user-read-recently-played user-top-read user-library-modify playlist-read-private playlist-read-collaborative playlist-modify-public playlist-modify-private\""
        + "}";
  }
}
