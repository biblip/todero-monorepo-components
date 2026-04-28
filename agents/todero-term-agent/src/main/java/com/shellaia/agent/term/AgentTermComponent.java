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
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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

  private static final int DEFAULT_COLS = 120;
  private static final int DEFAULT_ROWS = 30;

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
  @SuppressWarnings("unused")
  private final Storage storage;

  public AgentTermComponent(Storage storage) {
    this.storage = storage;
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
    String sanitized = sanitizeName(rawName);
    if (sanitized.isEmpty()) {
      return respondError(context, "invalid name");
    }
    Path cwd = Path.of(System.getProperty("user.home"), "term", sanitized);
    if (!Files.exists(cwd) || !Files.isDirectory(cwd)) {
      return respondError(context, "cwd does not exist: " + cwd);
    }
    ObjectNode body = JSON.createObjectNode();
    body.put("name", rawName);
    body.put("cwd", cwd.toString());
    body.put("cols", DEFAULT_COLS);
    body.put("rows", DEFAULT_ROWS);
    body.put("screen_mode", true);
    body.putArray("env").addObject().put("k", "TERM").put("v", "xterm-256color");
    return passthrough(context, "open", body.toString());
  }

  @Action(group = MAIN_GROUP, command = "write", description = "Write to session. Usage: write <idOrName> <dataB64>")
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

  @Action(group = MAIN_GROUP, command = "close", description = "Close session. Usage: close <idOrName>")
  public Boolean close(CommandContext context) {
    return forwardSingleTarget(context, "close");
  }

  @Action(group = MAIN_GROUP, command = "kill", description = "Kill session. Usage: kill <idOrName>")
  public Boolean kill(CommandContext context) {
    return forwardSingleTarget(context, "kill");
  }

  @Action(group = MAIN_GROUP, command = "resize", description = "Resize session. Usage: resize <idOrName> <cols> <rows>")
  public Boolean resize(CommandContext context) {
    return forwardRawArgs(context, "resize");
  }

  @Action(group = MAIN_GROUP, command = "events", description = "Start/Stop terminal output events (pass-through). Usage: events ON|OFF <idOrName> [intervalMs] [maxBytes] [mode]")
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

    CompletableFuture<AiatpResponse> future = CompletableFuture.supplyAsync(() -> {
      String sessionsJson = executeTerm(context, "sessions", "");
      String actionJson = planActionWithLlm(context, prompt, sessionsJson);
      PlannedAction planned = parsePlannedAction(actionJson);
      if (planned == null) {
        return buildJsonResponse(400, "{\"ok\":false,\"error\":\"invalid_llm_response\"}");
      }
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
      // Reuse open path (fixed defaults + cwd policy).
      ObjectNode body = JSON.createObjectNode();
      body.put("name", name);
      String sanitized = sanitizeName(name);
      if (sanitized.isEmpty()) {
        return buildJsonResponse(400, "{\"ok\":false,\"error\":\"invalid_name\"}");
      }
      Path cwd = Path.of(System.getProperty("user.home"), "term", sanitized);
      if (!Files.exists(cwd) || !Files.isDirectory(cwd)) {
        return buildJsonResponse(400, "{\"ok\":false,\"error\":\"cwd_missing\",\"cwd\":\"" + escapeJson(cwd.toString()) + "\"}");
      }
      body.put("cwd", cwd.toString());
      body.put("cols", DEFAULT_COLS);
      body.put("rows", DEFAULT_ROWS);
      body.put("screen_mode", true);
      body.putArray("env").addObject().put("k", "TERM").put("v", "xterm-256color");
      return executeTermResponse(parentContext, "open", body.toString());
    }

    if ("sessions".equals(command)) {
      return executeTermResponse(parentContext, "sessions", "");
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
      return executeTermResponse(parentContext, "write", id + " " + Base64.getEncoder().encodeToString(new byte[] { 3 }));
    }

    if ("write_text".equals(command)) {
      String text = planned.text == null ? "" : planned.text;
      String b64 = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
      return executeTermResponse(parentContext, "write", id + " " + b64);
    }

    if ("write_b64".equals(command)) {
      String b64 = safeTrim(planned.dataB64);
      if (b64.isEmpty()) {
        return buildJsonResponse(400, "{\"ok\":false,\"error\":\"missing_dataB64\"}");
      }
      return executeTermResponse(parentContext, "write", id + " " + b64);
    }

    if ("screen_text".equals(command)) {
      long maxBytes = planned.maxBytes <= 0 ? 65536 : planned.maxBytes;
      return executeTermResponse(parentContext, "screen_text", id + " " + maxBytes);
    }

    if ("screen_diff".equals(command)) {
      long maxBytes = planned.maxBytes <= 0 ? 65536 : planned.maxBytes;
      long sinceFrame = planned.sinceFrameId < 0 ? 0 : planned.sinceFrameId;
      return executeTermResponse(parentContext, "screen_diff", id + " " + sinceFrame + " " + maxBytes);
    }

    if ("close".equals(command) || "kill".equals(command)) {
      return executeTermResponse(parentContext, command, id);
    }

    if ("resize".equals(command)) {
      int cols = planned.cols <= 0 ? DEFAULT_COLS : planned.cols;
      int rows = planned.rows <= 0 ? DEFAULT_ROWS : planned.rows;
      return executeTermResponse(parentContext, "resize", id + " " + cols + " " + rows);
    }

    return buildJsonResponse(400, "{\"ok\":false,\"error\":\"unsupported_command\"}");
  }

  private String planActionWithLlm(CommandContext parentContext, String prompt, String sessionsJson) {
    AgentContext agentContext = new AgentContext();
    parentContext.bindAgentLlmRegistry(agentContext);
    LLMClient llm = agentContext.systemLlm()
        .map(instance -> instance.client())
        .orElseThrow(() -> new IllegalStateException("No system-wide LLM available for com.shellaia.agent.term"));

    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("sessions_json", safeTrim(sessionsJson));
    ctx.put("fixed_defaults", Map.of(
        "cols", DEFAULT_COLS,
        "rows", DEFAULT_ROWS,
        "screen_mode", true,
        "term", "xterm-256color",
        "cwd_policy", "~/term/<sanitizedName>"
    ));
    try {
      String contextJson = JSON.writeValueAsString(ctx);
      return llm.chat(PROCESS_PROMPT, prompt, contextJson);
    } catch (Exception e) {
      throw new IllegalStateException("LLM plan failed");
    }
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
    AiatpResponse resp = executeTermResponse(context, cmd, body);
    context.complete(resp);
    return true;
  }

  private static String executeTerm(CommandContext parentContext, String cmd, String body) {
    AiatpResponse resp = executeTermResponse(parentContext, cmd, body);
    if (resp == null || resp.getBody() == null) return "";
    String text = AiatpIO.bodyToString(resp.getBody(), StandardCharsets.UTF_8);
    return text == null ? "" : text;
  }

  private static AiatpResponse executeTermResponse(CommandContext parentContext, String cmd, String body) {
    CompletableFuture<AiatpResponse> out = new CompletableFuture<>();
    AiatpRequest internalReq = AiatpRuntimeAdapter.request(
        "ACTION",
        "/" + TERM_COMPONENT + "/" + cmd,
        AiatpIO.Body.ofString(body == null ? "" : body, StandardCharsets.UTF_8)
    );
    internalReq = inheritParentRouting(parentContext, internalReq);
    internalReq = AiatpRuntimeAdapter.withHeader(internalReq, CommandContext.HDR_INTERNAL_EVENT_DELIVERY, "local");

    CommandContext internalContext = parentContext.cloneBuilder()
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
