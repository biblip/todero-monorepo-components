package com.social100.todero.component.spotify;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.runtime.auth.AuthorizationErrorCode;
import com.social100.todero.common.runtime.auth.AuthorizationSecureEnvelope;
import com.social100.todero.common.storage.Storage;
import com.social100.todero.component.spotify.core.SpotifyCommandService;
import com.social100.todero.component.spotify.core.SpotifyConfig;
import com.social100.todero.component.spotify.core.SpotifyPkceService;
import com.social100.todero.processor.EventDefinition;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.PatternSyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.social100.todero.common.config.Util.parseDotenv;

@AIAController(name = "com.shellaia.verbatim.component.spotify",
    type = ServerType.AIA,
    visible = true,
    description = "Spotify playback control component",
    events = SpotifyComponent.SpotifyEvent.class)
public class SpotifyComponent {
  private static final Gson GSON = new Gson();
  private static final String DJ_AGENT = "com.shellaia.verbatim.agent.dj";
  private static final long DEFAULT_NOTIFY_MIN_MS = 2500L;
  private static final Pattern FORMAT_EQUALS = Pattern.compile("(^|\\s)--format=(json|text)(?=\\s|$)", Pattern.CASE_INSENSITIVE);
  private static final Pattern FORMAT_SPACED = Pattern.compile("(^|\\s)--format\\s+(json|text)(?=\\s|$)", Pattern.CASE_INSENSITIVE);

