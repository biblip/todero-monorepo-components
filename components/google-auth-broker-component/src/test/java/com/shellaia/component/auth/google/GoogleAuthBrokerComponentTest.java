package com.shellaia.component.auth.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.social100.processor.AIAController;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpResponse;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.storage.Storage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoogleAuthBrokerComponentTest {
  private final ObjectMapper json = new ObjectMapper();
  private Path tempDir;
  private String previousRoot;

  @BeforeEach
  void setUp() throws IOException {
    tempDir = Files.createTempDirectory("google-auth-broker-test");
    previousRoot = System.getProperty("todero.auth.root");
    System.setProperty("todero.auth.root", tempDir.resolve("auth-root").toString());
  }

  @AfterEach
  void tearDown() {
    if (previousRoot == null) {
      System.clearProperty("todero.auth.root");
    } else {
      System.setProperty("todero.auth.root", previousRoot);
    }
  }

  @Test
  void htmlRendersBrokerSurface() {
    GoogleAuthBrokerComponent component = new GoogleAuthBrokerComponent(new EmptyStorage());
    AtomicReference<AiatpResponse> out = new AtomicReference<>();
    assertTrue(component.html(context(out, "")));
    String body = AiatpIO.bodyToString(out.get().getBody(), StandardCharsets.UTF_8);
    assertTrue(body.contains("Todero Auth Broker"));
    assertTrue(body.contains("Begin sign-in"));
    assertTrue(body.contains("Poll now"));
    assertTrue(body.contains("Google device-flow configuration"));
  }

  @Test
  void authBeginCreatesDeviceSessionAndStatusReflectsIt() throws Exception {
    try (MockGoogleAuthServer server = new MockGoogleAuthServer()) {
      GoogleAuthBrokerComponent component = new GoogleAuthBrokerComponent(new EmptyStorage());
      AtomicReference<AiatpResponse> out = new AtomicReference<>();
      assertTrue(component.settingsPut(context(out,
          "{\"clientId\":\"client-123\",\"clientSecret\":\"secret-abc\",\"projectId\":\"proj-123\",\"authProvider\":\"google\",\"deviceAuthorizationUri\":\"" + server.deviceCodeUri() + "\",\"tokenUri\":\"" + server.tokenUri() + "\",\"userInfoUri\":\"" + server.userInfoUri() + "\",\"scope\":\"openid email profile\",\"projectName\":\"Todero\"}")));
      assertTrue(component.authBegin(context(out, "")));
      String beginJson = AiatpIO.bodyToString(out.get().getBody(), StandardCharsets.UTF_8);
      JsonNode begin = read(beginJson);
      assertTrue(begin.path("ok").asBoolean(false));
      assertEquals("G-DEVICE-123", begin.path("session").path("deviceCode").asText(""));
      assertEquals("G-CODE-456", begin.path("session").path("userCode").asText(""));
      assertEquals("http://127.0.0.1:" + server.port() + "/verify", begin.path("verificationUrl").asText(""));
      assertEquals("http://127.0.0.1:" + server.port() + "/verify", begin.path("session").path("verificationUrl").asText(""));
      assertTrue(Files.exists(tempDir.resolve("auth-root/session.json")));
      assertTrue(component.authStatus(context(out, "")));
      String statusJson = AiatpIO.bodyToString(out.get().getBody(), StandardCharsets.UTF_8);
      JsonNode status = read(statusJson);
      assertTrue(status.path("authenticated").asBoolean(true) == false);
      assertEquals("client-123", status.path("settings").path("clientId").asText(""));
      assertEquals("proj-123", status.path("settings").path("projectId").asText(""));
    }
  }

  @Test
  void authPollCompletesAndStoresPrincipal() throws Exception {
    try (MockGoogleAuthServer server = new MockGoogleAuthServer()) {
      GoogleAuthBrokerComponent component = new GoogleAuthBrokerComponent(new EmptyStorage());
      AtomicReference<AiatpResponse> out = new AtomicReference<>();
      assertTrue(component.settingsPut(context(out,
          "{\"clientId\":\"client-123\",\"clientSecret\":\"secret-abc\",\"projectId\":\"proj-123\",\"authProvider\":\"google\",\"deviceAuthorizationUri\":\"" + server.deviceCodeUri() + "\",\"tokenUri\":\"" + server.tokenUri() + "\",\"userInfoUri\":\"" + server.userInfoUri() + "\",\"scope\":\"openid email profile\",\"projectName\":\"Todero\"}")));
      assertTrue(component.authBegin(context(out, "")));
      assertTrue(component.authPoll(context(out, "")));
      String pollJson = AiatpIO.bodyToString(out.get().getBody(), StandardCharsets.UTF_8);
      JsonNode poll = read(pollJson);
      assertTrue(poll.path("ok").asBoolean(false));
      assertTrue(poll.path("authenticated").asBoolean(false));
      assertEquals("arturo@example.com", poll.path("principal").path("email").asText(""));
      assertEquals("Arturo Portilla", poll.path("principal").path("displayName").asText(""));
      assertEquals("google-user-123", poll.path("principal").path("subjectId").asText(""));
      assertTrue(component.authStatus(context(out, "")));
      String statusJson = AiatpIO.bodyToString(out.get().getBody(), StandardCharsets.UTF_8);
      JsonNode status = read(statusJson);
      assertTrue(status.path("authenticated").asBoolean(false));
      assertEquals("arturo@example.com", status.path("principal").path("email").asText(""));
    }
  }

  @Test
  void authPollCanReportPendingBeforeCompletion() throws Exception {
    try (MockGoogleAuthServer server = new MockGoogleAuthServer(true)) {
      GoogleAuthBrokerComponent component = new GoogleAuthBrokerComponent(new EmptyStorage());
      AtomicReference<AiatpResponse> out = new AtomicReference<>();
      assertTrue(component.settingsPut(context(out,
          "{\"clientId\":\"client-123\",\"deviceAuthorizationUri\":\"" + server.deviceCodeUri() + "\",\"tokenUri\":\"" + server.tokenUri() + "\",\"userInfoUri\":\"" + server.userInfoUri() + "\",\"scope\":\"openid email profile\"}")));
      assertTrue(component.authBegin(context(out, "")));
      assertTrue(component.authPoll(context(out, "")));
      String pollJson = AiatpIO.bodyToString(out.get().getBody(), StandardCharsets.UTF_8);
      JsonNode poll = read(pollJson);
      assertTrue(poll.path("ok").asBoolean(false));
      assertTrue(poll.path("authenticated").asBoolean(true) == false);
      assertEquals("PENDING", poll.path("session").path("status").asText(""));
    }
  }

  @Test
  void settingsPutAcceptsGoogleInstalledJsonAndIgnoresBrowserAuthUriForDeviceFlow() throws Exception {
    GoogleAuthBrokerComponent component = new GoogleAuthBrokerComponent(new EmptyStorage());
    AtomicReference<AiatpResponse> out = new AtomicReference<>();
    assertTrue(component.settingsPut(context(out,
        "{\"installed\":{\"client_id\":\"771944319724-d1ccg4mppt1rrefeqtt1gs9n2uec9874.apps.googleusercontent.com\",\"project_id\":\"numeric-oarlock-485317-q0\",\"auth_uri\":\"https://accounts.google.com/o/oauth2/auth\",\"token_uri\":\"https://oauth2.googleapis.com/token\",\"auth_provider_x509_cert_url\":\"https://www.googleapis.com/oauth2/v1/certs\"}}")));
    assertTrue(component.authStatus(context(out, "")));
    String statusJson = AiatpIO.bodyToString(out.get().getBody(), StandardCharsets.UTF_8);
    JsonNode status = read(statusJson);
    assertEquals("771944319724-d1ccg4mppt1rrefeqtt1gs9n2uec9874.apps.googleusercontent.com", status.path("settings").path("clientId").asText(""));
    assertEquals("numeric-oarlock-485317-q0", status.path("settings").path("projectId").asText(""));
    assertEquals("https://oauth2.googleapis.com/device/code", status.path("settings").path("deviceAuthorizationUri").asText(""));
    assertEquals("https://oauth2.googleapis.com/token", status.path("settings").path("tokenUri").asText(""));
  }

  private static CommandContext context(AtomicReference<AiatpResponse> out, String body) {
    return CommandContext.builder()
        .sourceId("google-auth-test")
        .responseConsumer(out::set)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.auth.google/html",
            AiatpIO.Body.ofString(body == null ? "" : body, StandardCharsets.UTF_8)))
        .build();
  }

  private JsonNode read(String jsonText) {
    try {
      return json.readTree(jsonText);
    } catch (Exception e) {
      throw new AssertionError("Unable to parse JSON: " + jsonText, e);
    }
  }

  private static final class MockGoogleAuthServer implements AutoCloseable {
    private final HttpServer server;
    private final boolean pendingOnly;
    private final AtomicInteger tokenCalls = new AtomicInteger();

    MockGoogleAuthServer() throws IOException {
      this(false);
    }

    MockGoogleAuthServer(boolean pendingOnly) throws IOException {
      this.pendingOnly = pendingOnly;
      this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/device/code", this::handleDeviceCode);
      server.createContext("/token", this::handleToken);
      server.createContext("/userinfo", this::handleUserInfo);
      server.start();
    }

    int port() {
      return server.getAddress().getPort();
    }

    String deviceCodeUri() {
      return "http://127.0.0.1:" + port() + "/device/code";
    }

    String tokenUri() {
      return "http://127.0.0.1:" + port() + "/token";
    }

    String userInfoUri() {
      return "http://127.0.0.1:" + port() + "/userinfo";
    }

    private void handleDeviceCode(HttpExchange exchange) throws IOException {
      writeJson(exchange, 200, Map.of(
          "device_code", "G-DEVICE-123",
          "user_code", "G-CODE-456",
          "verification_url", "http://127.0.0.1:" + port() + "/verify",
          "interval", 1,
          "expires_in", 600
      ));
    }

    private void handleToken(HttpExchange exchange) throws IOException {
      int call = tokenCalls.incrementAndGet();
      if (pendingOnly) {
        writeJson(exchange, 400, Map.of(
            "error", "authorization_pending",
            "error_description", "Waiting for user approval."
        ));
        return;
      }
      if (call == 1) {
        writeJson(exchange, 200, Map.of(
            "access_token", "access-token-123",
            "refresh_token", "refresh-token-456",
            "id_token", "eyJhbGciOiJub25lIn0.eyJzdWIiOiJnb29nbGUtdXNlci0xMjMifQ.",
            "token_type", "Bearer",
            "scope", "openid email profile",
            "expires_in", 3600
        ));
        return;
      }
      writeJson(exchange, 400, Map.of(
          "error", "authorization_pending",
          "error_description", "Waiting for user approval."
      ));
    }

    private void handleUserInfo(HttpExchange exchange) throws IOException {
      writeJson(exchange, 200, Map.of(
          "sub", "google-user-123",
          "email", "arturo@example.com",
          "name", "Arturo Portilla"
      ));
    }

    private void writeJson(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
      byte[] bytes = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(body);
      exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
      exchange.sendResponseHeaders(status, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  private static final class EmptyStorage implements Storage {
    @Override
    public void writeFile(String relativePath, byte[] bytes) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public byte[] readFile(String relativePath) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteFile(String relativePath) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<String> listFiles(String relativeDir) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void putSecret(String key, String value) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getSecret(String key) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteSecret(String key) throws IOException {
      throw new UnsupportedOperationException();
    }
  }
}
