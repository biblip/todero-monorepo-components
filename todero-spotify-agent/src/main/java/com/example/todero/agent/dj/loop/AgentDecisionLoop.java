package com.example.todero.agent.dj.loop;

import com.social100.todero.common.command.CommandContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class AgentDecisionLoop {
  public LoopSession start(LoopRequest request,
                           Planner planner,
                           ToolCallParser parser,
                           AsyncToolExecutor executor,
                           FollowupBuilder followupBuilder,
                           StopPolicy stopPolicy,
                           CompletionListener listener,
                           Executor planningExecutor,
                           ScheduledExecutorService scheduler) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(planner, "planner");
    Objects.requireNonNull(parser, "parser");
    Objects.requireNonNull(executor, "executor");
    Objects.requireNonNull(listener, "listener");
    Objects.requireNonNull(planningExecutor, "planningExecutor");
    Objects.requireNonNull(scheduler, "scheduler");
    StopPolicy policy = stopPolicy == null ? StopPolicy.terminalToolOnly() : stopPolicy;
    FollowupBuilder followup = followupBuilder == null ? FollowupBuilder.defaultBuilder() : followupBuilder;

    LoopSession session = new LoopSession(
        request,
        planner,
        parser,
        executor,
        followup,
        policy,
        listener,
        planningExecutor,
        scheduler
    );
    session.start();
    return session;
  }

  public interface Planner {
    PlannerResult plan(String prompt, int step) throws Exception;
  }

  public interface AsyncToolExecutor {
    void execute(ToolCall call, ToolExecutionHandle handle) throws Exception;
  }

  public interface ToolCallParser {
    ToolCall parse(String action) throws Exception;
  }

  public interface ToolEventSink {
    void onEvent(ToolEvent event);
  }

  public interface ToolExecutionHandle extends ToolEventSink {
    void complete(String message);

    void fail(String errorCode, String message);
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

    static StopPolicy terminalToolOnly() {
      return (call, tool, aggregate) -> true;
    }
  }

  public interface CompletionListener {
    void onCompleted(LoopResult result);
  }

  public record LoopRequest(String prompt,
                            String source,
                            String correlationId,
                            int maxSteps,
                            long requestTimeoutMs,
                            long toolTimeoutMs,
                            EventForwarder forwarder) {
  }

  public record PlannerResult(String action, String user, String html) {
  }

  public record ToolCall(String command, String args, String rawAction) {
  }

  public record ToolEvent(String channel, String phase, boolean terminal, String body, String errorCode) {
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
                           Channels channels,
                           DeliverySummary delivery) {
  }

  public record Channels(String chat,
                         String status,
                         String html,
                         String webviewMode,
                         boolean webviewReplace,
                         String authJson) {
  }

  public record DeliverySummary(boolean terminalStatusForwarded,
                                boolean terminalChatForwarded,
                                boolean terminalHtmlForwarded,
                                boolean terminalAuthForwarded,
                                boolean terminalErrorForwarded) {
    public static DeliverySummary none() {
      return new DeliverySummary(false, false, false, false, false);
    }

    public boolean hasTerminalForwardedContent() {
      return terminalStatusForwarded || terminalChatForwarded || terminalHtmlForwarded
          || terminalAuthForwarded || terminalErrorForwarded;
    }
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

    public static StopReason requestTimeout(long timeoutMs) {
      return new StopReason("request_timeout", "Agent processing exceeded " + Math.max(1, timeoutMs / 1000L) + " seconds");
    }

    public static StopReason toolTimeout(long timeoutMs) {
      return new StopReason("tool_timeout", "Tool execution timed out after " + Math.max(1, timeoutMs / 1000L) + "s");
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
      if (event.terminal()) {
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
    private boolean terminalStatusForwarded;
    private boolean terminalChatForwarded;
    private boolean terminalHtmlForwarded;
    private boolean terminalAuthForwarded;
    private boolean terminalErrorForwarded;

    public EventForwarder(CommandContext context) {
      this.context = context;
    }

    @Override
    public synchronized void onEvent(ToolEvent event) {
      if (context == null || event == null) {
        return;
      }
      String channel = safe(event.channel()).toLowerCase();
      String phase = safe(event.phase()).toLowerCase();
      String body = safe(event.body());
      String emitPhase = "error".equals(phase) ? "error" : (event.terminal() ? "final" : "progress");
      boolean terminal = event.terminal();
      switch (channel) {
        case "status" -> {
          context.emitStatus(body, emitPhase);
          if (terminal) {
            terminalStatusForwarded = true;
          }
        }
        case "chat" -> {
          context.emitChat(body, emitPhase);
          if (terminal) {
            terminalChatForwarded = true;
          }
        }
        case "html" -> {
          context.emitHtml(body, emitPhase, "html", true);
          if (terminal) {
            terminalHtmlForwarded = true;
          }
        }
        case "auth" -> {
          context.emitAuthJson(body, emitPhase);
          if (terminal) {
            terminalAuthForwarded = true;
          }
        }
        case "error" -> {
          context.emitStatus(body, emitPhase);
          if (terminal) {
            terminalErrorForwarded = true;
          }
        }
        default -> {
        }
      }
    }

    public synchronized DeliverySummary summary() {
      return new DeliverySummary(
          terminalStatusForwarded,
          terminalChatForwarded,
          terminalHtmlForwarded,
          terminalAuthForwarded,
          terminalErrorForwarded
      );
    }
  }

  public final class LoopSession {
    private final LoopRequest request;
    private final Planner planner;
    private final ToolCallParser parser;
    private final AsyncToolExecutor executor;
    private final FollowupBuilder followupBuilder;
    private final StopPolicy stopPolicy;
    private final CompletionListener listener;
    private final Executor planningExecutor;
    private final ScheduledExecutorService scheduler;
    private final List<ToolStep> steps = new ArrayList<>();

    private String workingPrompt;
    private int currentStep;
    private boolean completed;
    private PlannerResult lastPlan;
    private ToolCallResult lastTool;
    private ToolCall activeCall;
    private String activeAction = "";
    private ToolEventAggregate activeAggregate;
    private long activePlannerDurationMs;
    private long activeStepStartedAtNs;
    private long activeToolStartedAtNs;
    private ScheduledFuture<?> requestTimeoutTask;
    private ScheduledFuture<?> toolTimeoutTask;

    private LoopSession(LoopRequest request,
                        Planner planner,
                        ToolCallParser parser,
                        AsyncToolExecutor executor,
                        FollowupBuilder followupBuilder,
                        StopPolicy stopPolicy,
                        CompletionListener listener,
                        Executor planningExecutor,
                        ScheduledExecutorService scheduler) {
      this.request = request;
      this.planner = planner;
      this.parser = parser;
      this.executor = executor;
      this.followupBuilder = followupBuilder;
      this.stopPolicy = stopPolicy;
      this.listener = listener;
      this.planningExecutor = planningExecutor;
      this.scheduler = scheduler;
      this.workingPrompt = safe(request.prompt());
    }

    public void start() {
      long requestTimeoutMs = request.requestTimeoutMs();
      if (requestTimeoutMs > 0) {
        requestTimeoutTask = scheduler.schedule(
            () -> finish(StopReason.requestTimeout(requestTimeoutMs), lastPlan, lastTool),
            requestTimeoutMs,
            TimeUnit.MILLISECONDS
        );
      }
      startPlanningStep();
    }

    private void startPlanningStep() {
      final int stepNumber;
      synchronized (this) {
        if (completed) {
          return;
        }
        if (currentStep >= request.maxSteps()) {
          finish(StopReason.maxSteps(), lastPlan, lastTool);
          return;
        }
        currentStep++;
        stepNumber = currentStep;
        activeStepStartedAtNs = System.nanoTime();
      }
      planningExecutor.execute(() -> runPlanner(stepNumber));
    }

    private void runPlanner(int stepNumber) {
      long plannerStartedAtNs = System.nanoTime();
      PlannerResult plan;
      try {
        plan = planner.plan(workingPrompt, stepNumber);
      } catch (Exception e) {
        finish(StopReason.plannerException(safe(e.getMessage())), null, null);
        return;
      }
      long plannerDurationMs = elapsedMs(plannerStartedAtNs);
      onPlannerResolved(stepNumber, plan, plannerDurationMs);
    }

    private void onPlannerResolved(int stepNumber, PlannerResult plan, long plannerDurationMs) {
      ToolCall call;
      synchronized (this) {
        if (completed || stepNumber != currentStep) {
          return;
        }
        lastPlan = plan;
        activePlannerDurationMs = plannerDurationMs;
        String action = safe(plan == null ? "" : plan.action());
        if (action.isBlank() || "none".equalsIgnoreCase(action)) {
          finish(StopReason.actionNone(), lastPlan, lastTool);
          return;
        }
        activeAction = action;
        try {
          call = parser.parse(action);
        } catch (Exception e) {
          finish(StopReason.invalidAction(safe(e.getMessage())), lastPlan, lastTool);
          return;
        }
        if (call == null || safe(call.command()).isBlank()) {
          finish(StopReason.invalidAction("Unable to parse action."), lastPlan, lastTool);
          return;
        }
        activeCall = call;
        activeAggregate = new ToolEventAggregate();
        activeToolStartedAtNs = System.nanoTime();
        long toolTimeoutMs = request.toolTimeoutMs();
        if (toolTimeoutMs > 0) {
          cancelToolTimeout();
          int scheduledStep = currentStep;
          toolTimeoutTask = scheduler.schedule(
              () -> onToolTimeout(scheduledStep, toolTimeoutMs),
              toolTimeoutMs,
              TimeUnit.MILLISECONDS
          );
        }
      }

      try {
        executor.execute(call, new SessionToolExecutionHandle(stepNumber));
      } catch (Exception e) {
        finish(toolFailedResult(call, activeAggregate, "tool-execution-failed",
            "Tool execution failed: " + safe(e.getMessage())), lastPlan);
      }
    }

    private void onToolTimeout(int stepNumber, long toolTimeoutMs) {
      synchronized (this) {
        if (completed || stepNumber != currentStep || activeCall == null) {
          return;
        }
        lastTool = ToolCallResult.failure(activeCall, "tool-timeout",
            StopReason.toolTimeout(toolTimeoutMs).message, activeAggregate);
      }
      finish(StopReason.toolTimeout(toolTimeoutMs), lastPlan, lastTool);
    }

    private void onToolEvent(int stepNumber, ToolEvent event) {
      synchronized (this) {
        if (completed || stepNumber != currentStep || activeAggregate == null) {
          return;
        }
        activeAggregate.record(event);
        if (request.forwarder() != null) {
          request.forwarder().onEvent(event);
        }
      }
    }

    private void onToolCompletion(int stepNumber, boolean ok, String errorCode, String message) {
      ToolCallResult toolResult;
      PlannerResult planSnapshot;
      ToolCall callSnapshot;
      synchronized (this) {
        if (completed || stepNumber != currentStep || activeCall == null) {
          return;
        }
        cancelToolTimeout();
        callSnapshot = activeCall;
        planSnapshot = lastPlan;
        ToolEventAggregate aggregate = activeAggregate;
        toolResult = ok
            ? ToolCallResult.success(callSnapshot, safe(message), aggregate)
            : ToolCallResult.failure(callSnapshot, errorCode, safe(message), aggregate);
        if (aggregate != null && safe(toolResult.message()).isBlank()) {
          String fallback = safe(aggregate.lastError());
          if (fallback.isBlank()) {
            fallback = safe(aggregate.lastStatus());
          }
          if (fallback.isBlank()) {
            fallback = safe(aggregate.lastChat());
          }
          toolResult = ok
              ? ToolCallResult.success(callSnapshot, fallback, aggregate)
              : ToolCallResult.failure(callSnapshot, errorCode, fallback, aggregate);
        }
        lastTool = toolResult;
        steps.add(new ToolStep(
            currentStep,
            activeAction,
            callSnapshot,
            toolResult,
            activePlannerDurationMs,
            elapsedMs(activeToolStartedAtNs),
            elapsedMs(activeStepStartedAtNs)
        ));
        activeCall = null;
        activeAction = "";
        activeAggregate = null;
      }

      if (stopPolicy.shouldStop(callSnapshot, toolResult, toolResult.aggregate())) {
        finish(ok ? StopReason.toolTerminal() : StopReason.toolFailed(toolResult), planSnapshot, toolResult);
        return;
      }
      if (!toolResult.ok()) {
        finish(StopReason.toolFailed(toolResult), planSnapshot, toolResult);
        return;
      }

      synchronized (this) {
        workingPrompt = followupBuilder.build(request.prompt(), planSnapshot, callSnapshot, toolResult, stepNumber);
      }
      startPlanningStep();
    }

    private ToolCallResult toolFailedResult(ToolCall call,
                                            ToolEventAggregate aggregate,
                                            String errorCode,
                                            String message) {
      return ToolCallResult.failure(call, errorCode, message, aggregate);
    }

    private void finish(ToolCallResult toolResult, PlannerResult plan) {
      finish(StopReason.toolFailed(toolResult), plan, toolResult);
    }

    private void finish(StopReason stopReason, PlannerResult plan, ToolCallResult tool) {
      LoopResult result;
      synchronized (this) {
        if (completed) {
          return;
        }
        completed = true;
        cancelToolTimeout();
        cancelRequestTimeout();
        if (plan != null) {
          lastPlan = plan;
        }
        if (tool != null) {
          lastTool = tool;
        }
        DeliverySummary delivery = request.forwarder() == null
            ? DeliverySummary.none()
            : request.forwarder().summary();
        Channels channels = buildChannels(stopReason, lastPlan, lastTool);
        result = new LoopResult(stopReason, lastPlan, lastTool, List.copyOf(steps), channels, delivery);
      }
      listener.onCompleted(result);
    }

    private void cancelRequestTimeout() {
      if (requestTimeoutTask != null) {
        requestTimeoutTask.cancel(false);
        requestTimeoutTask = null;
      }
    }

    private void cancelToolTimeout() {
      if (toolTimeoutTask != null) {
        toolTimeoutTask.cancel(false);
        toolTimeoutTask = null;
      }
    }

    private final class SessionToolExecutionHandle implements ToolExecutionHandle {
      private final int stepNumber;

      private SessionToolExecutionHandle(int stepNumber) {
        this.stepNumber = stepNumber;
      }

      @Override
      public void onEvent(ToolEvent event) {
        onToolEvent(stepNumber, event);
      }

      @Override
      public void complete(String message) {
        onToolCompletion(stepNumber, true, "", message);
      }

      @Override
      public void fail(String errorCode, String message) {
        onToolCompletion(stepNumber, false, errorCode, message);
      }
    }
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
      case "planner_exception", "invalid_action", "tool_failed", "max_steps_reached", "request_timeout", "tool_timeout" -> true;
      default -> false;
    };
  }

  private static long elapsedMs(long startedAtNs) {
    return startedAtNs <= 0 ? 0 : Math.max(0, (System.nanoTime() - startedAtNs) / 1_000_000L);
  }

  private static String safe(String value) {
    return value == null ? "" : value.trim();
  }
}
