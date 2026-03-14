package com.example.todero.agent.dj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.ai.action.CommandAgentResponse;
import com.social100.todero.common.ai.action.AgentFailureResponseFactory;
import com.social100.todero.common.ai.agent.AgentContext;
import com.social100.todero.common.ai.agent.AgentDefinition;
import com.social100.todero.common.ai.agent.AgentPrompt;
import com.social100.todero.common.ai.llm.LLMClient;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpIORequestWrapper;
import com.social100.todero.common.agent.work.AppendActionRequest;
import com.social100.todero.common.agent.work.CompletionRequest;
import com.social100.todero.common.agent.work.FailureRequest;
import com.social100.todero.common.agent.work.OwnerAgentWorkLedger;
import com.social100.todero.common.agent.work.SharedAgentWorkLedgerRegistry;
import com.social100.todero.common.agent.work.SubtaskRequest;
import com.social100.todero.common.agent.work.WorkActionRecord;
import com.social100.todero.common.agent.work.WorkActionType;
import com.social100.todero.common.agent.work.WorkItemRecord;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.lineparser.LineParserUtil;
import com.social100.todero.common.routing.AgentCapabilityManifest;
import com.social100.todero.common.storage.Storage;
import com.social100.todero.processor.EventDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@AIAController(name = "com.shellaia.verbatim.agent.dj",
    type = ServerType.AI,
    visible = false,
    description = "DJ Agent with iterative planning and event-driven reactions",
    events = AgentDJComponent.SimpleEvent.class,
    capabilityProvider = DjAgentCapabilities.class)
public class AgentDJComponent {
  private static final String MAIN_GROUP = "Main";
  private static final String SPOTIFY_COMPONENT = "com.shellaia.verbatim.component.spotify";
  private static final int MAX_STEPS = 4;
  private static final long REQUEST_TIMEOUT_SECONDS = 45;
  private static final Set<String> VALID_EVENT_TYPES = Set.of("informational", "state_change", "anomaly");
  private static final int LEDGER_SUMMARY_MAX_ENTRIES = 12;
  private static final int LEDGER_SUMMARY_MAX_CHARS = 1400;
  private static final String LEDGER_OWNER_ID = "com.shellaia.verbatim.agent.dj";
  private static final Pattern TRACK_URI_PATTERN = Pattern.compile("spotify:track:[A-Za-z0-9]+");
  private static final Pattern PLAYLIST_ROW_PATTERN = Pattern.compile("^\\s*\\d+\\)\\s*(.+?)\\s*\\[id=([^,\\]]+).*$");
  private static final Pattern ADD_QUOTED_SONG_PATTERN = Pattern.compile("(?i)\\badd\\s+[\"']([^\"']+)[\"']");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> SUPPORTED_COMMANDS = Set.of(
      "play", "pause", "stop", "volume", "volume-up", "volume-down", "mute",
      "move", "skip", "previous", "status", "queue", "playlist-play", "recently-played",
      "top-tracks", "top-artists", "recommend", "suggest", "events",
      "playlist-next", "playlist-remove", "playlists", "playlist-list", "playlist-add", "playlist-add-current",
      "playlist-create", "playlist-reorder", "playlist-remove-pos"
  );
  private static final Set<String> AUTH_REQUIRED_CODES = Set.of("auth_required", "auth_scope_missing");

  //private final AgentDefinition agentDefinition;
  private final ExecutorService cognitionExecutor;
  private final Path ledgerPath;
  private volatile OwnerAgentWorkLedger ownerLedger;
  private final Object ledgerInitLock = new Object();
  private final ObjectMapper mapper = new ObjectMapper();
  private final Map<String, PendingAuthRetry> pendingAuthRetries = new ConcurrentHashMap<>();

