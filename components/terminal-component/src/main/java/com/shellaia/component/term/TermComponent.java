package com.shellaia.component.term;

import com.shellaia.component.term.nativeffi.NativeLibraryLoader;
import com.shellaia.component.term.nativeffi.NativeTerm;
import com.shellaia.component.term.nativeffi.ToderoTermLibrary;
import com.shellaia.component.term.util.Json;
import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpResponse;
import com.social100.todero.common.aiatpio.AiatpRequest;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.Util;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.processor.EventDefinition;
import com.social100.todero.common.storage.Storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@AIAController(
    name = "com.shellaia.term",
    type = ServerType.AIA,
    visible = true,
    description = "Virtual terminal sessions backed by todero-term native library.",
    events = TermComponent.TermEvent.class
)
public class TermComponent {
  static final String MAIN_GROUP = "Main";
  private static final String HTML_TEMPLATE_PATH = "com/shellaia/component/term/component.html";
  private static final String XTERM_JS_PATH = "com/shellaia/component/term/web/xterm.js";
  private static final String XTERM_CSS_PATH = "com/shellaia/component/term/web/xterm.css";
  private static final String HTML_TEMPLATE = loadResourceText(HTML_TEMPLATE_PATH);
  private static final String XTERM_JS = loadResourceText(XTERM_JS_PATH);
  private static final String XTERM_CSS = loadResourceText(XTERM_CSS_PATH);

