package com.shellaia.component.auth.google;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shellaia.tutil.auth.AuthStatePaths;
import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpRequest;
import com.social100.todero.common.aiatpio.AiatpResponse;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.routing.ToolCapabilityManifest;
import com.social100.todero.common.storage.Storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@AIAController(
    name = "com.shellaia.auth.google",
    type = ServerType.AIA,
    visible = true,
    description = "Google Account device-flow auth broker component for Todero",
    toolCapabilityProvider = GoogleAuthBrokerToolCapabilities.class
)
public class GoogleAuthBrokerComponent {
  static final String MAIN_GROUP = "Main";
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();
  private static final String DEFAULT_DEVICE_AUTHORIZATION_URI = "https://oauth2.googleapis.com/device/code";
  private static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";
  private static final String DEFAULT_USERINFO_URI = "https://openidconnect.googleapis.com/v1/userinfo";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String HTML_TEMPLATE_PATH = "com/shellaia/component/auth/google/component.html";
  private static final String HTML_TEMPLATE = loadResourceText(HTML_TEMPLATE_PATH);
  private static final String SETTINGS_FILE = AuthStatePaths.settingsFile().toString();
  private static final String SESSION_FILE = AuthStatePaths.sessionFile().toString();
  private static final String PRINCIPAL_FILE = AuthStatePaths.principalFile().toString();

  private final Storage storage;
  private final GoogleAuthBrokerStore store;

  public GoogleAuthBrokerComponent(Storage storage) {
    this.storage = storage;
    this.store = new GoogleAuthBrokerStore();
  }

  @Action(group = MAIN_GROUP, command = "html", description = "Render the Google auth broker surface.")
  public Boolean html(CommandContext context) {
    GoogleAuthSettings settings = store.readSettings();
    GoogleAuthSession session = store.readSession();
    GooglePrincipal principal = store.readPrincipal();
    String host = resolveRequestHost(context == null ? null : context.getAiatpRequest());
    String title = host == null || host.isBlank() ? "Todero Auth Broker" : "Todero Auth Broker (" + host + ")";
    String html = HTML_TEMPLATE
        .replace("${TITLE}", escapeHtml(title))
        .replace("${HEADING}", escapeHtml("Todero Auth Broker"))
        .replace("${SUBHEADING}", escapeHtml("Google TV / limited-input device flow with session tracking and principal storage."))
        .replace("${GENERATED_AT}", escapeHtml(Instant.now().toString()))
        .replace("${SETTINGS}", escapeHtml(buildJson(settings == null ? Map.of() : settings.toMap())))
        .replace("${SESSION}", escapeHtml(buildJson(session == null ? Map.of() : session.toMap())))
        .replace("${PRINCIPAL}", escapeHtml(buildJson(principal == null ? Map.of() : principal.toMap())))
        .replace("${AUTH_URL}", escapeHtml(authUrlFor(settings, session)))
        .replace("${STATE_FILE}", escapeHtml(AuthStatePaths.root().toString()));
    context.complete(buildTextResponse(200, html, "text/html; charset=utf-8"));
    return true;
  }

  @Action(group = MAIN_GROUP, command = "settings_get", description = "Get the Google auth broker settings.")
  public Boolean settingsGet(CommandContext context) {
    context.complete(buildTextResponse(200, store.settingsJson(), "application/json; charset=utf-8"));
    return true;
  }

  @Action(group = MAIN_GROUP, command = "settings_put", description = "Update the Google auth broker settings. Body must be JSON.")
  public Boolean settingsPut(CommandContext context) {
    String body = requestBody(context);
    if (body.isBlank()) {
      context.complete(buildTextResponse(400, "{\"ok\":false,\"error\":\"settings_json_required\"}", "application/json; charset=utf-8"));
      return true;
    }
    try {
      store.writeSettings(parseSettings(body));
      context.complete(buildTextResponse(200, "{\"ok\":true}", "application/json; charset=utf-8"));
      return true;
    } catch (Exception e) {
      context.complete(buildTextResponse(400, "{\"ok\":false,\"error\":\"settings_write_failed\",\"message\":" + quote(e.getMessage()) + "}", "application/json; charset=utf-8"));
      return true;
    }
  }

  @Action(group = MAIN_GROUP, command = "auth_begin", description = "Start a Google device-code auth session.")
  public Boolean authBegin(CommandContext context) {
    try {
      GoogleAuthSettings settings = store.readSettings();
      GoogleAuthSession session = GoogleAuthSession.begin(settings, parseBodyJson(requestBody(context)));
      store.writeSession(session);
      store.writeChallenge(renderChallengeHtml(session));
      context.complete(buildTextResponse(200, buildJson(Map.of(
          "ok", true,
          "message", "Google device authorization session created.",
          "session", session.toMap(),
          "verificationUrl", session.authorizeUrl(),
          "userCode", session.userCode(),
          "deviceCode", session.deviceCode(),
          "pollIntervalSeconds", session.pollIntervalSeconds(),
          "challengeHtml", renderChallengeHtml(session)
      )), "application/json; charset=utf-8"));
      return true;
    } catch (Exception e) {
      context.complete(buildTextResponse(400, buildJson(Map.of(
          "ok", false,
          "error", "auth_begin_failed",
          "message", safeMessage(e)
      )), "application/json; charset=utf-8"));
      return true;
    }
  }

