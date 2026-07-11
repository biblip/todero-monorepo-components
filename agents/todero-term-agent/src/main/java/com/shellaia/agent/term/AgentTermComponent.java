package com.shellaia.agent.term;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.ai.agent.AgentContext;
import com.social100.todero.common.ai.llm.LLMClient;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpRequest;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.aiatpio.AiatpResponse;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.storage.Storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@AIAController(name = "com.shellaia.agent.term",
    type = ServerType.AI,
    visible = true,
    description = "Terminal Agent (name-only sessions, fixed defaults)",
    capabilityProvider = TermAgentCapabilities.class)
public class AgentTermComponent {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String MAIN_GROUP = "Main";
  private static final String TERM_COMPONENT = "com.shellaia.term";
  private static final long TOOL_TIMEOUT_SECONDS = 3;
  private static final String LOG_PREFIX = "[TERM_AGENT]";

  private static final String LIBRARY_FILE = "term-agent/command-library-v1.json";
  private static final String UI_STATE_FILE = "term-agent/ui-state-v1.json";

  private static final int DEFAULT_COLS = 120;
  private static final int DEFAULT_ROWS = 30;
  private static final String MAC_ZSH = "/bin/zsh";

  private static final String HTML_TEMPLATE_PATH = "com/shellaia/agent/term/component.html";
  private static final String XTERM_JS_PATH = "com/shellaia/agent/term/web/xterm.js";
  private static final String XTERM_CSS_PATH = "com/shellaia/agent/term/web/xterm.css";
  private static final String PROCESS_PROMPT_PATH = "prompts/term-agent-process.md";

  private static final String HTML_TEMPLATE = loadResourceText(HTML_TEMPLATE_PATH);
  private static final String XTERM_JS = loadResourceText(XTERM_JS_PATH);
  private static final String XTERM_CSS = loadResourceText(XTERM_CSS_PATH);
  private static final String PROCESS_PROMPT = loadResourceText(PROCESS_PROMPT_PATH);

