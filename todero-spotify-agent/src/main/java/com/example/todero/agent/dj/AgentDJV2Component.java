package com.example.todero.agent.dj;

import com.example.todero.agent.dj.loop.AgentDecisionLoop;
import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.ai.action.CommandAgentResponse;
import com.social100.todero.common.ai.agent.AgentContext;
import com.social100.todero.common.ai.llm.LLMClient;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpIORequestWrapper;
import com.social100.todero.common.aiatpio.AiatpRequest;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.lineparser.LineParserUtil;
import com.social100.todero.common.routing.AgentCapabilityManifest;
import com.social100.todero.common.storage.Storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@AIAController(name = "com.shellaia.verbatim.agent.dj.v2",
    type = ServerType.AI,
    visible = true,
    description = "DJ Agent V2 with event-first decision loop",
    events = AgentDJV2Component.SimpleEvent.class,
    capabilityProvider = DjAgentV2Capabilities.class)
public class AgentDJV2Component {
  private static final String DJV2_BUILD_MARKER = "djv2-event-loop-2026-03-14T11:35Z";
  private static final String MAIN_GROUP = "Main";
  private static final String SPOTIFY_COMPONENT = "com.shellaia.verbatim.component.spotify";
  private static final int MAX_STEPS = 4;
  private static final long REQUEST_TIMEOUT_SECONDS = 60;
  private static final long TOOL_TIMEOUT_SECONDS = 12;
  private static final Set<String> SUPPORTED_COMMANDS = Set.of(
      "play", "pause", "stop", "volume", "volume-up", "volume-down", "mute",
      "move", "skip", "previous", "status", "queue", "playlist-play", "recently-played",
      "top-tracks", "top-artists", "recommend", "suggest", "events",
      "playlist-next", "playlist-remove", "playlists", "playlist-list", "playlist-add", "playlist-add-current",
      "playlist-create", "playlist-reorder", "playlist-remove-pos", "auth-begin", "auth-complete"
  );

  private final ExecutorService cognitionExecutor;
  private final ExecutorService toolDispatchExecutor;
  private final ScheduledExecutorService loopScheduler;