  @Action(group = MAIN_GROUP, command = "auth_poll", description = "Poll the Google token endpoint for the current device-code session.")
  public Boolean authPoll(CommandContext context) {
    try {
      GoogleAuthSettings settings = store.readSettings();
      GoogleAuthSession session = store.readSession();
      if (session == null) {
        throw new IllegalStateException("No active auth session.");
      }
      GoogleAuthSession updated = session.poll(settings);
      store.writeSession(updated);
      if (updated.authenticated()) {
        store.writePrincipal(updated.principal());
        store.writeChallenge(store.challengeFor(updated.principal(), updated));
      } else {
        store.writeChallenge(renderChallengeHtml(updated));
      }
      context.complete(buildTextResponse(200, buildJson(Map.of(
          "ok", true,
          "message", updated.lastMessage().isBlank()
              ? (updated.authenticated() ? "Google device authorization completed." : "Google device authorization pending.")
              : updated.lastMessage(),
          "session", updated.toMap(),
          "principal", updated.principal() == null ? Map.of() : updated.principal().toMap(),
          "verificationUrl", updated.authorizeUrl(),
          "userCode", updated.userCode(),
          "authenticated", updated.authenticated()
      )), "application/json; charset=utf-8"));
      return true;
    } catch (Exception e) {
      context.complete(buildTextResponse(400, buildJson(Map.of(
          "ok", false,
          "error", "auth_poll_failed",
          "message", safeMessage(e)
      )), "application/json; charset=utf-8"));
      return true;
    }
  }

  @Action(group = MAIN_GROUP, command = "auth_status", description = "Get the Google auth broker status.")
  public Boolean authStatus(CommandContext context) {
    context.complete(buildTextResponse(200, buildJson(store.statusSnapshot()), "application/json; charset=utf-8"));
    return true;
  }

  @Action(group = MAIN_GROUP, command = "capabilities", description = "Return the Google auth broker capability manifest.")
  public Boolean capabilities(CommandContext context) {
    ToolCapabilityManifest manifest = new GoogleAuthBrokerToolCapabilities().manifest();
    context.complete(buildTextResponse(200, buildJson(Map.of("manifest", manifest)), "application/json; charset=utf-8"));
    return true;
  }

  @Action(group = MAIN_GROUP, command = "auth_complete", description = "Alias for auth_poll in device-code mode.")
  public Boolean authComplete(CommandContext context) {
    return authPoll(context);
  }

  @Action(group = MAIN_GROUP, command = "auth_cancel", description = "Cancel the current Google auth session.")
  public Boolean authCancel(CommandContext context) {
    try {
      GoogleAuthSession session = store.readSession();
      if (session != null) {
        store.writeSession(session.cancel());
      }
      store.deletePrincipal();
      store.writeChallenge("");
      context.complete(buildTextResponse(200, "{\"ok\":true}", "application/json; charset=utf-8"));
      return true;
    } catch (Exception e) {
      context.complete(buildTextResponse(400, buildJson(Map.of(
          "ok", false,
          "error", "auth_cancel_failed",
          "message", safeMessage(e)
      )), "application/json; charset=utf-8"));
      return true;
    }
  }

  private static String renderChallengeHtml(GoogleAuthSession session) {
    if (session == null) {
      return "";
    }
    return "<div class=\"challenge\">"
        + "<div><b>Session:</b> " + escapeHtml(session.sessionId()) + "</div>"
        + "<div><b>Status:</b> " + escapeHtml(session.status()) + "</div>"
        + "<div><b>User code:</b> <code style=\"font-size:16px;letter-spacing:0.12em;\">" + escapeHtml(session.userCode()) + "</code></div>"
        + "<div><b>Verification URL:</b> <a href=\"" + escapeHtml(session.authorizeUrl()) + "\" target=\"_blank\" rel=\"noreferrer\">"
        + escapeHtml(session.authorizeUrl())
        + "</a></div>"
        + "<div><b>Poll interval:</b> " + session.pollIntervalSeconds() + "s</div>"
        + "</div>";
  }

