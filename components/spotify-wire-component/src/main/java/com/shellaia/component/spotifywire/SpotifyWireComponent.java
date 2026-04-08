package com.shellaia.component.spotifywire;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpRequest;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.model.component.ComponentNotReadyException;
import com.social100.todero.common.storage.Storage;
import com.shellaia.component.spotify.core.SpotifyAuthorizationRequiredException;
import com.shellaia.component.spotify.core.SpotifyCommandService;
import com.shellaia.component.spotify.core.SpotifyConfig;
import com.shellaia.component.spotify.core.SpotifyPkceService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static com.social100.todero.common.config.Util.parseDotenv;

/**
 * Spotify component that responds using the AIATP/1.0 wire primitives only:
 * - plain text responses (`Response-Channel` + text body)
 * - optional html events via `emitHtml`
 *
 * No JSON "channels envelope" is emitted by this component (POC).
 */
@AIAController(
    name = "com.shellaia.spotify.wire",
    type = ServerType.AIA,
    visible = true,
    description = "Spotify POC component (wire-only responses)"
)
public class SpotifyWireComponent {
  private static final Gson GSON = new Gson();

  private final Storage storage;
  private final Object initLock = new Object();
  volatile SpotifyPkceService spotifyPkceService;
  volatile SpotifyCommandService spotifyCommandService;

  public SpotifyWireComponent(Storage storage) {
    this.storage = storage;
  }

  @Action(group = "Main", command = "ping", description = "Respond with pong.")
  public Boolean ping(CommandContext context) {
    context.completeText(200, "pong");
    return Boolean.TRUE;
  }

  @Action(group = "Main", command = "auth-begin",
      description = "Start Spotify PKCE auth. Usage: auth-begin [redirect-profile=app|console]")
  public Boolean authBegin(CommandContext context) {
    String args = readBodyArgs(context);
    try {
      Map<String, String> argMap = parseArgMap(args);
      String profile = value(argMap, "redirect-profile", "redirectProfile");
      String owner = value(argMap, "owner", "ownerBinding");

      String host = resolveRequestHost(context);
      String authCompleteTarget = "aia://" + host + "/com.shellaia.spotify.wire/auth-complete";
      SpotifyPkceService.AuthBeginResult result = pkceService().authBegin(profile, null, owner, authCompleteTarget);

      // Give clients something to render if they support the html channel.
      if (result.ctaHtml() != null && !result.ctaHtml().isBlank()) {
        context.emitHtml(result.ctaHtml(), "progress", "html", true);
      }

      StringBuilder out = new StringBuilder();
      out.append(result.message()).append("\n");
      if (result.authorizeUrl() != null && !result.authorizeUrl().isBlank()) {
        out.append("Open this URL in your browser:\n").append(result.authorizeUrl()).append("\n");
      }
      out.append("After approval, you will be redirected and should land on:\n");
      out.append(authCompleteTarget).append("\n");
      context.completeText(result.ok() ? 200 : 500, out.toString().trim());
      return Boolean.TRUE;
    } catch (ComponentNotReadyException e) {
      throw e;
    } catch (Exception e) {
      context.completeText(500, "auth-begin failed: " + safeMsg(e));
      return Boolean.TRUE;
    }
  }

  @Action(group = "Main", command = "auth-complete",
      description = "Complete Spotify PKCE auth. Usage: auth-complete (via deep link) OR auth-complete state=<...> code=<...>")
  public Boolean authComplete(CommandContext context) {
    String args = readBodyArgs(context);
    try {
      SpotifyPkceService.AuthCompleteRequest request = parseAuthCompleteRequest(context, args);
      SpotifyPkceService.AuthCompleteResult result = pkceService().authComplete(request);
      context.completeText(result.ok() ? 200 : 500, result.message());
      return Boolean.TRUE;
    } catch (ComponentNotReadyException e) {
      throw e;
    } catch (Exception e) {
      context.completeText(500, "auth-complete failed: " + safeMsg(e));
      return Boolean.TRUE;
    }
  }

  @Action(group = "Main", command = "play", description = "Play by Spotify URI or search text. Usage: play <spotify:track:... | search term>")
  public Boolean play(CommandContext context) {
    String args = readBodyArgs(context);
    try {
      String msg = commandService().play(args);
      context.completeText(200, msg);
      return Boolean.TRUE;
    } catch (SpotifyAuthorizationRequiredException e) {
      context.completeText(500, e.getMessage());
      return Boolean.TRUE;
    } catch (Exception e) {
      context.completeText(500, "play failed: " + safeMsg(e));
      return Boolean.TRUE;
    }
  }

  @Action(group = "Main", command = "volume", description = "Set volume. Usage: volume <0..150>")
  public Boolean volume(CommandContext context) {
    String args = readBodyArgs(context);
    try {
      int level = Integer.parseInt(args.trim());
      String msg = commandService().volume(level);
      context.completeText(200, msg);
      return Boolean.TRUE;
    } catch (NumberFormatException e) {
      context.completeText(500, "Invalid volume. Usage: volume <0..150>");
      return Boolean.TRUE;
    } catch (SpotifyAuthorizationRequiredException e) {
      context.completeText(500, e.getMessage());
      return Boolean.TRUE;
    } catch (Exception e) {
      context.completeText(500, "volume failed: " + safeMsg(e));
      return Boolean.TRUE;
    }
  }