  public AgentDJV2Component(Storage storage) {
    System.out.println("[DJV2][BUILD] marker=" + DJV2_BUILD_MARKER);
    this.cognitionExecutor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "djv2-cognition");
      t.setDaemon(true);
      return t;
    });
    this.toolDispatchExecutor = Executors.newCachedThreadPool(r -> {
      Thread t = new Thread(r, "djv2-tool-dispatch");
      t.setDaemon(true);
      return t;
    });
    this.loopScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "djv2-loop-timer");
      t.setDaemon(true);
      return t;
    });
  }

  public enum SimpleEvent implements com.social100.todero.processor.EventDefinition {
    status("Built-in status channel event"),
    chat("Build-in chat channel event"),
    html("Built-in html channel event"),
    auth("Built-in auth channel event"),
    error("Built-in error channel event"),
    thought("Reasoning/thought channel"),
    AGENT_DECISION("Agent produced a structured decision event with trace metadata"),
    AGENT_REACTION("Agent reacted to a runtime event");

    private final String description;

    SimpleEvent(String description) {
      this.description = description;
    }

    @Override
    public String getDescription() {
      return description;
    }
  }

  @Action(group = MAIN_GROUP,
      command = "process",
      description = "Process a user goal with event-first decision loop")
  public Boolean process(CommandContext context) {
    String correlationId = newCorrelationId();
    String source = "process";
    String prompt = requestBody(context);
    System.out.println("[DJV2] process received correlationId=" + correlationId + " prompt=" + prompt);
    if (prompt.isBlank()) {
      emitStatusAndChat(context, "Prompt is required. Usage: process <goal>");
      return true;
    }
    context.emitStatus("Processing request...", "progress");
    cognitionExecutor.submit(() -> runLoopAndEmit(context, prompt, source, correlationId));
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "react",
      description = "Queue an external event for asynchronous agent reaction")
  public Boolean react(CommandContext context) {
    String correlationId = newCorrelationId();
    String raw = requestBody(context);
    if (raw.isBlank()) {
      emitStatusAndChat(context, "Event payload is required.");
      return true;
    }
    String prompt = "External event: " + raw;
    cognitionExecutor.submit(() -> runLoopAndEmit(context, prompt, "react", correlationId));

    context.emitStatus("Event accepted for asynchronous reaction.", "final");
    context.emitChat("Event accepted for asynchronous reaction.", "final");
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "capabilities",
      description = "Return runtime capability manifest for discovery")
  public Boolean capabilities(CommandContext context) {
    AgentCapabilityManifest manifest = new DjAgentV2Capabilities().manifest();
    context.emitChat(renderCapabilitiesEnvelope(manifest), "final");
    return true;
  }

  private AgentDecisionLoop.ToolCall parseAction(String action) {
    LineParserUtil.ParsedLine parsed = LineParserUtil.parse(action);
    if (parsed == null || safeTrim(parsed.first).isEmpty()) {
      throw new IllegalArgumentException("Unable to parse action: " + action);
    }
    String command = safeTrim(parsed.first).toLowerCase();
    String args = joinArgs(parsed.second, parsed.remaining);
    ValidatedAction validated = validateAndNormalizeAction(command, args);
    if (validated.error != null) {
      throw new IllegalArgumentException(validated.error);
    }
    return new AgentDecisionLoop.ToolCall(validated.command, validated.args, action);
  }

  private void executeSpotify(CommandContext parentContext,
                              AgentDecisionLoop.ToolCall call,
                              AgentDecisionLoop.ToolExecutionHandle handle) throws Exception {
    String requestId = "djv2-tool-" + newCorrelationId();
    AiatpRequest internalRequest = AiatpRuntimeAdapter.withHeader(
        AiatpRuntimeAdapter.request(
            "ACTION",
            "/" + SPOTIFY_COMPONENT + "/" + call.command(),
            AiatpIO.Body.ofString(safeTrim(call.args()), StandardCharsets.UTF_8)
        ),
        "X-Request-Id",
        requestId
    ).toBuilder()
        .requestId(requestId)
        .build();

    CommandContext internalContext = parentContext.cloneBuilder()
        .aiatpRequest(internalRequest)
        .eventConsumer(wrapper -> handleToolEvent(wrapper, requestId, handle))
        .build();

    toolDispatchExecutor.submit(() -> parentContext.execute(SPOTIFY_COMPONENT, call.command(), internalContext));
  }

  private void runLoopAndEmit(CommandContext context,
                              String prompt,
                              String source,
                              String correlationId) {
    try {
      AgentDecisionLoop loop = new AgentDecisionLoop();
      AgentDecisionLoop.EventForwarder forwarder = new AgentDecisionLoop.EventForwarder(context);
      AgentDecisionLoop.LoopRequest request = new AgentDecisionLoop.LoopRequest(
          prompt,
          source,
          correlationId,
          MAX_STEPS,
          REQUEST_TIMEOUT_SECONDS * 1000L,
          TOOL_TIMEOUT_SECONDS * 1000L,
          forwarder);
      AgentDecisionLoop.Planner planner = (workingPrompt, step) -> planNextAction(context, workingPrompt);
      AgentDecisionLoop.ToolCallParser parser = this::parseAction;
      AgentDecisionLoop.AsyncToolExecutor executor = (call, handle) -> executeSpotify(context, call, handle);
      loop.start(request, planner, parser, executor, null, null,
          result -> emitLoopResult(context, result, source, correlationId),
          cognitionExecutor,
          loopScheduler);
    } catch (Exception e) {
      emitStatusAndChat(context, "Agent execution failed: " + safeTrim(e.getMessage()));
    }
  }

  private void handleToolEvent(AiatpIORequestWrapper wrapper,
                               String expectedRequestId,
                               AgentDecisionLoop.ToolExecutionHandle handle) {
    if (wrapper == null || wrapper.getAiatpEvent() == null) {
      return;
    }
    com.social100.todero.common.aiatpio.AiatpEvent event = wrapper.getAiatpEvent();
    if (!"REQ".equalsIgnoreCase(safeTrim(event.getScope()))) {
      return;
    }
    String ref = safeTrim(event.getReference());
    if (!expectedRequestId.equals(ref)) {
      return;
    }
    String channel = safeTrim(event.getChannel()).toLowerCase();
    String phase = safeTrim(event.getPhase()).toLowerCase();
    String body = safeTrim(AiatpIO.bodyToString(event.getBody(), StandardCharsets.UTF_8));
    String errorCode = "";
    if ("auth".equals(channel)) {
      errorCode = extractAuthErrorCode(body);
    }
    if ("error".equals(channel) && errorCode.isBlank()) {
      errorCode = "tool-execution-failed";
    }
    boolean terminal = event.isTerminal();
    AgentDecisionLoop.ToolEvent toolEvent = new AgentDecisionLoop.ToolEvent(channel, phase, terminal, body, errorCode);
    handle.onEvent(toolEvent);
    if (terminal) {
      boolean ok = !"error".equals(channel) && errorCode.isBlank();
      String message = body.isBlank() ? (ok ? "completed" : "Tool failed.") : body;
      if (ok) {
        handle.complete(message);
      } else {
        handle.fail(errorCode.isBlank() ? "tool-execution-failed" : errorCode, message);
      }
    }
  }

  private AgentDecisionLoop.PlannerResult planNextAction(CommandContext context, String prompt) throws Exception {
    AgentContext agentContext = new AgentContext();
    context.bindAgentLlmRegistry(agentContext);
    LLMClient llm = agentContext.systemLlm()
        .map(instance -> instance.client())
        .orElseThrow(() -> new IllegalStateException(
            "No active system LLM available for DJV2 planning. Check llm-providers configuration and API key."));
    String raw;
    try {
      raw = llm.chat(loadSystemPrompt("prompts/default-system-prompt.md"), prompt, "{}");
    } catch (Exception e) {
      throw new IllegalStateException("Planner LLM invocation failed: " + safeTrim(e.getMessage()), e);
    }
    System.out.println("[DJV2] LLM response: " + raw);
    CommandAgentResponse response;
    try {
      response = parsePlannerResponse(raw);
    } catch (Exception e) {
      throw new IllegalStateException("Planner returned malformed output: " + safeTrim(e.getMessage()), e);
    }
    if (safeTrim(response.getAction()).isEmpty()
        && safeTrim(response.getUser()).isEmpty()
        && safeTrim(response.getHtml()).isEmpty()) {
      throw new IllegalStateException("Planner returned empty output.");
    }
    return new AgentDecisionLoop.PlannerResult(response.getAction(), response.getUser(), response.getHtml());
  }

  private CommandAgentResponse parsePlannerResponse(String raw) throws Exception {
    var root = extractFirstJsonBlock(raw);
    String request = readPath(root, "request");
    String action = readPath(root, "action");
    if (safeTrim(action).isEmpty()) {
      action = readPath(root, "plan.action");
    }
    String user = readPath(root, "user");
    if (safeTrim(user).isEmpty()) {
      user = readPath(root, "plan.user");
    }
    String html = readPath(root, "html");
    if (safeTrim(html).isEmpty()) {
      html = readPath(root, "plan.html");
    }
    return new CommandAgentResponse(request, action, user, html);
  }

  private void emitLoopResult(CommandContext context,
                              AgentDecisionLoop.LoopResult result,
                              String source,
                              String correlationId) {
    if (result == null || result.channels() == null) {
      emitStatusAndChat(context, "Agent completed without result.");
      return;
    }
    AgentDecisionLoop.Channels channels = result.channels();
    AgentDecisionLoop.DeliverySummary delivery = result.delivery() == null
        ? AgentDecisionLoop.DeliverySummary.none()
        : result.delivery();
    String stopCode = safeTrim(result.stopReason() == null ? null : result.stopReason().code);
    String stopMessage = safeTrim(result.stopReason() == null ? null : result.stopReason().message);
    boolean failed = isFailureStop(stopCode);
    boolean suppressSuccessReplay = "tool_terminal".equals(stopCode) && delivery.hasTerminalForwardedContent();
    if (failed) {
      String failure = stopMessage.isEmpty() ? "Agent execution failed." : stopMessage;
      context.emitStatus(failure, "final");
      context.emitChat(failure, "final");
    } else if (!suppressSuccessReplay && !delivery.terminalStatusForwarded() && !safeTrim(channels.status()).isEmpty()) {
      context.emitStatus(channels.status(), "final");
    }
    if (!failed && !suppressSuccessReplay && !delivery.terminalChatForwarded() && !safeTrim(channels.chat()).isEmpty()) {
      context.emitChat(channels.chat(), "final");
    }
    if (!suppressSuccessReplay && !delivery.terminalHtmlForwarded() && !safeTrim(channels.html()).isEmpty()) {
      context.emitHtml(channels.html(), "final",
          safeTrim(channels.webviewMode()).isEmpty() ? "html" : channels.webviewMode(),
          channels.webviewReplace());
    }
    if (!suppressSuccessReplay && !delivery.terminalAuthForwarded() && !safeTrim(channels.authJson()).isEmpty()) {
      context.emitAuthJson(channels.authJson(), "final");
    }
    context.emitThought("source=" + source + " correlationId=" + correlationId
        + " stopReason=" + result.stopReason().code, "final");
  }

  private static boolean isFailureStop(String stopCode) {
    return "planner_exception".equals(stopCode)
        || "invalid_action".equals(stopCode)
        || "tool_failed".equals(stopCode)
        || "request_timeout".equals(stopCode)
        || "tool_timeout".equals(stopCode)
        || "max_steps_reached".equals(stopCode);
  }

  private void emitStatusAndChat(CommandContext context, String message) {
    context.emitStatus(message, "final");
    context.emitChat(message, "final");
  }

  private static String loadSystemPrompt(String resourcePath) {
    try (InputStream in = AgentDJV2Component.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new RuntimeException("Missing system prompt resource: " + resourcePath);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read system prompt: " + resourcePath, e);
    }
  }

  private static String renderCapabilitiesEnvelope(AgentCapabilityManifest manifest) {
    try {
      return "{"
          + "\"status\":\"ok\","
          + "\"source\":\"runtime_capabilities_action\","
          + "\"manifest\":" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(manifest)
          + "}";
    } catch (Exception e) {
      return "{\"status\":\"error\",\"error\":\"capability_manifest_encode_failed\"}";
    }
  }

  private static com.fasterxml.jackson.databind.JsonNode extractFirstJsonBlock(String raw) throws Exception {
    String s = safeTrim(raw);
    if (s.isEmpty()) {
      return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    }
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
    } catch (Exception ignored) {
    }
    int first = s.indexOf('{');
    int last = s.lastIndexOf('}');
    if (first >= 0 && last > first) {
      String sub = s.substring(first, last + 1);
      return new com.fasterxml.jackson.databind.ObjectMapper().readTree(sub);
    }
    return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
  }

  private static String readPath(com.fasterxml.jackson.databind.JsonNode root, String path) {
    if (root == null || path == null || path.isBlank()) {
      return "";
    }
    com.fasterxml.jackson.databind.JsonNode cur = root;
    for (String p : path.split("\\.")) {
      if (cur == null || cur.isMissingNode() || cur.isNull()) {
        return "";
      }
      cur = cur.path(p);
    }
    if (cur == null || cur.isMissingNode() || cur.isNull()) {
      return "";
    }
    return cur.isTextual() ? cur.asText() : cur.toString();
  }

  private static String joinArgs(String second, String remaining) {
    String a = safeTrim(second);
    String b = safeTrim(remaining);
    if (a.isEmpty()) {
      return b;
    }
    if (b.isEmpty()) {
      return a;
    }
    return a + " " + b;
  }

  private static String extractAuthErrorCode(String authJson) {
    String text = safeTrim(authJson);
    if (text.isEmpty()) {
      return "";
    }
    try {
      com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
      return safeTrim(readPath(root, "errorCode"));
    } catch (Exception ignored) {
      return "";
    }
  }

  private static ValidatedAction validateAndNormalizeAction(String command, String rawArgs) {
    if (!SUPPORTED_COMMANDS.contains(command)) {
      return ValidatedAction.error(command, rawArgs, "unsupported-command",
          "Planned command is not allowed: " + command);
    }
    String args = safeTrim(rawArgs);
    switch (command) {
      case "pause", "stop", "mute", "volume-up", "volume-down", "playlist-next", "playlist-remove" -> {
        if (!args.isEmpty()) {
          return ValidatedAction.error(command, args, "invalid-arguments",
              "Command '" + command + "' does not accept arguments.");
        }
        return ValidatedAction.ok(command, "");
      }
      case "play" -> {
        return ValidatedAction.ok(command, args);
      }
      case "volume" -> {
        if (args.isEmpty()) {
          return ValidatedAction.error(command, args, "invalid-arguments",
              "Command 'volume' requires 1 integer argument (0..150).");
        }
        try {
          int volume = Integer.parseInt(args);
          if (volume < 0 || volume > 150) {
            return ValidatedAction.error(command, args, "invalid-arguments", "Volume must be between 0 and 150.");
          }
          return ValidatedAction.ok(command, String.valueOf(volume));
        } catch (NumberFormatException e) {
          return ValidatedAction.error(command, args, "invalid-arguments", "Volume must be an integer.");
        }
      }
      case "skip" -> {
        if (args.isEmpty()) {
          return ValidatedAction.error(command, args, "invalid-arguments",
              "Command 'skip' requires an integer seconds offset.");
        }
        try {
          int seconds = Integer.parseInt(args);
          if (seconds < -3600 || seconds > 3600 || seconds == 0) {
            return ValidatedAction.error(command, args, "invalid-arguments",
                "Skip seconds must be between -3600 and 3600 (excluding 0).");
          }
          return ValidatedAction.ok(command, String.valueOf(seconds));
        } catch (NumberFormatException e) {
          return ValidatedAction.error(command, args, "invalid-arguments", "Skip seconds must be an integer.");
        }
      }
      case "move" -> {
        if (args.isEmpty()) {
          return ValidatedAction.error(command, args, "invalid-arguments",
              "Command 'move' requires a time (HH:MM:SS, MM:SS, or SS).");
        }
        if (!args.matches("^\\d{1,2}:\\d{1,2}:\\d{1,2}$|^\\d{1,2}:\\d{1,2}$|^\\d+$")) {
          return ValidatedAction.error(command, args, "invalid-arguments",
              "Move argument must match HH:MM:SS, MM:SS, or SS.");
        }
        return ValidatedAction.ok(command, args);
      }
      case "status" -> {
        if (args.isEmpty()) {
          return ValidatedAction.ok(command, "");
        }
        if ("all".equalsIgnoreCase(args)) {
          return ValidatedAction.ok(command, "all");
        }
        return ValidatedAction.error(command, args, "invalid-arguments", "Status only accepts optional argument: all.");
      }
      case "previous", "queue" -> {
        if (!args.isEmpty()) {
          return ValidatedAction.error(command, args, "invalid-arguments",
              "Command '" + command + "' does not accept arguments.");
        }
        return ValidatedAction.ok(command, "");
      }
      default -> {
        return ValidatedAction.ok(command, args);
      }
    }
  }

  private static String safeTrim(String v) {
    return v == null ? "" : v.trim();
  }

  private static String requestBody(CommandContext context) {
    AiatpRequest request = context == null ? null : context.getAiatpRequest();
    if (request == null || request.getBody() == null) {
      return "";
    }
    String body = AiatpIO.bodyToString(request.getBody(), StandardCharsets.UTF_8);
    return body == null ? "" : body.trim();
  }

  private static String newCorrelationId() {
    return UUID.randomUUID().toString();
  }

  private record ValidatedAction(String command, String args, String errorCode, String error) {
    static ValidatedAction ok(String command, String args) {
      return new ValidatedAction(command, args, "", null);
    }

    static ValidatedAction error(String command, String args, String errorCode, String message) {
      return new ValidatedAction(command, args, errorCode, message);
    }
  }
}