  private static GoogleAuthSettings parseSettings(String body) throws IOException {
    JsonNode node = JSON.readTree(body);
    node = effectiveSettingsNode(node);
    return new GoogleAuthSettings(
        trim(firstNonBlank(node, "clientId", "client_id")),
        trim(firstNonBlank(node, "clientSecret", "client_secret")),
        trim(firstNonBlank(node, "projectId", "project_id", "projectName", "project_name")),
        trim(firstNonBlank(node, "authProvider", "auth_provider", "provider")),
        trim(firstNonBlank(node, "deviceAuthorizationUri", "device_authorization_uri", "deviceCodeUri", "device_code_uri")),
        trim(firstNonBlank(node, "tokenUri", "token_uri")),
        trim(firstNonBlank(node, "userInfoUri", "userInfoUri", "userinfo_uri", "profileUri", "profile_uri")),
        trim(firstNonBlank(node, "scope")),
        trim(firstNonBlank(node, "projectName", "project_name", "projectId", "project_id", "project"))
    );
  }

  private static JsonNode effectiveSettingsNode(JsonNode node) {
    if (node == null) {
      return JSON.createObjectNode();
    }
    if (node.hasNonNull("installed")) {
      JsonNode installed = node.path("installed");
      if (installed.isObject()) {
        return installed;
      }
    }
    if (node.hasNonNull("web")) {
      JsonNode web = node.path("web");
      if (web.isObject()) {
        return web;
      }
    }
    return node;
  }

  private static JsonNode parseBodyJson(String body) throws IOException {
    if (body == null || body.isBlank()) {
      return JSON.createObjectNode();
    }
    return JSON.readTree(body);
  }

  private static String requestBody(CommandContext context) {
    if (context == null || context.getAiatpRequest() == null) {
      return "";
    }
    return AiatpIO.bodyToString(context.getAiatpRequest().getBody(), StandardCharsets.UTF_8).trim();
  }

  private static AiatpResponse buildTextResponse(int statusCode, String body, String contentType) {
    AiatpIO.Headers headers = new AiatpIO.Headers();
    headers.set("Content-Type", contentType);
    return AiatpResponse.builder()
        .statusCode(statusCode)
        .reasonPhrase(statusCode >= 400 ? "error" : "completed")
        .headers(headers)
        .body(AiatpIO.Body.ofString(body == null ? "" : body, StandardCharsets.UTF_8))
        .build();
  }

  private static String render(Object value) {
    if (value == null) {
      return "null";
    }
    try {
      return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    } catch (Exception e) {
      return String.valueOf(value);
    }
  }

  private static String buildJson(Object value) {
    try {
      return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    } catch (Exception e) {
      return "{\"ok\":false,\"error\":\"json_render_failed\"}";
    }
  }

