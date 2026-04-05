package com.shellaia.component.spotify.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
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
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class SpotifyPkceServiceAuthFlowTest {
  private static final Gson GSON = new Gson();

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

    SpotifyPkceService.AuthBeginResult begin = service.authBegin("console", null, "owner-a", "aia://test-host");
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
  void authBeginEncodesStateWithCanonicalAuthCompleteTarget() throws Exception {
    startTokenServer(200, tokenResponseWithScopes(), 0);

    TestStorage storage = new TestStorage();
    SpotifyPkceService service = newService(storage, tokenServerUri());

    String authCompleteTarget =
        "aia://test-host/com.shellaia.spotify/auth-complete";
    SpotifyPkceService.AuthBeginResult begin = service.authBegin("console", null, "owner-a", authCompleteTarget);
    assertTrue(begin.ok());
    assertNotNull(begin.session());
    assertEquals(authCompleteTarget, begin.authCompleteTarget());

    String decoded = new String(Base64.getUrlDecoder().decode(begin.session().state()), StandardCharsets.UTF_8);
    JsonObject state = GSON.fromJson(decoded, JsonObject.class);
    assertEquals(authCompleteTarget, state.get("auth-complete").getAsString());
    assertFalse(state.has("aia_uri"));
    assertEquals(begin.session().sessionId(), state.get("session-id").getAsString());
    assertFalse(state.has("internal_state"));
  }

  @Test
  void authBeginRejectsMissingAuthCompleteTarget() throws Exception {
    startTokenServer(200, tokenResponseWithScopes(), 0);

    TestStorage storage = new TestStorage();
    SpotifyPkceService service = newService(storage, tokenServerUri());

    AuthorizationValidationException ex = assertThrows(
        AuthorizationValidationException.class,
        () -> service.authBegin("console", null, "owner-a", " ")
    );
    assertEquals(AuthorizationErrorCode.AUTH_AIA_URI_MISSING, ex.code());
  }

  @Test
  void authCompleteSucceedsWithoutEnvelope() throws Exception {
    startTokenServer(200, tokenResponseWithScopes(), 0);

    TestStorage storage = new TestStorage();
    SpotifyPkceService service = newService(storage, tokenServerUri());
    SpotifyPkceService.AuthBeginResult begin = service.authBegin("console", null, "owner-a", "aia://test-host");

    SpotifyPkceService.AuthCompleteResult complete = service.authComplete(
        new SpotifyPkceService.AuthCompleteRequest(
            begin.session().sessionId(),
            begin.session().state(),
            "oauth-code-123",
            null,
            null
        )
    );

    assertTrue(complete.ok());
    assertNotNull(complete.session());
    assertEquals(AuthorizationSessionStatus.COMPLETED, complete.session().status());
  }

  @Test
  void authCompleteIgnoresTamperedEnvelope() throws Exception {
    startTokenServer(200, tokenResponseWithScopes(), 0);

    TestStorage storage = new TestStorage();
    SpotifyPkceService service = newService(storage, tokenServerUri());
    SpotifyPkceService.AuthBeginResult begin = service.authBegin("console", null, "owner-a", "aia://test-host");

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

    assertTrue(complete.ok());
    assertEquals(AuthorizationSessionStatus.COMPLETED, complete.session().status());
  }

  @Test
  void authCompleteRejectsReplayOnSecondAttempt() throws Exception {
    startTokenServer(200, tokenResponseWithScopes(), 0);

    TestStorage storage = new TestStorage();
    SpotifyPkceService service = newService(storage, tokenServerUri());
    SpotifyPkceService.AuthBeginResult begin = service.authBegin("console", null, "owner-a", "aia://test-host");

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
    SpotifyPkceService.AuthBeginResult begin = service.authBegin("console", null, "owner-a", "aia://test-host");

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
  void authCompleteIgnoresMismatchedEnvelopeSessionBinding() throws Exception {
    startTokenServer(200, tokenResponseWithScopes(), 0);

    TestStorage storage = new TestStorage();
    SpotifyPkceService service = newService(storage, tokenServerUri());
    SpotifyPkceService.AuthBeginResult begin = service.authBegin("console", null, "owner-a", "aia://test-host");

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

    assertTrue(complete.ok());
    assertEquals(AuthorizationSessionStatus.COMPLETED, complete.session().status());
  }

  @Test
  void authBeginUsesProfileSpecificRedirectUrisAndBuildsAuthCtaWebview() {
    TestStorage storage = new TestStorage();
    SpotifyConfig config = SpotifyConfig.builder()
        .clientId("client-test")
        .redirectUrlApp("https://auth.shellaia.com/component/callback")
        .redirectUrlConsole("http://127.0.0.1:34895/spotify/callback")
        .redirectAllowlist("https://auth.shellaia.com/component/callback,http://127.0.0.1:34895/spotify/callback")
        .build();
    SpotifyPkceService service = new SpotifyPkceService(config, storage, URI.create("http://127.0.0.1/token"));

    SpotifyPkceService.AuthBeginResult appBegin = service.authBegin("app", null, "owner-a", "aia://test-host");
    assertTrue(appBegin.authorizeUrl().contains("redirect_uri=https%3A%2F%2Fauth.shellaia.com%2Fcomponent%2Fcallback"));

    SpotifyPkceService.AuthBeginResult consoleBegin = service.authBegin("console", null, "owner-a", "aia://test-host");
    assertTrue(consoleBegin.authorizeUrl().contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A34895%2Fspotify%2Fcallback"));

    String html = appBegin.ctaHtml();
    assertTrue(html.contains("Authorize Spotify"));
    assertTrue(html.contains("href="));
  }

  @Test
  void authBeginAppAcceptsConfiguredAbsoluteRedirectOutsideHardcodedCallback() {
    TestStorage storage = new TestStorage();
    SpotifyConfig config = SpotifyConfig.builder()
        .clientId("client-test")
        .redirectUrlApp("https://login.example.com/custom/callback")
        .redirectUrlConsole("http://127.0.0.1:34895/spotify/callback")
        .redirectAllowlist("https://auth.shellaia.com/component/callback")
        .build();
    SpotifyPkceService service = new SpotifyPkceService(config, storage, URI.create("http://127.0.0.1/token"));

    SpotifyPkceService.AuthBeginResult begin = service.authBegin("app", null, "owner-a", "aia://test-host");

    assertTrue(begin.ok());
    assertTrue(begin.authorizeUrl().contains("redirect_uri=https%3A%2F%2Flogin.example.com%2Fcustom%2Fcallback"));
  }

  @Test
  void authBeginAppRejectsRelativeConfiguredRedirect() {
    TestStorage storage = new TestStorage();
    SpotifyConfig config = SpotifyConfig.builder()
        .clientId("client-test")
        .redirectUrlApp("/component/callback")
        .redirectUrlConsole("http://127.0.0.1:34895/spotify/callback")
        .build();
    SpotifyPkceService service = new SpotifyPkceService(config, storage, URI.create("http://127.0.0.1/token"));

    AuthorizationValidationException ex = assertThrows(
        AuthorizationValidationException.class,
        () -> service.authBegin("app", null, "owner-a", "aia://test-host")
    );

    assertEquals(AuthorizationErrorCode.AUTH_REDIRECT_URI_DISALLOWED, ex.code());
    assertEquals("App redirect-uri must be an absolute URI.", ex.getMessage());
  }

  @Test
  void authBeginConsoleRejectsRelativeConfiguredRedirect() {
    TestStorage storage = new TestStorage();
    SpotifyConfig config = SpotifyConfig.builder()
        .clientId("client-test")
        .redirectUrlApp("https://auth.shellaia.com/component/callback")
        .redirectUrlConsole("/spotify/callback")
        .build();
    SpotifyPkceService service = new SpotifyPkceService(config, storage, URI.create("http://127.0.0.1/token"));

    AuthorizationValidationException ex = assertThrows(
        AuthorizationValidationException.class,
        () -> service.authBegin("console", null, "owner-a", "aia://test-host")
    );

    assertEquals(AuthorizationErrorCode.AUTH_REDIRECT_URI_DISALLOWED, ex.code());
    assertEquals("Console redirect-uri must be an absolute URI.", ex.getMessage());
  }

  @Test
  void authBeginRejectsDeprecatedExplicitProfile() {
    TestStorage storage = new TestStorage();
    SpotifyConfig config = SpotifyConfig.builder()
        .clientId("client-test")
        .redirectUrlApp("https://auth.shellaia.com/component/callback")
        .redirectUrlConsole("http://127.0.0.1:34895/spotify/callback")
        .build();
    SpotifyPkceService service = new SpotifyPkceService(config, storage, URI.create("http://127.0.0.1/token"));

    AuthorizationValidationException ex = assertThrows(
        AuthorizationValidationException.class,
        () -> service.authBegin("explicit", "https://login.example.com/custom/callback", "owner-a", "aia://test-host")
    );

    assertEquals(AuthorizationErrorCode.AUTH_REDIRECT_PROFILE_INVALID, ex.code());
    assertEquals("Invalid redirect profile.", ex.getMessage());
  }

  private static SpotifyPkceService newService(TestStorage storage, URI tokenUri) {
    SpotifyConfig config = SpotifyConfig.builder()
        .clientId("client-test")
        .redirectUrlApp("https://auth.shellaia.com/component/callback")
        .redirectUrlConsole("http://127.0.0.1:34895/spotify/callback")
        .redirectAllowlist("https://auth.shellaia.com/component/callback,http://127.0.0.1:34895/spotify/callback")
        .build();
    return new SpotifyPkceService(config, storage, tokenUri);
  }

  @Test
  void authBeginAppAcceptsComponentCallbackWithoutProvider() {
    TestStorage storage = new TestStorage();
    SpotifyConfig config = SpotifyConfig.builder()
        .clientId("client-test")
        .redirectUrlApp("https://auth.shellaia.com/component/callback")
        .redirectUrlConsole("http://127.0.0.1:34895/spotify/callback")
        .redirectAllowlist("https://auth.shellaia.com/component/callback,http://127.0.0.1:34895/spotify/callback")
        .build();
    SpotifyPkceService service = new SpotifyPkceService(config, storage, URI.create("http://127.0.0.1/token"));

    SpotifyPkceService.AuthBeginResult begin = service.authBegin("app", null, "owner-a", "aia://test-host");
    assertTrue(begin.ok());
    assertTrue(begin.authorizeUrl().contains("redirect_uri=https%3A%2F%2Fauth.shellaia.com%2Fcomponent%2Fcallback"));
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