  final SpotifyPkceService spotifyPkceService;
  final SpotifyCommandService spotifyCommandService;
  private final AgentEventForwardingGate forwardingGate = new AgentEventForwardingGate();
  private volatile EventStreamOwner activeEventStreamOwner;
  private final ExecutorService agentNotifyExecutor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "spotify-agent-notify");
    t.setDaemon(true);
    return t;
  });

  public SpotifyComponent(Storage storage) {
    Map<String, String> env;
    try {
      byte[] envBytes = storage.readFile(".env");
      env = parseDotenv(envBytes);
    } catch (IOException e) {
      throw new RuntimeException(e);
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

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "events",
      description = "Start/Stop Spotify playback event stream and optionally inform DJ agent. Usage: events ON|OFF [intervalMs] [notify-agent=true|false] [notify-min-ms=<ms>] [output=typed|legacy] [filter=all|track|playback|device|context] [--format json|text]")
  public Boolean eventsCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    String args = input.args;
    System.out.println("[SPOTIFY] events command args=" + args);
    if (args.isEmpty()) {
      return usage(context, input, "events", "events ON|OFF [intervalMs] [notify-agent=true|false] [notify-min-ms=<ms>] [output=typed|legacy] [filter=all|track|playback|device|context] [--format json|text]");
    }

    String[] parts = args.split("\\s+");
    String mode = parts[0];
    if (!"ON".equalsIgnoreCase(mode) && !"OFF".equalsIgnoreCase(mode)) {
      return usage(context, input, "events", "events ON|OFF [intervalMs] [notify-agent=true|false] [notify-min-ms=<ms>] [output=typed|legacy] [filter=all|track|playback|device|context] [--format json|text]");
    }
    long intervalMs = 1500;
    boolean notifyAgent = false;
    long notifyMinMs = DEFAULT_NOTIFY_MIN_MS;
    SpotifyCommandService.EventOutputMode outputMode = SpotifyCommandService.EventOutputMode.DELTA_TYPED;
    SpotifyCommandService.EventFilter eventFilter = SpotifyCommandService.EventFilter.ALL;

    if (parts.length >= 2) {
      if (parts[1].matches("^\\d+$")) {
        try {
          intervalMs = Long.parseLong(parts[1]);
        } catch (NumberFormatException ignored) {
          return usage(context, input, "events", "events ON|OFF [intervalMs] [notify-agent=true|false] [notify-min-ms=<ms>] [output=typed|legacy] [filter=all|track|playback|device|context] [--format json|text]");
        }
      }
    }
    for (int i = 1; i < parts.length; i++) {
      String p = parts[i];
      if (p.matches("^\\d+$")) {
        if (i != 1) {
          return usage(context, input, "events", "events ON|OFF [intervalMs] [notify-agent=true|false] [notify-min-ms=<ms>] [output=typed|legacy] [filter=all|track|playback|device|context] [--format json|text]");
        }
        continue;
      }
      if (p.toLowerCase().startsWith("notify-agent=")) {
        String v = p.substring("notify-agent=".length());
        if (!"true".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v)) {
          return usage(context, input, "events", "events ON|OFF [intervalMs] [notify-agent=true|false] [notify-min-ms=<ms>] [output=typed|legacy] [filter=all|track|playback|device|context] [--format json|text]");
        }
        notifyAgent = "true".equalsIgnoreCase(v);
        continue;
      }
      if (p.toLowerCase().startsWith("notify-min-ms=")) {
        try {
          notifyMinMs = Long.parseLong(p.substring("notify-min-ms=".length()));
        } catch (NumberFormatException ignored) {
          return usage(context, input, "events", "events ON|OFF [intervalMs] [notify-agent=true|false] [notify-min-ms=<ms>] [output=typed|legacy] [filter=all|track|playback|device|context] [--format json|text]");
        }
        continue;
      }
      if (p.toLowerCase().startsWith("output=")) {
        String v = p.substring("output=".length()).toLowerCase();
        if ("typed".equals(v)) {
          outputMode = SpotifyCommandService.EventOutputMode.DELTA_TYPED;
        } else if ("legacy".equals(v)) {
          outputMode = SpotifyCommandService.EventOutputMode.LEGACY_TEXT;
        } else {
          return usage(context, input, "events", "events ON|OFF [intervalMs] [notify-agent=true|false] [notify-min-ms=<ms>] [output=typed|legacy] [filter=all|track|playback|device|context] [--format json|text]");
        }
        continue;
      }
      if (p.toLowerCase().startsWith("filter=")) {
        String v = p.substring("filter=".length()).toUpperCase();
        try {
          eventFilter = SpotifyCommandService.EventFilter.valueOf(v);
        } catch (IllegalArgumentException ignored) {
          return usage(context, input, "events", "events ON|OFF [intervalMs] [notify-agent=true|false] [notify-min-ms=<ms>] [output=typed|legacy] [filter=all|track|playback|device|context] [--format json|text]");
        }
        continue;
      }
      return usage(context, input, "events", "events ON|OFF [intervalMs] [notify-agent=true|false] [notify-min-ms=<ms>] [output=typed|legacy] [filter=all|track|playback|device|context] [--format json|text]");
    }
    notifyMinMs = Math.max(0L, Math.min(120000L, notifyMinMs));
    final boolean notifyAgentFinal = notifyAgent;
    final long notifyMinMsFinal = notifyMinMs;
    final SpotifyCommandService.EventOutputMode outputModeFinal = outputMode;
    final SpotifyCommandService.EventFilter eventFilterFinal = eventFilter;

    if ("OFF".equalsIgnoreCase(mode)) {
      forwardingGate.reset();
      EventStreamOwner previousOwner = this.activeEventStreamOwner;
      this.activeEventStreamOwner = null;
      if (previousOwner != null) {
        long ageMs = Math.max(0L, System.currentTimeMillis() - previousOwner.startedAtMs);
        System.out.println("[SPOTIFY] event-stream owner-stop ownerContextId=" + previousOwner.ownerContextId
            + " reason=events_off ageMs=" + ageMs + " already_stopped=false");
      } else {
        System.out.println("[SPOTIFY] event-stream owner-stop ownerContextId=none"
            + " reason=events_off ageMs=0 already_stopped=true");
      }
    } else {
      EventStreamOwner owner = new EventStreamOwner(
          context.getId(),
          System.currentTimeMillis(),
          intervalMs,
          notifyAgentFinal,
          outputModeFinal,
          eventFilterFinal
      );
      this.activeEventStreamOwner = owner;
      System.out.println("[SPOTIFY] event-stream owner-start ownerContextId=" + owner.ownerContextId
          + " intervalMs=" + owner.intervalMs
          + " notifyAgent=" + owner.notifyAgent
          + " output=" + owner.outputMode.name().toLowerCase()
          + " filter=" + owner.filter.name().toLowerCase());
    }

    String result = spotifyCommandService.events(mode, intervalMs, new SpotifyCommandService.EventsConfig(outputModeFinal, eventFilterFinal), event -> {
      EventStreamOwner owner = this.activeEventStreamOwner;
      if (owner == null) {
        return;
      }
      boolean hasManager = context.getComponentManager() != null;
      boolean hasSourceId = context.getId() != null && !context.getId().trim().isEmpty();
      boolean hasHttpRequest = context.getHttpRequest() != null;
      boolean detachedLike = !hasManager || !hasHttpRequest;
      if (detachedLike && !owner.localOnlyMode) {
        owner.localOnlyMode = true;
        System.out.println("[SPOTIFY] event-stream owner-detached ownerContextId=" + owner.ownerContextId
            + " action=degrade_to_local hasManager=" + hasManager
            + " hasSourceId=" + hasSourceId
            + " hasHttpRequest=" + hasHttpRequest);
      }
      String eventJson = renderPlaybackEventAsJson(event);
      System.out.println("[SPOTIFY] events callback payload=" + eventJson);
      if (!owner.localOnlyMode) {
        if (outputModeFinal == SpotifyCommandService.EventOutputMode.LEGACY_TEXT) {
          context.event(SpotifyEvent.PLAYBACK_STATUS.name(), event.legacyStatus());
        } else {
          context.event(SpotifyEvent.PLAYBACK_EVENT_V2.name(), eventJson);
        }
      }
      if (!notifyAgentFinal) {
        return;
      }
      if (owner.localOnlyMode) {
        System.out.println("[SPOTIFY] notify-agent skipped: event-stream is in local-only mode");
        return;
      }
      long now = System.currentTimeMillis();
      if (!forwardingGate.shouldForward(event.fingerprint(), now, notifyMinMsFinal)) {
        System.out.println("[SPOTIFY] notify-agent skipped by forwarding gate");
        return;
      }
      final String payload = outputModeFinal == SpotifyCommandService.EventOutputMode.LEGACY_TEXT
          ? "event_type=state_change"
          + "&source=spotify_component"
          + "&kind=playback_status"
          + "&message=" + urlEncode(event.legacyStatus())
          + "&at_ms=" + now
          : renderAgentReactPayloadAsJson(event);
      System.out.println("[SPOTIFY] notify-agent dispatch payload=" + payload);
      System.out.println("[SPOTIFY] notify-agent context-health hasManager=" + hasManager
          + " hasSourceId=" + hasSourceId
          + " hasHttpRequest=" + hasHttpRequest);
      if (!hasManager) {
        System.out.println("[SPOTIFY] notify-agent skipped: command context has no componentManager (tool call must propagate parent context).");
        return;
      }
      agentNotifyExecutor.submit(() -> {
        try {
          CommandContext internal = CommandContext.builder()
              .httpRequest(AiatpIO.HttpRequest.newBuilder("ACTION", "/" + DJ_AGENT + "/react")
                  .body(AiatpIO.Body.ofString(payload, StandardCharsets.UTF_8))
                  .build())
              .consumer(ignored -> {
              })
              .build();
          context.execute(DJ_AGENT, "react", internal);
          System.out.println("[SPOTIFY] notify-agent execute sent to DJ agent");
        } catch (Exception e) {
          System.out.println("[SPOTIFY] notify-agent execute skipped reason=execute_failed type="
              + e.getClass().getSimpleName() + " message=" + e.getMessage());
        }
      });
    });

    System.out.println("[SPOTIFY] events command result=" + result);
    return respond(context, input, "events", args, result);
  }

  private static final class EventStreamOwner {
    final String ownerContextId;
    final long startedAtMs;
    final long intervalMs;
    final boolean notifyAgent;
    final SpotifyCommandService.EventOutputMode outputMode;
    final SpotifyCommandService.EventFilter filter;
    volatile boolean localOnlyMode;

    EventStreamOwner(String ownerContextId,
                     long startedAtMs,
                     long intervalMs,
                     boolean notifyAgent,
                     SpotifyCommandService.EventOutputMode outputMode,
                     SpotifyCommandService.EventFilter filter) {
      this.ownerContextId = ownerContextId;
      this.startedAtMs = startedAtMs;
      this.intervalMs = intervalMs;
      this.notifyAgent = notifyAgent;
      this.outputMode = outputMode;
      this.filter = filter;
      this.localOnlyMode = false;
    }
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "auth-status",
      description = "Show Spotify auth diagnostics (required/granted scopes, token expiry, and device state). Usage: auth-status [--format json|text]")
  public Boolean authStatusCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    return respond(context, input, "auth-status", input.args, this.spotifyPkceService.authStatus());
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "auth-begin",
      description = "Start delegated Spotify auth session. Usage: auth-begin [redirect-profile=app|console|explicit] [redirect-uri=<uri>] [owner=<binding>] [--format json|text]")
  public Boolean authBeginCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    try {
      Map<String, String> args = parseArgMap(input.args);
      String profile = value(args, "redirect-profile", "redirectProfile");
      String redirectUri = value(args, "redirect-uri", "redirectUri");
      String owner = value(args, "owner", "ownerBinding");

      SpotifyPkceService.AuthBeginResult result = spotifyPkceService.authBegin(profile, redirectUri, owner);
      if (input.format == ResponseFormat.TEXT) {
        String text = result.message()
            + "\nsessionId: " + result.session().sessionId()
            + "\nexpiresAtMs: " + result.session().expiresAtMs()
            + "\nauthorizeUrl: " + result.authorizeUrl();
        context.response(text);
        return true;
      }
      context.emitStatus(result.message(), "progress");
      if (result.ctaHtml() != null && !result.ctaHtml().isBlank()) {
        context.emitWebviewHtml(result.ctaHtml(), "progress", "html", true);
      }
      Map<String, Object> authPayload = new LinkedHashMap<>();
      authPayload.put("provider", "spotify");
      authPayload.put("session", sessionMap(result.session()));
      authPayload.put("authorizeUrl", result.authorizeUrl());
      authPayload.put("requiredScopes", spotifyPkceService.requiredScopes());
      authPayload.put("secureEnvelope", envelopeMap(result.secureEnvelope()));
      authPayload.put("relayPolicy", relayPolicyMap(result.relayPolicy()));
      authPayload.put("openExternally", true);
      authPayload.put("ctaLabel", "Authorize Spotify");
      context.emitAuthJson(GSON.toJson(authPayload), "final");
      return true;
    } catch (Exception e) {
      return respond(context, input, "auth-begin", input.args, "auth-begin failed [error_code=" + inferAuthErrorCode(e) + "]: " + redact(e.getMessage()), false, inferAuthErrorCode(e));
    }
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "auth-complete",
      description = "Complete delegated Spotify auth session. Usage: auth-complete session-id=<id> state=<state> code=<code> [secure envelope fields] [--format json|text]")
  public Boolean authCompleteCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    try {
      SpotifyPkceService.AuthCompleteRequest request = parseAuthCompleteRequest(input.args);
      SpotifyPkceService.AuthCompleteResult result = spotifyPkceService.authComplete(request);
      if (input.format == ResponseFormat.TEXT) {
        context.response(result.message());
        return true;
      }
      if (result.ok()) {
        context.emitStatus(result.message(), "final");
      } else {
        context.emitProtocolError(result.message());
      }
      return true;
    } catch (Exception e) {
      return respond(context, input, "auth-complete", input.args, "auth-complete failed [error_code=" + inferAuthErrorCode(e) + "]: " + redact(e.getMessage()), false, inferAuthErrorCode(e));
    }
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "auth-session",
      description = "Query delegated auth session status. Usage: auth-session [session-id=<id>] [--format json|text]")
  public Boolean authSessionCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    Map<String, String> args = parseArgMap(input.args);
    String sessionId = value(args, "session-id", "sessionId");
    SpotifyPkceService.AuthSessionResult result = spotifyPkceService.authSession(sessionId);
    if (input.format == ResponseFormat.TEXT) {
      context.response(result.message() + (result.session() == null ? "" : ("\nstatus: " + result.session().status().name())));
      return true;
    }
    if (result.ok()) {
      context.emitStatus(result.message(), "final");
    } else {
      context.emitProtocolError(result.message());
    }
    return true;
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "auth-cancel",
      description = "Cancel delegated auth session. Usage: auth-cancel [session-id=<id>] [--format json|text]")
  public Boolean authCancelCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    Map<String, String> args = parseArgMap(input.args);
    String sessionId = value(args, "session-id", "sessionId");
    SpotifyPkceService.AuthSessionResult result = spotifyPkceService.authCancel(sessionId);
    if (input.format == ResponseFormat.TEXT) {
      context.response(result.message());
      return true;
    }
    if (result.ok()) {
      context.emitStatus(result.message(), "final");
    } else {
      context.emitProtocolError(result.message());
    }
    return true;
  }

  private static String urlEncode(String value) {
    try {
      return URLEncoder.encode(value, StandardCharsets.UTF_8);
    } catch (Exception e) {
      return "";
    }
  }

  private static String renderPlaybackEventAsJson(SpotifyCommandService.PlaybackEventData event) {
    StringBuilder json = new StringBuilder(768);
    json.append('{');
    json.append("\"schema\":\"spotify.playback.event.v2\",");
    json.append("\"event_type\":").append(quoteJson(event.eventType())).append(',');
    json.append("\"source\":\"spotify_component\",");
    json.append("\"kind\":").append(quoteJson(event.kind())).append(',');
    json.append("\"at_ms\":").append(event.atMs()).append(',');
    json.append("\"summary\":").append(quoteJson(event.summary())).append(',');
    json.append("\"state\":").append(renderPlaybackStateAsJson(event.state())).append(',');
    json.append("\"delta\":").append(renderDeltaAsJson(event.delta())).append(',');
    json.append("\"legacy_status\":").append(quoteJson(event.legacyStatus()));
    json.append('}');
    return json.toString();
  }

  private static String renderAgentReactPayloadAsJson(SpotifyCommandService.PlaybackEventData event) {
    StringBuilder json = new StringBuilder(768);
    json.append('{');
    json.append("\"event_type\":").append(quoteJson(event.eventType())).append(',');
    json.append("\"source\":\"spotify_component\",");
    json.append("\"kind\":").append(quoteJson(event.kind())).append(',');
    json.append("\"message\":").append(quoteJson(event.summary())).append(',');
    json.append("\"at_ms\":").append(event.atMs()).append(',');
    json.append("\"schema\":\"spotify.playback.event.v2\",");
    json.append("\"playback\":").append(renderPlaybackStateAsJson(event.state())).append(',');
    json.append("\"delta\":").append(renderDeltaAsJson(event.delta()));
    json.append('}');
    return json.toString();
  }

  private static String renderDeltaAsJson(Map<String, String> delta) {
    if (delta == null || delta.isEmpty()) {
      return "{}";
    }
    StringBuilder json = new StringBuilder(256);
    json.append('{');
    boolean first = true;
    for (Map.Entry<String, String> e : delta.entrySet()) {
      if (!first) {
        json.append(',');
      }
      first = false;
      json.append(quoteJson(e.getKey())).append(':').append(quoteJson(e.getValue()));
    }
    json.append('}');
    return json.toString();
  }

  private static String renderPlaybackStateAsJson(SpotifyCommandService.PlaybackSnapshot state) {
    if (state == null) {
      return "{}";
    }
    return "{"
        + "\"is_playing\":" + state.playing() + ","
        + "\"track_uri\":" + quoteJson(state.trackUri()) + ","
        + "\"track_name\":" + quoteJson(state.trackName()) + ","
        + "\"artist_name\":" + quoteJson(state.artistName()) + ","
        + "\"context_uri\":" + quoteJson(state.contextUri()) + ","
        + "\"context_type\":" + quoteJson(state.contextType()) + ","
        + "\"device_id\":" + quoteJson(state.deviceId()) + ","
        + "\"device_name\":" + quoteJson(state.deviceName()) + ","
        + "\"volume_percent\":" + (state.volumePercent() == null ? "null" : state.volumePercent()) + ","
        + "\"shuffle_state\":" + (state.shuffleState() == null ? "null" : state.shuffleState()) + ","
        + "\"repeat_state\":" + quoteJson(state.repeatState()) + ","
        + "\"progress_ms\":" + (state.progressMs() == null ? "null" : state.progressMs()) + ","
        + "\"duration_ms\":" + (state.durationMs() == null ? "null" : state.durationMs())
        + "}";
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "move",
      description = "Moves the playback to the specified time. Usage: move <HH:MM:SS|MM:SS|SS> [--format json|text]")
  public Boolean moveCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    final String moveTo = input.args;
    if (moveTo == null || moveTo.isEmpty()) {
      return usage(context, input, "move", "move <HH:MM:SS|MM:SS|SS> [--format json|text]");
    }
    return respond(context, input, "move", moveTo, this.spotifyCommandService.move(moveTo));
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "mute",
      description = "Toggles the mute state of the playback if valid media is loaded. Usage: mute [--format json|text]")
  public Boolean muteCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    return respond(context, input, "mute", input.args, this.spotifyCommandService.muteToggle());
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "pause",
      description = "Pauses the playback if it is currently playing. Usage: pause [--format json|text]")
  public Boolean pauseCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    return respond(context, input, "pause", input.args, this.spotifyCommandService.pause());
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "play",
      description = "Plays the specified media file. If no file is specified, resumes the current one. Usage: play [media] [--format json|text]")
  public Boolean playCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    final String mediaPathToPlay = input.args;
    System.out.println("[SPOTIFY] play command received media=" + mediaPathToPlay);
    String result = this.spotifyCommandService.play(mediaPathToPlay);
    System.out.println("[SPOTIFY] play command result=" + result);
    return respond(context, input, "play", mediaPathToPlay, result);
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "skip",
      description = "Skips the playback forward or backward by the specified number of seconds. Usage: skip <+/-seconds> [--format json|text]")
  public Boolean skipCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    final String skipTimeString = input.args;
    if (skipTimeString == null || skipTimeString.isEmpty()) {
      return usage(context, input, "skip", "skip <+/-seconds> [--format json|text]");
    }
    try {
      int skipTime = Integer.parseInt(skipTimeString);
      return respond(context, input, "skip", skipTimeString, this.spotifyCommandService.skipSeconds(skipTime));
    } catch (NumberFormatException e) {
      return usage(context, input, "skip", "skip <+/-seconds> [--format json|text]");
    }
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "status",
      description = "Displays the current status of Spotify's player. Use 'status all' for all media info available. Usage: status [all] [--format json|text]")
  public Boolean statusCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    final String parameter = input.args;
    boolean all = ("all".equalsIgnoreCase(parameter));
    return respond(context, input, "status", parameter, this.spotifyCommandService.status(all));
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "stop",
      description = "Stops the playback if it is currently active. Usage: stop [--format json|text]")
  public Boolean stopCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    return respond(context, input, "stop", input.args, this.spotifyCommandService.stop());
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "volume",
      description = "Sets the volume to a specified level between 0 and 150. Usage: volume <level> [--format json|text]")
  public Boolean volumeCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    final String volumeString = input.args;
    if (volumeString == null || volumeString.isEmpty()) {
      return usage(context, input, "volume", "volume <level> [--format json|text]");
    }
    try {
      int volume = Integer.parseInt(volumeString);
      return respond(context, input, "volume", volumeString, this.spotifyCommandService.volume(volume));
    } catch (NumberFormatException e) {
      return usage(context, input, "volume", "volume <level> [--format json|text]");
    }
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "volume-down",
      description = "Decreases the volume by 5 units. Usage: volume-down [--format json|text]")
  public Boolean volumeDownCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    return respond(context, input, "volume-down", input.args, this.spotifyCommandService.volumeDown());
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "volume-up",
      description = "Increases the volume by 5 units. Usage: volume-up [--format json|text]")
  public Boolean volumeUpCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    return respond(context, input, "volume-up", input.args, this.spotifyCommandService.volumeUp());
  }

  @Action(group = SpotifyCommandService.PLAYLIST_GROUP,
      command = "playlist-remove",
      description = "Remove current paying media from the playlist Usage: playlist-remove [--format json|text], if there is no current media playing then does nothing")
  public Boolean playlistRemove(CommandContext context) {
    ParsedInput input = parseInput(context);
    return respond(context, input, "playlist-remove", input.args, this.spotifyCommandService.playlistRemoveCurrentIfFromPlaylist());
  }

  @Action(group = SpotifyCommandService.PLAYLIST_GROUP,
      command = "playlist-next",
      description = "Play the next media in the playlist. Usage: playlist-next [--format json|text]")
  public Boolean playlistNext(CommandContext context) {
    ParsedInput input = parseInput(context);
    return respond(context, input, "playlist-next", input.args, this.spotifyCommandService.playlistNext());
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "devices",
      description = "List available Spotify devices. Usage: devices [--format json|text]")
  public Boolean devicesCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    return respond(context, input, "devices", input.args, this.spotifyCommandService.devices());
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "metrics",
      description = "Show Spotify command counters and latency metrics. Usage: metrics [--format json|text]")
  public Boolean metricsCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    return respond(context, input, "metrics", input.args, this.spotifyCommandService.metricsReport());
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "select-device",
      description = "Transfer playback to a specific device. Usage: select-device <deviceId> [--format json|text]")
  public Boolean selectDeviceCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    String body = input.args;
    if (body.isEmpty()) {
      return usage(context, input, "select-device", "select-device <deviceId> [--format json|text]");
    }
    return respond(context, input, "select-device", body, this.spotifyCommandService.selectDevice(body));
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "queue-add",
      description = "Add track or episode URI to playback queue. Usage: queue-add <spotify:track|episode:uri> [--format json|text]")
  public Boolean queueAddCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    String body = input.args;
    if (body.isEmpty()) {
      return usage(context, input, "queue-add", "queue-add <spotify:track|episode:uri> [--format json|text]");
    }
    return respond(context, input, "queue-add", body, this.spotifyCommandService.queueAdd(body));
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "queue",
      description = "List currently playing item and queued items. Usage: queue [--format json|text]")
  public Boolean queueCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    return respond(context, input, "queue", input.args, this.spotifyCommandService.queueList());
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "previous",
      description = "Skip to previous track in playback context. Usage: previous [--format json|text]")
  public Boolean previousCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    return respond(context, input, "previous", input.args, this.spotifyCommandService.previous());
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "recently-played",
      description = "List recently played tracks. Usage: recently-played [limit<=50] [--format json|text]")
  public Boolean recentlyPlayedCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    String body = input.args;
    int limit = 10;
    if (!body.isEmpty()) {
      try {
        limit = Integer.parseInt(body);
      } catch (NumberFormatException e) {
        return usage(context, input, "recently-played", "recently-played [limit<=50] [--format json|text]");
      }
    }
    return respond(context, input, "recently-played", body, this.spotifyCommandService.recentlyPlayed(limit));
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "top-tracks",
      description = "List user's top tracks. Usage: top-tracks [limit<=50] [short_term|medium_term|long_term] [--format json|text]")
  public Boolean topTracksCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    String body = input.args;
    int limit = 10;
    String timeRange = "medium_term";
    if (!body.isEmpty()) {
      String[] parts = body.split("\\s+");
      for (String p : parts) {
        if (p.matches("^\\d+$")) {
          limit = Integer.parseInt(p);
        } else {
          timeRange = p;
        }
      }
    }
    return respond(context, input, "top-tracks", body, this.spotifyCommandService.topTracks(limit, timeRange));
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "top-artists",
      description = "List user's top artists. Usage: top-artists [limit<=50] [short_term|medium_term|long_term] [--format json|text]")
  public Boolean topArtistsCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    String body = input.args;
    int limit = 10;
    String timeRange = "medium_term";
    if (!body.isEmpty()) {
      String[] parts = body.split("\\s+");
      for (String p : parts) {
        if (p.matches("^\\d+$")) {
          limit = Integer.parseInt(p);
        } else {
          timeRange = p;
        }
      }
    }
    return respond(context, input, "top-artists", body, this.spotifyCommandService.topArtists(limit, timeRange));
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "recommend",
      description = "Get recommendations using a seed track query/URI/id. Usage: recommend <seedTrackQueryOrUriOrId> [limit<=20] [--format json|text]")
  public Boolean recommendCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    String body = input.args;
    if (body.isEmpty()) {
      return usage(context, input, "recommend", "recommend <seedTrackQueryOrUriOrId> [limit<=20] [--format json|text]");
    }
    String[] parts = body.split("\\s+");
    int limit = 8;
    int endExclusive = parts.length;
    if (parts.length > 1 && parts[parts.length - 1].matches("^\\d+$")) {
      limit = Integer.parseInt(parts[parts.length - 1]);
      endExclusive = parts.length - 1;
    }
    StringBuilder seed = new StringBuilder();
    for (int i = 0; i < endExclusive; i++) {
      if (i > 0) seed.append(' ');
      seed.append(parts[i]);
    }
    if (seed.toString().isBlank()) {
      return usage(context, input, "recommend", "recommend <seedTrackQueryOrUriOrId> [limit<=20] [--format json|text]");
    }
    return respond(context, input, "recommend", body, this.spotifyCommandService.recommendByTrackSeed(seed.toString(), limit));
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "suggest",
      description = "Suggest songs for a mood/theme using resilient Spotify search. Usage: suggest <themeOrQuery> [limit<=12] [--format json|text]")
  public Boolean suggestCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    String body = input.args;
    if (body.isEmpty()) {
      return usage(context, input, "suggest", "suggest <themeOrQuery> [limit<=12] [--format json|text]");
    }

    String[] parts = body.split("\\s+");
    int limit = 8;
    int endExclusive = parts.length;
    if (parts.length > 1 && parts[parts.length - 1].matches("^\\d+$")) {
      limit = Integer.parseInt(parts[parts.length - 1]);
      endExclusive = parts.length - 1;
    }
    StringBuilder theme = new StringBuilder();
    for (int i = 0; i < endExclusive; i++) {
      if (i > 0) theme.append(' ');
      theme.append(parts[i]);
    }
    if (theme.toString().isBlank()) {
      return usage(context, input, "suggest", "suggest <themeOrQuery> [limit<=12] [--format json|text]");
    }
    return respond(context, input, "suggest", body, this.spotifyCommandService.suggestByTheme(theme.toString(), limit));
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "shuffle",
      description = "Enable or disable shuffle mode. Usage: shuffle on|off [--format json|text]")
  public Boolean shuffleCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    String body = input.args;
    if (body.isEmpty()) {
      return usage(context, input, "shuffle", "shuffle on|off [--format json|text]");
    }
    String normalized = body.toLowerCase();
    if (!"on".equals(normalized) && !"off".equals(normalized)) {
      return usage(context, input, "shuffle", "shuffle on|off [--format json|text]");
    }
    return respond(context, input, "shuffle", body, this.spotifyCommandService.shuffle("on".equals(normalized)));
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "repeat",
      description = "Set repeat mode. Usage: repeat off|track|context [--format json|text]")
  public Boolean repeatCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    String body = input.args;
    if (body.isEmpty()) {
      return usage(context, input, "repeat", "repeat off|track|context [--format json|text]");
    }
    String normalized = body.toLowerCase();
    if (!"off".equals(normalized) && !"track".equals(normalized) && !"context".equals(normalized)) {
      return usage(context, input, "repeat", "repeat off|track|context [--format json|text]");
    }
    return respond(context, input, "repeat", body, this.spotifyCommandService.repeat(normalized));
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "like",
      description = "Save the currently playing track to your library. Usage: like [--format json|text]")
  public Boolean likeCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    return respond(context, input, "like", input.args, this.spotifyCommandService.likeCurrentTrack());
  }

  @Action(group = SpotifyCommandService.PLAYLIST_GROUP,
      command = "playlists",
      description = "List current user's playlists. Usage: playlists [limit<=50] [offset>=0] [--format json|text]")
  public Boolean playlistsCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    String body = input.args;
    int limit = 20;
    int offset = 0;
    if (!body.isEmpty()) {
      String[] parts = body.split("\\s+");
      try {
        if (parts.length >= 1 && !parts[0].isBlank()) {
          limit = Integer.parseInt(parts[0]);
        }
        if (parts.length >= 2 && !parts[1].isBlank()) {
          offset = Integer.parseInt(parts[1]);
        }
        if (parts.length > 2) {
          return usage(context, input, "playlists", "playlists [limit<=50] [offset>=0] [--format json|text]");
        }
      } catch (NumberFormatException e) {
        return usage(context, input, "playlists", "playlists [limit<=50] [offset>=0] [--format json|text]");
      }
    }
    return respond(context, input, "playlists", body, this.spotifyCommandService.playlists(limit, offset));
  }

  @Action(group = SpotifyCommandService.PLAYLIST_GROUP,
      command = "playlist-list",
      description = "List tracks in playlist. Usage: playlist-list <playlistId> [limit] [--format json|text]")
  public Boolean playlistListCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    String body = input.args;
    if (body.isEmpty()) {
      return usage(context, input, "playlist-list", "playlist-list <playlistId> [limit] [--format json|text]");
    }

    String[] parts = body.split("\\s+");
    String playlistId = parts[0];
    int limit = 20;
    if (parts.length >= 2) {
      try {
        limit = Integer.parseInt(parts[1]);
      } catch (NumberFormatException e) {
        return usage(context, input, "playlist-list", "playlist-list <playlistId> [limit] [--format json|text]");
      }
    }

    return respond(context, input, "playlist-list", body, this.spotifyCommandService.playlistList(playlistId, limit));
  }

  @Action(group = SpotifyCommandService.PLAYLIST_GROUP,
      command = "playlist-add",
      description = "Add one or more track URIs to playlist. Usage: playlist-add <playlistId> <trackUri> [trackUri ...] [--format json|text]")
  public Boolean playlistAddCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    String body = input.args;
    if (body.isEmpty()) {
      return usage(context, input, "playlist-add", "playlist-add <playlistId> <trackUri> [trackUri ...] [--format json|text]");
    }

    String[] parts = body.split("\\s+");
    if (parts.length < 2) {
      return usage(context, input, "playlist-add", "playlist-add <playlistId> <trackUri> [trackUri ...] [--format json|text]");
    }

    String playlistId = parts[0];
    List<String> uris = new ArrayList<>();
    for (int i = 1; i < parts.length; i++) {
      if (!parts[i].isBlank()) {
        uris.add(parts[i]);
      }
    }
    if (uris.isEmpty()) {
      return usage(context, input, "playlist-add", "playlist-add <playlistId> <trackUri> [trackUri ...] [--format json|text]");
    }

    return respond(context, input, "playlist-add", body, this.spotifyCommandService.playlistAdd(playlistId, uris));
  }

  @Action(group = SpotifyCommandService.PLAYLIST_GROUP,
      command = "playlist-add-current",
      description = "Search a song title and add the exact title match to the current playlist context. Usage: playlist-add-current <songTitle> [--format json|text]")
  public Boolean playlistAddCurrentCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    String body = input.args;
    if (body.isBlank()) {
      return usage(context, input, "playlist-add-current", "playlist-add-current <songTitle> [--format json|text]");
    }
    return respond(context, input, "playlist-add-current", body, this.spotifyCommandService.playlistAddCurrentBySongTitle(body));
  }

  @Action(group = SpotifyCommandService.PLAYLIST_GROUP,
      command = "playlist-create",
      description = "Create a playlist for current user. Usage: playlist-create <name> [public=true|false] [description=<text>] [--format json|text]")
  public Boolean playlistCreateCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    String body = input.args;
    if (body.isEmpty()) {
      return usage(context, input, "playlist-create", "playlist-create <name> [public=true|false] [description=<text>] [--format json|text]");
    }
    boolean isPublic = false;
    String description = "";
    StringBuilder name = new StringBuilder();
    for (String token : body.split("\\s+")) {
      if (token.startsWith("public=")) {
        String raw = token.substring("public=".length()).toLowerCase();
        if (!"true".equals(raw) && !"false".equals(raw)) {
          return usage(context, input, "playlist-create", "playlist-create <name> [public=true|false] [description=<text>] [--format json|text]");
        }
        isPublic = "true".equals(raw);
      } else if (token.startsWith("description=")) {
        description = token.substring("description=".length()).replace('_', ' ');
      } else {
        if (name.length() > 0) name.append(' ');
        name.append(token);
      }
    }
    if (name.toString().isBlank()) {
      return usage(context, input, "playlist-create", "playlist-create <name> [public=true|false] [description=<text>] [--format json|text]");
    }
    return respond(context, input, "playlist-create", body, this.spotifyCommandService.playlistCreate(name.toString(), isPublic, description));
  }

  @Action(group = SpotifyCommandService.PLAYLIST_GROUP,
      command = "playlist-reorder",
      description = "Reorder playlist items by range start and insert index. Usage: playlist-reorder <playlistId> <rangeStart> <insertBefore> [rangeLength<=100] [--format json|text]")
  public Boolean playlistReorderCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    String body = input.args;
    String[] parts = body.split("\\s+");
    if (parts.length < 3) {
      return usage(context, input, "playlist-reorder", "playlist-reorder <playlistId> <rangeStart> <insertBefore> [rangeLength<=100] [--format json|text]");
    }
    try {
      String playlistId = parts[0];
      int rangeStart = Integer.parseInt(parts[1]);
      int insertBefore = Integer.parseInt(parts[2]);
      int rangeLength = parts.length >= 4 ? Integer.parseInt(parts[3]) : 1;
      return respond(context, input, "playlist-reorder", body, this.spotifyCommandService.playlistReorder(playlistId, rangeStart, insertBefore, rangeLength));
    } catch (NumberFormatException e) {
      return usage(context, input, "playlist-reorder", "playlist-reorder <playlistId> <rangeStart> <insertBefore> [rangeLength<=100] [--format json|text]");
    }
  }

  @Action(group = SpotifyCommandService.PLAYLIST_GROUP,
      command = "playlist-remove-pos",
      description = "Remove playlist item at position index. Usage: playlist-remove-pos <playlistId> <position> [--format json|text]")
  public Boolean playlistRemovePosCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    String body = input.args;
    String[] parts = body.split("\\s+");
    if (parts.length != 2) {
      return usage(context, input, "playlist-remove-pos", "playlist-remove-pos <playlistId> <position> [--format json|text]");
    }
    try {
      return respond(context, input, "playlist-remove-pos", body,
          this.spotifyCommandService.playlistRemovePosition(parts[0], Integer.parseInt(parts[1])));
    } catch (NumberFormatException e) {
      return usage(context, input, "playlist-remove-pos", "playlist-remove-pos <playlistId> <position> [--format json|text]");
    }
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "playlist-play",
      description = "Play a playlist by id or URI. Usage: playlist-play <playlistId|spotify:playlist:uri> [offset] [--format json|text]")
  public Boolean playlistPlayCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    String body = input.args;
    if (body.isEmpty()) {
      return usage(context, input, "playlist-play", "playlist-play <playlistId|spotify:playlist:uri> [offset] [--format json|text]");
    }
    String[] parts = body.split("\\s+");
    String playlistIdOrUri = parts[0];
    int offset = 0;
    if (parts.length >= 2) {
      try {
        offset = Integer.parseInt(parts[1]);
      } catch (NumberFormatException e) {
        return usage(context, input, "playlist-play", "playlist-play <playlistId|spotify:playlist:uri> [offset] [--format json|text]");
      }
    }
    if (parts.length > 2) {
      return usage(context, input, "playlist-play", "playlist-play <playlistId|spotify:playlist:uri> [offset] [--format json|text]");
    }
    return respond(context, input, "playlist-play", body, this.spotifyCommandService.playlistPlay(playlistIdOrUri, offset));
  }

  @Action(group = SpotifyCommandService.MAIN_GROUP,
      command = "play-context",
      description = "Play context URI at optional offset. Usage: play-context <spotify:album|artist|playlist:uri> [offset] [--format json|text]")
  public Boolean playContextCommand(CommandContext context) {
    ParsedInput input = parseInput(context);
    String body = input.args;
    if (body.isEmpty()) {
      return usage(context, input, "play-context", "play-context <spotify:album|artist|playlist:uri> [offset] [--format json|text]");
    }

    String[] parts = body.split("\\s+");
    String contextUri = parts[0];
    int offset = 0;
    if (parts.length >= 2) {
      try {
        offset = Integer.parseInt(parts[1]);
      } catch (NumberFormatException e) {
        return usage(context, input, "play-context", "play-context <spotify:album|artist|playlist:uri> [offset] [--format json|text]");
      }
    }

    return respond(context, input, "play-context", body, this.spotifyCommandService.playContextWithOffset(contextUri, offset));
  }

  private static SpotifyPkceService.AuthCompleteRequest parseAuthCompleteRequest(String raw) {
    if (raw == null || raw.isBlank()) {
      return new SpotifyPkceService.AuthCompleteRequest(null, null, null, null, null);
    }
    String trimmed = raw.trim();
    if (trimmed.startsWith("{")) {
      JsonObject o = GSON.fromJson(trimmed, JsonObject.class);
      String sessionId = readString(o, "sessionId", "session-id");
      String state = readString(o, "state");
      String code = readString(o, "code");
      String error = readString(o, "error");
      AuthorizationSecureEnvelope envelope = parseEnvelopeFromJson(o.getAsJsonObject("secureEnvelope"));
      return new SpotifyPkceService.AuthCompleteRequest(sessionId, state, code, error, envelope);
    }
    Map<String, String> args = parseArgMap(trimmed);
    String sessionId = value(args, "session-id", "sessionId");
    String state = value(args, "state");
    String code = value(args, "code");
    String error = value(args, "error");
    AuthorizationSecureEnvelope envelope = parseEnvelopeFromArgs(args, sessionId);
    return new SpotifyPkceService.AuthCompleteRequest(sessionId, state, code, error, envelope);
  }

  private static AuthorizationSecureEnvelope parseEnvelopeFromJson(JsonObject envelopeJson) {
    if (envelopeJson == null || envelopeJson.entrySet().isEmpty()) {
      return null;
    }
    String envelopeId = readString(envelopeJson, "envelopeId", "envelope-id");
    String sessionId = readString(envelopeJson, "sessionId", "session-id");
    String nonce = readString(envelopeJson, "nonce");
    long ttlSec = readLong(envelopeJson, "ttlSec", "ttl-sec");
    String opaquePayload = readString(envelopeJson, "opaquePayload", "opaque-payload");
    String integrity = readString(envelopeJson, "integrity");
    String algorithm = readString(envelopeJson, "algorithm");
    long issuedAtMs = readLong(envelopeJson, "issuedAtMs", "issued-at-ms");
    if (envelopeId == null || sessionId == null || nonce == null || ttlSec <= 0 || opaquePayload == null || integrity == null || algorithm == null || issuedAtMs <= 0) {
      return null;
    }
    return new AuthorizationSecureEnvelope(envelopeId, sessionId, nonce, ttlSec, opaquePayload, integrity, algorithm, issuedAtMs);
  }

  private static AuthorizationSecureEnvelope parseEnvelopeFromArgs(Map<String, String> args, String sessionIdFallback) {
    String envelopeId = value(args, "envelope-id", "envelopeId");
    String sessionId = value(args, "session-id", "sessionId");
    if (sessionId == null) {
      sessionId = sessionIdFallback;
    }
    String nonce = value(args, "nonce");
    String ttl = value(args, "ttl-sec", "ttlSec");
    String opaquePayload = value(args, "opaque-payload", "opaquePayload");
    String integrity = value(args, "integrity");
    String algorithm = value(args, "algorithm");
    String issued = value(args, "issued-at-ms", "issuedAtMs");
    if (envelopeId == null || sessionId == null || nonce == null || ttl == null || opaquePayload == null || integrity == null || algorithm == null || issued == null) {
      return null;
    }
    try {
      return new AuthorizationSecureEnvelope(
          envelopeId,
          sessionId,
          nonce,
          Long.parseLong(ttl),
          opaquePayload,
          integrity,
          algorithm,
          Long.parseLong(issued)
      );
    } catch (Exception e) {
      return null;
    }
  }

  private static Map<String, String> parseArgMap(String argsRaw) {
    Map<String, String> out = new LinkedHashMap<>();
    if (argsRaw == null || argsRaw.isBlank()) {
      return out;
    }
    String trimmed = argsRaw.trim();
    if (trimmed.startsWith("{")) {
      JsonObject o = GSON.fromJson(trimmed, JsonObject.class);
      if (o != null) {
        for (Map.Entry<String, JsonElement> entry : o.entrySet()) {
          if (entry.getValue() == null || entry.getValue().isJsonNull()) {
            continue;
          }
          out.put(entry.getKey(), entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString() : entry.getValue().toString());
        }
      }
      return out;
    }
    String[] parts = trimmed.split("\\s+");
    for (String part : parts) {
      int idx = part.indexOf('=');
      if (idx <= 0 || idx >= part.length() - 1) {
        continue;
      }
      String key = part.substring(0, idx).trim();
      String value = part.substring(idx + 1).trim();
      if (!key.isEmpty() && !value.isEmpty()) {
        out.put(key, value);
      }
    }
    return out;
  }

  private static String value(Map<String, String> map, String... keys) {
    if (map == null || keys == null) {
      return null;
    }
    for (String key : keys) {
      if (key == null) continue;
      String value = map.get(key);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static String readString(JsonObject object, String... keys) {
    if (object == null) return null;
    for (String key : keys) {
      if (key == null || !object.has(key)) continue;
      JsonElement value = object.get(key);
      if (value != null && !value.isJsonNull()) {
        String text = value.getAsString();
        if (text != null && !text.isBlank()) {
          return text;
        }
      }
    }
    return null;
  }

  private static long readLong(JsonObject object, String... keys) {
    String value = readString(object, keys);
    if (value == null) return 0L;
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  private static Map<String, Object> sessionMap(com.social100.todero.common.runtime.auth.AuthorizationSession session) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("sessionId", session.sessionId());
    out.put("provider", session.provider());
    out.put("ownerBinding", session.ownerBinding());
    out.put("state", session.state());
    out.put("status", session.status().name());
    out.put("createdAtMs", session.createdAtMs());
    out.put("expiresAtMs", session.expiresAtMs());
    out.put("completedAtMs", session.completedAtMs());
    out.put("errorCode", session.errorCode());
    return out;
  }

  private static Map<String, Object> envelopeMap(AuthorizationSecureEnvelope envelope) {
    if (envelope == null) {
      return null;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("envelopeId", envelope.envelopeId());
    out.put("sessionId", envelope.sessionId());
    out.put("nonce", envelope.nonce());
    out.put("ttlSec", envelope.ttlSec());
    out.put("opaquePayload", envelope.opaquePayload());
    out.put("integrity", envelope.integrity());
    out.put("algorithm", envelope.algorithm());
    out.put("issuedAtMs", envelope.issuedAtMs());
    return out;
  }

  private static Map<String, Object> relayPolicyMap(com.social100.todero.common.runtime.auth.AuthorizationRelayPolicy relayPolicy) {
    if (relayPolicy == null) {
      return null;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("opaque", relayPolicy.opaque());
    out.put("inspectAllowed", relayPolicy.inspectAllowed());
    out.put("maxTtlSec", relayPolicy.maxTtlSec());
    return out;
  }

  private static String inferAuthErrorCode(Exception e) {
    String msg = e == null ? null : e.getMessage();
    if (msg == null || msg.isBlank()) {
      return "execution_failed";
    }
    String lower = msg.toLowerCase();
    if (lower.contains("redirect")) return AuthorizationErrorCode.AUTH_REDIRECT_URI_DISALLOWED;
    if (lower.contains("state")) return AuthorizationErrorCode.AUTH_STATE_MISMATCH;
    if (lower.contains("session")) return AuthorizationErrorCode.AUTH_SESSION_MISSING;
    return "execution_failed";
  }

  private static String redact(String message) {
    if (message == null) {
      return "";
    }
    return message
        .replaceAll("(?i)code=[^\\s&]+", "code=<redacted>")
        .replaceAll("(?i)access_token[^\\s]*", "access_token=<redacted>");
  }

  private ParsedInput parseInput(CommandContext context) {
    String body = AiatpIO.bodyToString(context.getHttpRequest().body(), StandardCharsets.UTF_8);
    String args = body == null ? "" : body.trim();
    ResponseFormat format = ResponseFormat.TEXT;

    Matcher mEq = FORMAT_EQUALS.matcher(args);
    if (mEq.find()) {
      format = "json".equalsIgnoreCase(mEq.group(2)) ? ResponseFormat.JSON : ResponseFormat.TEXT;
      args = (args.substring(0, mEq.start()) + " " + args.substring(mEq.end())).trim();
    }

    Matcher mSp = FORMAT_SPACED.matcher(args);
    if (mSp.find()) {
      format = "json".equalsIgnoreCase(mSp.group(2)) ? ResponseFormat.JSON : ResponseFormat.TEXT;
      args = (args.substring(0, mSp.start()) + " " + args.substring(mSp.end())).trim();
    }

    args = args.replaceAll("\\s+", " ").trim();
    return new ParsedInput(args, format);
  }

  private Boolean usage(CommandContext context, ParsedInput input, String command, String usageText) {
    return respond(context, input, command, input.args, "Usage: " + usageText, false, "invalid_arguments");
  }

  private Boolean respond(CommandContext context, ParsedInput input, String command, String args, String message) {
    boolean ok = inferSuccess(message);
    String errorCode = ok ? null : inferErrorCode(message);
    return respond(context, input, command, args, message, ok, errorCode);
  }

  private Boolean respond(CommandContext context,
                          ParsedInput input,
                          String command,
                          String args,
                          String message,
                          boolean ok,
                          String errorCode) {
    String safeMessage = message == null ? "" : message;
    if (input.format == ResponseFormat.TEXT) {
      context.response(safeMessage);
      return true;
    }
    if (!ok) {
      context.emitProtocolError(safeMessage);
      return true;
    }
    if ("suggest".equalsIgnoreCase(command == null ? "" : command.trim())) {
      context.emitStatus(safeMessage, "progress");
      context.emitWebviewHtml(buildSuggestHtml(safeMessage), "final", "html", true);
      return true;
    }
    context.emitStatus(safeMessage, "final");
    return true;
  }

  private static boolean shouldEmitFailureMeta(String message) {
    return containsFailureHint(message);
  }

  private static boolean containsFailureHint(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    String lowered = text.toLowerCase(Locale.ROOT);
    return lowered.contains("cannot") || lowered.contains("can't") || lowered.contains("unable")
        || lowered.contains("no devices") || lowered.contains("not handled");
  }

  private static String failureErrorCode(String errorCode) {
    if (errorCode == null || errorCode.isBlank()) {
      return "agent_capability_mismatch";
    }
    String normalized = errorCode.trim().toLowerCase(Locale.ROOT);
    if ("invalid_arguments".equals(normalized)) {
      return "agent_missing_args";
    }
    if ("execution_failed".equals(normalized)) {
      return "agent_capability_mismatch";
    }
    return normalized;
  }

  private static String buildSuggestHtml(String message) {
    if (message == null || message.isBlank()) {
      return null;
    }
    String[] lines = message.split("\\R");
    Pattern itemPattern;
    try {
      itemPattern = Pattern.compile("^\\s*\\d+\\)\\s+(.+?)\\s+\\[uri=(spotify:track:[A-Za-z0-9]+)]\\s*$");
    } catch (PatternSyntaxException e) {
      return null;
    }

    String title = "Suggestions";
    List<String[]> items = new ArrayList<>();
    for (String raw : lines) {
      String line = raw == null ? "" : raw.trim();
      if (line.isEmpty()) {
        continue;
      }
      if (line.toLowerCase().startsWith("suggestions for")) {
        title = line;
      }
      Matcher m = itemPattern.matcher(line);
      if (m.matches()) {
        items.add(new String[]{m.group(1).trim(), m.group(2).trim()});
      }
    }
    if (items.isEmpty()) {
      return null;
    }

    StringBuilder rows = new StringBuilder();
    for (String[] item : items) {
      String label = escapeHtml(item[0]);
      String action = escapeHtml("play " + item[1]);
      rows.append("<li style=\"margin-bottom:10px;\">")
          .append("<div style=\"font-size:14px; margin-bottom:6px;\">").append(label).append("</div>")
          .append("<button style=\"background:#1db954;color:#04110a;border:none;border-radius:8px;padding:8px 10px;font-weight:700;\" ")
          .append("onclick=\"Android.runAction('").append(action).append("')\">Play</button>")
          .append("</li>");
    }

    return "<html>"
        + "<body style=\"font-family: sans-serif; padding: 12px; margin: 0; background: #0d1117; color: #e6edf3;\">"
        + "<div style=\"border:1px solid #30363d; border-radius:10px; padding:12px; background:#161b22;\">"
        + "<div style=\"font-size:12px; color:#8b949e; margin-bottom:6px;\">Spotify</div>"
        + "<div style=\"font-size:18px; font-weight:700; margin-bottom:10px;\">"
        + escapeHtml(title)
        + "</div>"
        + "<ol style=\"padding-left:18px; margin:0;\">"
        + rows
        + "</ol>"
        + "</div>"
        + "</body>"
        + "</html>";
  }

  private static String escapeHtml(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private static boolean inferSuccess(String message) {
    String text = message == null ? "" : message.trim();
    if (text.isEmpty()) {
      return true;
    }
    if (text.startsWith("Usage:")) {
      return false;
    }
    if (text.startsWith("Error:")) {
      return false;
    }
    if (text.startsWith("java.lang.")) {
      return false;
    }
    return !text.matches("(?i)^[a-z0-9-]+ failed(?:\\s+\\[error_code=[a-z0-9_\\-]+])?:.*");
  }

  private static String inferErrorCode(String message) {
    String text = message == null ? "" : message.trim();
    if (text.startsWith("Usage:") || text.startsWith("Error:")) {
      return "invalid_arguments";
    }
    if (text.startsWith("java.lang.")) {
      return "execution_failed";
    }
    Matcher marker = Pattern.compile("\\[error_code=([a-z0-9_\\-]+)]", Pattern.CASE_INSENSITIVE).matcher(text);
    if (marker.find()) {
      return marker.group(1).toLowerCase();
    }
    Matcher m = Pattern.compile("(?i)^([a-z0-9-]+) failed(?:\\s+\\[error_code=[a-z0-9_\\-]+])?:.*").matcher(text);
    if (m.matches()) {
      return m.group(1).toLowerCase() + "_failed";
    }
    return "execution_failed";
  }

  private static String quoteJson(String value) {
    if (value == null) {
      return "null";
    }
    StringBuilder out = new StringBuilder(value.length() + 2);
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"':
          out.append("\\\"");
          break;
        case '\\':
          out.append("\\\\");
          break;
        case '\b':
          out.append("\\b");
          break;
        case '\f':
          out.append("\\f");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        default:
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
      }
    }
    out.append('"');
    return out.toString();
  }

  private enum ResponseFormat {
    JSON,
    TEXT
  }

  private record ParsedInput(String args, ResponseFormat format) {
  }

  public enum SpotifyEvent implements EventDefinition {
    status("Built-in status channel event"),
    html("Built-in html channel event"),
    auth("Built-in auth channel event"),
    error("Built-in error channel event"),
    PLAYBACK_STATUS("Periodic Spotify playback status event (legacy text mode)"),
    PLAYBACK_EVENT_V2("Typed Spotify playback event stream with state and delta payload");

    private final String description;

    SpotifyEvent(String description) {
      this.description = description;
    }

    @Override
    public String getDescription() {
      return description;
    }
  }
}
