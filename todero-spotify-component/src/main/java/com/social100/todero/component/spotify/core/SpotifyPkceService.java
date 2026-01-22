package com.social100.todero.component.spotify.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.SerializedName;
import com.social100.todero.common.runtime.auth.AuthorizationChannelsContract;
import com.social100.todero.common.runtime.auth.AuthorizationEnvelopeIntegrityVerifier;
import com.social100.todero.common.runtime.auth.AuthorizationErrorCode;
import com.social100.todero.common.runtime.auth.AuthorizationRelayPolicy;
import com.social100.todero.common.runtime.auth.AuthorizationSecureEnvelope;
import com.social100.todero.common.runtime.auth.AuthorizationSession;
import com.social100.todero.common.runtime.auth.AuthorizationSessionStatus;
import com.social100.todero.common.runtime.auth.AuthorizationValidation;
import com.social100.todero.common.runtime.auth.AuthorizationValidationException;
import com.social100.todero.common.storage.Storage;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.model_objects.miscellaneous.Device;
import se.michaelthelin.spotify.model_objects.specification.Paging;
import se.michaelthelin.spotify.model_objects.specification.Track;
import se.michaelthelin.spotify.requests.data.player.GetUsersAvailableDevicesRequest;
import se.michaelthelin.spotify.requests.data.player.StartResumeUsersPlaybackRequest;
import se.michaelthelin.spotify.requests.data.player.TransferUsersPlaybackRequest;
import se.michaelthelin.spotify.requests.data.search.simplified.SearchTracksRequest;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.util.Base64;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class SpotifyPkceService {

  private static final String AUTH_URL = "https://accounts.spotify.com/authorize";
  private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
  private static final Gson GSON = new Gson();
  private static final long AUTH_SESSION_TTL_SEC = 300L;
  private static final long ENVELOPE_TTL_SEC = 300L;
  private static final String PROVIDER = "spotify";
  private static final String APP_DELEGATED_AUTH_HOST = "auth.shellaia.com";
  private static final String APP_DELEGATED_AUTH_PATH = "/oauth2/component/callback";
  private static final Set<String> REQUIRED_SCOPES = Set.of(
      "user-read-playback-state",
      "user-modify-playback-state",
      "user-read-recently-played",
      "user-top-read",
      "user-library-modify",
      "playlist-read-private",
      "playlist-read-collaborative",
      "playlist-modify-public",
      "playlist-modify-private"
  );

  private final String clientId;
  private final URI redirectUriApp;
  private final URI redirectUriConsole;
  private final Set<String> explicitRedirectAllowlist;
  private final String deviceIdEnv; // optional
  private final SpotifyApi spotifyApi;
  private final TokenStore tokenStore;
  private final SpotifyAuthSessionStore authSessionStore;
  private final URI tokenEndpoint;

  public SpotifyPkceService(SpotifyConfig spotifyConfig, Storage storage) {
    this(spotifyConfig, storage, URI.create(TOKEN_URL));
  }

  SpotifyPkceService(SpotifyConfig spotifyConfig, Storage storage, URI tokenEndpoint) {
    this.clientId = spotifyConfig.getClientId();
    this.redirectUriApp = parseUri(spotifyConfig.getRedirectUrlApp());
    this.redirectUriConsole = parseUri(spotifyConfig.getRedirectUrlConsole());
    this.explicitRedirectAllowlist = parseRedirectAllowlist(spotifyConfig.getRedirectAllowlist());
    this.deviceIdEnv = spotifyConfig.getDeviceId();
    this.tokenEndpoint = tokenEndpoint;

    URI spotifyApiRedirect = redirectUriApp != null
        ? redirectUriApp
        : (redirectUriConsole != null ? redirectUriConsole : URI.create("http://invalid.local/spotify/callback"));
    this.spotifyApi = new SpotifyApi.Builder()
        .setClientId(clientId)
        .setRedirectUri(spotifyApiRedirect)
        .build();

    this.tokenStore = new TokenStore(storage);
    this.authSessionStore = new SpotifyAuthSessionStore(storage);
  }

  /* ===================== AUTH ===================== */

  private void ensureAuthorized() {
    Optional<TokenStore.TokenData> saved = tokenStore.read();
    if (saved.isEmpty()) {
      throw SpotifyAuthorizationRequiredException.missingToken();
    }
    TokenStore.TokenData td = saved.get();
    if (TokenStore.isExpired(td)) {
      try {
        td = refresh(td.refreshToken);
        tokenStore.write(td);
      } catch (Exception e) {
        throw SpotifyAuthorizationRequiredException.refreshFailed();
      }
    }
    Set<String> missingScopes = missingRequiredScopes(td.scope);
    if (!missingScopes.isEmpty()) {
      throw SpotifyAuthorizationRequiredException.missingScopes(missingScopes);
    }
    applyTokens(td);
  }

  public AuthBeginResult authBegin(String redirectProfile, String redirectUriInput, String ownerBinding) {
    String profile = normalizeRedirectProfile(redirectProfile);
    URI selectedRedirectUri = resolveRedirectUri(profile, redirectUriInput);
    String verifier = PkceUtil.generateCodeVerifier();
    String challenge = PkceUtil.codeChallengeS256(verifier);
    String state = UUID.randomUUID().toString().replace("-", "");
    String sessionId = UUID.randomUUID().toString().replace("-", "");
    long nowMs = System.currentTimeMillis();
    long expiresAtMs = nowMs + (AUTH_SESSION_TTL_SEC * 1000L);
    String normalizedOwner = normalizeOwnerBinding(ownerBinding);

    AuthorizationSession session = new AuthorizationSession(
        sessionId,
        PROVIDER,
        normalizedOwner,
        state,
        AuthorizationSessionStatus.PENDING,
        nowMs,
        expiresAtMs,
        null,
        null
    );
    AuthorizationSecureEnvelope envelope = buildSecureEnvelope(sessionId, state, nowMs, ENVELOPE_TTL_SEC);

    SpotifyAuthSessionStore.SessionEnvelope persisted = new SpotifyAuthSessionStore.SessionEnvelope(
        session,
        selectedRedirectUri.toString(),
        verifier,
        String.join(" ", REQUIRED_SCOPES),
        envelope
    );
    authSessionStore.save(persisted);

    String authorizeUrl = buildAuthorizeUrl(selectedRedirectUri, verifier, state);
    AuthorizationRelayPolicy relayPolicy = new AuthorizationRelayPolicy(true, false, (int) ENVELOPE_TTL_SEC);

    Map<String, Object> channels = AuthorizationChannelsContract.authRequiredPayload(
        PROVIDER,
        sessionId,
        authorizeUrl,
        expiresAtMs,
        envelope,
        relayPolicy,
        "Spotify authorization required.",
        "Open the authorization URL, then complete with auth-complete.",
        "com.shellaia.verbatim.component.spotify auth-complete ..."
    );
    applyAuthCta(channels, authorizeUrl, expiresAtMs);

    return new AuthBeginResult(true, null, "Authorization session created.", session, authorizeUrl, channels, relayPolicy, envelope);
  }

  public AuthCompleteResult authComplete(AuthCompleteRequest request) {
    if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
      return AuthCompleteResult.error(AuthorizationErrorCode.AUTH_SESSION_MISSING, "Missing auth session id.");
    }
    Optional<SpotifyAuthSessionStore.SessionEnvelope> maybeEnvelope = authSessionStore.get(request.sessionId());
    if (maybeEnvelope.isEmpty()) {
      return AuthCompleteResult.error(AuthorizationErrorCode.AUTH_SESSION_MISSING, "Authorization session not found.");
    }
    SpotifyAuthSessionStore.SessionEnvelope stored = maybeEnvelope.get();
    AuthorizationSession session = stored.session();
    long nowMs = System.currentTimeMillis();

    if (AuthorizationValidation.isExpired(nowMs, session.expiresAtMs())) {
      authSessionStore.updateSessionStatus(stored, AuthorizationSessionStatus.EXPIRED, nowMs, AuthorizationErrorCode.AUTH_SESSION_EXPIRED);
      return AuthCompleteResult.error(AuthorizationErrorCode.AUTH_SESSION_EXPIRED, "Authorization session expired.");
    }
    if (session.status() == AuthorizationSessionStatus.COMPLETED) {
      return AuthCompleteResult.error(AuthorizationErrorCode.AUTH_SESSION_ALREADY_COMPLETED, "Authorization session already completed.");
    }
    if (session.status() == AuthorizationSessionStatus.CANCELED) {
      return AuthCompleteResult.error(AuthorizationErrorCode.AUTH_SESSION_CANCELED, "Authorization session canceled.");
    }
    if (request.error() != null && !request.error().isBlank()) {
      authSessionStore.updateSessionStatus(stored, AuthorizationSessionStatus.FAILED, nowMs, AuthorizationErrorCode.AUTH_EXCHANGE_FAILED);
      return AuthCompleteResult.error(AuthorizationErrorCode.AUTH_EXCHANGE_FAILED, "Authorization callback returned error.");
    }
    try {
      AuthorizationValidation.requireState(session.state(), request.state());
      verifySecureEnvelope(stored, request, nowMs);
    } catch (AuthorizationValidationException e) {
      authSessionStore.updateSessionStatus(stored, AuthorizationSessionStatus.FAILED, nowMs, e.code());
      return AuthCompleteResult.error(e.code(), e.getMessage());
    }

    try {
      TokenStore.TokenData td = exchangeCodeForTokens(request.code(), stored.codeVerifier(), URI.create(stored.redirectUri()));
      Set<String> missingScopes = missingRequiredScopes(td.scope);
      if (!missingScopes.isEmpty()) {
        authSessionStore.updateSessionStatus(stored, AuthorizationSessionStatus.FAILED, nowMs, AuthorizationErrorCode.AUTH_SCOPE_MISSING);
        return AuthCompleteResult.error(
            AuthorizationErrorCode.AUTH_SCOPE_MISSING,
            "Authorization completed but required scopes are missing: " + String.join(", ", missingScopes)
        );
      }
      tokenStore.write(td);
      applyTokens(td);
      SpotifyAuthSessionStore.SessionEnvelope completed = authSessionStore.updateSessionStatus(
          stored,
          AuthorizationSessionStatus.COMPLETED,
          nowMs,
          null
      );
      return AuthCompleteResult.ok(completed.session(), tokenSummary(td));
    } catch (Exception e) {
      authSessionStore.updateSessionStatus(stored, AuthorizationSessionStatus.FAILED, nowMs, AuthorizationErrorCode.AUTH_EXCHANGE_FAILED);
      return AuthCompleteResult.error(AuthorizationErrorCode.AUTH_EXCHANGE_FAILED, "Spotify auth exchange failed.");
    }
  }

  public AuthSessionResult authSession(String sessionId) {
    Optional<SpotifyAuthSessionStore.SessionEnvelope> resolved =
        (sessionId == null || sessionId.isBlank()) ? authSessionStore.getActive() : authSessionStore.get(sessionId);
    if (resolved.isEmpty()) {
      return AuthSessionResult.error(AuthorizationErrorCode.AUTH_SESSION_MISSING, "Authorization session not found.");
    }
    AuthorizationSession session = resolved.get().session();
    if (session.status() == AuthorizationSessionStatus.PENDING
        && AuthorizationValidation.isExpired(System.currentTimeMillis(), session.expiresAtMs())) {
      SpotifyAuthSessionStore.SessionEnvelope expired = authSessionStore.updateSessionStatus(
          resolved.get(), AuthorizationSessionStatus.EXPIRED, System.currentTimeMillis(), AuthorizationErrorCode.AUTH_SESSION_EXPIRED
      );
      session = expired.session();
    }
    return AuthSessionResult.ok(session);
  }

  public AuthSessionResult authCancel(String sessionId) {
    Optional<SpotifyAuthSessionStore.SessionEnvelope> resolved =
        (sessionId == null || sessionId.isBlank()) ? authSessionStore.getActive() : authSessionStore.get(sessionId);
    if (resolved.isEmpty()) {
      return AuthSessionResult.error(AuthorizationErrorCode.AUTH_SESSION_MISSING, "Authorization session not found.");
    }
    SpotifyAuthSessionStore.SessionEnvelope canceled = authSessionStore.updateSessionStatus(
        resolved.get(),
        AuthorizationSessionStatus.CANCELED,
        System.currentTimeMillis(),
        AuthorizationErrorCode.AUTH_SESSION_CANCELED
    );
    return AuthSessionResult.ok(canceled.session());
  }

  private TokenStore.TokenData exchangeCodeForTokens(String code, String codeVerifier)
      throws IOException, InterruptedException {
    return exchangeCodeForTokens(code, codeVerifier, redirectUriApp);
  }

  private TokenStore.TokenData exchangeCodeForTokens(String code, String codeVerifier, URI redirectForExchange)
      throws IOException, InterruptedException {
    HttpClient http = HttpClient.newHttpClient();

    String body = UriBuilder.of("") // form body
        .add("grant_type", "authorization_code")
        .add("code", code)
        .add("redirect_uri", redirectForExchange.toString())
        .add("client_id", clientId)
        .add("code_verifier", codeVerifier)
        .formBody();

    HttpRequest req = HttpRequest.newBuilder(tokenEndpoint)
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .build();

    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() >= 300) {
      throw new RuntimeException("Token exchange failed: " + resp.statusCode() + " " + resp.body());
    }

    return parseTokenResponse(resp.body());
  }

  private TokenStore.TokenData refresh(String refreshToken) throws IOException, InterruptedException {
    HttpClient http = HttpClient.newHttpClient();

    String body = UriBuilder.of("") // form body
        .add("grant_type", "refresh_token")
        .add("refresh_token", refreshToken)
        .add("client_id", clientId)
        .formBody();

    HttpRequest req = HttpRequest.newBuilder(tokenEndpoint)
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .build();

    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() >= 300) {
      throw new RuntimeException("Refresh failed: " + resp.statusCode() + " " + resp.body());
    }

    TokenStore.TokenData td = parseTokenResponse(resp.body());
    if (td.refreshToken == null || td.refreshToken.isBlank()) {
      td.refreshToken = refreshToken; // reuse old on rotation-less refresh
    }
    Optional<TokenStore.TokenData> previous = tokenStore.read();
    if ((td.scope == null || td.scope.isBlank()) && previous.isPresent()) {
      td.scope = previous.get().scope;
    }
    return td;
  }

  private TokenStore.TokenData parseTokenResponse(String json) {
    TokenResponse tr = GSON.fromJson(json, TokenResponse.class);
    if (tr.accessToken == null) throw new RuntimeException("No access_token in response");
    TokenStore.TokenData td = new TokenStore.TokenData();
    td.accessToken = tr.accessToken;
    td.refreshToken = tr.refreshToken; // may be null on refresh
    td.expiresAtEpoch = Instant.now().getEpochSecond() + (tr.expiresIn != null ? tr.expiresIn : 3600);
    td.scope = tr.scope;
    return td;
  }

  private void applyTokens(TokenStore.TokenData td) {
    spotifyApi.setAccessToken(td.accessToken);
    if (td.refreshToken != null) {
      spotifyApi.setRefreshToken(td.refreshToken);
    }
  }

  public SpotifyApi getSpotifyApi() {
    ensureAuthorized();
    return this.spotifyApi;
  }

  public Set<String> requiredScopes() {
    return REQUIRED_SCOPES;
  }

  private String buildAuthorizeUrl(URI targetRedirectUri, String verifier, String state) {
    String scopes = String.join(" ", REQUIRED_SCOPES);
    return UriBuilder.of(AUTH_URL)
        .add("response_type", "code")
        .add("client_id", clientId)
        .add("redirect_uri", targetRedirectUri.toString())
        .add("scope", scopes)
        .add("state", state)
        .add("code_challenge_method", "S256")
        .add("code_challenge", PkceUtil.codeChallengeS256(verifier))
        .build();
  }

  private static String normalizeRedirectProfile(String redirectProfile) {
    if (redirectProfile == null || redirectProfile.isBlank()) {
      return "app";
    }
    String p = redirectProfile.trim().toLowerCase();
    if (!"app".equals(p) && !"console".equals(p) && !"explicit".equals(p)) {
      throw new AuthorizationValidationException(
          AuthorizationErrorCode.AUTH_REDIRECT_PROFILE_INVALID,
          "Invalid redirect profile."
      );
    }
    return p;
  }

  private URI resolveRedirectUri(String profile, String redirectUriInput) {
    if ("app".equals(profile)) {
      URI appRedirect = requireConfiguredRedirect(redirectUriApp, "App redirect-uri is not configured.");
      return validateAppDelegatedRedirect(appRedirect);
    }
    if ("console".equals(profile)) {
      return requireConfiguredRedirect(redirectUriConsole, "Console redirect-uri is not configured.");
    }
    if ("explicit".equals(profile)) {
      if (redirectUriInput == null || redirectUriInput.isBlank()) {
        throw new AuthorizationValidationException(
            AuthorizationErrorCode.AUTH_REDIRECT_URI_DISALLOWED,
            "redirect-uri is required for explicit profile."
        );
      }
      URI explicit = parseUri(redirectUriInput.trim());
      if (!explicitRedirectAllowlist.contains(explicit.toString())) {
        throw new AuthorizationValidationException(
            AuthorizationErrorCode.AUTH_REDIRECT_URI_DISALLOWED,
            "Explicit redirect-uri is not allowed by policy."
        );
      }
      return explicit;
    }
    throw new AuthorizationValidationException(
        AuthorizationErrorCode.AUTH_REDIRECT_PROFILE_INVALID,
        "Invalid redirect profile."
    );
  }

  private static URI requireConfiguredRedirect(URI uri, String message) {
    if (uri != null) {
      return uri;
    }
    throw new AuthorizationValidationException(
        AuthorizationErrorCode.AUTH_REDIRECT_URI_DISALLOWED,
        message
    );
  }

  private static URI parseUri(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return URI.create(raw.trim());
    } catch (Exception e) {
      throw new AuthorizationValidationException(
          AuthorizationErrorCode.AUTH_REDIRECT_URI_DISALLOWED,
          "Invalid redirect-uri format."
      );
    }
  }

  private static URI validateAppDelegatedRedirect(URI uri) {
    if (uri == null) {
      throw new AuthorizationValidationException(
          AuthorizationErrorCode.AUTH_REDIRECT_URI_DISALLOWED,
          "App redirect-uri is not configured."
      );
    }
    String scheme = safeLower(uri.getScheme());
    String host = safeLower(uri.getHost());
    String path = uri.getPath() == null ? "" : uri.getPath();
    if (!"https".equals(scheme) || !APP_DELEGATED_AUTH_HOST.equals(host) || !APP_DELEGATED_AUTH_PATH.equals(path)) {
      throw new AuthorizationValidationException(
          AuthorizationErrorCode.AUTH_REDIRECT_URI_DISALLOWED,
          "App redirect-uri must be https://auth.shellaia.com/oauth2/component/callback with provider=spotify."
      );
    }
    String provider = queryParam(uri.getRawQuery(), "provider");
    if (!PROVIDER.equals(safeLower(provider))) {
      throw new AuthorizationValidationException(
          AuthorizationErrorCode.AUTH_REDIRECT_URI_DISALLOWED,
          "App redirect-uri must include provider=spotify."
      );
    }
    return uri;
  }

  private static String queryParam(String rawQuery, String key) {
    if (rawQuery == null || rawQuery.isBlank()) {
      return "";
    }
    for (String part : rawQuery.split("&")) {
      if (part == null || part.isBlank()) {
        continue;
      }
      String[] kv = part.split("=", 2);
      String k = urlDecode(kv[0]);
      if (!key.equalsIgnoreCase(k)) {
        continue;
      }
      return kv.length == 2 ? urlDecode(kv[1]) : "";
    }
    return "";
  }

  private static String urlDecode(String value) {
    try {
      return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    } catch (Exception e) {
      return "";
    }
  }

  private static String safeLower(String value) {
    return value == null ? "" : value.trim().toLowerCase();
  }

  private static Set<String> parseRedirectAllowlist(String raw) {
    if (raw == null || raw.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(s -> !s.isBlank())
        .map(SpotifyPkceService::parseUri)
        .map(URI::toString)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static void applyAuthCta(Map<String, Object> payload, String authorizeUrl, long expiresAtMs) {
    if (payload == null) {
      return;
    }
    Object channelsObj = payload.get("channels");
    if (channelsObj instanceof Map<?, ?> channelsAny) {
      @SuppressWarnings("unchecked")
      Map<String, Object> channels = (Map<String, Object>) channelsAny;
      Map<String, Object> webview = new LinkedHashMap<>();
      webview.put("html", buildAuthCtaHtml(authorizeUrl, expiresAtMs));
      webview.put("mode", "html");
      webview.put("replace", true);
      channels.put("webview", webview);
    }
    Object authObj = payload.get("auth");
    if (authObj instanceof Map<?, ?> authAny) {
      @SuppressWarnings("unchecked")
      Map<String, Object> auth = (Map<String, Object>) authAny;
      auth.put("openExternally", true);
      auth.put("ctaLabel", "Authorize Spotify");
    }
  }

  private static String buildAuthCtaHtml(String authorizeUrl, long expiresAtMs) {
    String safeUrl = htmlEscape(authorizeUrl == null ? "" : authorizeUrl);
    String expiry = expiresAtMs > 0 ? Long.toString(expiresAtMs) : "";
    return "<html><body style=\"font-family:sans-serif;padding:14px;margin:0;background:#0d1117;color:#e6edf3;\">"
        + "<div style=\"border:1px solid #30363d;border-radius:12px;padding:14px;background:#161b22;\">"
        + "<div style=\"font-size:12px;color:#8b949e;margin-bottom:6px;\">Spotify Authorization</div>"
        + "<div style=\"font-size:16px;font-weight:700;margin-bottom:8px;\">Connect your Spotify account</div>"
        + "<p style=\"margin:0 0 10px 0;font-size:13px;color:#c9d1d9;\">Tap the button to open Spotify authorization in your browser.</p>"
        + "<a href=\"" + safeUrl + "\" target=\"_blank\" rel=\"noopener\" "
        + "style=\"display:inline-block;background:#1db954;color:#04110a;text-decoration:none;font-weight:700;border-radius:8px;padding:10px 12px;\">Authorize Spotify</a>"
        + "<div style=\"margin-top:10px;font-size:11px;color:#8b949e;word-break:break-all;\">URL: " + safeUrl + "</div>"
        + (expiry.isEmpty() ? "" : "<div style=\"margin-top:6px;font-size:11px;color:#8b949e;\">expiresAtMs: " + expiry + "</div>")
        + "</div></body></html>";
  }

  private static String htmlEscape(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private static String normalizeOwnerBinding(String ownerBinding) {
    if (ownerBinding == null || ownerBinding.isBlank()) {
      return "default-owner";
    }
    return ownerBinding.trim();
  }

  private AuthorizationSecureEnvelope buildSecureEnvelope(String sessionId, String state, long nowMs, long ttlSec) {
    String nonce = UUID.randomUUID().toString().replace("-", "");
    String payload = Base64.getUrlEncoder().withoutPadding()
        .encodeToString((sessionId + ":" + state).getBytes(StandardCharsets.UTF_8));
    String algorithm = "HMAC-SHA256";
    String integrity = computeEnvelopeIntegrity(sessionId, nonce, ttlSec, payload, algorithm, nowMs);
    return new AuthorizationSecureEnvelope(
        UUID.randomUUID().toString().replace("-", ""),
        sessionId,
        nonce,
        ttlSec,
        payload,
        integrity,
        algorithm,
        nowMs
    );
  }

  private void verifySecureEnvelope(SpotifyAuthSessionStore.SessionEnvelope stored, AuthCompleteRequest request, long nowMs) {
    AuthorizationSecureEnvelope provided = request.secureEnvelope();
    AuthorizationSecureEnvelope expected = stored.envelope();
    if (provided == null || expected == null) {
      throw new AuthorizationValidationException(
          AuthorizationErrorCode.AUTH_ENVELOPE_INVALID,
          "Missing secure envelope."
      );
    }
    if (!expected.envelopeId().equals(provided.envelopeId())) {
      throw new AuthorizationValidationException(
          AuthorizationErrorCode.AUTH_ENVELOPE_INVALID,
          "Envelope id mismatch."
      );
    }
    if (!expected.nonce().equals(provided.nonce())) {
      throw new AuthorizationValidationException(
          AuthorizationErrorCode.AUTH_ENVELOPE_INVALID,
          "Envelope nonce mismatch."
      );
    }
    if (!expected.opaquePayload().equals(provided.opaquePayload())) {
      throw new AuthorizationValidationException(
          AuthorizationErrorCode.AUTH_ENVELOPE_INVALID,
          "Envelope payload mismatch."
      );
    }
    AuthorizationEnvelopeIntegrityVerifier verifier = envelope -> {
      String expectedIntegrity = computeEnvelopeIntegrity(
          envelope.sessionId(),
          envelope.nonce(),
          envelope.ttlSec(),
          envelope.opaquePayload(),
          envelope.algorithm(),
          envelope.issuedAtMs()
      );
      return MessageDigest.isEqual(
          expectedIntegrity.getBytes(StandardCharsets.UTF_8),
          envelope.integrity().getBytes(StandardCharsets.UTF_8)
      );
    };
    AuthorizationValidation.validateEnvelope(
        provided,
        stored.session().sessionId(),
        nowMs,
        verifier,
        (scope, nonce, expiresAtMs, atMs) -> authSessionStore.registerReplayNonce(scope, nonce, expiresAtMs, atMs),
        "spotify-auth-complete"
    );
  }

  private String computeEnvelopeIntegrity(String sessionId,
                                          String nonce,
                                          long ttlSec,
                                          String opaquePayload,
                                          String algorithm,
                                          long issuedAtMs) {
    String secret = authSessionStore.getOrCreateEnvelopeSecret();
    String canonical = sessionId + "|" + nonce + "|" + ttlSec + "|" + opaquePayload + "|" + algorithm + "|" + issuedAtMs;
    return hmacSha256(secret, canonical);
  }

  private static String hmacSha256(String secret, String message) {
    try {
      javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
      javax.crypto.spec.SecretKeySpec key = new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
      mac.init(key);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new RuntimeException("Failed to calculate envelope integrity.", e);
    }
  }

  private static Map<String, Object> tokenSummary(TokenStore.TokenData td) {
    Map<String, Object> tokenStatus = new LinkedHashMap<>();
    tokenStatus.put("tokenPresent", td != null && td.accessToken != null && !td.accessToken.isBlank());
    tokenStatus.put("missingScopes", td == null ? Set.of() : missingRequiredScopes(td.scope));
    tokenStatus.put("expiresAtEpoch", td == null ? 0L : td.expiresAtEpoch);
    return tokenStatus;
  }

  /* ===================== API ===================== */

  public Paging<Track> searchTracks(String q, int limit) {
    ensureFreshToken();
    try {
      SearchTracksRequest req = spotifyApi.searchTracks(q).limit(limit).build();
      return req.execute();
    } catch (Exception e) {
      throw new RuntimeException("Search failed", e);
    }
  }

  public void playTrackUri(String trackUri) {
    ensureFreshToken();
    ensureActiveDevice(); // make sure a device is active before attempting playback

    JsonArray uris = new JsonArray();
    uris.add(new JsonPrimitive(trackUri));

    StartResumeUsersPlaybackRequest.Builder b =
        spotifyApi.startResumeUsersPlayback().uris(uris);

    if (deviceIdEnv != null && !deviceIdEnv.isBlank()) {
      b = b.device_id(deviceIdEnv);
    }
    try {
      b.build().execute();
      System.out.println("Requested playback for " + trackUri +
          (deviceIdEnv != null ? " on device " + deviceIdEnv : ""));
    } catch (Exception e) {
      throw new RuntimeException("Playback failed. Is a Spotify device active and authorized?", e);
    }
  }

  /**
   * List available devices for this user.
   */
  public Device[] listDevices() {
    ensureFreshToken();
    try {
      GetUsersAvailableDevicesRequest req = spotifyApi.getUsersAvailableDevices().build();
      return req.execute();
    } catch (Exception e) {
      throw new RuntimeException("Failed to get devices", e);
    }
  }

  /**
   * Ensure there is an active device. If SPOTIFY_DEVICE_ID is set, activate it; else activate the first available.
   */
  private void ensureActiveDevice() {
    ensureFreshToken();
    try {
      Device[] devices = listDevices();
      if (devices == null || devices.length == 0) {
        throw new IllegalStateException(
            "No devices found. Open Spotify on any device (desktop/mobile/web), log in, and try again.");
      }

      // If a specific device is configured, activate it
      if (deviceIdEnv != null && !deviceIdEnv.isBlank()) {
        boolean exists = false;
        for (Device d : devices) {
          if (deviceIdEnv.equals(d.getId())) {
            exists = true;
            break;
          }
        }
        if (!exists) {
          StringBuilder sb = new StringBuilder("Configured device not found. Available devices:\n");
          for (Device d : devices) {
            sb.append("- ").append(d.getName()).append(" (").append(d.getId()).append(") active=")
                .append(d.getIs_active()).append(" type=").append(d.getType()).append("\n");
          }
          throw new IllegalStateException(sb.toString());
        }
        transferToDevice(deviceIdEnv);
        return;
      }

      // If any device is already active, we're good
      for (Device d : devices) {
        if (Boolean.TRUE.equals(d.getIs_active())) return;
      }

      // Otherwise, activate the first available device
      String targetId = devices[0].getId();
      transferToDevice(targetId);

    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Failed to ensure active device", e);
    }
  }

  private void transferToDevice(String deviceId) throws Exception {
    JsonArray ids = new JsonArray();
    ids.add(new JsonPrimitive(deviceId));

    TransferUsersPlaybackRequest req = spotifyApi
        .transferUsersPlayback(ids)
        .build();
    req.execute();
  }

  /**
   * Convenience: print devices to stdout (ids, names, active).
   */
  public void printDevices() {
    Device[] devices = listDevices();
    System.out.println("Available devices:");
    for (Device d : devices) {
      System.out.printf("- %s  id=%s  active=%s  type=%s%n",
          d.getName(), d.getId(), d.getIs_active(), d.getType());
    }
  }

  private void ensureFreshToken() {
    Optional<TokenStore.TokenData> saved = tokenStore.read();
    if (saved.isEmpty()) {
      throw SpotifyAuthorizationRequiredException.missingToken();
    }
    TokenStore.TokenData td = saved.get();
    Set<String> missingScopes = missingRequiredScopes(td.scope);
    if (!missingScopes.isEmpty()) {
      throw SpotifyAuthorizationRequiredException.missingScopes(missingScopes);
    }
    if (TokenStore.isExpired(td)) {
      try {
        TokenStore.TokenData newTd = refresh(td.refreshToken);
        Set<String> missingRefreshedScopes = missingRequiredScopes(newTd.scope);
        if (!missingRefreshedScopes.isEmpty()) {
          throw SpotifyAuthorizationRequiredException.missingScopes(missingRefreshedScopes);
        }
        tokenStore.write(newTd);
        applyTokens(newTd);
      } catch (Exception e) {
        throw SpotifyAuthorizationRequiredException.refreshFailed();
      }
    } else {
      applyTokens(td);
    }
  }

  public record AuthBeginResult(boolean ok,
                                String errorCode,
                                String message,
                                AuthorizationSession session,
                                String authorizeUrl,
                                Map<String, Object> channelsPayload,
                                AuthorizationRelayPolicy relayPolicy,
                                AuthorizationSecureEnvelope secureEnvelope) {
  }

  public record AuthCompleteRequest(String sessionId,
                                    String state,
                                    String code,
                                    String error,
                                    AuthorizationSecureEnvelope secureEnvelope) {
  }

  public record AuthCompleteResult(boolean ok,
                                   String errorCode,
                                   String message,
                                   AuthorizationSession session,
                                   Map<String, Object> tokenStatus) {
    static AuthCompleteResult ok(AuthorizationSession session, Map<String, Object> tokenStatus) {
      return new AuthCompleteResult(true, null, "Authorization completed.", session, tokenStatus);
    }

    static AuthCompleteResult error(String errorCode, String message) {
      return new AuthCompleteResult(false, errorCode, message, null, null);
    }
  }

  public record AuthSessionResult(boolean ok,
                                  String errorCode,
                                  String message,
                                  AuthorizationSession session) {
    static AuthSessionResult ok(AuthorizationSession session) {
      return new AuthSessionResult(true, null, "OK", session);
    }

    static AuthSessionResult error(String errorCode, String message) {
      return new AuthSessionResult(false, errorCode, message, null);
    }
  }

  private record TokenResponse(
      @SerializedName("access_token") String accessToken,
      @SerializedName("refresh_token") String refreshToken,
      @SerializedName("expires_in") Integer expiresIn,
      @SerializedName("token_type") String tokenType,
      @SerializedName("scope") String scope
  ) {
  }

  public String authStatus() {
    StringBuilder out = new StringBuilder();
    out.append("requiredScopes: ").append(String.join(" ", REQUIRED_SCOPES)).append("\n");
    authSessionStore.getActive().ifPresent(active -> {
      AuthorizationSession s = active.session();
      out.append("activeSessionId: ").append(s.sessionId()).append("\n");
      out.append("activeSessionStatus: ").append(s.status().name()).append("\n");
      out.append("activeSessionExpiresAtMs: ").append(s.expiresAtMs()).append("\n");
    });

    Optional<TokenStore.TokenData> saved = tokenStore.read();
    if (saved.isEmpty()) {
      out.append("tokenPresent: false\n");
      out.append("message: No token stored. Run delegated auth-begin/auth-complete.\n");
      out.append("devices: unknown (not checked)\n");
      return out.toString().trim();
    }

    TokenStore.TokenData td = saved.get();
    Set<String> granted = parseScopes(td.scope);
    Set<String> missing = missingRequiredScopes(td.scope);
    long now = Instant.now().getEpochSecond();
    long remaining = td.expiresAtEpoch - now;

    out.append("tokenPresent: true\n");
    out.append("expiresAtEpoch: ").append(td.expiresAtEpoch).append("\n");
    out.append("expiresInSec: ").append(remaining).append("\n");
    out.append("expired: ").append(TokenStore.isExpired(td)).append("\n");
    out.append("grantedScopes: ").append(td.scope == null ? "" : td.scope).append("\n");
    out.append("missingScopes: ").append(missing.isEmpty() ? "none" : String.join(" ", missing)).append("\n");
    out.append("reconsentRequired: ").append(!missing.isEmpty()).append("\n");

    if (!missing.isEmpty()) {
      out.append("reconsentGuidance: Re-run any Spotify command and approve all requested scopes in consent screen.\n");
    }

    try {
      Device[] devices = listDevices();
      if (devices == null || devices.length == 0) {
        out.append("devices: none\n");
      } else {
        long activeCount = Arrays.stream(devices).filter(d -> Boolean.TRUE.equals(d.getIs_active())).count();
        out.append("devicesCount: ").append(devices.length).append("\n");
        out.append("devicesActive: ").append(activeCount).append("\n");
      }
    } catch (Exception e) {
      out.append("devicesCheck: failed (").append(e.getMessage()).append(")\n");
    }

    if (!granted.isEmpty() && missing.isEmpty()) {
      out.append("status: ready");
    } else if (!missing.isEmpty()) {
      out.append("status: needs_reconsent");
    } else {
      out.append("status: partial");
    }

    return out.toString().trim();
  }

  private static Set<String> parseScopes(String scopeText) {
    if (scopeText == null || scopeText.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(scopeText.trim().split("\\s+"))
        .filter(s -> s != null && !s.isBlank())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static Set<String> missingRequiredScopes(String scopeText) {
    Set<String> granted = parseScopes(scopeText);
    return REQUIRED_SCOPES.stream()
        .filter(s -> !granted.contains(s))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /* ===== simple URL/form helpers ===== */

  private static class UriBuilder {
    private final StringBuilder sb;
    private boolean firstParam = true;

    private UriBuilder(String base) {
      this.sb = new StringBuilder(base == null ? "" : base);
    }

    static UriBuilder of(String base) {
      return new UriBuilder(base);
    }

    UriBuilder add(String k, String v) {
      sb.append(firstParam ? '?' : '&');
      firstParam = false;
      sb.append(URLEncoder.encode(k, java.nio.charset.StandardCharsets.UTF_8))
          .append('=')
          .append(URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8));
      return this;
    }

    String build() {
      return sb.toString(); // no double base
    }

    String formBody() {
      String s = sb.toString();
      return s.startsWith("?") ? s.substring(1) : s;
    }
  }
}