  private final Storage storage;
  private final Map<String, String> dotenv;
  private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
    Thread t = new Thread(r, "term-component-bg");
    t.setDaemon(true);
    return t;
  });

  private volatile ScheduledFuture<?> cleanupTask;
  private volatile EventStreamOwner activeEventStreamOwner;

  public TermComponent(Storage storage) {
    this.storage = storage;
    this.dotenv = loadDotenv(storage);
    startCleanupLoop();
  }

  @Action(group = MAIN_GROUP, command = "html", description = "Render an HTML page for interactive terminal sessions. Usage: html")
  public Boolean html(CommandContext context) {
    AiatpRequest request = context == null ? null : context.getAiatpRequest();
    String host = resolveRequestHost(request);
    String title = "Terminal Component";
    String heading = host == null || host.isBlank() ? title : title + " (" + escapeHtml(host) + ")";
    String generatedAt = Instant.now().toString();
    String html = HTML_TEMPLATE
        .replace("${TITLE}", escapeHtml(title))
        .replace("${HEADING}", escapeHtml(heading))
        .replace("${GENERATED_AT}", escapeHtml(generatedAt))
        .replace("${XTERM_CSS}", XTERM_CSS)
        .replace("${XTERM_JS}", XTERM_JS);
    context.complete(buildTextResponse(200, html, "text/html; charset=utf-8"));
    return true;
  }

  @Action(group = MAIN_GROUP, command = "open", description = "Open a terminal session. Body is JSON: {name?, cwd, program?, argv?, env?, cols?, rows?, buffer_max_bytes?, read_chunk_max_bytes?, screen_mode?, screen_scrollback_lines?, screen_diff_history?, screen_retain_raw_buffer?, idleTimeoutMs?}")
  public Boolean open(CommandContext context) {
    String body = requestBody(context);
    if (body == null || body.isBlank()) {
      respondJson(context, 200, Json.obj(Map.of(
          "ok", false,
          "message", "Usage: open (body JSON)"
      )));
      return false;
    }

    String id = "t_" + UUID.randomUUID();
    String requestedName = extractJsonString(body, "name");
    String sessionName = requestedName == null || requestedName.isBlank() ? id : requestedName.trim();

    // Validate allowlisted cwd before creating.
    String cwd = extractJsonString(body, "cwd");
    if (cwd == null || cwd.isBlank()) {
      respondJson(context, 400, Json.obj(Map.of("ok", false, "message", "cwd is required")));
      return false;
    }
    Path allowedRoot = resolveAllowedRootForCwd(cwd);
    if (allowedRoot == null) {
      respondJson(context, 400, Json.obj(Map.of(
          "ok", false,
          "message", "cwd is not allowed by TODERO_TERM_ALLOWED_WORKSPACES"
      )));
      return false;
    }

    long idleTimeoutMs = parseLong(extractJsonNumber(body, "idleTimeoutMs"), defaultIdleTimeoutMs());
    idleTimeoutMs = clamp(idleTimeoutMs, 10_000L, 24L * 60L * 60L * 1000L);

    try {
      Path preparedCwd = prepareCwd(cwd, allowedRoot);
      String configJson = mergeConfig(body, preparedCwd.toString());
      NativeTerm nativeTerm = ensureNative();
      var handle = nativeTerm.createFromJson(configJson);
      Session session = new Session(id, sessionName, handle, nativeTerm, idleTimeoutMs);
      sessions.put(id, session);
      session.screenEnabled = nativeTerm.screenEnabled(handle);

      Map<String, Object> resp = new LinkedHashMap<>();
      resp.put("ok", true);
      resp.put("id", id);
      resp.put("name", session.name);
      resp.put("seqStart", 0);
      resp.put("seqEnd", 0);
      resp.put("screenEnabled", session.screenEnabled);
      respondJson(context, 200, Json.obj(resp));
      return true;
    } catch (Exception e) {
      respondJson(context, 500, Json.obj(Map.of("ok", false, "message", e.toString())));
      return false;
    }
  }

  @Action(group = MAIN_GROUP, command = "sessions", description = "List active sessions and runtime metadata. Usage: sessions")
  public Boolean sessions(CommandContext context) {
    long now = System.currentTimeMillis();
    EventStreamOwner owner = activeEventStreamOwner;
    List<Map<String, Object>> items = new ArrayList<>();
    for (Session session : sessions.values()) {
      Map<String, Object> item = new LinkedHashMap<>();
      long createdAtMs = session.createdAtMs;
      long lastActivityMs = session.lastActivityMs.get();
      item.put("id", session.id);
      item.put("name", session.name);
      item.put("screenEnabled", session.screenEnabled);
      item.put("createdAtMs", createdAtMs);
      item.put("ageMs", Math.max(0L, now - createdAtMs));
      item.put("lastActivityMs", lastActivityMs);
      item.put("idleForMs", Math.max(0L, now - lastActivityMs));
      item.put("idleTimeoutMs", session.idleTimeoutMs);
      item.put("idleExpiresInMs", Math.max(0L, session.idleTimeoutMs - (now - lastActivityMs)));
      item.put("eventStreamActive", owner != null && session.id.equals(owner.sessionId));
      if (owner != null && session.id.equals(owner.sessionId)) {
        item.put("eventMode", owner.eventMode.wireValue);
        item.put("eventIntervalMs", owner.intervalMs);
      }
      if (session.screenEnabled) {
        try {
          item.putAll(screenInfoFields(session.nativeTerm.screenInfo(session.handle)));
        } catch (RuntimeException e) {
          item.put("screenInfoError", e.getMessage());
        }
      }
      items.add(item);
    }
    items.sort(Comparator.comparing(m -> String.valueOf(m.get("id"))));

    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("ok", true);
    resp.put("count", items.size());
    resp.put("sessions", items);
    respondJson(context, 200, Json.obj(resp));
    return true;
  }

  @Action(group = MAIN_GROUP, command = "write", description = "Write to session stdin. Usage: write <id> <dataB64>")
  public Boolean write(CommandContext context) {
    Args args = Args.parse(requestBody(context));
    if (args.size() < 2) {
      respondJson(context, 200, Json.obj(Map.of("ok", false, "message", "Usage: write <id> <dataB64>")));
      return false;
    }
    String id = args.at(0);
    Session s = sessions.get(id);
    if (s == null) {
      respondJson(context, 404, Json.obj(Map.of("ok", false, "message", "unknown session id")));
      return false;
    }

    String b64 = args.at(1);
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(b64);
    } catch (IllegalArgumentException e) {
      respondJson(context, 400, Json.obj(Map.of("ok", false, "message", "invalid base64")));
      return false;
    }

    try {
      s.touch();
      s.nativeTerm.write(s.handle, bytes);
      respondJson(context, 200, Json.obj(Map.of("ok", true, "written", bytes.length)));
      return true;
    } catch (NativeTerm.NativeTermException e) {
      respondJson(context, 500, Json.obj(errorJson(e)));
      return false;
    }
  }

  @Action(group = MAIN_GROUP, command = "read", description = "Read output since seq. Usage: read <id> [sinceSeq] [maxBytes]")
  public Boolean read(CommandContext context) {
    Args args = Args.parse(requestBody(context));
    if (args.isEmpty()) {
      respondJson(context, 200, Json.obj(Map.of("ok", false, "message", "Usage: read <id> [sinceSeq] [maxBytes]")));
      return false;
    }
    String id = args.first();
    Session s = sessions.get(id);
    if (s == null) {
      respondJson(context, 404, Json.obj(Map.of("ok", false, "message", "unknown session id")));
      return false;
    }

    long sinceSeq = args.longAt(1, 0L);
    int maxBytes = (int) clamp(args.longAt(2, defaultReadChunkMaxBytes()), 1, maxReadChunkMaxBytes());

    try {
      s.touch();
      NativeTerm.ReadResult rr = s.nativeTerm.read(s.handle, sinceSeq, maxBytes);
      Map<String, Object> resp = new LinkedHashMap<>();
      resp.put("ok", true);
      resp.put("sinceSeq", sinceSeq);
      resp.put("newSeq", rr.newSeq());
      resp.put("len", rr.bytes().length);
      resp.put("dataB64", NativeTerm.b64(rr.bytes()));
      respondJson(context, 200, Json.obj(resp));
      return true;
    } catch (NativeTerm.NativeTermException e) {
      respondJson(context, 500, Json.obj(errorJson(e)));
      return false;
    }
  }

  @Action(group = MAIN_GROUP, command = "buffer", description = "Snapshot retained ring buffer. Usage: buffer <id> [maxBytes]")
  public Boolean buffer(CommandContext context) {
    Args args = Args.parse(requestBody(context));
    if (args.isEmpty()) {
      respondJson(context, 200, Json.obj(Map.of("ok", false, "message", "Usage: buffer <id> [maxBytes]")));
      return false;
    }
    String id = args.first();
    Session s = sessions.get(id);
    if (s == null) {
      respondJson(context, 404, Json.obj(Map.of("ok", false, "message", "unknown session id")));
      return false;
    }

    int maxBytes = (int) clamp(args.longAt(1, defaultBufferMaxBytes()), 1, defaultBufferMaxBytes());

    try {
      s.touch();
      NativeTerm.BufferSnapshot snap = s.nativeTerm.buffer(s.handle, maxBytes);
      Map<String, Object> resp = new LinkedHashMap<>();
      resp.put("ok", true);
      resp.put("seqStart", snap.seqStart());
      resp.put("seqEnd", snap.seqEnd());
      resp.put("len", snap.bytes().length);
      resp.put("dataB64", NativeTerm.b64(snap.bytes()));
      respondJson(context, 200, Json.obj(resp));
      return true;
    } catch (NativeTerm.NativeTermException e) {
      respondJson(context, 500, Json.obj(errorJson(e)));
      return false;
    }
  }

  @Action(group = MAIN_GROUP, command = "screen_info", description = "Get screen metadata. Usage: screen_info <id>")
  public Boolean screenInfo(CommandContext context) {
    Args args = Args.parse(requestBody(context));
    if (args.isEmpty()) {
      respondJson(context, 200, Json.obj(Map.of("ok", false, "message", "Usage: screen_info <id>")));
      return false;
    }
    Session s = sessions.get(args.first());
    if (s == null) {
      respondJson(context, 404, Json.obj(Map.of("ok", false, "message", "unknown session id")));
      return false;
    }
    try {
      s.touch();
      NativeTerm.ScreenInfo info = s.nativeTerm.screenInfo(s.handle);
      respondJson(context, 200, Json.obj(screenInfoResponse(info)));
      return true;
    } catch (NativeTerm.NativeTermException e) {
      respondJson(context, 500, Json.obj(errorJson(e)));
      return false;
    }
  }

  @Action(group = MAIN_GROUP, command = "screen_text", description = "Get visible screen as UTF-8 text. Usage: screen_text <id> [maxBytes]")
  public Boolean screenText(CommandContext context) {
    return screenPayloadAction(context, ScreenPayloadMode.TEXT);
  }

  @Action(group = MAIN_GROUP, command = "screen_formatted", description = "Get visible screen as ANSI-formatted bytes. Usage: screen_formatted <id> [maxBytes]")
  public Boolean screenFormatted(CommandContext context) {
    return screenPayloadAction(context, ScreenPayloadMode.FORMATTED);
  }

  @Action(group = MAIN_GROUP, command = "screen_diff", description = "Get ANSI screen diff since frame id. Usage: screen_diff <id> [sinceFrameId] [maxBytes]")
  public Boolean screenDiff(CommandContext context) {
    Args args = Args.parse(requestBody(context));
    if (args.isEmpty()) {
      respondJson(context, 200, Json.obj(Map.of("ok", false, "message", "Usage: screen_diff <id> [sinceFrameId] [maxBytes]")));
      return false;
    }
    Session s = sessions.get(args.first());
    if (s == null) {
      respondJson(context, 404, Json.obj(Map.of("ok", false, "message", "unknown session id")));
      return false;
    }
    long sinceFrameId = args.longAt(1, 0L);
    int maxBytes = (int) clamp(args.longAt(2, defaultScreenMaxBytes()), 1, maxScreenMaxBytes());
    try {
      s.touch();
      NativeTerm.ScreenDiffPayload payload = s.nativeTerm.screenDiff(s.handle, sinceFrameId, maxBytes);
      Map<String, Object> resp = new LinkedHashMap<>();
      resp.put("ok", true);
      resp.put("id", s.id);
      resp.put("mode", "screen-diff");
      resp.put("dataB64", NativeTerm.b64(payload.bytes()));
      resp.put("len", payload.bytes().length);
      resp.put("frameIdStart", payload.diffInfo().frameIdStart());
      resp.put("frameIdEnd", payload.diffInfo().frameIdEnd());
      resp.put("truncated", payload.diffInfo().truncated());
      resp.putAll(screenInfoFields(payload.info()));
      respondJson(context, 200, Json.obj(resp));
      return true;
    } catch (NativeTerm.NativeTermException e) {
      respondJson(context, 500, Json.obj(errorJson(e)));
      return false;
    }
  }

  @Action(group = MAIN_GROUP, command = "screen_scrollback", description = "Get screen scrollback text as UTF-8. Usage: screen_scrollback <id> [maxBytes]")
  public Boolean screenScrollback(CommandContext context) {
    Args args = Args.parse(requestBody(context));
    if (args.isEmpty()) {
      respondJson(context, 200, Json.obj(Map.of("ok", false, "message", "Usage: screen_scrollback <id> [maxBytes]")));
      return false;
    }
    Session s = sessions.get(args.first());
    if (s == null) {
      respondJson(context, 404, Json.obj(Map.of("ok", false, "message", "unknown session id")));
      return false;
    }
    int maxBytes = (int) clamp(args.longAt(1, defaultScreenMaxBytes()), 1, maxScreenMaxBytes());
    try {
      s.touch();
      byte[] bytes = s.nativeTerm.screenScrollbackText(s.handle, maxBytes);
      respondJson(context, 200, Json.obj(Map.of(
          "ok", true,
          "id", s.id,
          "mode", "screen-scrollback",
          "len", bytes.length,
          "dataB64", NativeTerm.b64(bytes)
      )));
      return true;
    } catch (NativeTerm.NativeTermException e) {
      respondJson(context, 500, Json.obj(errorJson(e)));
      return false;
    }
  }

  @Action(group = MAIN_GROUP, command = "resize", description = "Resize terminal. Usage: resize <id> <cols> <rows>")
  public Boolean resize(CommandContext context) {
    Args args = Args.parse(requestBody(context));
    if (args.size() < 3) {
      respondJson(context, 200, Json.obj(Map.of("ok", false, "message", "Usage: resize <id> <cols> <rows>")));
      return false;
    }
    String id = args.at(0);
    Session s = sessions.get(id);
    if (s == null) {
      respondJson(context, 404, Json.obj(Map.of("ok", false, "message", "unknown session id")));
      return false;
    }

    int cols = (int) clamp(args.longAt(1, 80), 10, 400);
    int rows = (int) clamp(args.longAt(2, 24), 5, 200);

    try {
      s.touch();
      s.nativeTerm.resize(s.handle, cols, rows);
      respondJson(context, 200, Json.obj(Map.of("ok", true)));
      return true;
    } catch (NativeTerm.NativeTermException e) {
      respondJson(context, 500, Json.obj(errorJson(e)));
      return false;
    }
  }

  @Action(group = MAIN_GROUP, command = "set_buffer_limit", description = "Set ring buffer max bytes. Usage: set_buffer_limit <id> <maxBytes>")
  public Boolean setBufferLimit(CommandContext context) {
    Args args = Args.parse(requestBody(context));
    if (args.size() < 2) {
      respondJson(context, 200, Json.obj(Map.of("ok", false, "message", "Usage: set_buffer_limit <id> <maxBytes>")));
      return false;
    }
    String id = args.at(0);
    Session s = sessions.get(id);
    if (s == null) {
      respondJson(context, 404, Json.obj(Map.of("ok", false, "message", "unknown session id")));
      return false;
    }
    long maxBytes = clamp(args.longAt(1, defaultBufferMaxBytes()), 1, 32L * 1024L * 1024L);

    try {
      s.touch();
      s.nativeTerm.setBufferLimit(s.handle, maxBytes);
      respondJson(context, 200, Json.obj(Map.of("ok", true, "maxBytes", maxBytes)));
      return true;
    } catch (NativeTerm.NativeTermException e) {
      respondJson(context, 500, Json.obj(errorJson(e)));
      return false;
    }
  }

  @Action(group = MAIN_GROUP, command = "wait_exit", description = "Wait for process exit. Usage: wait_exit <id> [timeoutMs]")
  public Boolean waitExit(CommandContext context) {
    Args args = Args.parse(requestBody(context));
    if (args.isEmpty()) {
      respondJson(context, 200, Json.obj(Map.of("ok", false, "message", "Usage: wait_exit <id> [timeoutMs]")));
      return false;
    }
    String id = args.first();
    Session s = sessions.get(id);
    if (s == null) {
      respondJson(context, 404, Json.obj(Map.of("ok", false, "message", "unknown session id")));
      return false;
    }
    int timeoutMs = (int) clamp(args.longAt(1, 0L), 0, 120_000L);

    try {
      s.touch();
      NativeTerm.WaitExitResult r = s.nativeTerm.waitExit(s.handle, timeoutMs);
      respondJson(context, 200, Json.obj(Map.of(
          "ok", true,
          "exited", r.exited(),
          "exitCode", r.exitCode()
      )));
      return true;
    } catch (NativeTerm.NativeTermException e) {
      respondJson(context, 500, Json.obj(errorJson(e)));
      return false;
    }
  }

  @Action(group = MAIN_GROUP, command = "close", description = "Close and free session. Usage: close <id>")
  public Boolean close(CommandContext context) {
    return closeLike(context, false);
  }

  @Action(group = MAIN_GROUP, command = "kill", description = "Kill and free session. Usage: kill <id>")
  public Boolean kill(CommandContext context) {
    return closeLike(context, true);
  }

  @Action(group = MAIN_GROUP, command = "events", description = "Start/Stop output event stream. Usage: events ON|OFF <id> [intervalMs] [maxBytes] [raw|screen-diff|screen-formatted|screen-text]")
  public Boolean events(CommandContext context) {
    Args args = Args.parse(requestBody(context));
    if (args.size() < 2) {
      respondJson(context, 200, Json.obj(Map.of("ok", false, "message", "Usage: events ON|OFF <id> [intervalMs] [maxBytes] [raw|screen-diff|screen-formatted|screen-text]")));
      return false;
    }

    String mode = args.at(0);
    String id = args.at(1);
    Session s = sessions.get(id);
    if (s == null) {
      respondJson(context, 404, Json.obj(Map.of("ok", false, "message", "unknown session id")));
      return false;
    }

    if ("OFF".equalsIgnoreCase(mode)) {
      EventStreamOwner prev = activeEventStreamOwner;
      activeEventStreamOwner = null;
      if (prev != null) {
        prev.stop();
      }
      respondJson(context, 200, Json.obj(Map.of("ok", true, "message", "events OFF")));
      return true;
    }
    if (!"ON".equalsIgnoreCase(mode)) {
      respondJson(context, 200, Json.obj(Map.of("ok", false, "message", "Usage: events ON|OFF <id> [intervalMs] [maxBytes] [raw|screen-diff|screen-formatted|screen-text]")));
      return false;
    }

    long intervalMs = clamp(args.longAt(2, 750L), 100L, 10_000L);
    int maxBytes = (int) clamp(args.longAt(3, defaultReadChunkMaxBytes()), 1, maxScreenMaxBytes());
    EventMode eventMode = EventMode.from(args.at(4));
    if (eventMode.requiresScreen() && !s.screenEnabled) {
      respondJson(context, 400, Json.obj(Map.of("ok", false, "message", "screen mode is not enabled for this session")));
      return false;
    }

    EventStreamOwner prev = activeEventStreamOwner;
    if (prev != null) {
      prev.stop();
    }

    EventStreamOwner owner = new EventStreamOwner(context.getId(), id, intervalMs, maxBytes, eventMode);
    activeEventStreamOwner = owner;
    owner.start(context, s);
    respondJson(context, 200, Json.obj(Map.of("ok", true, "message", "events ON", "id", id, "mode", eventMode.wireValue)));
    return true;
  }

  private boolean closeLike(CommandContext context, boolean kill) {
    Args args = Args.parse(requestBody(context));
    if (args.isEmpty()) {
      respondJson(context, 200, Json.obj(Map.of("ok", false, "message", "Usage: " + (kill ? "kill" : "close") + " <id>")));
      return false;
    }
    String id = args.first();
    Session s = sessions.remove(id);
    if (s == null) {
      respondJson(context, 404, Json.obj(Map.of("ok", false, "message", "unknown session id")));
      return false;
    }

    try {
      if (kill) {
        s.nativeTerm.kill(s.handle);
      } else {
        s.nativeTerm.close(s.handle);
      }
    } catch (Exception ignored) {
    } finally {
      try {
        s.nativeTerm.free(s.handle);
      } catch (Exception ignored) {
      }
    }

    respondJson(context, 200, Json.obj(Map.of("ok", true)));
    return true;
  }

  private NativeTerm ensureNative() {
    String libPath = env("TODERO_TERM_NATIVE_LIB_PATH");
    ToderoTermLibrary lib = NativeLibraryLoader.loadOrThrow(libPath);
    return new NativeTerm(lib);
  }

  private void startCleanupLoop() {
    if (cleanupTask != null) return;
    cleanupTask = scheduler.scheduleAtFixedRate(() -> {
      long now = System.currentTimeMillis();
      for (Session s : sessions.values()) {
        if (now - s.lastActivityMs.get() > s.idleTimeoutMs) {
          sessions.remove(s.id);
          try {
            s.nativeTerm.kill(s.handle);
          } catch (Exception ignored) {
          }
          try {
            s.nativeTerm.free(s.handle);
          } catch (Exception ignored) {
          }
        }
      }
    }, 30, 30, TimeUnit.SECONDS);
  }

  private static Map<String, Object> errorJson(NativeTerm.NativeTermException e) {
    return Map.of(
        "ok", false,
        "errorCode", e.errorCode,
        "message", e.getMessage()
    );
  }

  private Boolean screenPayloadAction(CommandContext context, ScreenPayloadMode mode) {
    Args args = Args.parse(requestBody(context));
    String usage = mode == ScreenPayloadMode.TEXT
        ? "Usage: screen_text <id> [maxBytes]"
        : "Usage: screen_formatted <id> [maxBytes]";
    if (args.isEmpty()) {
      respondJson(context, 200, Json.obj(Map.of("ok", false, "message", usage)));
      return false;
    }
    Session s = sessions.get(args.first());
    if (s == null) {
      respondJson(context, 404, Json.obj(Map.of("ok", false, "message", "unknown session id")));
      return false;
    }
    int maxBytes = (int) clamp(args.longAt(1, defaultScreenMaxBytes()), 1, maxScreenMaxBytes());
    try {
      s.touch();
      NativeTerm.ScreenPayload payload = mode == ScreenPayloadMode.TEXT
          ? s.nativeTerm.screenText(s.handle, maxBytes)
          : s.nativeTerm.screenFormatted(s.handle, maxBytes);
      Map<String, Object> resp = new LinkedHashMap<>();
      resp.put("ok", true);
      resp.put("id", s.id);
      resp.put("mode", mode == ScreenPayloadMode.TEXT ? "screen-text" : "screen-formatted");
      resp.put("len", payload.bytes().length);
      resp.put("dataB64", NativeTerm.b64(payload.bytes()));
      resp.putAll(screenInfoFields(payload.info()));
      respondJson(context, 200, Json.obj(resp));
      return true;
    } catch (NativeTerm.NativeTermException e) {
      respondJson(context, 500, Json.obj(errorJson(e)));
      return false;
    }
  }

  private static Map<String, Object> screenInfoResponse(NativeTerm.ScreenInfo info) {
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("ok", true);
    resp.putAll(screenInfoFields(info));
    return resp;
  }

  private static Map<String, Object> screenInfoFields(NativeTerm.ScreenInfo info) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("rows", info.rows());
    fields.put("cols", info.cols());
    fields.put("cursorRow", info.cursorRow());
    fields.put("cursorCol", info.cursorCol());
    fields.put("alternateScreen", info.alternateScreen());
    fields.put("hideCursor", info.hideCursor());
    fields.put("applicationCursor", info.applicationCursor());
    fields.put("frameId", info.frameId());
    return fields;
  }

  private static String requestBody(CommandContext context) {
    if (context == null || context.getAiatpRequest() == null || context.getAiatpRequest().getBody() == null) {
      return "";
    }
    String body = AiatpIO.bodyToString(context.getAiatpRequest().getBody(), StandardCharsets.UTF_8);
    return body == null ? "" : body;
  }

  private static void respondJson(CommandContext context, int status, String json) {
    AiatpIO.Headers headers = new AiatpIO.Headers();
    headers.set("Content-Type", "application/json; charset=utf-8");
    context.complete(AiatpResponse.builder()
        .statusCode(status)
        .reasonPhrase(status >= 400 ? "error" : "completed")
        .headers(headers)
        .body(AiatpIO.Body.ofString(json == null ? "" : json, StandardCharsets.UTF_8))
        .build());
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

  private static String resolveRequestHost(AiatpRequest request) {
    if (request == null || request.getHeaders() == null) {
      return null;
    }
    return request.getHeaders().getFirst("Host");
  }

  private static String escapeHtml(String value) {
    String input = value == null ? "" : value;
    return input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private static String loadResourceText(String path) {
    try (InputStream in = TermComponent.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("Resource not found: " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load resource: " + path, e);
    }
  }

  private static long clamp(long v, long min, long max) {
    return Math.max(min, Math.min(max, v));
  }

  private static long parseLong(String raw, long fallback) {
    if (raw == null || raw.isBlank()) return fallback;
    try {
      return Long.parseLong(raw.trim());
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private long defaultIdleTimeoutMs() {
    return parseLong(env("TODERO_TERM_IDLE_TIMEOUT_MS"), Duration.ofMinutes(10).toMillis());
  }

  private int defaultReadChunkMaxBytes() {
    return (int) clamp(parseLong(env("TODERO_TERM_READ_CHUNK_MAX_BYTES"), 64 * 1024L), 1024L, maxReadChunkMaxBytes());
  }

  private static int maxReadChunkMaxBytes() {
    return 512 * 1024;
  }

  private int defaultBufferMaxBytes() {
    return (int) clamp(parseLong(env("TODERO_TERM_BUFFER_MAX_BYTES"), 1024 * 1024L), 1024L, 32L * 1024L * 1024L);
  }

  private int defaultScreenMaxBytes() {
    return (int) clamp(parseLong(env("TODERO_TERM_SCREEN_MAX_BYTES"), 256 * 1024L), 1024L, maxScreenMaxBytes());
  }

  private static int maxScreenMaxBytes() {
    return 2 * 1024 * 1024;
  }

  private boolean isCwdAllowed(String cwd) {
    return resolveAllowedRootForCwd(cwd) != null;
  }

  static boolean isPathUnderRoot(Path cwd, Path allowedRoot) {
    return cwd != null && allowedRoot != null && cwd.startsWith(allowedRoot);
  }

  static Path normalizeAbsolutePath(String rawPath) {
    if (rawPath == null || rawPath.isBlank()) {
      return null;
    }
    try {
      Path path = Path.of(rawPath.trim());
      if (!path.isAbsolute()) {
        return null;
      }
      return path.normalize();
    } catch (RuntimeException e) {
      return null;
    }
  }

  static Path canonicalCandidatePath(String rawPath) {
    Path normalized = normalizeAbsolutePath(rawPath);
    if (normalized == null) {
      return null;
    }
    Path existing = normalized;
    while (existing != null && !Files.exists(existing)) {
      existing = existing.getParent();
    }
    if (existing == null) {
      return null;
    }
    try {
      Path canonicalExisting = existing.toRealPath();
      Path relative = existing.relativize(normalized);
      return canonicalExisting.resolve(relative).normalize();
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  static Path canonicalAbsolutePath(String rawPath) {
    if (rawPath == null || rawPath.isBlank()) {
      return null;
    }
    try {
      Path path = Path.of(rawPath.trim());
      if (!path.isAbsolute()) {
        return null;
      }
      return path.toRealPath();
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  private Path resolveAllowedRootForCwd(String cwd) {
    Path candidateCwd = canonicalCandidatePath(cwd);
    if (candidateCwd == null) {
      return null;
    }
    String raw = env("TODERO_TERM_ALLOWED_WORKSPACES");
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String normalized = raw.replace('\n', ';');
    for (String entry : normalized.split(";")) {
      if (entry == null) continue;
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) continue;
      Path allowedPath = canonicalAbsolutePath(trimmed);
      if (allowedPath != null && isPathUnderRoot(candidateCwd, allowedPath)) {
        return allowedPath;
      }
    }
    return null;
  }

  static Path prepareCwd(String cwd, Path allowedRoot) throws IOException {
    Path candidateCwd = canonicalCandidatePath(cwd);
    if (candidateCwd == null || allowedRoot == null || !isPathUnderRoot(candidateCwd, allowedRoot)) {
      throw new IOException("cwd is not allowed by TODERO_TERM_ALLOWED_WORKSPACES");
    }
    Files.createDirectories(candidateCwd);
    Path canonicalCwd = candidateCwd.toRealPath();
    if (!isPathUnderRoot(canonicalCwd, allowedRoot)) {
      throw new IOException("resolved cwd escaped allowed workspace root");
    }
    return canonicalCwd;
  }

  private String mergeConfig(String body, String cwd) {
    // Minimal config merge: ensure cwd and allowed_workspaces are set.
    // Callers can pass program/argv/env/cols/rows/buffer_max_bytes/read_chunk_max_bytes directly.
    String allowedRaw = env("TODERO_TERM_ALLOWED_WORKSPACES");
    String[] allowed = allowedRaw == null ? new String[0] : allowedRaw.replace('\n', ';').split(";");

    StringBuilder sb = new StringBuilder();
    sb.append('{');
    sb.append("\"cwd\":").append(Json.quote(cwd));

    // copy-through user config fields (best-effort): program, argv, env, cols, rows, buffer_max_bytes, read_chunk_max_bytes
    String[] passthroughKeys = new String[]{"program", "argv", "env", "cols", "rows", "buffer_max_bytes", "read_chunk_max_bytes",
        "screen_mode", "screen_scrollback_lines", "screen_diff_history", "screen_retain_raw_buffer"};
    for (String k : passthroughKeys) {
      String v = extractJsonValue(body, k);
      if (v != null) {
        sb.append(',').append(Json.quote(k)).append(':').append(v);
      }
    }

    sb.append(',').append("\"allowed_workspaces\":[");
    boolean first = true;
    for (String a : allowed) {
      if (a == null) continue;
      String t = a.trim();
      if (t.isEmpty()) continue;
      if (!first) sb.append(',');
      first = false;
      sb.append(Json.quote(t));
    }
    sb.append(']');

    sb.append('}');
    return sb.toString();
  }

  private String env(String key) {
    if (dotenv != null) {
      String dv = dotenv.get(key);
      if (dv != null && !dv.isBlank()) return dv;
    }
    String v = System.getenv(key);
    if (v != null && !v.isBlank()) return v;
    return null;
  }

  private static Map<String, String> loadDotenv(Storage storage) {
    if (storage == null) return Map.of();
    try {
      byte[] envBytes = storage.readFile(".env");
      if (envBytes == null || envBytes.length == 0) return Map.of();
      return Util.parseDotenv(envBytes);
    } catch (IOException ignored) {
      return Map.of();
    } catch (RuntimeException ignored) {
      return Map.of();
    }
  }

  // Best-effort JSON extraction helpers (kept intentionally simple for v1).
  private static String extractJsonString(String json, String key) {
    String v = extractJsonValue(json, key);
    if (v == null) return null;
    v = v.trim();
    if (v.length() < 2 || v.charAt(0) != '"' || v.charAt(v.length() - 1) != '"') return null;
    return unescapeJsonString(v.substring(1, v.length() - 1));
  }

  private static String extractJsonNumber(String json, String key) {
    String v = extractJsonValue(json, key);
    if (v == null) return null;
    v = v.trim();
    if (!v.matches("^-?\\d+$")) return null;
    return v;
  }

  private static String extractJsonValue(String json, String key) {
    if (json == null) return null;
    String needle = '"' + key + '"';
    int idx = json.indexOf(needle);
    if (idx < 0) return null;
    int colon = json.indexOf(':', idx + needle.length());
    if (colon < 0) return null;
    int i = colon + 1;
    while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
    if (i >= json.length()) return null;

    char c = json.charAt(i);
    if (c == '"') {
      int end = i + 1;
      boolean esc = false;
      while (end < json.length()) {
        char ch = json.charAt(end);
        if (esc) {
          esc = false;
        } else if (ch == '\\') {
          esc = true;
        } else if (ch == '"') {
          return json.substring(i, end + 1);
        }
        end++;
      }
      return null;
    }

    if (c == '{' || c == '[') {
      int depth = 0;
      boolean inString = false;
      boolean esc = false;
      for (int j = i; j < json.length(); j++) {
        char ch = json.charAt(j);
        if (inString) {
          if (esc) {
            esc = false;
          } else if (ch == '\\') {
            esc = true;
          } else if (ch == '"') {
            inString = false;
          }
          continue;
        }
        if (ch == '"') {
          inString = true;
          continue;
        }
        if (ch == '{' || ch == '[') depth++;
        if (ch == '}' || ch == ']') {
          depth--;
          if (depth == 0) {
            return json.substring(i, j + 1);
          }
        }
      }
      return null;
    }

    int end = i;
    while (end < json.length()) {
      char ch = json.charAt(end);
      if (ch == ',' || ch == '}' || ch == ']') break;
      end++;
    }
    return json.substring(i, end).trim();
  }

  private static String unescapeJsonString(String s) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c != '\\') {
        sb.append(c);
        continue;
      }
      if (i + 1 >= s.length()) break;
      char n = s.charAt(++i);
      switch (n) {
        case '"' -> sb.append('"');
        case '\\' -> sb.append('\\');
        case '/' -> sb.append('/');
        case 'b' -> sb.append('\b');
        case 'f' -> sb.append('\f');
        case 'n' -> sb.append('\n');
        case 'r' -> sb.append('\r');
        case 't' -> sb.append('\t');
        case 'u' -> {
          if (i + 4 < s.length()) {
            String hex = s.substring(i + 1, i + 5);
            try {
              sb.append((char) Integer.parseInt(hex, 16));
            } catch (NumberFormatException ignored) {
            }
            i += 4;
          }
        }
        default -> sb.append(n);
      }
    }
    return sb.toString();
  }

  public enum TermEvent implements EventDefinition {
    TERM_OUTPUT("Terminal output chunk"),
    TERM_SCREEN("Terminal screen payload"),
    TERM_EXIT("Terminal exited"),
    TERM_ERROR("Terminal error");

    private final String description;

    TermEvent(String description) {
      this.description = description;
    }

    @Override
    public String getDescription() {
      return description;
    }
  }

  private static final class Session {
    final String id;
    final String name;
    final com.sun.jna.Pointer handle;
    final NativeTerm nativeTerm;
    final long createdAtMs = System.currentTimeMillis();
    final AtomicLong lastActivityMs = new AtomicLong(System.currentTimeMillis());
    final long idleTimeoutMs;
    volatile boolean screenEnabled;

    Session(String id, String name, com.sun.jna.Pointer handle, NativeTerm nativeTerm, long idleTimeoutMs) {
      this.id = id;
      this.name = name;
      this.handle = handle;
      this.nativeTerm = nativeTerm;
      this.idleTimeoutMs = idleTimeoutMs;
    }

    void touch() {
      lastActivityMs.set(System.currentTimeMillis());
    }
  }

  private final class EventStreamOwner {
    final String ownerContextId;
    final String sessionId;
    final long intervalMs;
    final int maxBytes;
    final EventMode eventMode;
    final AtomicLong rawCursor = new AtomicLong(0);
    final AtomicLong screenFrameCursor = new AtomicLong(0);
    volatile ScheduledFuture<?> task;

    EventStreamOwner(String ownerContextId, String sessionId, long intervalMs, int maxBytes, EventMode eventMode) {
      this.ownerContextId = ownerContextId;
      this.sessionId = sessionId;
      this.intervalMs = intervalMs;
      this.maxBytes = maxBytes;
      this.eventMode = eventMode;
    }

    void start(CommandContext context, Session session) {
      task = scheduler.scheduleAtFixedRate(() -> {
        try {
          Session s = sessions.get(sessionId);
          if (s == null) {
            stop();
            return;
          }
          emitPayload(context, s);

          NativeTerm.WaitExitResult er = s.nativeTerm.waitExit(s.handle, 0);
          if (er.exited()) {
            String payload = Json.obj(Map.of(
                "id", sessionId,
                "exited", true,
                "exitCode", er.exitCode()
            ));
            context.emitCustom(TermEvent.TERM_EXIT.name(), TermEvent.TERM_EXIT.name(),
                "application/json; charset=utf-8",
                payload.getBytes(StandardCharsets.UTF_8),
                "progress");
            sessions.remove(sessionId);
            try {
              s.nativeTerm.free(s.handle);
            } catch (Exception ignored) {
            }
            stop();
          }
        } catch (Exception e) {
          String payload = Json.obj(Map.of(
              "id", sessionId,
              "message", String.valueOf(e)
          ));
          context.emitCustom(TermEvent.TERM_ERROR.name(), TermEvent.TERM_ERROR.name(),
              "application/json; charset=utf-8",
              payload.getBytes(StandardCharsets.UTF_8),
              "progress");
        }
      }, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    void stop() {
      ScheduledFuture<?> t = task;
      if (t != null) {
        t.cancel(false);
      }
      task = null;
    }

    private void emitPayload(CommandContext context, Session s) {
      switch (eventMode) {
        case RAW -> {
          long since = rawCursor.get();
          NativeTerm.ReadResult rr = s.nativeTerm.read(s.handle, since, maxBytes);
          rawCursor.set(rr.newSeq());
          if (rr.bytes().length == 0) {
            return;
          }
          String payload = Json.obj(Map.of(
              "id", sessionId,
              "mode", eventMode.wireValue,
              "sinceSeq", since,
              "newSeq", rr.newSeq(),
              "len", rr.bytes().length,
              "dataB64", NativeTerm.b64(rr.bytes())
          ));
          context.emitCustom(TermEvent.TERM_OUTPUT.name(), TermEvent.TERM_OUTPUT.name(),
              "application/json; charset=utf-8",
              payload.getBytes(StandardCharsets.UTF_8),
              "progress");
        }
        case SCREEN_DIFF -> {
          long sinceFrame = screenFrameCursor.get();
          NativeTerm.ScreenDiffPayload payload = s.nativeTerm.screenDiff(s.handle, sinceFrame, maxBytes);
          screenFrameCursor.set(payload.info().frameId());
          if (payload.bytes().length == 0 && payload.info().frameId() == sinceFrame) {
            return;
          }
          Map<String, Object> resp = new LinkedHashMap<>();
          resp.put("id", sessionId);
          resp.put("mode", eventMode.wireValue);
          resp.put("len", payload.bytes().length);
          resp.put("dataB64", NativeTerm.b64(payload.bytes()));
          resp.put("frameIdStart", payload.diffInfo().frameIdStart());
          resp.put("frameIdEnd", payload.diffInfo().frameIdEnd());
          resp.put("truncated", payload.diffInfo().truncated());
          resp.putAll(screenInfoFields(payload.info()));
          context.emitCustom(TermEvent.TERM_SCREEN.name(), TermEvent.TERM_SCREEN.name(),
              "application/json; charset=utf-8",
              Json.obj(resp).getBytes(StandardCharsets.UTF_8),
              "progress");
        }
        case SCREEN_FORMATTED, SCREEN_TEXT -> {
          NativeTerm.ScreenPayload payload = eventMode == EventMode.SCREEN_FORMATTED
              ? s.nativeTerm.screenFormatted(s.handle, maxBytes)
              : s.nativeTerm.screenText(s.handle, maxBytes);
          long previousFrame = screenFrameCursor.getAndSet(payload.info().frameId());
          if (previousFrame == payload.info().frameId() && payload.bytes().length == 0) {
            return;
          }
          Map<String, Object> resp = new LinkedHashMap<>();
          resp.put("id", sessionId);
          resp.put("mode", eventMode.wireValue);
          resp.put("len", payload.bytes().length);
          resp.put("dataB64", NativeTerm.b64(payload.bytes()));
          resp.putAll(screenInfoFields(payload.info()));
          context.emitCustom(TermEvent.TERM_SCREEN.name(), TermEvent.TERM_SCREEN.name(),
              "application/json; charset=utf-8",
              Json.obj(resp).getBytes(StandardCharsets.UTF_8),
              "progress");
        }
      }
    }
  }

  private enum EventMode {
    RAW("raw"),
    SCREEN_DIFF("screen-diff"),
    SCREEN_FORMATTED("screen-formatted"),
    SCREEN_TEXT("screen-text");

    final String wireValue;

    EventMode(String wireValue) {
      this.wireValue = wireValue;
    }

    static EventMode from(String raw) {
      if (raw == null || raw.isBlank()) return RAW;
      return switch (raw.trim().toLowerCase()) {
        case "screen-diff" -> SCREEN_DIFF;
        case "screen-formatted" -> SCREEN_FORMATTED;
        case "screen-text" -> SCREEN_TEXT;
        default -> RAW;
      };
    }

    boolean requiresScreen() {
      return this != RAW;
    }
  }

  private enum ScreenPayloadMode {
    TEXT,
    FORMATTED
  }

  private static final class Args {
    private final String[] parts;

    private Args(String[] parts) {
      this.parts = parts;
    }

    static Args parse(String body) {
      if (body == null) return new Args(new String[0]);
      String trimmed = body.trim();
      if (trimmed.isEmpty()) return new Args(new String[0]);
      // This component uses the request body as arg string for CLI-style commands.
      return new Args(trimmed.split("\\s+"));
    }

    boolean isEmpty() {
      return parts.length == 0;
    }

    int size() {
      return parts.length;
    }

    String first() {
      return at(0);
    }

    String at(int idx) {
      return idx >= 0 && idx < parts.length ? parts[idx] : "";
    }

    long longAt(int idx, long fallback) {
      String v = at(idx);
      if (v == null || v.isBlank()) return fallback;
      try {
        return Long.parseLong(v);
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
  }
}