  public AgentDJComponent(Storage storage) {
    this.cognitionExecutor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "dj-agent-cognition");
      t.setDaemon(true);
      return t;
    });
    this.ledgerPath = defaultLedgerPath();
  }

  private static String loadSystemPrompt(String resourcePath) {
    try (InputStream in = AgentDJComponent.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new RuntimeException("Missing system prompt resource: " + resourcePath);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read system prompt: " + resourcePath, e);
    }
  }

  @Action(group = MAIN_GROUP,
      command = "process",
      description = "Process a user goal with iterative planning, tool execution, and tool-response evaluation")
  public Boolean process(CommandContext context) {
    final String correlationId = newCorrelationId();
    final String source = "process";
    String prompt = AiatpIO.bodyToString(context.getHttpRequest().body(), StandardCharsets.UTF_8).trim();
    System.out.println("[DJ-AGENT] process received correlationId=" + correlationId + " prompt=" + prompt);
    if (prompt.isEmpty()) {
      context.response(renderContractEnvelope(
          source,
          correlationId,
          "invalid_request",
          "Prompt is required. Usage: process <goal>",
          "Prompt is required. Usage: process <goal>",
          null,
          "none",
          false
      ));
      return true;
    }
    WorkItemRecord rootWork;
    try {
      ensureOwnerLedger(context);
      rootWork = openRootLedgerWork(source, prompt, true, correlationId);
    } catch (Exception e) {
      context.response(renderContractEnvelope(
          source,
          correlationId,
          "ledger_unavailable",
          "Unable to create execution task in Agent Work Ledger: " + safeTrim(e.getMessage()),
          "Agent Work Ledger initialization failed.",
          null,
          "none",
          false
      ));
      return true;
    }

    CompletableFuture<LoopResult> future = CompletableFuture.supplyAsync(
        () -> runGoalLoop(context, prompt, true, source, correlationId, rootWork.workId()),
        cognitionExecutor
    );

    try {
      LoopResult result = future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      context.response(renderLoopResultAsJson(result));
      context.emitThought(renderDecisionEventAsJson(result), "final");
    } catch (TimeoutException e) {
      String timeoutMessage = "Agent processing exceeded " + REQUEST_TIMEOUT_SECONDS + " seconds";
      context.response(renderContractEnvelope(
          source,
          correlationId,
          "timeout",
          timeoutMessage,
          timeoutMessage,
          null,
          "none",
          false
      ));
    } catch (Exception e) {
      String message = safeTrim(e.getMessage()).isEmpty() ? "Unexpected agent failure." : safeTrim(e.getMessage());
      context.response(renderContractEnvelope(
          source,
          correlationId,
          "agent_failed",
          message,
          "Agent execution failed.",
          null,
          "none",
          false
      ));
    }
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "react",
      description = "Queue an external event for asynchronous agent reaction. Usage: react event_type=<informational|state_change|anomaly>&source=<src>&message=<text>[&kind=<k>][&at_ms=<epochMs>] OR JSON payload")
  public Boolean react(CommandContext context) {
    final String correlationId = newCorrelationId();
    final String source = "react";
    String rawPayload = AiatpIO.bodyToString(context.getHttpRequest().body(), StandardCharsets.UTF_8).trim();
    System.out.println("[DJ-AGENT] react received correlationId=" + correlationId + " payload=" + rawPayload);
    if (rawPayload.isEmpty()) {
      context.response(renderContractEnvelope(
          source,
          correlationId,
          "invalid_event_payload",
          "Event payload is required. Usage: " + usageReact(),
          "Event payload is required.",
          null,
          "none",
          false
      ));
      return true;
    }
    ParsedEventPayload parsed = parseEventPayload(rawPayload);
    if (parsed.error != null) {
      context.response(renderContractEnvelope(
          source,
          correlationId,
          "invalid_event_payload",
          parsed.error + " Usage: " + usageReact(),
          parsed.error,
          null,
          "none",
          false
      ));
      return true;
    }
    final EventPayload event = parsed.payload;
    String eventPrompt = buildEventPrompt(event);
    WorkItemRecord rootWork;
    try {
      ensureOwnerLedger(context);
      rootWork = openRootLedgerWork(source, eventPrompt, false, correlationId);
      appendLedgerAction(rootWork.workId(), WorkActionType.NOTE,
          "event_type=" + event.eventType + " source=" + event.source + " kind=" + event.kind + " at_ms=" + event.atMs,
          null, null, null, null, null);
    } catch (Exception e) {
      context.response(renderContractEnvelope(
          source,
          correlationId,
          "ledger_unavailable",
          "Unable to create reaction task in Agent Work Ledger: " + safeTrim(e.getMessage()),
          "Agent Work Ledger initialization failed.",
          null,
          "none",
          false
      ));
      return true;
    }

    cognitionExecutor.submit(() -> {
      LoopResult result = runGoalLoop(context, eventPrompt, false, source, correlationId, rootWork.workId());
      System.out.println("[DJ-AGENT] react completed correlationId=" + correlationId
          + " stopReason=" + result.stopReason
          + " action=" + safeTrim(result.finalResponse == null ? null : result.finalResponse.getAction()));
    });

    context.response(renderContractEnvelope(
        source,
        correlationId,
        null,
        "accepted",
        "Event accepted for asynchronous reaction.",
        null,
        "none",
        false
    ));
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "capabilities",
      description = "Return runtime capability manifest for discovery")
  public Boolean capabilities(CommandContext context) {
    AgentCapabilityManifest manifest = new DjAgentCapabilities().manifest();
    context.response(renderCapabilitiesEnvelope(manifest));
    return true;
  }

  private LoopResult runGoalLoop(CommandContext parentContext,
                                 String initialPrompt,
                                 boolean interactiveRequest,
                                 String source,
                                 String correlationId,
                                 String rootWorkId) {
    long startedAtNs = System.nanoTime();
    AgentContext agentContext = new AgentContext();
    parentContext.bindAgentLlmRegistry(agentContext);
    LLMClient llm = agentContext.systemLlm()
        .map(instance -> instance.client())
        .orElseThrow(() -> new IllegalStateException(
            "No system-wide LLM available in registry for agent " + LEDGER_OWNER_ID));
    PlaylistAddIntent playlistAddIntent = detectPlaylistAddIntent(initialPrompt);
    String currentPlaylistSongTitle = detectCurrentPlaylistSongTitle(initialPrompt);

    appendLedgerAction(rootWorkId, WorkActionType.PLAN,
        "loop_started source=" + source + " interactive=" + interactiveRequest + " correlationId=" + correlationId,
        null, null, null, null, null);

    AuthCompletionIntent completionIntent = parseAuthCompletionIntent(initialPrompt);
    if (completionIntent != null) {
      return runAuthCompletionFlow(parentContext, completionIntent, initialPrompt, source, correlationId, rootWorkId);
    }

    String workingPrompt = injectLedgerSummary(initialPrompt, rootWorkId);
    CommandAgentResponse lastResponse = null;
    List<ToolStep> toolSteps = new ArrayList<>();
    StopReason stopReason = StopReason.MAX_STEPS_REACHED;

    for (int step = 1; step <= MAX_STEPS; step++) {
      long stepStartedAtNs = System.nanoTime();
      CommandAgentResponse response;
      long planStartedAtNs = System.nanoTime();
      try {
        response = planNextAction(llm, new AgentPrompt(workingPrompt), agentContext);
      } catch (Exception e) {
        long plannerDurationMs = elapsedMs(planStartedAtNs);
        long stepDurationMs = elapsedMs(stepStartedAtNs);
        toolSteps.add(new ToolStep(step, "none", "planner", "", "Planner failed.", plannerDurationMs, 0, stepDurationMs));
        stopReason = StopReason.PLANNER_EXCEPTION;
        appendLedgerAction(rootWorkId, WorkActionType.FAIL,
            "planner_exception step=" + step + " message=" + safeTrim(e.getMessage()),
            "planner", null, null, null, safeTrim(e.getMessage()));
        if (lastResponse == null) {
          lastResponse = fallbackResponse(
              initialPrompt,
              stopReason,
              correlationId,
              AgentFailureResponseFactory.detailsFromThrowable(e)
          );
        }
        break;
      }
      long plannerDurationMs = elapsedMs(planStartedAtNs);
      lastResponse = response;

      String action = safeTrim(response.getAction());
      action = coercePlannerAction(initialPrompt, action, step, interactiveRequest);
      action = coerceCurrentPlaylistSongAddAction(action, currentPlaylistSongTitle, step, interactiveRequest);
      action = coercePlaylistAddAction(action, toolSteps, playlistAddIntent, step, interactiveRequest);
      appendLedgerAction(rootWorkId, WorkActionType.PLAN,
          "step=" + step + " planner_action=" + safeTrim(action),
          null, null, null, plannerDurationMs, null);
      System.out.println("[DJ-AGENT] planner action correlationId=" + correlationId + " step=" + step + " action=" + action);
      if (action.isEmpty() || "none".equalsIgnoreCase(action)) {
        long stepDurationMs = elapsedMs(stepStartedAtNs);
        toolSteps.add(new ToolStep(step, "none", "none", "", "", plannerDurationMs, 0, stepDurationMs));
        stopReason = StopReason.ACTION_NONE;
        appendLedgerAction(rootWorkId, WorkActionType.DECISION,
            "step=" + step + " action=none stopReason=" + stopReason.code,
            null, null, null, null, null);
        break;
      }

      long toolStartedAtNs = System.nanoTime();
      ToolExecution tool = executeSpotifyAction(parentContext, action);
      long toolDurationMs = elapsedMs(toolStartedAtNs);
      long stepDurationMs = elapsedMs(stepStartedAtNs);
      toolSteps.add(new ToolStep(step, action, tool.command, tool.args, tool.output, plannerDurationMs, toolDurationMs, stepDurationMs));
      ownerLedger().recordToolStep(rootWorkId, tool.command, tool.args, redactedForLogs(safeTrim(tool.rawOutput())),
          toolDurationMs, safeTrim(tool.errorCode), safeTrim(tool.executed ? null : tool.output));

      if (interactiveRequest && isAuthRequiredToolResult(tool)) {
        System.out.println("[DJ-AGENT] auth escalation triggered correlationId=" + correlationId
            + " step=" + step + " command=" + tool.command + " reason=auth_required_tool_result");
        ToolExecution authBegin = executeSpotifyInternal(parentContext, "auth-begin", "redirect-profile=app owner=" + LEDGER_OWNER_ID);
        System.out.println("[DJ-AGENT] auth escalation auth-begin executed="
            + authBegin.executed + " errorCode=" + safeTrim(authBegin.errorCode()));
        toolSteps.add(new ToolStep(step, "auth-begin", "auth-begin", "redirect-profile=app owner=" + LEDGER_OWNER_ID,
            authBegin.rawOutput(), plannerDurationMs, toolDurationMs, stepDurationMs));
        appendLedgerAction(rootWorkId, WorkActionType.NOTE,
            "AUTH_REQUIRED command=" + tool.command + " code=" + safeTrim(tool.errorCode()),
            tool.command, tool.args, redactedForLogs(tool.rawOutput()), toolDurationMs, tool.errorCode(), null);
        if (authBegin.executed) {
          String sessionId = extractAuthSessionId(authBegin.rawOutput());
          if (!sessionId.isEmpty()) {
            pendingAuthRetries.put(sessionId, new PendingAuthRetry(tool.command, tool.args, initialPrompt, rootWorkId));
          }
          appendLedgerAction(rootWorkId, WorkActionType.NOTE,
              "AUTH_BEGIN sessionId=" + (sessionId.isEmpty() ? "unknown" : sessionId),
              "auth-begin", "redirect-profile=app owner=" + LEDGER_OWNER_ID, null, null, null, null);
          lastResponse = new CommandAgentResponse(
              initialPrompt,
              "none",
              "Spotify authorization required. Open the authorization link and complete authentication.",
              null
          );
          stopReason = StopReason.AUTH_REQUIRED;
          break;
        }
        stopReason = StopReason.TOOL_EXECUTION_FAILED;
        lastResponse = failureResponse(initialPrompt, stopReason, "auth-begin", correlationId, authBegin.output);
        appendLedgerAction(rootWorkId, WorkActionType.RECOVERY,
            "auth_begin_failed step=" + step + " stopReason=" + stopReason.code,
            "auth-begin", "redirect-profile=app owner=" + LEDGER_OWNER_ID, safeTrim(authBegin.output), null, safeTrim(authBegin.errorCode));
        break;
      }

      if (!tool.executed) {
        if ("unsupported-command".equals(tool.errorCode())) {
          stopReason = StopReason.UNSUPPORTED_ACTION;
        } else if ("invalid-arguments".equals(tool.errorCode())) {
          stopReason = StopReason.INVALID_ARGUMENTS;
        } else {
          stopReason = StopReason.TOOL_EXECUTION_FAILED;
        }
        lastResponse = failureResponse(initialPrompt, stopReason, tool.command, correlationId, tool.output);
        appendLedgerAction(rootWorkId, WorkActionType.RECOVERY,
            "tool_failure step=" + step + " stopReason=" + stopReason.code,
            tool.command, tool.args, safeTrim(tool.output), toolDurationMs, safeTrim(tool.errorCode));
        break;
      }

      if (!interactiveRequest && step >= 2) {
        // For background reactions, keep loops short and non-blocking.
        stopReason = StopReason.BACKGROUND_STEP_LIMIT;
        break;
      }

      workingPrompt = buildFollowupPrompt(initialPrompt, response, tool, step, rootWorkId);
      if (step == MAX_STEPS) {
        stopReason = StopReason.MAX_STEPS_REACHED;
      }
    }

    if (lastResponse == null) {
      lastResponse = fallbackResponse(initialPrompt, stopReason, correlationId, "");
    }
    finalizeLedgerWork(rootWorkId, stopReason, lastResponse, toolSteps);
    return new LoopResult(initialPrompt, lastResponse, toolSteps, stopReason, elapsedMs(startedAtNs), source, correlationId);
  }

  private ToolExecution executeSpotifyAction(CommandContext parentContext, String action) {
    LineParserUtil.ParsedLine parsed = LineParserUtil.parse(action);
    if (parsed == null || safeTrim(parsed.first).isEmpty()) {
      return ToolExecution.error("invalid-action", "invalid-action", "", "Unable to parse action: " + action, "");
    }

    String command = safeTrim(parsed.first).toLowerCase();
    String args = joinArgs(parsed.second, parsed.remaining);
    System.out.println("[DJ-AGENT] executeSpotifyAction parsed command=" + command + " args=" + args);
    ValidatedAction validated = validateAndNormalizeAction(command, args);
    if (validated.error != null) {
      System.out.println("[DJ-AGENT] validateAndNormalizeAction failed command=" + command + " args=" + args + " error=" + validated.error());
      return ToolExecution.error(validated.errorCode(), command, args, validated.error(), "");
    }
    command = validated.command;
    args = validated.args;
    return executeSpotifyInternal(parentContext, command, args);
  }

  private ToolExecution executeSpotifyInternal(CommandContext parentContext, String command, String args) {
    String argsWithFormat = safeTrim(args).isBlank() ? "--format json" : safeTrim(args) + " --format json";
    String internalRequestId = "dj-tool-" + newCorrelationId();
    CompletableFuture<SpotifyExecutionResult> outFuture = new CompletableFuture<>();

    CommandContext internalContext = parentContext.cloneBuilder()
        .httpRequest(AiatpIO.HttpRequest.newBuilder("ACTION", "/" + SPOTIFY_COMPONENT + "/" + command)
            .setHeader("X-Request-Id", internalRequestId)
            .body(AiatpIO.Body.ofString(argsWithFormat, StandardCharsets.UTF_8))
            .build())
        .eventConsumer(wrapper -> completeFromSpotifyEvent(outFuture, wrapper, internalRequestId))
        .consumer(response -> {
          String body = AiatpIO.bodyToString(response.body(), StandardCharsets.UTF_8);
          if (!safeTrim(body).isEmpty()) {
            outFuture.complete(new SpotifyExecutionResult(
                "response",
                "",
                "",
                body,
                "",
                response.status()
            ));
          }
        })
        .build();

    try {
      System.out.println("[DJ-AGENT] dispatching context.execute component=" + SPOTIFY_COMPONENT + " command=" + command + " args=" + argsWithFormat);
      parentContext.execute(SPOTIFY_COMPONENT, command, internalContext);
      SpotifyExecutionResult executionResult = outFuture.get(12, TimeUnit.SECONDS);
      String safeOutput = safeTrim(executionResult.body);
      System.out.println("Tool response [" + command + "]: " + redactedForLogs(safeOutput));
      boolean fromEvent = "event".equals(executionResult.source);
      SpotifyEnvelope envelope = fromEvent ? new SpotifyEnvelope(false, true, executionResult.errorCode, safeOutput) : parseSpotifyEnvelope(safeOutput);
      String effectiveOutput = fromEvent
          ? safeOutput
          : (envelope.recognized ? envelope.message : safeOutput);
      String eventErrorCode = safeTrim(executionResult.errorCode);
      boolean failed = executionResult.status >= 400
          || (!eventErrorCode.isEmpty())
          || ("error".equals(executionResult.channel))
          || (!fromEvent && envelope.recognized && !envelope.ok)
          || (!fromEvent && !envelope.recognized && isExecutionFailure(executionResult.status, safeOutput));
      if (failed) {
        System.out.println("[DJ-AGENT] tool execution classified failure command=" + command + " status=" + executionResult.status + " body=" + redactedForLogs(safeOutput));
        String errorCode = !eventErrorCode.isEmpty()
            ? eventErrorCode
            : (envelope.recognized && safeTrim(envelope.errorCode).length() > 0
            ? envelope.errorCode
            : "tool-execution-failed");
        return ToolExecution.error(errorCode, command, args, effectiveOutput, safeOutput);
      }
      System.out.println("[DJ-AGENT] tool execution success command=" + command + " status=" + executionResult.status);
      return new ToolExecution(true, command, args, effectiveOutput, "", safeOutput);
    } catch (TimeoutException e) {
      System.out.println("[DJ-AGENT] tool execution timeout command=" + command + " after 12s");
      return ToolExecution.error("tool-execution-failed", command, args, "Tool execution timed out after 12s", "");
    } catch (Exception e) {
      System.out.println("Tool execution failure [" + command + "]: " + e.getMessage());
      return ToolExecution.error("tool-execution-failed", command, args, "Tool execution failed: " + e.getMessage(), "");
    }
  }

  private void completeFromSpotifyEvent(CompletableFuture<SpotifyExecutionResult> outFuture,
                                        AiatpIORequestWrapper wrapper,
                                        String expectedRequestId) {
    if (outFuture.isDone() || wrapper == null || wrapper.getXEvent() == null) {
      return;
    }
    AiatpIO.XProto.Event event = wrapper.getXEvent();
    if (event.scope != AiatpIO.XProto.EventScope.REQ || !expectedRequestId.equals(safeTrim(event.reference))) {
      return;
    }
    String channel = safeTrim(event.channel).toLowerCase();
    String phase = safeTrim(event.headers().getFirst("Event-Phase")).toLowerCase();
    String body = safeTrim(AiatpIO.bodyToString(event.body(), StandardCharsets.UTF_8));
    String errorCode = "auth".equals(channel) ? extractAuthErrorCode(body) : "";
    if ("error".equals(channel) && errorCode.isEmpty()) {
      errorCode = "tool-execution-failed";
    }
    boolean terminal = "error".equals(channel)
        || ("auth".equals(channel) && (!errorCode.isEmpty() || "final".equals(phase)))
        || ("status".equals(channel) && "final".equals(phase));
    if (!terminal) {
      return;
    }
    outFuture.complete(new SpotifyExecutionResult("event", channel, phase, body, errorCode, 200));
  }

  private String extractAuthErrorCode(String authJson) {
    String text = safeTrim(authJson);
    if (text.isEmpty()) {
      return "";
    }
    try {
      JsonNode root = mapper.readTree(text);
      return safeTrim(readPath(root, "errorCode"));
    } catch (Exception ignored) {
      return "";
    }
  }

  private CommandAgentResponse planNextAction(LLMClient llm, AgentPrompt prompt, AgentContext context) throws Exception {
    String contextJson = mapper.writeValueAsString(context.getAll());
    String raw = llm.chat(loadSystemPrompt("prompts/default-system-prompt.md"), prompt.getMessage(), contextJson);
    System.out.println("LLM Response");
    System.out.println(raw);
    JsonNode root = extractFirstJsonBlockLocal(raw);

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

  private JsonNode extractFirstJsonBlockLocal(String raw) {
    String s = safeTrim(raw);
    if (s.isEmpty()) {
      return mapper.createObjectNode();
    }
    try {
      return mapper.readTree(s);
    } catch (Exception ignored) {
    }

    int first = s.indexOf('{');
    int last = s.lastIndexOf('}');
    if (first >= 0 && last > first) {
      String sub = s.substring(first, last + 1);
      try {
        return mapper.readTree(sub);
      } catch (Exception ignored) {
      }
    }
    return mapper.createObjectNode();
  }

  private static String readPath(JsonNode root, String path) {
    if (root == null || path == null || path.isBlank()) {
      return "";
    }
    JsonNode cur = root;
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

  private static boolean isExecutionFailure(int statusCode, String output) {
    if (statusCode >= 400) {
      return true;
    }
    String v = safeTrim(output);
    if (v.isEmpty()) {
      return false;
    }
    return v.startsWith("ComponentEntry with name")
        || v.startsWith("CommandDescriptor '")
        || v.startsWith("Failed to execute command")
        || v.matches("(?i)^[a-z0-9-]+ failed(?:\\s+\\[error_code=[a-z0-9_\\-]+])?:.*");
  }

  private SpotifyEnvelope parseSpotifyEnvelope(String output) {
    String text = safeTrim(output);
    if (text.isEmpty()) {
      return new SpotifyEnvelope(false, true, "", "");
    }
    try {
      JsonNode root = mapper.readTree(text);
      if (!root.has("ok") || !root.has("message")) {
        return new SpotifyEnvelope(false, true, "", text);
      }
      boolean ok = root.path("ok").asBoolean(true);
      String message = readPath(root, "message");
      String errorCode = readPath(root, "errorCode");
      return new SpotifyEnvelope(true, ok, errorCode, message.isBlank() ? text : message);
    } catch (Exception ignored) {
      return new SpotifyEnvelope(false, true, "", text);
    }
  }

  private static ValidatedAction validateAndNormalizeAction(String command, String rawArgs) {
    if (!SUPPORTED_COMMANDS.contains(command)) {
      return ValidatedAction.error(command, rawArgs, "unsupported-command",
          "Planned command is not allowed: " + command + ". Allowed: " + String.join(", ", SUPPORTED_COMMANDS));
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
          return ValidatedAction.error(command, args, "invalid-arguments", "Command 'volume' requires 1 integer argument (0..150).");
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
          return ValidatedAction.error(command, args, "invalid-arguments", "Command 'skip' requires an integer seconds offset.");
        }
        try {
          int seconds = Integer.parseInt(args);
          if (seconds < -3600 || seconds > 3600 || seconds == 0) {
            return ValidatedAction.error(command, args, "invalid-arguments", "Skip seconds must be between -3600 and 3600 (excluding 0).");
          }
          return ValidatedAction.ok(command, String.valueOf(seconds));
        } catch (NumberFormatException e) {
          return ValidatedAction.error(command, args, "invalid-arguments", "Skip seconds must be an integer.");
        }
      }
      case "move" -> {
        if (args.isEmpty()) {
          return ValidatedAction.error(command, args, "invalid-arguments", "Command 'move' requires a time (HH:MM:SS, MM:SS, or SS).");
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
          return ValidatedAction.error(command, args, "invalid-arguments", "Command '" + command + "' does not accept arguments.");
        }
        return ValidatedAction.ok(command, "");
      }
      case "playlists" -> {
        if (args.isEmpty()) {
          return ValidatedAction.ok(command, "");
        }
        String[] tokens = args.split("\\s+");
        if (tokens.length > 2) {
          return ValidatedAction.error(command, args, "invalid-arguments", "playlists accepts up to two numeric args: [limit] [offset].");
        }
        for (String token : tokens) {
          if (!token.matches("^\\d+$")) {
            return ValidatedAction.error(command, args, "invalid-arguments", "playlists args must be numeric.");
          }
        }
        return ValidatedAction.ok(command, args);
      }
      case "playlist-list" -> {
        if (args.isEmpty()) {
          return ValidatedAction.error(command, args, "invalid-arguments", "playlist-list requires playlistId and optional numeric limit.");
        }
        String[] tokens = args.split("\\s+");
        if (tokens.length > 2) {
          return ValidatedAction.error(command, args, "invalid-arguments", "playlist-list usage: <playlistId> [limit].");
        }
        if (tokens[0].isBlank()) {
          return ValidatedAction.error(command, args, "invalid-arguments", "playlist-list requires non-empty playlistId.");
        }
        if (tokens.length == 2) {
          if (!tokens[1].matches("^\\d+$")) {
            return ValidatedAction.error(command, args, "invalid-arguments", "playlist-list limit must be numeric.");
          }
          int limit = Integer.parseInt(tokens[1]);
          if (limit < 1 || limit > 100) {
            return ValidatedAction.error(command, args, "invalid-arguments", "playlist-list limit must be between 1 and 100.");
          }
          return ValidatedAction.ok(command, tokens[0] + " " + limit);
        }
        return ValidatedAction.ok(command, tokens[0]);
      }
      case "playlist-add" -> {
        if (args.isEmpty()) {
          return ValidatedAction.error(command, args, "invalid-arguments", "playlist-add usage: <playlistId> <trackUri> [trackUri ...].");
        }
        String[] tokens = args.split("\\s+");
        if (tokens.length < 2) {
          return ValidatedAction.error(command, args, "invalid-arguments", "playlist-add usage: <playlistId> <trackUri> [trackUri ...].");
        }
        if (tokens[0].isBlank()) {
          return ValidatedAction.error(command, args, "invalid-arguments", "playlist-add requires non-empty playlistId.");
        }
        for (int i = 1; i < tokens.length; i++) {
          String uri = safeTrim(tokens[i]);
          if (uri.isEmpty()) {
            return ValidatedAction.error(command, args, "invalid-arguments", "playlist-add requires one or more non-empty track URIs.");
          }
        }
        return ValidatedAction.ok(command, args);
      }
      case "playlist-add-current" -> {
        if (args.isEmpty()) {
          return ValidatedAction.error(command, args, "invalid-arguments", "playlist-add-current requires a song title.");
        }
        return ValidatedAction.ok(command, args);
      }
      case "playlist-play" -> {
        if (args.isEmpty()) {
          return ValidatedAction.error(command, args, "invalid-arguments", "playlist-play requires playlistId/uri.");
        }
        String[] tokens = args.split("\\s+");
        if (tokens.length > 2) {
          return ValidatedAction.error(command, args, "invalid-arguments", "playlist-play usage: <playlistId|uri> [offset].");
        }
        if (tokens.length == 2 && !tokens[1].matches("^-?\\d+$")) {
          return ValidatedAction.error(command, args, "invalid-arguments", "playlist-play offset must be an integer.");
        }
        return ValidatedAction.ok(command, args);
      }
      case "recently-played" -> {
        if (args.isEmpty()) {
          return ValidatedAction.ok(command, "");
        }
        if (!args.matches("^\\d+$")) {
          return ValidatedAction.error(command, args, "invalid-arguments", "recently-played only accepts optional numeric limit.");
        }
        int limit = Integer.parseInt(args);
        if (limit < 1 || limit > 50) {
          return ValidatedAction.error(command, args, "invalid-arguments", "recently-played limit must be between 1 and 50.");
        }
        return ValidatedAction.ok(command, String.valueOf(limit));
      }
      case "top-tracks", "top-artists" -> {
        if (args.isEmpty()) {
          return ValidatedAction.ok(command, "");
        }
        String[] tokens = args.split("\\s+");
        Integer limit = null;
        String range = null;
        for (String token : tokens) {
          if (token.matches("^\\d+$")) {
            if (limit != null) {
              return ValidatedAction.error(command, args, "invalid-arguments", command + " limit specified more than once.");
            }
            limit = Integer.parseInt(token);
            if (limit < 1 || limit > 50) {
              return ValidatedAction.error(command, args, "invalid-arguments", command + " limit must be between 1 and 50.");
            }
          } else {
            String normalized = token.toLowerCase();
            if (!Set.of("short_term", "medium_term", "long_term", "short", "medium", "long").contains(normalized)) {
              return ValidatedAction.error(command, args, "invalid-arguments", command + " range must be short_term, medium_term, or long_term.");
            }
            if (range != null) {
              return ValidatedAction.error(command, args, "invalid-arguments", command + " range specified more than once.");
            }
            range = normalized;
          }
        }
        String normalized = "";
        if (limit != null) {
          normalized = String.valueOf(limit);
        }
        if (range != null) {
          if (!normalized.isEmpty()) normalized += " ";
          normalized += range;
        }
        return ValidatedAction.ok(command, normalized);
      }
      case "recommend", "suggest" -> {
        if (args.isEmpty()) {
          return ValidatedAction.error(command, args, "invalid-arguments", command + " requires a theme/query.");
        }
        String[] tokens = args.split("\\s+");
        if (tokens.length > 1 && tokens[tokens.length - 1].matches("^\\d+$")) {
          int limit = Integer.parseInt(tokens[tokens.length - 1]);
          int max = "suggest".equals(command) ? 12 : 20;
          if (limit < 1 || limit > max) {
            return ValidatedAction.error(command, args, "invalid-arguments", command + " limit must be between 1 and " + max + ".");
          }
        }
        return ValidatedAction.ok(command, args);
      }
      case "playlist-create" -> {
        if (args.isEmpty()) {
          return ValidatedAction.error(command, args, "invalid-arguments", "playlist-create requires a name.");
        }
        return ValidatedAction.ok(command, args);
      }
      case "playlist-reorder" -> {
        String[] tokens = args.split("\\s+");
        if (tokens.length < 3 || tokens.length > 4) {
          return ValidatedAction.error(command, args, "invalid-arguments", "playlist-reorder usage: <playlistId> <rangeStart> <insertBefore> [rangeLength].");
        }
        try {
          int rs = Integer.parseInt(tokens[1]);
          int ib = Integer.parseInt(tokens[2]);
          int rl = tokens.length == 4 ? Integer.parseInt(tokens[3]) : 1;
          if (rs < 0 || ib < 0 || rl < 1 || rl > 100) {
            return ValidatedAction.error(command, args, "invalid-arguments", "playlist-reorder expects non-negative positions and rangeLength 1..100.");
          }
          return ValidatedAction.ok(command, tokens[0] + " " + rs + " " + ib + (tokens.length == 4 ? " " + rl : ""));
        } catch (NumberFormatException e) {
          return ValidatedAction.error(command, args, "invalid-arguments", "playlist-reorder positions must be integers.");
        }
      }
      case "playlist-remove-pos" -> {
        String[] tokens = args.split("\\s+");
        if (tokens.length != 2) {
          return ValidatedAction.error(command, args, "invalid-arguments", "playlist-remove-pos usage: <playlistId> <position>.");
        }
        try {
          int position = Integer.parseInt(tokens[1]);
          if (position < 0) {
            return ValidatedAction.error(command, args, "invalid-arguments", "playlist-remove-pos position must be >= 0.");
          }
          return ValidatedAction.ok(command, tokens[0] + " " + position);
        } catch (NumberFormatException e) {
          return ValidatedAction.error(command, args, "invalid-arguments", "playlist-remove-pos position must be an integer.");
        }
      }
      case "events" -> {
        if (args.isEmpty()) {
          return ValidatedAction.error(command, args, "invalid-arguments",
              "Command 'events' requires ON or OFF. Optional: intervalMs notify-agent=true|false notify-min-ms=<ms> output=typed|legacy filter=all|track|playback|device|context.");
        }
        String[] tokens = args.split("\\s+");
        String mode = tokens[0].toUpperCase();
        if (!"ON".equals(mode) && !"OFF".equals(mode)) {
          return ValidatedAction.error(command, args, "invalid-arguments", "events first argument must be ON or OFF.");
        }
        long interval = 1500;
        String notifyArg = "";
        String notifyMinArg = "";
        String outputArg = "";
        String filterArg = "";
        Set<String> seen = new HashSet<>();
        for (int i = 1; i < tokens.length; i++) {
          String token = tokens[i].trim();
          if (token.isEmpty()) {
            continue;
          }
          if (token.matches("^\\d+$")) {
            if (!seen.add("interval")) {
              return ValidatedAction.error(command, args, "invalid-arguments", "events interval specified more than once.");
            }
            try {
              interval = Long.parseLong(token);
            } catch (NumberFormatException ignored) {
              return ValidatedAction.error(command, args, "invalid-arguments", "events interval must be a positive integer.");
            }
            if (interval < 250 || interval > 60000) {
              return ValidatedAction.error(command, args, "invalid-arguments", "events interval must be between 250 and 60000 ms.");
            }
          } else if (token.startsWith("notify-agent=")) {
            if (!seen.add("notify-agent")) {
              return ValidatedAction.error(command, args, "invalid-arguments", "events notify-agent specified more than once.");
            }
            String v = token.substring("notify-agent=".length()).toLowerCase();
            if (!"true".equals(v) && !"false".equals(v)) {
              return ValidatedAction.error(command, args, "invalid-arguments", "notify-agent must be true or false.");
            }
            notifyArg = "notify-agent=" + v;
          } else if (token.startsWith("notify-min-ms=")) {
            if (!seen.add("notify-min-ms")) {
              return ValidatedAction.error(command, args, "invalid-arguments", "events notify-min-ms specified more than once.");
            }
            String raw = token.substring("notify-min-ms=".length());
            long minMs;
            try {
              minMs = Long.parseLong(raw);
            } catch (NumberFormatException ignored) {
              return ValidatedAction.error(command, args, "invalid-arguments", "notify-min-ms must be a non-negative integer.");
            }
            if (minMs < 0 || minMs > 120000) {
              return ValidatedAction.error(command, args, "invalid-arguments", "notify-min-ms must be between 0 and 120000.");
            }
            notifyMinArg = "notify-min-ms=" + minMs;
          } else if (token.startsWith("output=")) {
            if (!seen.add("output")) {
              return ValidatedAction.error(command, args, "invalid-arguments", "events output specified more than once.");
            }
            String raw = token.substring("output=".length()).toLowerCase();
            if (!"typed".equals(raw) && !"legacy".equals(raw)) {
              return ValidatedAction.error(command, args, "invalid-arguments", "output must be typed or legacy.");
            }
            outputArg = "output=" + raw;
          } else if ("typed".equalsIgnoreCase(token) || "legacy".equalsIgnoreCase(token)) {
            if (!seen.add("output")) {
              return ValidatedAction.error(command, args, "invalid-arguments", "events output specified more than once.");
            }
            outputArg = "output=" + token.toLowerCase();
          } else if ("true".equalsIgnoreCase(token) || "false".equalsIgnoreCase(token)) {
            if (!seen.add("notify-agent")) {
              return ValidatedAction.error(command, args, "invalid-arguments", "events notify-agent specified more than once.");
            }
            notifyArg = "notify-agent=" + token.toLowerCase();
          } else if (token.startsWith("filter=")) {
            if (!seen.add("filter")) {
              return ValidatedAction.error(command, args, "invalid-arguments", "events filter specified more than once.");
            }
            String raw = token.substring("filter=".length()).toLowerCase();
            if (!Set.of("all", "track", "playback", "device", "context").contains(raw)) {
              return ValidatedAction.error(command, args, "invalid-arguments", "filter must be one of: all, track, playback, device, context.");
            }
            filterArg = "filter=" + raw;
          } else {
            return ValidatedAction.error(command, args, "invalid-arguments",
                "Unsupported events argument: " + token);
          }
        }
        String normalizedArgs = mode;
        if (seen.contains("interval")) {
          normalizedArgs += " " + interval;
        }
        if (!notifyArg.isEmpty()) {
          normalizedArgs += " " + notifyArg;
        }
        if (!notifyMinArg.isEmpty()) {
          normalizedArgs += " " + notifyMinArg;
        }
        if (!outputArg.isEmpty()) {
          normalizedArgs += " " + outputArg;
        }
        if (!filterArg.isEmpty()) {
          normalizedArgs += " " + filterArg;
        }
        return ValidatedAction.ok(command, normalizedArgs);
      }
      default -> {
        return ValidatedAction.error(command, args, "unsupported-command", "Unsupported command: " + command);
      }
    }
  }

  private String buildEventPrompt(EventPayload event) {
    StringBuilder prompt = new StringBuilder(512);
    prompt.append("You received a Spotify runtime event. ")
        .append("Decide whether to act. If action is needed, return one spotify command. ")
        .append("If no action is needed, return action=none.\n\nEvent:\n")
        .append("event_type=").append(event.eventType).append('\n')
        .append("source=").append(event.source).append('\n')
        .append("kind=").append(event.kind).append('\n')
        .append("at_ms=").append(event.atMs).append('\n')
        .append("message=").append(event.message);
    if (!safeTrim(event.structuredJson).isEmpty()) {
      prompt.append("\nstructured_event_json=").append(event.structuredJson);
    }
    return prompt.toString();
  }

  private static String coercePlannerAction(String initialPrompt,
                                            String plannerAction,
                                            int step,
                                            boolean interactiveRequest) {
    String action = safeTrim(plannerAction);
    if (action.toLowerCase().startsWith("recommend ")) {
      action = "suggest " + safeTrim(action.substring("recommend".length()));
    }
    if (!interactiveRequest || step != 1) {
      return action;
    }

    String prompt = safeTrim(initialPrompt).toLowerCase();
    boolean actionNone = action.isEmpty() || "none".equalsIgnoreCase(action);

    if (isRecommendationIntent(prompt) && actionNone) {
      String seed = inferRecommendationSeed(initialPrompt);
      return "suggest " + seed + " 10";
    }

    if (isTrackEventsIntent(prompt)) {
      if (actionNone) {
        return "events ON 1500 output=typed filter=track";
      }
      if (action.toLowerCase().startsWith("events ")) {
        String normalized = action;
        if (normalized.toLowerCase().contains("filter=all")) {
          normalized = normalized.replaceAll("(?i)filter=all", "filter=track");
        } else if (!normalized.toLowerCase().contains("filter=")) {
          normalized += " filter=track";
        }
        if (!normalized.toLowerCase().contains("output=") && !normalized.toLowerCase().contains(" typed")
            && !normalized.toLowerCase().contains(" legacy")) {
          normalized += " output=typed";
        }
        return normalized;
      }
    }
    return action;
  }

  private static String coerceCurrentPlaylistSongAddAction(String plannerAction,
                                                           String currentPlaylistSongTitle,
                                                           int step,
                                                           boolean interactiveRequest) {
    if (!interactiveRequest || step != 1) {
      return plannerAction;
    }
    if (safeTrim(currentPlaylistSongTitle).isEmpty()) {
      return plannerAction;
    }
    String action = safeTrim(plannerAction);
    if (!action.isEmpty() && !"none".equalsIgnoreCase(action)) {
      return action;
    }
    return "playlist-add-current " + currentPlaylistSongTitle;
  }

  private static String coercePlaylistAddAction(String plannerAction,
                                                List<ToolStep> toolSteps,
                                                PlaylistAddIntent intent,
                                                int step,
                                                boolean interactiveRequest) {
    if (!interactiveRequest || intent == null) {
      return plannerAction;
    }
    String action = safeTrim(plannerAction);
    if (!action.isEmpty() && !"none".equalsIgnoreCase(action)) {
      return action;
    }
    if (step > 3) {
      return action;
    }

    Optional<String> trackUri = findCurrentTrackUri(toolSteps);
    if (trackUri.isEmpty()) {
      return "status all";
    }
    Optional<String> playlistId = findPlaylistIdByName(toolSteps, intent.playlistName());
    if (playlistId.isEmpty()) {
      return "playlists 50 0";
    }
    return "playlist-add " + playlistId.get() + " " + trackUri.get();
  }

  private static Optional<String> findCurrentTrackUri(List<ToolStep> toolSteps) {
    for (int i = toolSteps.size() - 1; i >= 0; i--) {
      ToolStep step = toolSteps.get(i);
      String command = safeTrim(step.toolCommand).toLowerCase();
      if (!command.equals("status") && !command.equals("play")) {
        continue;
      }
      Matcher m = TRACK_URI_PATTERN.matcher(safeTrim(step.toolOutput));
      if (m.find()) {
        return Optional.of(m.group());
      }
    }
    return Optional.empty();
  }

  private static Optional<String> findPlaylistIdByName(List<ToolStep> toolSteps, String playlistName) {
    String target = normalizeForCompare(playlistName);
    if (target.isEmpty()) {
      return Optional.empty();
    }
    for (int i = toolSteps.size() - 1; i >= 0; i--) {
      ToolStep step = toolSteps.get(i);
      if (!"playlists".equalsIgnoreCase(safeTrim(step.toolCommand))) {
        continue;
      }
      String[] lines = safeTrim(step.toolOutput).split("\\R");
      for (String line : lines) {
        Matcher row = PLAYLIST_ROW_PATTERN.matcher(line);
        if (!row.matches()) {
          continue;
        }
        String foundName = normalizeForCompare(row.group(1));
        String foundId = safeTrim(row.group(2));
        if (foundId.isEmpty()) {
          continue;
        }
        if (foundName.equals(target) || foundName.contains(target) || target.contains(foundName)) {
          return Optional.of(foundId);
        }
      }
    }
    return Optional.empty();
  }

  private static PlaylistAddIntent detectPlaylistAddIntent(String prompt) {
    String raw = safeTrim(prompt);
    String lower = raw.toLowerCase();
    if (lower.isEmpty()) {
      return null;
    }
    boolean mentionsPlaylist = lower.contains("playlist");
    boolean addIntent = lower.contains("add ") || lower.contains("add this song")
        || lower.contains("agrega") || lower.contains("añade");
    boolean currentSong = lower.contains("current song")
        || lower.contains("song that is playing")
        || lower.contains("playing right now")
        || lower.contains("la que está sonando")
        || lower.contains("canción actual");
    if (!mentionsPlaylist || !addIntent || !currentSong) {
      return null;
    }

    String extracted = extractPlaylistName(raw);
    if (safeTrim(extracted).isEmpty()) {
      return null;
    }
    return new PlaylistAddIntent(extracted.trim());
  }

  private static String detectCurrentPlaylistSongTitle(String prompt) {
    String raw = safeTrim(prompt);
    String lower = raw.toLowerCase();
    if (lower.isEmpty()) {
      return "";
    }
    boolean hasAddVerb = lower.contains("add ");
    if (!hasAddVerb) {
      return "";
    }
    boolean isCurrentTrackByNameIntent = lower.contains("song that is playing")
        || lower.contains("currently playing")
        || lower.contains("playing right now")
        || lower.contains("la que está sonando")
        || lower.contains("canción actual");
    if (isCurrentTrackByNameIntent) {
      return "";
    }
    Matcher quoted = ADD_QUOTED_SONG_PATTERN.matcher(raw);
    if (quoted.find()) {
      return safeTrim(quoted.group(1));
    }
    Matcher addToPlaylist = Pattern.compile("(?i)\\badd\\s+(.+?)\\s+to\\s+(?:my\\s+|current\\s+)?playlist\\b").matcher(raw);
    if (addToPlaylist.find()) {
      return safeTrim(addToPlaylist.group(1).replaceAll("^[\"']|[\"']$", ""));
    }
    return "";
  }

  private static String extractPlaylistName(String rawPrompt) {
    String trimmed = safeTrim(rawPrompt);
    if (trimmed.isEmpty()) {
      return "";
    }
    Pattern[] patterns = new Pattern[] {
        Pattern.compile("(?i)playlist\\s+called\\s+['\"]?([^'\".!?]+)['\"]?"),
        Pattern.compile("(?i)playlist\\s+named\\s+['\"]?([^'\".!?]+)['\"]?"),
        Pattern.compile("(?i)playlist\\s+llamada\\s+['\"]?([^'\".!?]+)['\"]?"),
        Pattern.compile("(?i)playlist\\s+([A-Za-z0-9][^.!?]+)$")
    };
    for (Pattern p : patterns) {
      Matcher m = p.matcher(trimmed);
      if (m.find()) {
        return safeTrim(m.group(1));
      }
    }
    return "";
  }

  private static String normalizeForCompare(String value) {
    return safeTrim(value).toLowerCase().replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ");
  }

  private static boolean isRecommendationIntent(String prompt) {
    return prompt.contains("recommend")
        || prompt.contains("similar to")
        || prompt.contains("list of songs")
        || prompt.contains("song list")
        || prompt.contains("playlist ideas")
        || prompt.contains("suggest songs");
  }

  private static boolean isTrackEventsIntent(String prompt) {
    boolean monitor = prompt.contains("event") || prompt.contains("monitor");
    boolean track = prompt.contains("track change") || prompt.contains("track changes");
    return monitor && track;
  }

  private static String inferRecommendationSeed(String prompt) {
    String original = safeTrim(prompt);
    String lower = original.toLowerCase();
    int idx = lower.indexOf("similar to");
    String seed;
    if (idx >= 0) {
      seed = original.substring(idx + "similar to".length()).trim();
    } else {
      seed = original.replaceAll("(?i)^recommend( songs?)?( similar)?( to)?", "").trim();
    }
    seed = seed.replaceAll("[\\p{Punct}\\s]+$", "").trim();
    if (seed.isEmpty()) {
      return "popular songs";
    }
    return seed;
  }

  private String buildFollowupPrompt(String initialPrompt, CommandAgentResponse response, ToolExecution tool, int step, String rootWorkId) {
    String prompt = "Continue solving the user's goal with tool feedback.\n" +
        "Original goal:\n" + initialPrompt + "\n\n" +
        "Step: " + step + "\n" +
        "Planned action: " + safeTrim(response.getAction()) + "\n" +
        "Tool output:\n" + tool.output + "\n\n" +
        "If goal is satisfied, return action=none. Otherwise plan one next command.";
    return injectLedgerSummary(prompt, rootWorkId);
  }

  private LoopResult runAuthCompletionFlow(CommandContext parentContext,
                                           AuthCompletionIntent intent,
                                           String initialPrompt,
                                           String source,
                                           String correlationId,
                                           String rootWorkId) {
    long startedAtNs = System.nanoTime();
    List<ToolStep> steps = new ArrayList<>();
    appendLedgerAction(rootWorkId, WorkActionType.NOTE,
        "AUTH_COMPLETE sessionId=" + safeTrim(intent.sessionId),
        "auth-complete", "session-id=<redacted>", null, null, null, null);

    String args = intent.rawArgs;
    ToolExecution complete = executeSpotifyInternal(parentContext, "auth-complete", args);
    steps.add(new ToolStep(1, "auth-complete", "auth-complete", "<redacted>", complete.rawOutput(), 0, 0, 0));
    if (!complete.executed) {
      String errorMessage = "Authorization completion failed. " + safeTrim(complete.output);
      CommandAgentResponse failed = new CommandAgentResponse(
          initialPrompt, "none", errorMessage,
          buildAuthResultHtml("error", "Authorization failed", errorMessage, true)
      );
      appendLedgerAction(rootWorkId, WorkActionType.FAIL,
          "AUTH_COMPLETE_FAILED code=" + safeTrim(complete.errorCode),
          "auth-complete", null, null, null, safeTrim(complete.errorCode), null);
      finalizeLedgerWork(rootWorkId, StopReason.TOOL_EXECUTION_FAILED, failed, steps);
      return new LoopResult(initialPrompt, failed, steps, StopReason.TOOL_EXECUTION_FAILED, elapsedMs(startedAtNs), source, correlationId);
    }

    PendingAuthRetry pending = pendingAuthRetries.remove(safeTrim(intent.sessionId));
    if (pending == null) {
      String successMessage = "Authorization completed successfully.";
      CommandAgentResponse done = new CommandAgentResponse(
          initialPrompt, "none", successMessage,
          buildAuthResultHtml("success", "Spotify connected", successMessage, true)
      );
      appendLedgerAction(rootWorkId, WorkActionType.NOTE,
          "AUTH_COMPLETE_SUCCESS sessionWithoutPendingRetry=true",
          "auth-complete", null, null, null, null, null);
      finalizeLedgerWork(rootWorkId, StopReason.ACTION_NONE, done, steps);
      return new LoopResult(initialPrompt, done, steps, StopReason.ACTION_NONE, elapsedMs(startedAtNs), source, correlationId);
    }

    ToolExecution retry = executeSpotifyInternal(parentContext, pending.command(), pending.args());
    steps.add(new ToolStep(2, pending.command() + " " + pending.args(), retry.command, retry.args, retry.rawOutput(), 0, 0, 0));
    appendLedgerAction(rootWorkId, WorkActionType.NOTE,
        "AUTH_RETRY_RESULT command=" + pending.command() + " success=" + retry.executed,
        retry.command, retry.args, redactedForLogs(retry.rawOutput()), null, retry.executed ? null : retry.errorCode, null);
    StopReason stop = retry.executed ? StopReason.ACTION_NONE : StopReason.TOOL_EXECUTION_FAILED;
    String retryMessage = retry.executed
        ? "Authorization completed and command retried successfully."
        : "Authorization completed but retry failed: " + safeTrim(retry.output);
    String retryStatus = retry.executed ? "success" : "warning";
    String retryTitle = retry.executed ? "Spotify connected" : "Spotify connected with warning";
    CommandAgentResponse response = retry.executed
        ? new CommandAgentResponse(initialPrompt, "none", retryMessage,
            buildAuthResultHtml(retryStatus, retryTitle, retryMessage, true))
        : new CommandAgentResponse(initialPrompt, "none", retryMessage,
            buildAuthResultHtml(retryStatus, retryTitle, retryMessage, true));
    finalizeLedgerWork(rootWorkId, stop, response, steps);
    return new LoopResult(initialPrompt, response, steps, stop, elapsedMs(startedAtNs), source, correlationId);
  }

  private static String buildAuthResultHtml(String status, String title, String detail, boolean includeCapabilities) {
    String normalizedStatus = safeTrim(status).isEmpty() ? "info" : safeTrim(status).toLowerCase();
    String badgeColor = switch (normalizedStatus) {
      case "success" -> "#1db954";
      case "error" -> "#f85149";
      case "warning" -> "#d29922";
      default -> "#58a6ff";
    };
    String safeTitle = escapeHtml(title);
    String safeDetail = escapeHtml(detail);
    String capabilities = includeCapabilities
        ? "<div style=\"margin-top:10px;font-size:12px;color:#9fb0c3;\">"
            + "You can now ask to: play songs, pause, stop, skip, change volume, queue songs, check playback status."
            + "</div>"
        : "";
    return "<html><body style=\"font-family:sans-serif;padding:14px;margin:0;background:#0d1117;color:#e6edf3;\">"
        + "<div style=\"border:1px solid #30363d;border-radius:12px;padding:14px;background:#161b22;\">"
        + "<div style=\"display:inline-block;background:" + badgeColor + ";color:#04110a;padding:4px 8px;border-radius:999px;font-size:11px;font-weight:700;\">"
        + escapeHtml(normalizedStatus.toUpperCase())
        + "</div>"
        + "<div style=\"font-size:16px;font-weight:700;margin-top:8px;\">" + safeTitle + "</div>"
        + "<p style=\"margin:8px 0 0 0;font-size:13px;color:#c9d1d9;\">" + safeDetail + "</p>"
        + capabilities
        + "</div></body></html>";
  }

  private static String escapeHtml(String value) {
    String v = safeTrim(value);
    if (v.isEmpty()) {
      return "";
    }
    return v
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private static AuthCompletionIntent parseAuthCompletionIntent(String prompt) {
    String raw = safeTrim(prompt);
    if (raw.isEmpty()) {
      return null;
    }
    if (!raw.toLowerCase().startsWith("auth-complete ")) {
      return null;
    }
    String args = safeTrim(raw.substring("auth-complete".length()));
    if (args.isEmpty()) {
      return null;
    }
    Map<String, String> kv = new LinkedHashMap<>();
    for (String token : args.split("\\s+")) {
      String[] pair = token.split("=", 2);
      if (pair.length == 2) {
        kv.put(pair[0].trim().toLowerCase(), pair[1].trim());
      }
    }
    String sessionId = kv.getOrDefault("session-id", kv.getOrDefault("sessionid", ""));
    String code = kv.getOrDefault("code", "");
    if (sessionId.isBlank() || code.isBlank()) {
      return null;
    }
    return new AuthCompletionIntent(sessionId, args);
  }

  private static boolean isAuthRequiredError(String errorCode) {
    return AUTH_REQUIRED_CODES.contains(safeTrim(errorCode).toLowerCase());
  }

  private static boolean isAuthRequiredToolResult(ToolExecution tool) {
    if (tool == null) {
      return false;
    }
    if (isAuthRequiredError(tool.errorCode())) {
      return true;
    }
    String output = safeTrim(tool.output()).toLowerCase();
    String raw = safeTrim(tool.rawOutput()).toLowerCase();
    return output.contains("spotifyauthorizationrequiredexception")
        || raw.contains("spotifyauthorizationrequiredexception")
        || output.contains("spotify authorization is required")
        || raw.contains("spotify authorization is required");
  }

  private static String extractAuthSessionId(String output) {
    String text = safeTrim(output);
    if (text.isEmpty()) {
      return "";
    }
    try {
      JsonNode root = JSON.readTree(text);
      String fromSession = readPath(root, "session.sessionId");
      if (!fromSession.isBlank()) {
        return fromSession;
      }
      String fromAuth = readPath(root, "auth.sessionId");
      if (!fromAuth.isBlank()) {
        return fromAuth;
      }
      return "";
    } catch (Exception ignored) {
      return "";
    }
  }

  private static String redactedForLogs(String raw) {
    String text = safeTrim(raw);
    if (text.isEmpty()) {
      return "";
    }
    try {
      JsonNode root = JSON.readTree(text);
      if (root.isObject()) {
        redactNode((com.fasterxml.jackson.databind.node.ObjectNode) root);
        return root.toString();
      }
      return text;
    } catch (Exception ignored) {
      return text
          .replaceAll("(?i)code=[^\\s&]+", "code=<redacted>")
          .replaceAll("(?i)opaquePayload[=:][^\\s,}]+", "opaquePayload=<redacted>")
          .replaceAll("(?i)integrity[=:][^\\s,}]+", "integrity=<redacted>");
    }
  }

  private static void redactNode(com.fasterxml.jackson.databind.node.ObjectNode node) {
    if (node.has("secureEnvelope")) {
      node.put("secureEnvelope", "<redacted>");
    }
    if (node.has("auth") && node.get("auth").isObject()) {
      com.fasterxml.jackson.databind.node.ObjectNode auth = (com.fasterxml.jackson.databind.node.ObjectNode) node.get("auth");
      if (auth.has("secureEnvelope")) {
        auth.put("secureEnvelope", "<redacted>");
      }
    }
    node.fieldNames().forEachRemaining(field -> {
      JsonNode child = node.get(field);
      if (child != null && child.isObject()) {
        redactNode((com.fasterxml.jackson.databind.node.ObjectNode) child);
      }
    });
  }

  private static String renderLoopResultAsJson(LoopResult result) {
    StringBuilder json = new StringBuilder(512);
    String user = result.finalResponse == null ? null : safeTrim(result.finalResponse.getUser());
    String html = result.finalResponse == null ? null : safeTrim(result.finalResponse.getHtml());
    json.append('{');
    json.append("\"source\":").append(quoteJson(result.source)).append(',');
    json.append("\"correlationId\":").append(quoteJson(result.correlationId)).append(',');
    json.append("\"request\":").append(quoteJson(result.request)).append(',');
    json.append("\"action\":").append(quoteJson(result.finalResponse == null ? null : result.finalResponse.getAction())).append(',');
    json.append("\"user\":").append(quoteJson(user)).append(',');
    json.append("\"html\":").append(quoteJson(html)).append(',');
    json.append("\"auth\":").append(renderAuthJson(result.toolSteps)).append(',');
    json.append("\"channels\":").append(renderChannelsJson(result, user, html)).append(',');
    json.append("\"meta\":").append(renderMetaJson(result)).append(',');
    json.append("\"stopReason\":").append(quoteJson(result.stopReason.code)).append(',');
    json.append("\"stopMessage\":").append(quoteJson(result.stopReason.message)).append(',');
    json.append("\"totalDurationMs\":").append(result.totalDurationMs).append(',');
    json.append("\"stepCount\":").append(result.toolSteps.size()).append(',');
    json.append("\"toolSteps\":[");
    for (int i = 0; i < result.toolSteps.size(); i++) {
      ToolStep step = result.toolSteps.get(i);
      if (i > 0) {
        json.append(',');
      }
      json.append('{');
      json.append("\"step\":").append(step.step).append(',');
      json.append("\"agentAction\":").append(quoteJson(step.agentAction)).append(',');
      json.append("\"toolCommand\":").append(quoteJson(step.toolCommand)).append(',');
      json.append("\"toolArgs\":").append(quoteJson(step.toolArgs)).append(',');
      json.append("\"toolOutput\":").append(quoteJson(step.toolOutput)).append(',');
      json.append("\"planningDurationMs\":").append(step.planningDurationMs).append(',');
      json.append("\"toolDurationMs\":").append(step.toolDurationMs).append(',');
      json.append("\"stepDurationMs\":").append(step.stepDurationMs);
      json.append('}');
    }
    json.append(']');
    json.append('}');
    return json.toString();
  }

  private static String renderAuthJson(List<ToolStep> toolSteps) {
    ToolStep last = findLastToolStep(toolSteps);
    if (last == null) {
      return "null";
    }
    String output = safeTrim(last.toolOutput);
    if (output.isEmpty()) {
      return "null";
    }
    try {
      JsonNode root = JSON.readTree(output);
      JsonNode auth = root.path("auth");
      if (!auth.isObject()) {
        return "null";
      }
      return auth.toString();
    } catch (Exception ignored) {
      return "null";
    }
  }

  private static String renderContractEnvelope(String source,
                                               String correlationId,
                                               String error,
                                               String message,
                                               String statusMessage,
                                               String html,
                                               String webviewMode,
                                               boolean webviewReplace) {
    String normalizedMode = safeTrim(webviewMode).isEmpty() ? "none" : safeTrim(webviewMode);
    return "{"
        + "\"source\":" + quoteJson(source) + ","
        + "\"correlationId\":" + quoteJson(correlationId) + ","
        + "\"error\":" + quoteJson(error) + ","
        + "\"message\":" + quoteJson(message) + ","
        + "\"channels\":{"
        + "\"chat\":{\"message\":" + quoteJson(message) + "},"
        + "\"status\":{\"message\":" + quoteJson(statusMessage) + "},"
        + "\"thought\":{\"message\":" + quoteJson("source=" + safeTrim(source) + " correlationId=" + safeTrim(correlationId)) + "},"
        + "\"webview\":{"
        + "\"html\":" + quoteJson(html) + ","
        + "\"mode\":" + quoteJson(normalizedMode) + ","
        + "\"replace\":" + webviewReplace
        + "}"
        + "}"
        + "}";
  }

  private static String renderChannelsJson(LoopResult result, String user, String html) {
    ToolStep lastToolStep = findLastToolStep(result.toolSteps);
    String command = lastToolStep == null ? "" : safeTrim(lastToolStep.toolCommand).toLowerCase();
    ToolChannels toolChannels = extractToolChannels(lastToolStep == null ? "" : safeTrim(lastToolStep.toolOutput));
    String status = buildStatusMessage(lastToolStep, result.stopReason);
    String selectedHtml = safeTrim(html);
    boolean authBeginHtml = "auth-begin".equals(command)
        && toolChannels.html != null
        && !toolChannels.html.isBlank()
        && "html".equalsIgnoreCase(safeTrim(toolChannels.webviewMode));
    if (selectedHtml.isEmpty() && authBeginHtml) {
      selectedHtml = safeTrim(toolChannels.html);
    }
    if ("suggest".equals(command)
        && toolChannels.html != null
        && !toolChannels.html.isBlank()
        && toolChannels.html.contains("Android.runAction(")) {
      selectedHtml = toolChannels.html;
    }
    boolean hasHtml = selectedHtml != null && !selectedHtml.isBlank();
    boolean suggestMode = "suggest".equals(command) && !hasHtml;
    String mode;
    boolean replace;
    if (hasHtml) {
      if (authBeginHtml) {
        mode = safeTrim(toolChannels.webviewMode).isEmpty() ? "html" : safeTrim(toolChannels.webviewMode);
        replace = toolChannels.webviewReplace != null ? toolChannels.webviewReplace : true;
      } else {
        mode = "html";
        replace = true;
      }
    } else if (suggestMode) {
      mode = "suggestions_from_toolsteps";
      replace = true;
    } else {
      mode = "none";
      replace = false;
    }

    String thought = buildThoughtSummary(result, lastToolStep);
    return "{"
        + "\"chat\":{\"message\":" + quoteJson(safeTrim(user)) + "},"
        + "\"status\":{\"message\":" + quoteJson(status) + "},"
        + "\"thought\":{\"message\":" + quoteJson(thought) + "},"
        + "\"webview\":{"
        + "\"html\":" + quoteJson(hasHtml ? selectedHtml : null) + ","
        + "\"mode\":" + quoteJson(mode) + ","
        + "\"replace\":" + replace
        + "}"
        + "}";
  }

  private static String buildThoughtSummary(LoopResult result, ToolStep lastToolStep) {
    if (result == null) {
      return "agent=dj";
    }
    String command = lastToolStep == null ? "none" : safeTrim(lastToolStep.toolCommand);
    if (command.isEmpty()) {
      command = "none";
    }
    int steps = result.toolSteps == null ? 0 : result.toolSteps.size();
    return "agent=dj stopReason=" + safeTrim(result.stopReason.code)
        + " steps=" + steps
        + " command=" + command
        + " durationMs=" + result.totalDurationMs;
  }

  private static String renderMetaJson(LoopResult result) {
    if (result == null) {
      return "null";
    }
    if (!shouldEmitFailureMeta(result)) {
      return "null";
    }
    return "{"
        + "\"outcome\":\"unhandled_intent\","
        + "\"errorCode\":\"agent_capability_mismatch\""
        + "}";
  }

  private static boolean shouldEmitFailureMeta(LoopResult result) {
    if (result == null) {
      return false;
    }
    if (result.stopReason == StopReason.UNSUPPORTED_ACTION) {
      return true;
    }
    if (result.finalResponse == null || safeTrim(result.finalResponse.getUser()).isBlank()) {
      return true;
    }
    return false;
  }

  private static ToolStep findLastToolStep(List<ToolStep> steps) {
    if (steps == null || steps.isEmpty()) {
      return null;
    }
    for (int i = steps.size() - 1; i >= 0; i--) {
      ToolStep step = steps.get(i);
      String cmd = safeTrim(step.toolCommand).toLowerCase();
      if (!cmd.isEmpty() && !"none".equals(cmd) && !"planner".equals(cmd)) {
        return step;
      }
    }
    return null;
  }

  private static String buildStatusMessage(ToolStep step, StopReason stopReason) {
    if (step == null) {
      return "Agent stop reason: " + stopReason.code;
    }
    String cmd = safeTrim(step.toolCommand);
    String output = safeTrim(step.toolOutput);
    ToolChannels channels = extractToolChannels(output);
    if (channels.statusMessage != null && !channels.statusMessage.isBlank()) {
      String firstLine = firstNonBlankLine(channels.statusMessage);
      if (!firstLine.isEmpty()) {
        return "Tool " + cmd + ": " + firstLine;
      }
    }
    String structuredMessage = extractToolMessage(output);
    if (!structuredMessage.isEmpty()) {
      String firstLine = firstNonBlankLine(structuredMessage);
      if (!firstLine.isEmpty()) {
        return "Tool " + cmd + ": " + firstLine;
      }
    }
    String firstLine = "";
    if (!output.isEmpty()) {
      firstLine = firstNonBlankLine(output);
    }
    if (firstLine.isEmpty()) {
      firstLine = "completed";
    }
    return "Tool " + cmd + ": " + firstLine;
  }

  private static String firstNonBlankLine(String text) {
    if (text == null || text.isBlank()) {
      return "";
    }
    String[] lines = text.split("\\R");
    for (String line : lines) {
      String s = safeTrim(line);
      if (!s.isEmpty()) {
        return s;
      }
    }
    return "";
  }

  private static ToolChannels extractToolChannels(String toolOutput) {
    if (toolOutput == null || toolOutput.isBlank()) {
      return ToolChannels.EMPTY;
    }
    try {
      JsonNode root = JSON.readTree(toolOutput);
      JsonNode channels = root.path("channels");
      if (!channels.isObject()) {
        return ToolChannels.EMPTY;
      }
      String status = readText(channels.path("status"), "message");
      JsonNode webview = channels.path("webview");
      String html = webview.isObject() ? readText(webview, "html") : "";
      String mode = webview.isObject() ? readText(webview, "mode") : "";
      Boolean replace = null;
      if (webview.isObject() && webview.has("replace")) {
        JsonNode replaceNode = webview.path("replace");
        if (replaceNode.isBoolean()) {
          replace = replaceNode.asBoolean();
        } else {
          String replaceText = safeTrim(replaceNode.asText(""));
          if (!replaceText.isEmpty()) {
            if ("true".equalsIgnoreCase(replaceText)) {
              replace = true;
            } else if ("false".equalsIgnoreCase(replaceText)) {
              replace = false;
            }
          }
        }
      }
      return new ToolChannels(status, html, mode, replace);
    } catch (Exception ignored) {
      return ToolChannels.EMPTY;
    }
  }

  private static String extractToolMessage(String toolOutput) {
    if (toolOutput == null || toolOutput.isBlank()) {
      return "";
    }
    try {
      JsonNode root = JSON.readTree(toolOutput);
      return safeTrim(root.path("message").asText(""));
    } catch (Exception ignored) {
      return "";
    }
  }

  private static String readText(JsonNode node, String field) {
    if (node == null || node.isMissingNode()) {
      return "";
    }
    String value = safeTrim(node.path(field).asText(""));
    return "null".equalsIgnoreCase(value) ? "" : value;
  }

  private record ToolChannels(String statusMessage, String html, String webviewMode, Boolean webviewReplace) {
    private static final ToolChannels EMPTY = new ToolChannels("", "", "", null);
  }

  private static String renderDecisionEventAsJson(LoopResult result) {
    return "{"
        + "\"type\":\"agent_decision\","
        + "\"trace\":{"
        + "\"source\":" + quoteJson(result.source) + ","
        + "\"correlationId\":" + quoteJson(result.correlationId) + ","
        + "\"stopReason\":" + quoteJson(result.stopReason.code) + ","
        + "\"totalDurationMs\":" + result.totalDurationMs + ","
        + "\"stepCount\":" + result.toolSteps.size()
        + "},"
        + "\"decision\":" + renderLoopResultAsJson(result)
        + "}";
  }

  private static CommandAgentResponse fallbackResponse(String request,
                                                       StopReason stopReason,
                                                       String correlationId,
                                                       String rawDetails) {
    return AgentFailureResponseFactory.stopBeforeCompletion(
        request,
        mapFailureKind(stopReason),
        correlationId,
        rawDetails
    );
  }

  private static CommandAgentResponse failureResponse(String request,
                                                      StopReason stopReason,
                                                      String command,
                                                      String correlationId,
                                                      String rawDetails) {
    return AgentFailureResponseFactory.commandFailed(
        request,
        command,
        mapFailureKind(stopReason),
        correlationId,
        rawDetails
    );
  }

  private static AgentFailureResponseFactory.FailureKind mapFailureKind(StopReason stopReason) {
    return switch (stopReason) {
      case PLANNER_EXCEPTION -> AgentFailureResponseFactory.FailureKind.PLANNER_EXCEPTION;
      case TOOL_EXECUTION_FAILED -> AgentFailureResponseFactory.FailureKind.TOOL_EXECUTION_FAILED;
      case UNSUPPORTED_ACTION -> AgentFailureResponseFactory.FailureKind.UNSUPPORTED_ACTION;
      case INVALID_ARGUMENTS -> AgentFailureResponseFactory.FailureKind.INVALID_ARGUMENTS;
      case MAX_STEPS_REACHED -> AgentFailureResponseFactory.FailureKind.MAX_STEPS_REACHED;
      default -> AgentFailureResponseFactory.FailureKind.INTERNAL_ERROR;
    };
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

  private String renderCapabilitiesEnvelope(AgentCapabilityManifest manifest) {
    try {
      return "{"
          + "\"status\":\"ok\","
          + "\"source\":\"runtime_capabilities_action\","
          + "\"manifest\":" + mapper.writeValueAsString(manifest)
          + "}";
    } catch (Exception e) {
      return "{\"status\":\"error\",\"error\":\"capability_manifest_encode_failed\"}";
    }
  }

  private static String safeTrim(String v) {
    return v == null ? "" : v.trim();
  }

  private static String usageReact() {
    return "Usage: react event_type=<informational|state_change|anomaly>&source=<src>&message=<text>[&kind=<k>][&at_ms=<epochMs>] OR JSON payload";
  }

  private ParsedEventPayload parseEventPayload(String raw) {
    Map<String, String> kv = new LinkedHashMap<>();
    String normalized = raw.trim();
    if (normalized.startsWith("{") && normalized.endsWith("}")) {
      try {
        JsonNode root = mapper.readTree(normalized);
        String eventType = safeTrim(root.path("event_type").asText()).toLowerCase();
        String source = safeTrim(root.path("source").asText());
        String kind = safeTrim(root.path("kind").asText("generic"));
        long atMs = root.path("at_ms").asLong(System.currentTimeMillis());
        String message = safeTrim(root.path("message").asText());

        if (eventType.isEmpty()) {
          eventType = "state_change";
        }
        if (source.isEmpty()) {
          source = "spotify_component";
        }
        if (message.isEmpty()) {
          message = safeTrim(root.path("summary").asText());
        }
        if (message.isEmpty()) {
          message = "Spotify runtime event received.";
        }
        if (!VALID_EVENT_TYPES.contains(eventType)) {
          return new ParsedEventPayload(null, "event_type must be one of: informational, state_change, anomaly.");
        }
        if (atMs <= 0) {
          atMs = System.currentTimeMillis();
        }
        return new ParsedEventPayload(new EventPayload(eventType, source, kind.isEmpty() ? "generic" : kind, message, atMs, normalized), null);
      } catch (Exception e) {
        return new ParsedEventPayload(null, "Invalid JSON payload: " + safeTrim(e.getMessage()));
      }
    }

    if (normalized.contains("&")) {
      for (String part : normalized.split("&")) {
        String[] pair = part.split("=", 2);
        if (pair.length != 2) {
          continue;
        }
        kv.put(safeTrim(urlDecode(pair[0])).toLowerCase(), safeTrim(urlDecode(pair[1])));
      }
    } else {
      for (String token : normalized.split("\\s+")) {
        String[] pair = token.split("=", 2);
        if (pair.length != 2) {
          continue;
        }
        kv.put(safeTrim(urlDecode(pair[0])).toLowerCase(), safeTrim(urlDecode(pair[1])));
      }
    }

    String eventType = safeTrim(kv.get("event_type")).toLowerCase();
    String source = safeTrim(kv.get("source"));
    String message = safeTrim(kv.get("message"));
    String kind = safeTrim(kv.getOrDefault("kind", "generic"));
    String atMsRaw = safeTrim(kv.getOrDefault("at_ms", "0"));

    if (eventType.isEmpty() || source.isEmpty() || message.isEmpty()) {
      return new ParsedEventPayload(null, "Required keys missing. Required: event_type, source, message.");
    }
    if (!VALID_EVENT_TYPES.contains(eventType)) {
      return new ParsedEventPayload(null, "event_type must be one of: informational, state_change, anomaly.");
    }

    long atMs;
    try {
      atMs = Long.parseLong(atMsRaw);
    } catch (NumberFormatException e) {
      return new ParsedEventPayload(null, "at_ms must be a number when provided.");
    }
    if (atMs <= 0) {
      atMs = System.currentTimeMillis();
    }

    EventPayload payload = new EventPayload(eventType, source, kind.isEmpty() ? "generic" : kind, message, atMs, "");
    return new ParsedEventPayload(payload, null);
  }

  private static String urlDecode(String v) {
    try {
      return URLDecoder.decode(v, StandardCharsets.UTF_8);
    } catch (Exception ignored) {
      return v;
    }
  }

  private static String renderEventPayloadAsJson(EventPayload event) {
    return "{"
        + "\"event_type\":" + quoteJson(event.eventType) + ","
        + "\"source\":" + quoteJson(event.source) + ","
        + "\"kind\":" + quoteJson(event.kind) + ","
        + "\"message\":" + quoteJson(event.message) + ","
        + "\"at_ms\":" + event.atMs + ","
        + "\"structured_json\":" + quoteJson(event.structuredJson)
        + "}";
  }

  private static String renderReactionEventAsJson(EventPayload event, String source, String correlationId) {
    return "{"
        + "\"type\":\"agent_reaction\","
        + "\"trace\":{"
        + "\"source\":" + quoteJson(source) + ","
        + "\"correlationId\":" + quoteJson(correlationId)
        + "},"
        + "\"event\":" + renderEventPayloadAsJson(event)
        + "}";
  }

  private String injectLedgerSummary(String prompt, String rootWorkId) {
    try {
      List<WorkActionRecord> actions = ownerLedger().getActions(rootWorkId, new com.social100.todero.common.agent.work.QueryOptions(LEDGER_SUMMARY_MAX_ENTRIES, 0));
      StringBuilder summary = new StringBuilder();
      for (WorkActionRecord action : actions) {
        if (summary.length() >= LEDGER_SUMMARY_MAX_CHARS) {
          break;
        }
        summary.append("- ")
            .append(action.actionType())
            .append(": ")
            .append(safeTrim(action.text()))
            .append('\n');
      }
      if (summary.length() == 0) {
        summary.append("- no prior ledger actions\n");
      }
      return "Agent Work Ledger (source of truth):\n"
          + summary
          + "\nCurrent task:\n"
          + prompt;
    } catch (Exception e) {
      return "Current task:\n" + prompt;
    }
  }

  private WorkItemRecord openRootLedgerWork(String source, String prompt, boolean interactive, String correlationId) {
    List<String> labels = interactive
        ? List.of("spotify", "agent", source, "interactive")
        : List.of("spotify", "agent", source, "event");
    WorkItemRecord root = ownerLedger().beginGoal(prompt, labels);
    ownerLedger().appendAction(root.workId(), new AppendActionRequest(
        WorkActionType.NOTE,
        "correlationId=" + correlationId + " source=" + source,
        null, null, null, null, null, null
    ));
    ownerLedger().splitIntoSubtasks(root.workId(), List.of(
        new SubtaskRequest("plan", "derive next spotify action", 1, List.of("plan")),
        new SubtaskRequest("execute", "execute spotify command", 1, List.of("execute")),
        new SubtaskRequest("evaluate", "evaluate tool result and decide next step", 1, List.of("evaluate"))
    ));
    return root;
  }

  private void appendLedgerAction(String workId,
                                  WorkActionType type,
                                  String text,
                                  String toolName,
                                  String toolArgs,
                                  String toolResultDigest,
                                  Long latencyMs,
                                  String errorCode) {
    appendLedgerAction(workId, type, text, toolName, toolArgs, toolResultDigest, latencyMs, errorCode, null);
  }

  private void appendLedgerAction(String workId,
                                  WorkActionType type,
                                  String text,
                                  String toolName,
                                  String toolArgs,
                                  String toolResultDigest,
                                  Long latencyMs,
                                  String errorCode,
                                  String errorMessage) {
    try {
      ownerLedger().appendAction(workId, new AppendActionRequest(
          type, text, toolName, toolArgs, toolResultDigest, latencyMs, errorCode, errorMessage
      ));
    } catch (Exception e) {
      System.out.println("[DJ-AGENT] ledger append skipped workId=" + workId + " error=" + safeTrim(e.getMessage()));
    }
  }

  private void finalizeLedgerWork(String rootWorkId,
                                  StopReason stopReason,
                                  CommandAgentResponse lastResponse,
                                  List<ToolStep> toolSteps) {
    String summary = safeTrim(lastResponse == null ? null : lastResponse.getUser());
    if (summary.isEmpty()) {
      summary = stopReason.message;
    }
    if (isFailureStopReason(stopReason)) {
      String details = lastToolOutput(toolSteps);
      ownerLedger().markFailed(rootWorkId, new FailureRequest(stopReason.code, details, summary));
      appendLedgerAction(rootWorkId, WorkActionType.FAIL, "finalize_failed stopReason=" + stopReason.code,
          null, null, details, null, stopReason.code, summary);
    } else {
      ownerLedger().markDone(rootWorkId, new CompletionRequest(summary, 1.0));
      appendLedgerAction(rootWorkId, WorkActionType.COMPLETE, "finalize_done stopReason=" + stopReason.code,
          null, null, null, null, null);
    }
  }

  private static boolean isFailureStopReason(StopReason stopReason) {
    return stopReason == StopReason.PLANNER_EXCEPTION
        || stopReason == StopReason.TOOL_EXECUTION_FAILED
        || stopReason == StopReason.UNSUPPORTED_ACTION
        || stopReason == StopReason.INVALID_ARGUMENTS
        || stopReason == StopReason.MAX_STEPS_REACHED;
  }

  private static String lastToolOutput(List<ToolStep> toolSteps) {
    ToolStep step = findLastToolStep(toolSteps);
    return step == null ? "" : safeTrim(step.toolOutput);
  }

  private static Path defaultLedgerPath() {
    String custom = System.getProperty("todero.agent.dj.ledger.dir");
    if (custom != null && !custom.isBlank()) {
      return Path.of(custom.trim());
    }
    return Path.of(System.getProperty("user.home"), ".todero", "data", "state", "agent-work-ledger", "dj-agent");
  }

  private void ensureOwnerLedger(CommandContext context) {
    if (context == null) {
      throw new IllegalArgumentException("CommandContext is required to resolve owner-scoped ledger.");
    }
    if (this.ownerLedger != null) {
      return;
    }
    synchronized (ledgerInitLock) {
      if (this.ownerLedger == null) {
        this.ownerLedger = context.workLedger(this.ledgerPath);
      }
    }
  }

  private OwnerAgentWorkLedger ownerLedger() {
    OwnerAgentWorkLedger existing = this.ownerLedger;
    if (existing != null) {
      return existing;
    }
    synchronized (ledgerInitLock) {
      if (this.ownerLedger == null) {
        this.ownerLedger = OwnerAgentWorkLedger.bind(
            SharedAgentWorkLedgerRegistry.shared(this.ledgerPath),
            LEDGER_OWNER_ID
        );
      }
      return this.ownerLedger;
    }
  }

  private static long elapsedMs(long startedAtNs) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNs);
  }

  private static String newCorrelationId() {
    return UUID.randomUUID().toString();
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
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
    return out.toString();
  }

  public enum SimpleEvent implements EventDefinition {
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

  private record ToolExecution(boolean executed, String command, String args, String output, String errorCode, String rawOutput) {
    static ToolExecution error(String errorCode, String command, String args, String output, String rawOutput) {
      return new ToolExecution(false, command, args, output, errorCode, rawOutput);
    }
  }

  private record SpotifyExecutionResult(String source,
                                        String channel,
                                        String phase,
                                        String body,
                                        String errorCode,
                                        int status) {
  }

  private record SpotifyEnvelope(boolean recognized, boolean ok, String errorCode, String message) {
  }

  private record ToolStep(int step,
                          String agentAction,
                          String toolCommand,
                          String toolArgs,
                          String toolOutput,
                          long planningDurationMs,
                          long toolDurationMs,
                          long stepDurationMs) {
  }

  private record LoopResult(String request,
                            CommandAgentResponse finalResponse,
                            List<ToolStep> toolSteps,
                            StopReason stopReason,
                            long totalDurationMs,
                            String source,
                            String correlationId) {
  }

  private enum StopReason {
    ACTION_NONE("action_none", "planner concluded no further action is needed"),
    PLANNER_EXCEPTION("planner_exception", "planner failed with an internal exception"),
    TOOL_EXECUTION_FAILED("tool_execution_failed", "spotify command execution failed"),
    UNSUPPORTED_ACTION("unsupported_action", "planner proposed a command outside the allowed spotify command set"),
    INVALID_ARGUMENTS("invalid_arguments", "planner proposed invalid arguments for a spotify command"),
    AUTH_REQUIRED("auth_required", "spotify authorization is required before continuing"),
    BACKGROUND_STEP_LIMIT("background_step_limit", "background reaction reached step cap"),
    MAX_STEPS_REACHED("max_steps_reached", "maximum loop step limit reached");

    private final String code;
    private final String message;

    StopReason(String code, String message) {
      this.code = code;
      this.message = message;
    }
  }

  private record EventPayload(String eventType, String source, String kind, String message, long atMs, String structuredJson) {
  }

  private record PlaylistAddIntent(String playlistName) {
  }

  private record ParsedEventPayload(EventPayload payload, String error) {
  }

  private record PendingAuthRetry(String command, String args, String initialPrompt, String rootWorkId) {
  }

  private record AuthCompletionIntent(String sessionId, String rawArgs) {
  }

  private record ValidatedAction(String command, String args, String errorCode, String error) {
    static ValidatedAction ok(String command, String args) {
      return new ValidatedAction(command, args, null, null);
    }

    static ValidatedAction error(String command, String args, String errorCode, String error) {
      return new ValidatedAction(command, args, errorCode, error);
    }
  }
}