  @Action(group = "Main", command = "status", description = "Get playback status. Usage: status [all]")
  public Boolean status(CommandContext context) {
    String args = readBodyArgs(context);
    try {
      boolean all = "all".equalsIgnoreCase(args.trim());
      String msg = commandService().status(all);
      context.completeText(200, msg);
      return Boolean.TRUE;
    } catch (SpotifyAuthorizationRequiredException e) {
      context.completeText(500, e.getMessage());
      return Boolean.TRUE;
    } catch (Exception e) {
      context.completeText(500, "status failed: " + safeMsg(e));
      return Boolean.TRUE;
    }
  }

  // ===== init =====

  private void ensureReady() {
    if (spotifyPkceService != null && spotifyCommandService != null) {
      return;
    }
    synchronized (initLock) {
      if (spotifyPkceService != null && spotifyCommandService != null) {
        return;
      }
      Map<String, String> env;
      try {
        byte[] envBytes = storage.readFile(".env");
        env = parseDotenv(envBytes);
      } catch (IOException e) {
        throw new ComponentNotReadyException("missing_configuration",
            "Spotify wire component is not ready: missing required .env configuration.", e);
      } catch (RuntimeException e) {
        throw new ComponentNotReadyException("initialization_failed",
            "Spotify wire component is not ready: failed to parse configuration.", e);
      }

      SpotifyConfig spotifyConfig = SpotifyConfig.builder()
          .clientId(env.get("SPOTIFY_CLIENT_ID"))
          .redirectUrlApp(env.get("SPOTIFY_REDIRECT_URI_APP"))
          .redirectUrlConsole(env.get("SPOTIFY_REDIRECT_URI_CONSOLE"))
          .redirectAllowlist(env.get("SPOTIFY_REDIRECT_URI_ALLOWLIST"))
          .deviceId(env.get("SPOTIFY_DEVICE_ID"))
          .build();
      spotifyPkceService = new SpotifyPkceService(spotifyConfig, storage);
      spotifyCommandService = new SpotifyCommandService(spotifyPkceService, null);
    }
  }

  private SpotifyPkceService pkceService() {
    ensureReady();
    return spotifyPkceService;
  }

  private SpotifyCommandService commandService() {
    ensureReady();
    return spotifyCommandService;
  }

  // ===== parsing helpers =====

  private static String readBodyArgs(CommandContext context) {
    AiatpRequest request = context == null ? null : context.getAiatpRequest();
    String body = request == null || request.getBody() == null
        ? ""
        : AiatpIO.bodyToString(request.getBody(), StandardCharsets.UTF_8);
    return body == null ? "" : body.trim().replaceAll("\\s+", " ");
  }

  private static Map<String, String> parseArgMap(String args) {
    Map<String, String> out = new LinkedHashMap<>();
    if (args == null || args.isBlank()) {
      return out;
    }
    String[] parts = args.trim().split("\\s+");
    for (String p : parts) {
      int idx = p.indexOf('=');
      if (idx <= 0) {
        continue;
      }
      out.put(p.substring(0, idx).trim().toLowerCase(Locale.ROOT), p.substring(idx + 1).trim());
    }
    return out;
  }

  private static String value(Map<String, String> map, String... keys) {
    if (map == null || keys == null) {
      return null;
    }
    for (String k : keys) {
      if (k == null) continue;
      String v = map.get(k.toLowerCase(Locale.ROOT));
      if (v != null && !v.isBlank()) {
        return v.trim();
      }
    }
    return null;
  }

  private static String resolveRequestHost(CommandContext context) {
    AiatpRequest req = context == null ? null : context.getAiatpRequest();
    if (req != null && req.getHeaders() != null) {
      String host = req.getHeaders().getFirst("Host");
      if (host != null && !host.isBlank()) {
        return host.trim();
      }
    }
    return "localhost";
  }

  private static SpotifyPkceService.AuthCompleteRequest parseAuthCompleteRequest(CommandContext context, String rawArgs) {
    AiatpRequest req = context == null ? null : context.getAiatpRequest();

    // Prefer query params (deep link / component callback style).
    String state = req != null ? safeTrim(req.getQueryParam("state")) : "";
    String code = req != null ? safeTrim(req.getQueryParam("code")) : "";
    String error = req != null ? safeTrim(req.getQueryParam("error")) : "";

    // Fallback to body args.
    Map<String, String> argMap = parseArgMap(rawArgs);
    if (state.isEmpty()) state = safeTrim(value(argMap, "state"));
    if (code.isEmpty()) code = safeTrim(value(argMap, "code"));
    if (error.isEmpty()) error = safeTrim(value(argMap, "error"));

    String sessionId = safeTrim(value(argMap, "session-id", "sessionId"));
    if (sessionId.isEmpty() && !state.isEmpty()) {
      sessionId = safeTrim(extractSessionIdFromState(state));
    }
    return new SpotifyPkceService.AuthCompleteRequest(sessionId, state, code, error, null);
  }

  private static String extractSessionIdFromState(String state) {
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(state);
      String json = new String(decoded, StandardCharsets.UTF_8);
      JsonObject obj = GSON.fromJson(json, JsonObject.class);
      if (obj != null && obj.has("session-id")) {
        return safeTrim(obj.get("session-id").getAsString());
      }
      return "";
    } catch (Exception ignored) {
      return "";
    }
  }

  private static String safeTrim(String value) {
    if (value == null) return "";
    String t = value.trim();
    return t.isEmpty() ? "" : t;
  }

  private static String safeMsg(Throwable t) {
    if (t == null) return "";
    String m = t.getMessage();
    return m == null ? t.getClass().getSimpleName() : m;
  }
}

