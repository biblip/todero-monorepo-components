package com.example.todero.agent.dj.loop;

import com.social100.todero.common.command.CommandContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class AgentDecisionLoop {
  private static final Set<String> TERMINAL_CHANNELS = Set.of("status", "chat", "html", "auth", "error");

  public LoopResult run(LoopRequest request,
                        Planner planner,
                        ToolCallParser parser,
                        ToolExecutor executor,
                        FollowupBuilder followupBuilder,
                        StopPolicy stopPolicy) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(planner, "planner");
    Objects.requireNonNull(parser, "parser");
    Objects.requireNonNull(executor, "executor");
    StopPolicy policy = stopPolicy == null ? StopPolicy.terminalEventOnly() : stopPolicy;
    FollowupBuilder followup = followupBuilder == null ? FollowupBuilder.defaultBuilder() : followupBuilder;

    String workingPrompt = request.prompt();
    List<ToolStep> steps = new ArrayList<>();
    StopReason stopReason = StopReason.maxSteps();
    PlannerResult lastPlan = null;
    ToolCallResult lastTool = null;

    for (int step = 1; step <= request.maxSteps(); step++) {
      long stepStartedAtNs = System.nanoTime();
      long plannerStartedAtNs = System.nanoTime();
      try {
        lastPlan = planner.plan(workingPrompt, step);
      } catch (Exception e) {
        stopReason = StopReason.plannerException(safe(e.getMessage()));
        break;
      }
      long plannerDurationMs = elapsedMs(plannerStartedAtNs);

      String action = safe(lastPlan == null ? "" : lastPlan.action());
      if (action.isBlank() || "none".equalsIgnoreCase(action)) {
        stopReason = StopReason.actionNone();
        break;
      }

      ToolCall call;
      try {
        call = parser.parse(action);
      } catch (Exception e) {
        stopReason = StopReason.invalidAction(safe(e.getMessage()));
        break;
      }
      if (call == null || call.command().isBlank()) {
        stopReason = StopReason.invalidAction("Unable to parse action.");
        break;
      }

      long toolStartedAtNs = System.nanoTime();
      ToolEventAggregate aggregate = new ToolEventAggregate();
      ToolEventSink sink = event -> {
        aggregate.record(event);
        if (request.forwarder() != null) {
          request.forwarder().onEvent(event);
        }
      };
      try {
        lastTool = executor.execute(call, sink);
      } catch (Exception e) {
        lastTool = ToolCallResult.failure(call, "tool-execution-failed",
            "Tool execution failed: " + safe(e.getMessage()), aggregate);
      }
      if (lastTool != null && lastTool.aggregate() == null) {
        lastTool = new ToolCallResult(lastTool.ok(), lastTool.errorCode(), lastTool.message(), aggregate);
      }
      long toolDurationMs = elapsedMs(toolStartedAtNs);
      long stepDurationMs = elapsedMs(stepStartedAtNs);
      steps.add(new ToolStep(step, action, call, lastTool, plannerDurationMs, toolDurationMs, stepDurationMs));

      if (policy.shouldStop(call, lastTool, aggregate)) {
        stopReason = lastTool != null && lastTool.ok() ? StopReason.toolTerminal() : StopReason.toolFailed(lastTool);
        break;
      }
      if (!lastTool.ok()) {
        stopReason = StopReason.toolFailed(lastTool);
        break;
      }

      workingPrompt = followup.build(request.prompt(), lastPlan, call, lastTool, step);
    }

    Channels channels = buildChannels(stopReason, lastPlan, lastTool);
    return new LoopResult(stopReason, lastPlan, lastTool, steps, channels);
  }

  private static Channels buildChannels(StopReason stopReason, PlannerResult plan, ToolCallResult tool) {
    String chat = plan == null ? "" : safe(plan.user());
    String status = "";
    String html = plan == null ? "" : safe(plan.html());
    String webviewMode = html.isBlank() ? "none" : "html";
    boolean webviewReplace = !html.isBlank();
    String authJson = "";

    if (tool != null && tool.aggregate() != null) {
      ToolEventAggregate agg = tool.aggregate();
      if (!safe(agg.lastStatus()).isBlank()) {
        status = agg.lastStatus();
      }
      if (!safe(agg.lastChat()).isBlank()) {
        chat = agg.lastChat();
      }
      if (!safe(agg.lastHtml()).isBlank()) {
        html = agg.lastHtml();
        webviewMode = safe(agg.lastHtmlMode()).isBlank() ? "html" : safe(agg.lastHtmlMode());
        webviewReplace = agg.lastHtmlReplace();
      }
      if (!safe(agg.lastAuthJson()).isBlank()) {
        authJson = agg.lastAuthJson();
      }
      if (status.isBlank() && !safe(tool.message()).isBlank()) {
        status = tool.message();
      }
    }
    if (isFailureStop(stopReason)) {
      String failureMessage = safe(stopReason == null ? "" : stopReason.message);
      if (status.isBlank()) {
        status = failureMessage.isBlank() ? "Agent execution failed." : failureMessage;
      }
      if (chat.isBlank()) {
        chat = failureMessage.isBlank() ? "Agent execution failed." : failureMessage;
      }
    }
    if (status.isBlank()) {
      status = chat.isBlank() ? "completed" : chat;
    }
    return new Channels(chat, status, html, webviewMode, webviewReplace, authJson);
  }

  private static boolean isFailureStop(StopReason stopReason) {
    if (stopReason == null) {
      return false;
    }
    return switch (safe(stopReason.code)) {
      case "planner_exception", "invalid_action", "tool_failed", "max_steps_reached" -> true;
      default -> false;
    };
  }

  public interface Planner {
    PlannerResult plan(String prompt, int step) throws Exception;
  }

  public interface ToolExecutor {
    ToolCallResult execute(ToolCall call, ToolEventSink sink) throws Exception;
  }

  public interface ToolCallParser {
    ToolCall parse(String action) throws Exception;
  }

  public interface ToolEventSink {
    void onEvent(ToolEvent event);
  }

  public interface FollowupBuilder {
    String build(String initialPrompt, PlannerResult plan, ToolCall call, ToolCallResult tool, int step);

    static FollowupBuilder defaultBuilder() {
      return (initial, plan, call, tool, step) -> {
        String summary = tool == null ? "" : safe(tool.message());
        return initial + "\n\nTool result (" + call.command() + "): " + summary;
      };
    }
  }

  public interface StopPolicy {
    boolean shouldStop(ToolCall call, ToolCallResult tool, ToolEventAggregate aggregate);

    static StopPolicy terminalEventOnly() {
      return (call, tool, aggregate) -> aggregate != null && aggregate.terminalSeen();
    }
  }

  public record LoopRequest(String prompt,
                            String source,
                            String correlationId,
                            int maxSteps,
                            EventForwarder forwarder) {
  }

  public record PlannerResult(String action, String user, String html) {
  }

  public record ToolCall(String command, String args, String rawAction) {
  }

  public record ToolEvent(String channel, String phase, String body, String errorCode) {
  }

  public record ToolCallResult(boolean ok, String errorCode, String message, ToolEventAggregate aggregate) {
    public static ToolCallResult success(ToolCall call, String message, ToolEventAggregate aggregate) {
      return new ToolCallResult(true, "", message, aggregate);
    }

    public static ToolCallResult failure(ToolCall call, String errorCode, String message, ToolEventAggregate aggregate) {
      return new ToolCallResult(false, safe(errorCode), message, aggregate);
    }
  }

  public record ToolStep(int step,
                         String action,
                         ToolCall call,
                         ToolCallResult tool,
                         long plannerDurationMs,
                         long toolDurationMs,
                         long stepDurationMs) {
  }

  public record LoopResult(StopReason stopReason,
                           PlannerResult plan,
                           ToolCallResult tool,
                           List<ToolStep> steps,
                           Channels channels) {
  }

  public record Channels(String chat,
                         String status,
                         String html,
                         String webviewMode,
                         boolean webviewReplace,
                         String authJson) {
  }

  public static final class StopReason {
    public final String code;
    public final String message;

    private StopReason(String code, String message) {
      this.code = code;
      this.message = message;
    }

    public static StopReason actionNone() {
      return new StopReason("action_none", "No action required.");
    }

    public static StopReason toolTerminal() {
      return new StopReason("tool_terminal", "Tool completed.");
    }

    public static StopReason toolFailed(ToolCallResult tool) {
      String msg = tool == null ? "Tool failed." : safe(tool.message());
      return new StopReason("tool_failed", msg.isBlank() ? "Tool failed." : msg);
    }

    public static StopReason invalidAction(String message) {
      return new StopReason("invalid_action", message.isBlank() ? "Invalid action." : message);
    }

    public static StopReason plannerException(String message) {
      return new StopReason("planner_exception", message.isBlank() ? "Planner exception." : message);
    }

    public static StopReason maxSteps() {
      return new StopReason("max_steps_reached", "Max steps reached.");
    }
  }

  public static final class ToolEventAggregate {
    private String lastStatus = "";
    private String lastChat = "";
    private String lastHtml = "";
    private String lastHtmlMode = "";
    private boolean lastHtmlReplace = true;
    private String lastAuthJson = "";
    private String lastError = "";
    private boolean terminalSeen = false;

    public void record(ToolEvent event) {
      if (event == null) {
        return;
      }
      String channel = safe(event.channel()).toLowerCase();
      String phase = safe(event.phase()).toLowerCase();
      String body = safe(event.body());
      switch (channel) {
        case "status" -> lastStatus = body;
        case "chat" -> lastChat = body;
        case "html" -> {
          lastHtml = body;
          lastHtmlMode = "html";
          lastHtmlReplace = true;
        }
        case "auth" -> lastAuthJson = body;
        case "error" -> lastError = body;
        default -> {
        }
      }
      if (isTerminal(channel, phase, event.errorCode())) {
        terminalSeen = true;
      }
    }

    public boolean terminalSeen() {
      return terminalSeen;
    }

    public String lastStatus() {
      return lastStatus;
    }

    public String lastChat() {
      return lastChat;
    }

    public String lastHtml() {
      return lastHtml;
    }

    public String lastHtmlMode() {
      return lastHtmlMode;
    }

    public boolean lastHtmlReplace() {
      return lastHtmlReplace;
    }

    public String lastAuthJson() {
      return lastAuthJson;
    }

    public String lastError() {
      return lastError;
    }
  }

  public static final class EventForwarder implements ToolEventSink {
    private final CommandContext context;

    public EventForwarder(CommandContext context) {
      this.context = context;
    }

    @Override
    public void onEvent(ToolEvent event) {
      if (context == null || event == null) {
        return;
      }
      String channel = safe(event.channel()).toLowerCase();
      String phase = safe(event.phase()).toLowerCase();
      String body = safe(event.body());
      String emitPhase = "final".equals(phase) ? "final" : "progress";
      switch (channel) {
        case "status" -> context.emitStatus(body, emitPhase);
        case "chat" -> context.emitChat(body, emitPhase);
        case "html" -> context.emitHtml(body, emitPhase, "html", true);
        case "auth" -> context.emitAuthJson(body, emitPhase);
        case "error" -> context.emitStatus(body, emitPhase);
        default -> {
        }
      }
    }
  }

  public static boolean isTerminal(String channel, String phase, String errorCode) {
    String normalized = safe(channel).toLowerCase();
    if (!TERMINAL_CHANNELS.contains(normalized)) {
      return false;
    }
    if ("error".equals(normalized)) {
      return true;
    }
    if ("auth".equals(normalized)) {
      return !safe(errorCode).isBlank() || phase.isBlank() || "final".equals(phase);
    }
    return phase.isBlank() || "final".equals(phase);
  }

  private static long elapsedMs(long startedAtNs) {
    return Math.max(0, (System.nanoTime() - startedAtNs) / 1_000_000L);
  }

  private static String safe(String value) {
    return value == null ? "" : value.trim();
  }
}