  private final ExecutorService cognitionExecutor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "term-agent-cognition");
    t.setDaemon(true);
    return t;
  });
  private final Storage storage;

  public AgentTermComponent(Storage storage) {
    this.storage = storage;
  }

  @Action(group = MAIN_GROUP, command = "library_get", description = "Get the persisted command library JSON.")
  public Boolean libraryGet(CommandContext context) {
    String json = readJsonFileOrDefault(LIBRARY_FILE, defaultLibraryJson());
    context.complete(buildJsonResponse(200, json));
    return true;
  }

  @Action(group = MAIN_GROUP, command = "library_put", description = "Replace the persisted command library JSON. Body must be JSON.")
  public Boolean libraryPut(CommandContext context) {
    String body = safeTrim(requestBody(context));
    if (body.isEmpty()) {
      return respondError(context, "body JSON is required");
    }
    JsonNode node;
    try {
      node = JSON.readTree(body);
    } catch (Exception e) {
      return respondError(context, "invalid JSON");
    }
    if (node == null || !node.isObject()) {
      return respondError(context, "body must be a JSON object");
    }
    ObjectNode normalized = normalizeLibrary((ObjectNode) node);
    try {
      storage.writeFile(LIBRARY_FILE, JSON.writeValueAsBytes(normalized));
      context.complete(buildJsonResponse(200, "{\"ok\":true}"));
      return true;
    } catch (Exception e) {
      context.complete(buildJsonResponse(500, "{\"ok\":false,\"error\":\"storage_write_failed\"}"));
      return true;
    }
  }

  @Action(group = MAIN_GROUP, command = "ui_state_get", description = "Get persisted UI state (selected tab/group).")
  public Boolean uiStateGet(CommandContext context) {
    String json = readJsonFileOrDefault(UI_STATE_FILE, "{\"selectedSessionId\":\"\",\"selectedSessionName\":\"\",\"selectedGroupId\":\"\"}");
    context.complete(buildJsonResponse(200, json));
    return true;
  }

  @Action(group = MAIN_GROUP, command = "ui_state_put", description = "Replace persisted UI state. Body must be JSON.")
  public Boolean uiStatePut(CommandContext context) {
    String body = safeTrim(requestBody(context));
    if (body.isEmpty()) {
      return respondError(context, "body JSON is required");
    }
    JsonNode node;
    try {
      node = JSON.readTree(body);
    } catch (Exception e) {
      return respondError(context, "invalid JSON");
    }
    if (node == null || !node.isObject()) {
      return respondError(context, "body must be a JSON object");
    }
    ObjectNode out = JSON.createObjectNode();
    out.put("selectedSessionId", safeTrim(node.path("selectedSessionId").asText("")));
    out.put("selectedSessionName", safeTrim(node.path("selectedSessionName").asText("")));
    out.put("selectedGroupId", safeTrim(node.path("selectedGroupId").asText("")));
    try {
      storage.writeFile(UI_STATE_FILE, JSON.writeValueAsBytes(out));
      context.complete(buildJsonResponse(200, "{\"ok\":true}"));
      return true;
    } catch (Exception e) {
      context.complete(buildJsonResponse(500, "{\"ok\":false,\"error\":\"storage_write_failed\"}"));
      return true;
    }
  }

  @Action(group = MAIN_GROUP, command = "html", description = "Render Terminal Agent UI")
  public Boolean html(CommandContext context) {
    String host = resolveRequestHost(context == null ? null : context.getAiatpRequest());
    String title = host == null || host.isBlank() ? "Terminal Agent" : ("Terminal Agent (" + host + ")");
    String heading = title;
    String generatedAt = Instant.now().toString();
    String body = HTML_TEMPLATE
        .replace("${TITLE}", escapeHtml(title))
        .replace("${HEADING}", escapeHtml(heading))
        .replace("${GENERATED_AT}", escapeHtml(generatedAt))
        .replace("${XTERM_JS}", XTERM_JS)
        .replace("${XTERM_CSS}", XTERM_CSS);
    context.complete(buildTextResponse(200, body, "text/html; charset=utf-8"));
    return true;
  }

  @Action(group = MAIN_GROUP, command = "sessions", description = "List active terminal sessions (pass-through)")
  public Boolean sessions(CommandContext context) {
    return passthrough(context, "sessions", "");
  }

  @Action(group = MAIN_GROUP, command = "open", description = "Open session by name. CWD=~/term/<sanitized>, screen_mode + TERM on, cols=120 rows=30.")
  public Boolean open(CommandContext context) {
    String rawName = safeTrim(requestBody(context));
    if (rawName.isEmpty()) {
      return respondError(context, "name is required");
    }
    try {
      return passthrough(context, "open", buildOpenBody(rawName).toString());
    } catch (RuntimeException e) {
      return respondError(context, e.getMessage());
    }
  }

  @Action(group = MAIN_GROUP, command = "write", description = "Write to session. Usage: write <idOrName> [viewerId] <dataB64>")
  public Boolean write(CommandContext context) {
    return forwardRawArgs(context, "write");
  }

  @Action(group = MAIN_GROUP, command = "ctrlc", description = "Send Ctrl-C to session. Usage: ctrlc <idOrName>")
  public Boolean ctrlc(CommandContext context) {
    String target = safeTrim(requestBody(context));
    if (target.isEmpty()) return respondError(context, "target is required");
    String id = resolveSessionId(context, target);
    if (id.isEmpty()) return respondError(context, "session not found: " + target);
    return passthrough(context, "write", id + " " + Base64.getEncoder().encodeToString(new byte[] { 3 }));
  }

  @Action(group = MAIN_GROUP, command = "screen_text", description = "Get screen text. Usage: screen_text <idOrName> [maxBytes]")
  public Boolean screenText(CommandContext context) {
    return forwardRawArgs(context, "screen_text");
  }

  @Action(group = MAIN_GROUP, command = "screen_diff", description = "Get screen diff. Usage: screen_diff <idOrName> <sinceFrameId> [maxBytes]")
  public Boolean screenDiff(CommandContext context) {
    return forwardRawArgs(context, "screen_diff");
  }

  @Action(group = MAIN_GROUP, command = "screen_scrollback", description = "Get screen scrollback text. Usage: screen_scrollback <idOrName> [maxBytes]")
  public Boolean screenScrollback(CommandContext context) {
    return forwardRawArgs(context, "screen_scrollback");
  }

  @Action(group = MAIN_GROUP, command = "attach", description = "Attach an exclusive viewer lease. Usage: attach <idOrName> <viewerId>")
  public Boolean attach(CommandContext context) {
    return forwardRawArgs(context, "attach");
  }

  @Action(group = MAIN_GROUP, command = "heartbeat", description = "Refresh an exclusive viewer lease. Usage: heartbeat <idOrName> <viewerId>")
  public Boolean heartbeat(CommandContext context) {
    return forwardRawArgs(context, "heartbeat");
  }

  @Action(group = MAIN_GROUP, command = "detach", description = "Release an exclusive viewer lease. Usage: detach <idOrName> <viewerId>")
  public Boolean detach(CommandContext context) {
    return forwardRawArgs(context, "detach");
  }

  @Action(group = MAIN_GROUP, command = "close", description = "Close session. Usage: close <idOrName>")
  public Boolean close(CommandContext context) {
    return forwardSingleTarget(context, "close");
  }

  @Action(group = MAIN_GROUP, command = "kill", description = "Kill session. Usage: kill <idOrName>")
  public Boolean kill(CommandContext context) {
    return forwardSingleTarget(context, "kill");
  }

  @Action(group = MAIN_GROUP, command = "resize", description = "Resize session. Usage: resize <idOrName> [viewerId] <cols> <rows>")
  public Boolean resize(CommandContext context) {
    return forwardRawArgs(context, "resize");
  }

  @Action(group = MAIN_GROUP, command = "events", description = "Start/Stop terminal output events (pass-through). Usage: events ON|OFF <idOrName> <viewerId> [intervalMs] [maxBytes] [mode]")
  public Boolean events(CommandContext context) {
    String body = safeTrim(requestBody(context));
    if (body.isEmpty()) return respondError(context, "args required");
    String[] parts = body.split("\\s+");
    if (parts.length < 2) return respondError(context, "usage: events ON|OFF <idOrName> ...");
    String mode = parts[0];
    String target = parts[1];
    String id = resolveSessionId(context, target);
    if (id.isEmpty()) return respondError(context, "session not found: " + target);
    StringBuilder sb = new StringBuilder();
    sb.append(mode).append(' ').append(id);
    for (int i = 2; i < parts.length; i++) {
      sb.append(' ').append(parts[i]);
    }
    return passthrough(context, "events", sb.toString());
  }

  @Action(group = MAIN_GROUP, command = "process", description = "Interpret a user request into a single terminal tool call (no loop).")
  public Boolean process(CommandContext context) {
    String prompt = safeTrim(requestBody(context));
    if (prompt.isEmpty()) {
      return respondError(context, "prompt is required");
    }
    log("process begin prompt_len=" + prompt.length() + " prompt_preview=\"" + elide(prompt, 120) + "\"");

    CompletableFuture<AiatpResponse> future = CompletableFuture.supplyAsync(() -> {
      String sessionsJson = executeTerm(context, "sessions", "");
      String actionJson = planActionWithLlm(context, prompt, sessionsJson);
      PlannedAction planned = parsePlannedAction(actionJson);
      if (planned == null) {
        return buildJsonResponse(400, "{\"ok\":false,\"error\":\"invalid_llm_response\"}");
      }
      log("process planned command=" + safeTrim(planned.command)
          + " target=" + safeTrim(planned.target)
          + " name=" + safeTrim(planned.name)
          + " submit=" + planned.submit);
      return executePlanned(context, planned);
    }, cognitionExecutor);

    try {
      AiatpResponse resp = future.get(TOOL_TIMEOUT_SECONDS + 4, TimeUnit.SECONDS);
      context.complete(resp);
    } catch (TimeoutException e) {
      context.complete(buildJsonResponse(504, "{\"ok\":false,\"error\":\"timeout\"}"));
    } catch (Exception e) {
      context.complete(buildJsonResponse(500, "{\"ok\":false,\"error\":\"agent_failed\"}"));
    }
    return true;
  }

  @Action(group = MAIN_GROUP, command = "capabilities", description = "Return agent capability manifest for discovery")
  public Boolean capabilities(CommandContext context) {
    try {
      String json = JSON.writeValueAsString(new TermAgentCapabilities().manifest());
      context.complete(buildJsonResponse(200, json));
    } catch (Exception e) {
      context.complete(buildJsonResponse(500, "{\"ok\":false,\"error\":\"capabilities_failed\"}"));
    }
    return true;
  }

  private AiatpResponse executePlanned(CommandContext parentContext, PlannedAction planned) {
    String command = safeTrim(planned.command).toLowerCase(Locale.ROOT);
    String target = safeTrim(planned.target);
    if (command.isEmpty()) {
      return buildJsonResponse(400, "{\"ok\":false,\"error\":\"missing_command\"}");
    }

    if ("open".equals(command)) {
      String name = safeTrim(planned.name);
      if (name.isEmpty()) {
        return buildJsonResponse(400, "{\"ok\":false,\"error\":\"open_requires_name\"}");
      }
      try {
        return executeTermResponse(parentContext, "open", buildOpenBody(name).toString(), true);
      } catch (RuntimeException e) {
        return buildJsonResponse(400, "{\"ok\":false,\"error\":\"open_prepare_failed\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
      }
    }

    if ("sessions".equals(command)) {
      return executeTermResponse(parentContext, "sessions", "", true);
    }

    // Everything below needs a session target.
    if (target.isEmpty()) {
      return buildJsonResponse(400, "{\"ok\":false,\"error\":\"missing_target\"}");
    }
    String id = resolveSessionId(parentContext, target);
    if (id.isEmpty()) {
      return buildJsonResponse(404, "{\"ok\":false,\"error\":\"session_not_found\"}");
    }

    if ("ctrlc".equals(command)) {
      return executeTermResponse(parentContext, "write", id + " " + Base64.getEncoder().encodeToString(new byte[] { 3 }), true);
    }

    if ("write_text".equals(command)) {
      return sendTextPaced(parentContext, id, planned.text, planned.submit);
    }

    if ("write_b64".equals(command)) {
      String b64 = safeTrim(planned.dataB64);
      if (b64.isEmpty()) {
        return buildJsonResponse(400, "{\"ok\":false,\"error\":\"missing_dataB64\"}");
      }
      return executeTermResponse(parentContext, "write", id + " " + b64, true);
    }

    if ("screen_text".equals(command)) {
      long maxBytes = planned.maxBytes <= 0 ? 65536 : planned.maxBytes;
      return executeTermResponse(parentContext, "screen_text", id + " " + maxBytes, true);
    }

    if ("screen_diff".equals(command)) {
      long maxBytes = planned.maxBytes <= 0 ? 65536 : planned.maxBytes;
      long sinceFrame = planned.sinceFrameId < 0 ? 0 : planned.sinceFrameId;
      return executeTermResponse(parentContext, "screen_diff", id + " " + sinceFrame + " " + maxBytes, true);
    }

    if ("screen_scrollback".equals(command)) {
      long maxBytes = planned.maxBytes <= 0 ? 262144 : planned.maxBytes;
      return executeTermResponse(parentContext, "screen_scrollback", id + " " + maxBytes, true);
    }

    if ("close".equals(command) || "kill".equals(command)) {
      return executeTermResponse(parentContext, command, id, true);
    }

    if ("resize".equals(command)) {
      int cols = planned.cols <= 0 ? DEFAULT_COLS : planned.cols;
      int rows = planned.rows <= 0 ? DEFAULT_ROWS : planned.rows;
      return executeTermResponse(parentContext, "resize", id + " " + cols + " " + rows, true);
    }

    return buildJsonResponse(400, "{\"ok\":false,\"error\":\"unsupported_command\"}");
  }

  private String planActionWithLlm(CommandContext parentContext, String prompt, String sessionsJson) {
    AgentContext agentContext = new AgentContext();
    log("llm bind_agent_registry");
    parentContext.bindAgentLlmRegistry(agentContext);
    LLMClient llm = agentContext.systemLlm()
        .map(instance -> instance.client())
        .orElseThrow(() -> {
          log("llm missing_system_llm");
          return new IllegalStateException("No system-wide LLM available for com.shellaia.agent.term");
        });

    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("sessions_json", safeTrim(sessionsJson));
    ctx.put("fixed_defaults", Map.of(
        "cols", DEFAULT_COLS,
        "rows", DEFAULT_ROWS,
        "screen_mode", true,
        "term", "xterm-256color",
        "program", MAC_ZSH,
        "cwd_policy", "~/term/<sanitizedName>"
    ));
    try {
      String contextJson = JSON.writeValueAsString(ctx);
      log("llm chat begin ctx_len=" + contextJson.length());
      return llm.chat(PROCESS_PROMPT, prompt, contextJson);
    } catch (Exception e) {
      log("llm chat failed type=" + e.getClass().getName() + " msg=" + safeTrim(String.valueOf(e.getMessage())));
      e.printStackTrace(System.out);
      throw new IllegalStateException("LLM plan failed");
    }
  }

  private static void log(String message) {
    System.out.println(LOG_PREFIX + " " + message);
  }

  private static String elide(String text, int maxLen) {
    if (text == null) return "";
    String s = safeTrim(text).replace("\n", "\\n").replace("\r", "\\r");
    if (s.length() <= maxLen) return s;
    return s.substring(0, maxLen) + "...";
  }

  private static PlannedAction parsePlannedAction(String llmRaw) {
    JsonNode root = extractFirstJsonBlock(llmRaw);
    if (root == null || !root.isObject()) return null;
    PlannedAction out = new PlannedAction();
    out.command = readText(root, "command");
    out.target = readText(root, "target");
    out.name = readText(root, "name");
    out.text = readText(root, "text");
    out.dataB64 = readText(root, "dataB64");
    out.submit = readBoolean(root, "submit", false);
    out.maxBytes = readLong(root, "maxBytes", 0);
    out.sinceFrameId = readLong(root, "sinceFrameId", 0);
    out.cols = (int) readLong(root, "cols", 0);
    out.rows = (int) readLong(root, "rows", 0);
    return out;
  }

  private static String readText(JsonNode node, String field) {
    JsonNode v = node.path(field);
    return v.isTextual() ? safeTrim(v.asText()) : "";
  }

  private static long readLong(JsonNode node, String field, long fallback) {
    JsonNode v = node.path(field);
    if (v == null || v.isMissingNode() || v.isNull()) return fallback;
    if (v.isNumber()) return v.asLong();
    if (v.isTextual()) {
      try { return Long.parseLong(v.asText().trim()); } catch (NumberFormatException ignored) { return fallback; }
    }
    return fallback;
  }

  private static boolean readBoolean(JsonNode node, String field, boolean fallback) {
    JsonNode v = node.path(field);
    if (v == null || v.isMissingNode() || v.isNull()) return fallback;
    if (v.isBoolean()) return v.asBoolean();
    if (v.isTextual()) {
      String text = safeTrim(v.asText()).toLowerCase(Locale.ROOT);
      if ("true".equals(text)) return true;
      if ("false".equals(text)) return false;
    }
    return fallback;
  }

  private static JsonNode extractFirstJsonBlock(String raw) {
    String text = raw == null ? "" : raw;
    int i = text.indexOf('{');
    if (i < 0) return null;
    int depth = 0;
    for (int j = i; j < text.length(); j++) {
      char c = text.charAt(j);
      if (c == '{') depth++;
      if (c == '}') {
        depth--;
        if (depth == 0) {
          String json = text.substring(i, j + 1);
          try { return JSON.readTree(json); } catch (Exception ignored) { return null; }
        }
      }
    }
    return null;
  }

  private static final class PlannedAction {
    String command;
    String target;
    String name;
    String text;
    String dataB64;
    boolean submit;
    long sinceFrameId;
    long maxBytes;
    int cols;
    int rows;
  }

  private Boolean forwardRawArgs(CommandContext context, String cmd) {
    String body = safeTrim(requestBody(context));
    if (body.isEmpty()) return respondError(context, "args required");
    // Replace idOrName with resolved id for safety.
    String[] parts = body.split("\\s+", 2);
    if (parts.length < 1) return respondError(context, "args required");
    String id = resolveSessionId(context, parts[0]);
    if (id.isEmpty()) return respondError(context, "session not found: " + parts[0]);
    String rest = parts.length == 2 ? parts[1] : "";
    String forwarded = rest.isEmpty() ? id : (id + " " + rest);
    return passthrough(context, cmd, forwarded);
  }

  private Boolean forwardSingleTarget(CommandContext context, String cmd) {
    String target = safeTrim(requestBody(context));
    if (target.isEmpty()) return respondError(context, "target required");
    String id = resolveSessionId(context, target);
    if (id.isEmpty()) return respondError(context, "session not found: " + target);
    return passthrough(context, cmd, id);
  }

  private Boolean passthrough(CommandContext context, String cmd, String body) {
    AiatpResponse resp = executeTermResponse(context, cmd, body, false);
    context.complete(resp);
    return true;
  }

  private static String executeTerm(CommandContext parentContext, String cmd, String body) {
    AiatpResponse resp = executeTermResponse(parentContext, cmd, body, true);
    if (resp == null || resp.getBody() == null) return "";
    String text = AiatpIO.bodyToString(resp.getBody(), StandardCharsets.UTF_8);
    return text == null ? "" : text;
  }

  private static AiatpResponse executeTermResponse(CommandContext parentContext, String cmd, String body) {
    return executeTermResponse(parentContext, cmd, body, false);
  }

  private static AiatpResponse executeTermResponse(CommandContext parentContext, String cmd, String body, boolean internalLocal) {
    CompletableFuture<AiatpResponse> out = new CompletableFuture<>();
    AiatpRequest internalReq = AiatpRuntimeAdapter.request(
        "ACTION",
        "/" + TERM_COMPONENT + "/" + cmd,
        AiatpIO.Body.ofString(body == null ? "" : body, StandardCharsets.UTF_8)
    );
    internalReq = inheritParentRouting(parentContext, internalReq);
    if (internalLocal) {
      internalReq = AiatpRuntimeAdapter.withHeader(internalReq, CommandContext.HDR_INTERNAL_EVENT_DELIVERY, "local");
    }

    CommandContext internalContext = parentContext.toBuilder()
        .aiatpRequest(internalReq)
        .responseConsumer(out::complete)
        .build();

    ExecutorService dispatchExecutor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "term-agent-tool-dispatch");
      t.setDaemon(true);
      return t;
    });
    try {
      dispatchExecutor.submit(() -> parentContext.execute(TERM_COMPONENT, cmd, internalContext));
    } finally {
      dispatchExecutor.shutdown();
    }

    try {
      return out.get(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (Exception e) {
      return buildJsonResponse(500, "{\"ok\":false,\"error\":\"tool_failed\"}");
    }
  }

  private static AiatpRequest inheritParentRouting(CommandContext parentContext, AiatpRequest internal) {
    if (parentContext == null || parentContext.getAiatpRequest() == null) {
      return internal;
    }
    AiatpRequest parent = parentContext.getAiatpRequest();
    if (parent.getHeaders() == null) return internal;
    String host = parent.getHeaders().getFirst("Host");
    if (host == null || host.isBlank()) return internal;
    return AiatpRuntimeAdapter.withHeader(internal, "Host", host);
  }

  private String resolveSessionId(CommandContext parentContext, String idOrName) {
    String raw = safeTrim(idOrName);
    if (raw.isEmpty()) return "";
    String sessionsJson = executeTerm(parentContext, "sessions", "");
    try {
      JsonNode root = JSON.readTree(sessionsJson);
      JsonNode sessions = root.path("sessions");
      if (!sessions.isArray()) return "";
      for (JsonNode s : sessions) {
        if (raw.equals(safeTrim(s.path("id").asText()))) return raw;
      }
      for (JsonNode s : sessions) {
        if (raw.equals(safeTrim(s.path("name").asText()))) return safeTrim(s.path("id").asText());
      }
      return "";
    } catch (Exception e) {
      return "";
    }
  }

  static String sanitizeName(String input) {
    String normalized = Normalizer.normalize(safeTrim(input), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    String out = normalized.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    out = out.replaceAll("-{2,}", "-").trim();
    return out;
  }

  static String composeWriteText(String text, boolean submit) {
    String base = text == null ? "" : text;
    return submit ? (base + "\r") : base;
  }

  private AiatpResponse sendTextPaced(CommandContext parentContext, String id, String text, boolean submit) {
    String content = text == null ? "" : text;
    String b64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    AiatpResponse resp = executeTermResponse(parentContext, "write", id + " " + b64, true);
    if (resp == null || resp.getStatusCode() >= 400) {
      return resp == null ? buildJsonResponse(500, "{\"ok\":false,\"error\":\"tool_failed\"}") : resp;
    }
    if (submit) {
      sleepQuietly(300L);
      String enterB64 = Base64.getEncoder().encodeToString("\r".getBytes(StandardCharsets.UTF_8));
      AiatpResponse enterResp = executeTermResponse(parentContext, "write", id + " " + enterB64, true);
      if (enterResp == null || enterResp.getStatusCode() >= 400) {
        return enterResp == null ? buildJsonResponse(500, "{\"ok\":false,\"error\":\"tool_failed\"}") : enterResp;
      }
    }
    return buildJsonResponse(200, "{\"ok\":true}");
  }

  private static void sleepQuietly(long millis) {
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private String readJsonFileOrDefault(String path, String defaultJson) {
    try {
      byte[] bytes = storage.readFile(path);
      if (bytes == null || bytes.length == 0) return defaultJson;
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (Exception ignored) {
      return defaultJson;
    }
  }

  private static String defaultLibraryJson() {
    // Stable minimal schema for the UI to evolve without breaking.
    String groupId = "grp_" + UUID.randomUUID();
    String cmdId = "cmd_" + UUID.randomUUID();
    return "{"
        + "\"schemaVersion\":1,"
        + "\"groups\":[{"
        + "\"id\":\"" + groupId + "\","
        + "\"name\":\"General\","
        + "\"order\":0,"
        + "\"commands\":[{"
        + "\"id\":\"" + cmdId + "\","
        + "\"name\":\"List files\","
        + "\"text\":\"ls -al\","
        + "\"submit\":true,"
        + "\"order\":0"
        + "}]"
        + "}]"
        + "}";
  }

  private ObjectNode normalizeLibrary(ObjectNode input) {
    ObjectNode out = JSON.createObjectNode();
    int schemaVersion = input.path("schemaVersion").asInt(1);
    out.put("schemaVersion", schemaVersion);
    List<ObjectNode> groups = new ArrayList<>();
    JsonNode groupsNode = input.path("groups");
    if (groupsNode != null && groupsNode.isArray()) {
      for (JsonNode g : groupsNode) {
        if (g == null || !g.isObject()) continue;
        ObjectNode group = JSON.createObjectNode();
        String id = safeTrim(g.path("id").asText(""));
        if (id.isEmpty()) id = "grp_" + UUID.randomUUID();
        group.put("id", id);
        group.put("name", safeTrim(g.path("name").asText("Group")));
        group.put("order", g.path("order").asInt(0));
        List<ObjectNode> commands = new ArrayList<>();
        JsonNode commandsNode = g.path("commands");
        if (commandsNode != null && commandsNode.isArray()) {
          for (JsonNode c : commandsNode) {
            if (c == null || !c.isObject()) continue;
            ObjectNode cmd = JSON.createObjectNode();
            String cid = safeTrim(c.path("id").asText(""));
            if (cid.isEmpty()) cid = "cmd_" + UUID.randomUUID();
            cmd.put("id", cid);
            cmd.put("name", safeTrim(c.path("name").asText("Command")));
            cmd.put("text", c.path("text").isNull() ? "" : c.path("text").asText(""));
            cmd.put("submit", c.path("submit").asBoolean(true));
            cmd.put("order", c.path("order").asInt(0));
            commands.add(cmd);
          }
        }
        commands.sort((a, b) -> Integer.compare(a.path("order").asInt(0), b.path("order").asInt(0)));
        group.set("commands", JSON.valueToTree(commands));
        groups.add(group);
      }
    }
    groups.sort((a, b) -> Integer.compare(a.path("order").asInt(0), b.path("order").asInt(0)));
    out.set("groups", JSON.valueToTree(groups));
    return out;
  }

  private ObjectNode buildOpenBody(String rawName) {
    String sanitized = sanitizeName(rawName);
    if (sanitized.isEmpty()) {
      throw new IllegalArgumentException("invalid name");
    }
    Path cwd = deriveAgentCwd(sanitized);
    ObjectNode body = JSON.createObjectNode();
    body.put("name", rawName);
    body.put("cwd", cwd.toString());
    body.put("cols", DEFAULT_COLS);
    body.put("rows", DEFAULT_ROWS);
    body.put("screen_mode", true);
    body.putArray("env").addObject().put("k", "TERM").put("v", "xterm-256color");
    applyDefaultProgram(body);
    return body;
  }

  static Path deriveAgentCwd(String sanitized) {
    return Path.of(System.getProperty("user.home"), "term", sanitized);
  }

  private static void applyDefaultProgram(ObjectNode body) {
    // Prefer /bin/zsh when available (macOS default); otherwise let the native library choose its default shell.
    try {
      if (java.nio.file.Files.exists(Path.of(MAC_ZSH))) {
        body.put("program", MAC_ZSH);
        body.putArray("argv").add(MAC_ZSH).add("-i");
      }
    } catch (Exception ignored) {
    }
  }

  private static String requestBody(CommandContext context) {
    if (context == null || context.getAiatpRequest() == null || context.getAiatpRequest().getBody() == null) {
      return "";
    }
    String body = AiatpIO.bodyToString(context.getAiatpRequest().getBody(), StandardCharsets.UTF_8);
    return body == null ? "" : body;
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

  private static AiatpResponse buildJsonResponse(int statusCode, String json) {
    return buildTextResponse(statusCode, json, "application/json; charset=utf-8");
  }

  private static Boolean respondError(CommandContext context, String message) {
    context.complete(buildJsonResponse(400, "{\"ok\":false,\"message\":\"" + escapeJson(message) + "\"}"));
    return true;
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

  private static String escapeJson(String value) {
    String s = value == null ? "" : value;
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
  }

  private static String safeTrim(String value) {
    return value == null ? "" : value.trim();
  }

  private static String loadResourceText(String path) {
    try (InputStream in = AgentTermComponent.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("Resource not found: " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load resource: " + path, e);
    }
  }
}