  private static String authUrlFor(GoogleAuthSettings settings, GoogleAuthSession session) {
    if (session != null && !session.authorizeUrl().isBlank()) {
      return session.authorizeUrl();
    }
    if (settings != null) {
      return settings.deviceAuthorizationUri();
    }
    return "";
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private static String quote(String value) {
    return value == null ? "\"\"" : "\"" + escapeJson(value) + "\"";
  }

  private static String safeMessage(Exception e) {
    return e == null ? "unknown_error" : trim(e.getMessage());
  }

  private static String urlEncode(String value) {
    try {
      return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    } catch (Exception e) {
      return "";
    }
  }

  private static String escapeJson(String value) {
    return value == null ? "" : value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  private static String escapeHtml(String value) {
    return value == null ? "" : value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private static String firstNonBlank(JsonNode node, String... keys) {
    if (node == null || keys == null) {
      return "";
    }
    for (String key : keys) {
      if (key == null || key.isBlank()) {
        continue;
      }
      String value = trim(node.path(key).asText(""));
      if (!value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private static String resolveRequestHost(AiatpRequest request) {
    if (request == null || request.getHeaders() == null) {
      return null;
    }
    return request.getHeaders().getFirst("Host");
  }

  private static String loadResourceText(String path) {
    try (InputStream in = GoogleAuthBrokerComponent.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("Resource not found: " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load resource: " + path, e);
    }
  }

  static final class GoogleAuthBrokerStore {
    private static final ObjectMapper JSON = new ObjectMapper();

    static {
      JSON.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    }

    GoogleAuthSettings readSettings() {
      return readJson(AuthStatePaths.settingsFile(), GoogleAuthSettings.class, GoogleAuthSettings.defaults());
    }

    void writeSettings(GoogleAuthSettings settings) throws IOException {
      writeJson(AuthStatePaths.settingsFile(), settings == null ? GoogleAuthSettings.defaults() : settings);
    }

    String settingsJson() {
      GoogleAuthSettings settings = readSettings();
      return buildJson(settings == null ? Map.of() : settings.toMap());
    }

    GoogleAuthSession readSession() {
      return readJson(AuthStatePaths.sessionFile(), GoogleAuthSession.class, null);
    }

    void writeSession(GoogleAuthSession session) throws IOException {
      writeJson(AuthStatePaths.sessionFile(), session);
    }

    GooglePrincipal readPrincipal() {
      return readJson(AuthStatePaths.principalFile(), GooglePrincipal.class, null);
    }

    void writePrincipal(GooglePrincipal principal) throws IOException {
      writeJson(AuthStatePaths.principalFile(), principal);
    }

    void deletePrincipal() {
      try {
        Files.deleteIfExists(AuthStatePaths.principalFile());
      } catch (IOException ignore) {
      }
    }

    void writeChallenge(String html) {
      try {
        Path file = AuthStatePaths.challengeFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, html == null ? "" : html, StandardCharsets.UTF_8);
      } catch (IOException ignore) {
      }
    }

    Map<String, Object> statusSnapshot() {
      Map<String, Object> snapshot = new LinkedHashMap<>();
      GoogleAuthSettings settings = readSettings();
      GoogleAuthSession session = readSession();
      GooglePrincipal principal = readPrincipal();
      snapshot.put("ok", true);
      snapshot.put("root", AuthStatePaths.root().toString());
      snapshot.put("settings", settings == null ? Map.of() : settings.toMap());
      snapshot.put("session", session == null ? Map.of() : session.toMap());
      snapshot.put("principal", principal == null ? Map.of() : principal.toMap());
      snapshot.put("authenticated", principal != null && principal.authenticated());
      snapshot.put("authUrl", session == null ? "" : session.authorizeUrl());
      snapshot.put("verificationUrl", session == null ? "" : session.authorizeUrl());
      snapshot.put("userCode", session == null ? "" : session.userCode());
      snapshot.put("pollIntervalSeconds", session == null ? 0 : session.pollIntervalSeconds());
      return snapshot;
    }

    String challengeFor(GooglePrincipal principal, GoogleAuthSession session) {
      return "<div class=\"challenge\">"
          + "<div><b>Principal:</b> " + escapeHtml(principal == null ? "" : principal.email()) + "</div>"
          + "<div><b>Session:</b> " + escapeHtml(session == null ? "" : session.sessionId()) + "</div>"
          + "<div><b>Status:</b> authenticated</div>"
          + "</div>";
    }

    private static <T> T readJson(Path path, Class<T> type, T fallback) {
      try {
        if (path == null || !Files.exists(path)) {
          return fallback;
        }
        return JSON.readValue(Files.readString(path, StandardCharsets.UTF_8), type);
      } catch (Exception e) {
        return fallback;
      }
    }

    private static void writeJson(Path path, Object value) throws IOException {
      if (path == null) {
        return;
      }
      Files.createDirectories(path.getParent());
      Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardCharsets.UTF_8);
    }
  }

  static final class GoogleAuthSettings {
    private String clientId;
    private String clientSecret;
    private String projectId;
    private String authProvider;
    private String deviceAuthorizationUri;
    private String tokenUri;
    private String userInfoUri;
    private String scope;
    private String projectName;

    GoogleAuthSettings() {
    }

    GoogleAuthSettings(String clientId,
                       String clientSecret,
                       String projectId,
                       String authProvider,
                       String deviceAuthorizationUri,
                       String tokenUri,
                       String userInfoUri,
                       String scope,
                       String projectName) {
      this.clientId = trim(clientId);
      this.clientSecret = trim(clientSecret);
      this.projectId = trim(projectId);
      this.authProvider = trim(authProvider);
      this.deviceAuthorizationUri = trim(deviceAuthorizationUri);
      this.tokenUri = trim(tokenUri);
      this.userInfoUri = trim(userInfoUri);
      this.scope = trim(scope);
      this.projectName = trim(projectName);
    }

    static GoogleAuthSettings defaults() {
      return new GoogleAuthSettings(
          "",
          "",
          "",
          "google",
          DEFAULT_DEVICE_AUTHORIZATION_URI,
          DEFAULT_TOKEN_URI,
          DEFAULT_USERINFO_URI,
          "openid email profile",
          "Todero"
      );
    }

    public String clientId() {
      return trim(clientId);
    }

    public String clientSecret() {
      return trim(clientSecret);
    }

    public String projectId() {
      return trim(projectId);
    }

    public String authProvider() {
      return trim(authProvider);
    }

    public String deviceAuthorizationUri() {
      String value = trim(deviceAuthorizationUri);
      return value.isBlank() ? DEFAULT_DEVICE_AUTHORIZATION_URI : value;
    }

    public String tokenUri() {
      String value = trim(tokenUri);
      return value.isBlank() ? DEFAULT_TOKEN_URI : value;
    }

    public String userInfoUri() {
      String value = trim(userInfoUri);
      return value.isBlank() ? DEFAULT_USERINFO_URI : value;
    }

    public String scope() {
      return trim(scope);
    }

    public String projectName() {
      return trim(projectName);
    }

    Map<String, Object> toMap() {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("clientId", clientId());
      out.put("clientSecret", clientSecret().isEmpty() ? "" : "••••••••");
      out.put("projectId", projectId());
      out.put("authProvider", authProvider());
      out.put("deviceAuthorizationUri", deviceAuthorizationUri());
      out.put("tokenUri", tokenUri());
      out.put("userInfoUri", userInfoUri());
      out.put("scope", scope());
      out.put("projectName", projectName());
      out.put("configured", !clientId().isEmpty() && !deviceAuthorizationUri().isEmpty() && !tokenUri().isEmpty());
      return out;
    }
  }

  static final class GoogleAuthSession {
    private String sessionId;
    private String authorizeUrl;
    private String deviceCode;
    private String userCode;
    private String deviceAuthorizationUri;
    private String tokenUri;
    private String userInfoUri;
    private String projectId;
    private String authProvider;
    private String status;
    private String sessionCreatedAt;
    private String sessionExpiresAt;
    private Integer pollIntervalSeconds;
    private String lastPollAt;
    private String lastMessage;
    private String lastError;
    private String accessToken;
    private String refreshToken;
    private String idToken;
    private String principalSubjectId;
    private String principalEmail;
    private String principalDisplayName;
    private String authenticatedAt;
    private GooglePrincipal principal;

    GoogleAuthSession() {
    }

    static GoogleAuthSession begin(GoogleAuthSettings settings, JsonNode input) throws IOException, InterruptedException {
      if (settings == null) {
        settings = GoogleAuthSettings.defaults();
      }
      GoogleAuthSession session = new GoogleAuthSession();
      session.sessionId = UUID.randomUUID().toString().replace("-", "");
      session.sessionCreatedAt = Instant.now().toString();
      session.sessionExpiresAt = Instant.now().plusSeconds(600).toString();
      session.status = "PENDING";
      session.projectId = settings.projectId();
      session.authProvider = settings.authProvider();
      session.deviceAuthorizationUri = settings.deviceAuthorizationUri();
      session.tokenUri = settings.tokenUri();
      session.userInfoUri = settings.userInfoUri();
      DeviceAuthorizationResponse device = requestDeviceAuthorization(settings, input);
      session.deviceCode = device.deviceCode();
      session.userCode = device.userCode();
      session.authorizeUrl = device.verificationUrl();
      session.pollIntervalSeconds = device.intervalSeconds();
      session.sessionExpiresAt = device.expiresAt();
      session.lastMessage = "Visit the verification URL and enter the device code.";
      return session;
    }

    GoogleAuthSession poll(GoogleAuthSettings settings) throws IOException, InterruptedException {
      if (settings == null) {
        settings = GoogleAuthSettings.defaults();
      }
      if ("COMPLETED".equalsIgnoreCase(status) || "CANCELED".equalsIgnoreCase(status) || "DENIED".equalsIgnoreCase(status)) {
        return copy();
      }
      if (isExpired()) {
        GoogleAuthSession expired = copy();
        expired.status = "EXPIRED";
        expired.lastMessage = "Device authorization session expired.";
        expired.lastError = "expired_token";
        return expired;
      }
      if (deviceCode() .isBlank()) {
        throw new IllegalStateException("No active device authorization session.");
      }
      TokenResponse token = requestToken(settings, this);
      if (token == null) {
        GoogleAuthSession next = copy();
        next.status = "PENDING";
        next.lastMessage = "Authorization pending.";
        return next;
      }
      if (token.error != null && !token.error.isBlank()) {
        String error = token.error.trim();
        GoogleAuthSession next = copy();
        next.lastPollAt = Instant.now().toString();
        next.lastError = error;
        next.lastMessage = token.errorDescription == null || token.errorDescription.isBlank()
            ? error
            : token.errorDescription.trim();
        switch (error) {
          case "authorization_pending" -> next.status = "PENDING";
          case "slow_down" -> {
            next.status = "PENDING";
            next.pollIntervalSeconds = Math.max(next.pollIntervalSeconds(), 5) + 5;
          }
          case "access_denied" -> next.status = "DENIED";
          case "expired_token" -> next.status = "EXPIRED";
          default -> next.status = "FAILED";
        }
        return next;
      }
      GooglePrincipal userInfo = token.accessToken == null || token.accessToken.isBlank()
          ? null
          : requestUserInfo(settings, token.accessToken);
      GooglePrincipal principal = GooglePrincipal.fromDeviceFlow(this, token, userInfo);
      GoogleAuthSession next = copy();
      next.status = "COMPLETED";
      next.lastPollAt = Instant.now().toString();
      next.lastMessage = "Google device authorization completed.";
      next.accessToken = principal.accessToken();
      next.refreshToken = principal.refreshToken();
      next.idToken = principal.idToken();
      next.principalSubjectId = principal.subjectId();
      next.principalEmail = principal.email();
      next.principalDisplayName = principal.displayName();
      next.authenticatedAt = principal.authenticatedAt();
      next.principal = principal;
      return next;
    }

    GoogleAuthSession cancel() {
      GoogleAuthSession next = copy();
      next.status = "CANCELED";
      next.lastMessage = "Authorization canceled.";
      return next;
    }

    private GoogleAuthSession copy() {
      GoogleAuthSession next = new GoogleAuthSession();
      next.sessionId = sessionId;
      next.authorizeUrl = authorizeUrl;
      next.deviceCode = deviceCode;
      next.userCode = userCode;
      next.deviceAuthorizationUri = deviceAuthorizationUri;
      next.tokenUri = tokenUri;
      next.userInfoUri = userInfoUri;
      next.projectId = projectId;
      next.authProvider = authProvider;
      next.status = status;
      next.sessionCreatedAt = sessionCreatedAt;
      next.sessionExpiresAt = sessionExpiresAt;
      next.pollIntervalSeconds = pollIntervalSeconds;
      next.lastPollAt = lastPollAt;
      next.lastMessage = lastMessage;
      next.lastError = lastError;
      next.accessToken = accessToken;
      next.refreshToken = refreshToken;
      next.idToken = idToken;
      next.principalSubjectId = principalSubjectId;
      next.principalEmail = principalEmail;
      next.principalDisplayName = principalDisplayName;
      next.authenticatedAt = authenticatedAt;
      next.principal = principal;
      return next;
    }

    String sessionId() {
      return trim(sessionId);
    }

    String deviceCode() {
      return trim(deviceCode);
    }

    String userCode() {
      return trim(userCode);
    }

    String authorizeUrl() {
      return trim(authorizeUrl);
    }

    String status() {
      return trim(status);
    }

    int pollIntervalSeconds() {
      return pollIntervalSeconds == null || pollIntervalSeconds <= 0 ? 5 : pollIntervalSeconds;
    }

    String lastMessage() {
      return trim(lastMessage);
    }

    String lastError() {
      return trim(lastError);
    }

    boolean authenticated() {
      return "COMPLETED".equalsIgnoreCase(status);
    }

    boolean isExpired() {
      try {
        return sessionExpiresAt != null && !sessionExpiresAt.isBlank() && Instant.parse(sessionExpiresAt).isBefore(Instant.now());
      } catch (Exception e) {
        return false;
      }
    }

    GooglePrincipal principal() {
      return principal;
    }

    String accessToken() {
      return trim(accessToken);
    }

    String refreshToken() {
      return trim(refreshToken);
    }

    String idToken() {
      return trim(idToken);
    }

    Map<String, Object> challenge() {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("sessionId", sessionId());
      out.put("status", status());
      out.put("userCode", userCode());
      out.put("verificationUrl", authorizeUrl());
      out.put("pollIntervalSeconds", pollIntervalSeconds());
      out.put("lastMessage", lastMessage());
      return out;
    }

    Map<String, Object> toMap() {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("sessionId", sessionId());
      out.put("deviceCode", deviceCode());
      out.put("userCode", userCode());
      out.put("verificationUrl", authorizeUrl());
      out.put("deviceAuthorizationUri", trim(deviceAuthorizationUri));
      out.put("tokenUri", trim(tokenUri));
      out.put("userInfoUri", trim(userInfoUri));
      out.put("projectId", trim(projectId));
      out.put("authProvider", trim(authProvider));
      out.put("status", status());
      out.put("sessionCreatedAt", trim(sessionCreatedAt));
      out.put("sessionExpiresAt", trim(sessionExpiresAt));
      out.put("pollIntervalSeconds", pollIntervalSeconds());
      out.put("lastPollAt", trim(lastPollAt));
      out.put("lastMessage", lastMessage());
      out.put("lastError", lastError());
      out.put("accessToken", accessToken().isEmpty() ? "" : "••••••••");
      out.put("refreshToken", refreshToken().isEmpty() ? "" : "••••••••");
      out.put("idToken", idToken().isEmpty() ? "" : "••••••••");
      out.put("principalSubjectId", trim(principalSubjectId));
      out.put("principalEmail", trim(principalEmail));
      out.put("principalDisplayName", trim(principalDisplayName));
      out.put("authenticatedAt", trim(authenticatedAt));
      out.put("authenticated", authenticated());
      return out;
    }
  }

  static final class GooglePrincipal {
    private String provider;
    private String subjectId;
    private String email;
    private String displayName;
    private String authenticatedAt;
    private String expiresAt;
    private boolean authenticated;
    private String sessionId;
    private String accessToken;
    private String refreshToken;
    private String idToken;

    GooglePrincipal() {
    }

    static GooglePrincipal fromDeviceFlow(GoogleAuthSession session,
                                          TokenResponse token,
                                          GooglePrincipal userInfo) {
      GooglePrincipal principal = new GooglePrincipal();
      principal.provider = "google";
      principal.subjectId = userInfo != null && !userInfo.subjectId().isBlank()
          ? userInfo.subjectId()
          : trim(token == null ? "" : token.idTokenSubject);
      principal.email = userInfo != null ? userInfo.email() : "";
      principal.displayName = userInfo != null ? userInfo.displayName() : principal.email;
      principal.authenticatedAt = Instant.now().toString();
      int expiresIn = token == null ? 3600 : token.expiresIn();
      principal.expiresAt = Instant.now().plusSeconds(Math.max(expiresIn, 60)).toString();
      principal.authenticated = true;
      principal.sessionId = session == null ? "" : session.sessionId();
      principal.accessToken = token == null ? "" : trim(token.accessToken);
      principal.refreshToken = token == null ? "" : trim(token.refreshToken);
      principal.idToken = token == null ? "" : trim(token.idToken);
      if (principal.email.isBlank() && userInfo != null) {
        principal.email = userInfo.email();
      }
      if (principal.displayName.isBlank() && userInfo != null) {
        principal.displayName = userInfo.displayName();
      }
      if (principal.subjectId.isBlank() && userInfo != null) {
        principal.subjectId = userInfo.subjectId();
      }
      if (principal.subjectId.isBlank()) {
        principal.subjectId = principal.email.isBlank() ? "google-user" : principal.email;
      }
      if (principal.displayName.isBlank()) {
        principal.displayName = principal.email.isBlank() ? principal.subjectId : principal.email;
      }
      return principal;
    }

    String provider() { return trim(provider); }
    String subjectId() { return trim(subjectId); }
    String email() { return trim(email); }
    String displayName() { return trim(displayName); }
    String authenticatedAt() { return trim(authenticatedAt); }
    String expiresAt() { return trim(expiresAt); }
    boolean authenticated() { return authenticated; }
    String sessionId() { return trim(sessionId); }
    String accessToken() { return trim(accessToken); }
    String refreshToken() { return trim(refreshToken); }
    String idToken() { return trim(idToken); }

    Map<String, Object> toMap() {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("provider", provider());
      out.put("subjectId", subjectId());
      out.put("email", email());
      out.put("displayName", displayName());
      out.put("authenticatedAt", authenticatedAt());
      out.put("expiresAt", expiresAt());
      out.put("authenticated", authenticated());
      out.put("sessionId", sessionId());
      out.put("accessToken", accessToken().isEmpty() ? "" : "••••••••");
      out.put("refreshToken", refreshToken().isEmpty() ? "" : "••••••••");
      out.put("idToken", idToken().isEmpty() ? "" : "••••••••");
      return out;
    }
  }

  static final class TokenResponse {
    private String accessToken;
    private String refreshToken;
    private String idToken;
    private String tokenType;
    private String scope;
    private Integer expiresIn;
    private String error;
    private String errorDescription;
    private String idTokenSubject;

    int expiresIn() {
      return expiresIn == null ? 3600 : Math.max(expiresIn, 1);
    }
  }

  static final class DeviceAuthorizationResponse {
    private final String deviceCode;
    private final String userCode;
    private final String verificationUrl;
    private final int intervalSeconds;
    private final String expiresAt;

    DeviceAuthorizationResponse(String deviceCode,
                                String userCode,
                                String verificationUrl,
                                int intervalSeconds,
                                String expiresAt) {
      this.deviceCode = trim(deviceCode);
      this.userCode = trim(userCode);
      this.verificationUrl = trim(verificationUrl);
      this.intervalSeconds = Math.max(intervalSeconds, 1);
      this.expiresAt = trim(expiresAt);
    }

    String deviceCode() {
      return deviceCode;
    }

    String userCode() {
      return userCode;
    }

    String verificationUrl() {
      return verificationUrl;
    }

    int intervalSeconds() {
      return intervalSeconds;
    }

    String expiresAt() {
      return expiresAt;
    }
  }

  private static DeviceAuthorizationResponse requestDeviceAuthorization(GoogleAuthSettings settings, JsonNode input)
      throws IOException, InterruptedException {
    if (settings == null) {
      settings = GoogleAuthSettings.defaults();
    }
    Map<String, String> form = new LinkedHashMap<>();
    form.put("client_id", settings.clientId());
    String scope = trim(firstNonBlank(input, "scope"));
    if (scope.isBlank()) {
      scope = settings.scope();
    }
    if (scope.isBlank()) {
      scope = "openid email profile";
    }
    form.put("scope", scope);
    JsonNode response = postForm(settings.deviceAuthorizationUri(), form);
    String error = trim(response.path("error").asText(""));
    if (!error.isBlank()) {
      throw new IllegalStateException(firstNonBlankText(response, "error_description", "error"));
    }
    String deviceCode = firstNonBlankText(response, "device_code");
    String userCode = firstNonBlankText(response, "user_code");
    String verificationUrl = firstNonBlankText(response, "verification_url", "verification_uri_complete", "verification_uri");
    int interval = parseInt(response.path("interval").asText("5"), 5);
    int expiresIn = parseInt(response.path("expires_in").asText("600"), 600);
    if (deviceCode.isBlank() || userCode.isBlank() || verificationUrl.isBlank()) {
      throw new IllegalStateException("Device authorization response missing required fields.");
    }
    return new DeviceAuthorizationResponse(deviceCode, userCode, verificationUrl, interval,
        Instant.now().plusSeconds(Math.max(expiresIn, 60)).toString());
  }

  private static TokenResponse requestToken(GoogleAuthSettings settings, GoogleAuthSession session)
      throws IOException, InterruptedException {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("client_id", settings.clientId());
    if (!settings.clientSecret().isBlank()) {
      form.put("client_secret", settings.clientSecret());
    }
    form.put("device_code", session.deviceCode());
    form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
    JsonNode response = postForm(settings.tokenUri(), form);
    TokenResponse token = new TokenResponse();
    token.accessToken = firstNonBlankText(response, "access_token");
    token.refreshToken = firstNonBlankText(response, "refresh_token");
    token.idToken = firstNonBlankText(response, "id_token");
    token.tokenType = firstNonBlankText(response, "token_type");
    token.scope = firstNonBlankText(response, "scope");
    token.expiresIn = parseIntOrNull(response.path("expires_in").asText(""));
    token.error = firstNonBlankText(response, "error");
    token.errorDescription = firstNonBlankText(response, "error_description");
    if (token.accessToken.isBlank() && token.error.isBlank()) {
      token.error = "authorization_pending";
      token.errorDescription = "Authorization pending.";
    }
    if (token.idToken != null && !token.idToken.isBlank()) {
      token.idTokenSubject = parseJwtClaim(token.idToken, "sub");
    }
    return token;
  }

  private static GooglePrincipal requestUserInfo(GoogleAuthSettings settings, String accessToken)
      throws IOException, InterruptedException {
    if (settings == null || accessToken == null || accessToken.isBlank()) {
      return null;
    }
    JsonNode response = getJson(settings.userInfoUri(), accessToken);
    GooglePrincipal principal = new GooglePrincipal();
    principal.provider = settings.authProvider().isBlank() ? "google" : settings.authProvider();
    principal.subjectId = firstNonBlankText(response, "sub", "subject");
    principal.email = firstNonBlankText(response, "email", "preferred_username");
    principal.displayName = firstNonBlankText(response, "name", "given_name", "family_name");
    return principal;
  }

  private static JsonNode postForm(String uri, Map<String, String> form) throws IOException, InterruptedException {
    if (uri == null || uri.isBlank()) {
      throw new IllegalArgumentException("Missing endpoint URI.");
    }
    String body = formEncode(form);
    HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    String responseBody = response.body() == null ? "" : response.body();
    if (responseBody.isBlank()) {
      return JSON.createObjectNode();
    }
    return JSON.readTree(responseBody);
  }

  private static JsonNode getJson(String uri, String bearerToken) throws IOException, InterruptedException {
    if (uri == null || uri.isBlank()) {
      return JSON.createObjectNode();
    }
    HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
        .timeout(Duration.ofSeconds(30))
        .header("Authorization", "Bearer " + bearerToken)
        .GET()
        .build();
    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    String responseBody = response.body() == null ? "" : response.body();
    if (responseBody.isBlank()) {
      return JSON.createObjectNode();
    }
    return JSON.readTree(responseBody);
  }

  private static String formEncode(Map<String, String> form) {
    if (form == null || form.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> entry : form.entrySet()) {
      if (entry == null) continue;
      if (sb.length() > 0) {
        sb.append('&');
      }
      sb.append(urlEncode(entry.getKey()));
      sb.append('=');
      sb.append(urlEncode(entry.getValue()));
    }
    return sb.toString();
  }

  private static int parseInt(String raw, int fallback) {
    try {
      return Integer.parseInt(trim(raw));
    } catch (Exception e) {
      return fallback;
    }
  }

  private static Integer parseIntOrNull(String raw) {
    try {
      String trimmed = trim(raw);
      if (trimmed.isBlank()) return null;
      return Integer.parseInt(trimmed);
    } catch (Exception e) {
      return null;
    }
  }

  private static String firstNonBlankText(JsonNode node, String... keys) {
    if (node == null || keys == null) {
      return "";
    }
    for (String key : keys) {
      if (key == null || key.isBlank()) {
        continue;
      }
      String value = trim(node.path(key).asText(""));
      if (!value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private static String parseJwtClaim(String jwt, String claimName) {
    try {
      if (jwt == null || jwt.isBlank() || claimName == null || claimName.isBlank()) {
        return "";
      }
      String[] parts = jwt.split("\\.");
      if (parts.length < 2) {
        return "";
      }
      byte[] decoded = java.util.Base64.getUrlDecoder().decode(parts[1]);
      JsonNode claims = JSON.readTree(new String(decoded, StandardCharsets.UTF_8));
      return trim(claims.path(claimName).asText(""));
    } catch (Exception e) {
      return "";
    }
  }
}
