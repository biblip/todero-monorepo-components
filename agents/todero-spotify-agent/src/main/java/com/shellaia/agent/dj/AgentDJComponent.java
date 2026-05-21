package com.shellaia.agent.dj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.ai.action.CommandAgentResponse;
import com.social100.todero.common.ai.action.AgentFailureResponseFactory;
import com.social100.todero.common.ai.agent.AgentContext;
import com.social100.todero.common.ai.agent.AgentDefinition;
import com.social100.todero.common.ai.agent.AgentPrompt;
import com.social100.todero.common.ai.llm.LLMClient;
import com.social100.todero.common.ai.llm.OpenAiLLM;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpIORequestWrapper;
import com.social100.todero.common.aiatpio.AiatpRequest;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.aiatpio.AiatpResponse;
import com.social100.todero.common.agent.work.AppendActionRequest;
import com.social100.todero.common.agent.work.CompletionRequest;
import com.social100.todero.common.agent.work.FailureRequest;
import com.social100.todero.common.agent.work.OwnerAgentWorkLedger;
import com.social100.todero.common.agent.work.SharedAgentWorkLedgerRegistry;
import com.social100.todero.common.agent.work.SubtaskRequest;
import com.social100.todero.common.agent.work.WorkActionRecord;
import com.social100.todero.common.agent.work.WorkActionType;
import com.social100.todero.common.agent.work.WorkItemRecord;
import com.social100.todero.common.agent.work.WorkStatus;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.lineparser.LineParserUtil;
import com.social100.todero.common.routing.AgentCapabilityManifest;
import com.social100.todero.common.routing.ToolAgentCapabilitySupport;
import com.social100.todero.common.storage.Storage;
import com.social100.todero.processor.EventDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.social100.todero.common.config.Util.parseDotenv;

@AIAController(name = "com.shellaia.agent.dj",
    type = ServerType.AI,
    visible = true,
    description = "DJ Agent with iterative planning for Spotify control",
    events = AgentDJComponent.SimpleEvent.class,
    capabilityProvider = DjAgentCapabilities.class)
public class AgentDJComponent {
  private static final String MAIN_GROUP = "Main";
  private static final String SPOTIFY_COMPONENT = "com.shellaia.spotify";
  private static final String UPSTREAM_CONTROL_HEADER = "X-AIATP-Upstream-Control";
  private static final int MAX_STEPS = 4;
  private static final long TOOL_TIMEOUT_SECONDS = 3;
  private static final int DEFAULT_RECOMMENDATION_COUNT = 1;
  private static final int MAX_RECOMMENDATION_COUNT = 12;
  private static final int PLANNER_RECENT_STEP_LIMIT = 4;
  private static final int PLANNER_HISTORY_LIMIT = 8;
  private static final String LEDGER_OWNER_ID = "com.shellaia.agent.dj";
  private static final Pattern TRACK_URI_PATTERN = Pattern.compile("spotify:track:[A-Za-z0-9]+");
  private static final Pattern RESOLVED_TRACK_PATTERN = Pattern.compile("^Resolved track:\\s+(.+?)\\s+—\\s+(.+?)\\s+\\[uri=(spotify:track:[A-Za-z0-9]+)]\\s*$", Pattern.MULTILINE);
  private static final Pattern PLAYING_PATTERN = Pattern.compile("(?im)^Playing:\\s*(true|false)\\s*$");
  private static final Pattern TRACK_TITLE_PATTERN = Pattern.compile("(?im)^Track:\\s*(.+?)\\s*$");
  private static final Pattern DEVICE_PATTERN = Pattern.compile("(?im)^Device:\\s*(.+?)\\s*$");
  private static final Pattern POSITION_PATTERN = Pattern.compile("(?im)^Position:\\s*(.+?)\\s*$");
  private static final Pattern PLAYLIST_ROW_PATTERN = Pattern.compile("^\\s*\\d+\\)\\s*(.+?)\\s*\\[id=([^,\\]]+).*$");
  private static final Pattern ADD_QUOTED_SONG_PATTERN = Pattern.compile("(?i)\\badd\\s+[\"']([^\"']+)[\"']");
  private static final Pattern EXPLICIT_COUNT_PATTERN = Pattern.compile("(?i)\\b(\\d{1,2})\\s+(songs?|tracks?|canciones?)\\b");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> SUPPORTED_COMMANDS = Set.of(
      "play", "pause", "stop", "volume", "volume-up", "volume-down", "mute",
      "move", "skip", "previous", "status", "queue", "playlist-play", "recently-played",
      "top-tracks", "top-artists", "resolve-track", "events",
      "playlist-next", "playlist-remove", "playlists", "playlist-list", "playlist-add", "playlist-add-current",
      "playlist-create", "playlist-reorder", "playlist-remove-pos"
  );
  private static final Set<String> AUTH_REQUIRED_CODES = Set.of("auth_required", "auth_scope_missing");
  //private final AgentDefinition agentDefinition;
  private final ExecutorService cognitionExecutor;
  private static final ExecutorService TOOL_PROGRESS_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
    Thread thread = new Thread(r, "dj-tool-progress");
    thread.setDaemon(true);
    return thread;
  });
  private final Path ledgerPath;
  private volatile OwnerAgentWorkLedger ownerLedger;
  private final Object ledgerInitLock = new Object();
  private final ObjectMapper mapper = new ObjectMapper();
  private final Map<String, PendingAuthRetry> pendingAuthRetries = new ConcurrentHashMap<>();
  private final Storage storage;
  private final String openApiKey;

  public AgentDJComponent(Storage storage) {
    this.storage = storage;
    this.cognitionExecutor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "dj-agent-cognition");
      t.setDaemon(true);
      return t;
    });
    this.ledgerPath = defaultLedgerPath();
    this.openApiKey = loadOpenApiKey(storage);
  }

  private static String loadOpenApiKey(Storage storage) {
    try {
      byte[] envBytes = storage.readFile(".env");
      if (envBytes == null || envBytes.length == 0) {
        return "";
      }
      return parseDotenv(envBytes).getOrDefault("OPENAI_API_KEY", "");
    } catch (IOException | RuntimeException e) {
      return "";
    }
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
    String prompt = requestBody(context);
    System.out.println("[DJ-AGENT] process received correlationId=" + correlationId + " prompt=" + prompt);
    if (!prompt.isEmpty()) {
      context.emitChat("Processing request...", "progress");
    }
    if (prompt.isEmpty()) {
      completeWireTerminal(
          context,
          "error",
          "failure",
          "invalid_request",
          "Prompt is required. Usage: process <goal>",
          "invalid_request",
          true,
          null
      );
      return true;
    }
    String rootWorkId = correlationId;
    CompletableFuture<LoopResult> future = CompletableFuture.supplyAsync(
        () -> runGoalLoop(context, prompt, true, source, correlationId, rootWorkId),
        cognitionExecutor
    );

    try {
      System.out.println("[DJ-AGENT][EMIT] process waiting for loop result");
      LoopResult result = future.get();
      System.out.println("[DJ-AGENT][EMIT] process result stopReason=" + result.stopReason
          + " steps=" + (result.toolSteps == null ? 0 : result.toolSteps.size()));
      emitWireFinal(context, result);
    } catch (Exception e) {
      Throwable reported = (e instanceof ExecutionException && e.getCause() != null) ? e.getCause() : e;
      String message = safeTrim(reported.getMessage()).isEmpty() ? "Unexpected agent failure." : safeTrim(reported.getMessage());
      System.out.println("[DJ-AGENT][EMIT] process exception message=" + safeTrim(reported.toString()));
      logThrowable("[DJ-AGENT][EMIT] process exception", reported);
      completeWireTerminal(
          context,
          "error",
          "failure",
          "agent_failed",
          message,
          "agent_failed",
          false,
          null
      );
    }
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "capabilities",
      description = "Return runtime capability manifest for discovery")
  public Boolean capabilities(CommandContext context) {
    try {
      AgentCapabilityManifest manifest = ToolAgentCapabilitySupport.resolveManifest(
          storage,
          context,
          new DjAgentCapabilities().manifest(),
          SPOTIFY_COMPONENT,
          buildCapabilitiesLlmClient(context)
      );
      String payload = mapper.writeValueAsString(Map.of("manifest", manifest));
      completeWireTerminal(
          context,
          "status",
          "success",
          "capabilities",
          payload,
          null,
          false,
          null,
          "application/json; charset=utf-8"
      );
    } catch (Exception e) {
      completeWireTerminal(
          context,
          "error",
          "failure",
          "capability_manifest_generate_failed",
          "DJ capability manifest could not be generated.",
          "capability_manifest_generate_failed",
          false,
          null
      );
    }
    return true;
  }

  private LLMClient buildCapabilitiesLlmClient(CommandContext context) {
    AgentContext agentContext = new AgentContext();
    if (context != null) {
      context.bindAgentLlmRegistry(agentContext);
    }
    return agentContext.systemLlm()
        .map(instance -> instance.client())
        .orElseGet(() -> safeTrim(openApiKey).isBlank() ? null : new OpenAiLLM(openApiKey, "gpt-4.1-mini"));
  }

  private LoopResult runGoalLoop(CommandContext parentContext,
                                 String initialPrompt,
                                 boolean interactiveRequest,
                                 String source,
                                 String correlationId,
                                  String rootWorkId) {
    System.out.println("[DJ-AGENT][LOOP] start source=" + safeTrim(source)
        + " interactive=" + interactiveRequest + " correlationId=" + safeTrim(correlationId));
    long startedAtNs = System.nanoTime();
    AgentContext llmContext = new AgentContext();
    parentContext.bindAgentLlmRegistry(llmContext);
    LLMClient llm;
    try {
      llm = llmContext.systemLlm()
          .map(instance -> instance.client())
          .orElseThrow(() -> new IllegalStateException(
              "No system-wide LLM available in registry for agent " + LEDGER_OWNER_ID));
    } catch (Throwable t) {
      logThrowable("[DJ-AGENT][LOOP] system LLM resolution failed", t);
      throw t;
    }
    PlaylistAddIntent playlistAddIntent = detectPlaylistAddIntent(initialPrompt);
    String currentPlaylistSongTitle = detectCurrentPlaylistSongTitle(initialPrompt);

    appendLedgerAction(rootWorkId, WorkActionType.PLAN,
        "loop_started source=" + source + " interactive=" + interactiveRequest + " correlationId=" + correlationId,
        null, null, null, null, null);

    AuthCompletionIntent completionIntent = parseAuthCompletionIntent(initialPrompt);
    if (completionIntent != null) {
      return runAuthCompletionFlow(parentContext, completionIntent, initialPrompt, source, correlationId, rootWorkId);
    }

    StopReason stopReason = StopReason.MAX_STEPS_REACHED;
    GoalIntent goalIntent = normalizeGoalIntent(llm, initialPrompt, source, correlationId, interactiveRequest, rootWorkId);
    System.out.println("[DJ-AGENT] normalized goal correlationId=" + correlationId
        + " intent=" + goalIntent.intent()
        + " targetScope=" + goalIntent.targetScope()
        + " supportedByToolchain=" + goalIntent.supportedByToolchain()
        + " wantsPlayback=" + goalIntent.wantsPlayback()
        + " referencesCurrentPlayback=" + goalIntent.referencesCurrentPlayback());
    if (!goalIntent.supportedByToolchain()) {
      stopReason = StopReason.OUT_OF_SCOPE;
      CommandAgentResponse unsupported = fallbackResponse(
          initialPrompt,
          stopReason,
          correlationId,
          firstNonBlank(goalIntent.unsupportedReason(), goalIntent.reason())
      );
      finalizeLedgerWork(rootWorkId, stopReason, unsupported, List.of());
      return new LoopResult(initialPrompt, unsupported, List.of(), stopReason, elapsedMs(startedAtNs), source, correlationId);
    }
    if (goalIntent.isRecommendationFlow()) {
      return runRecommendationFlow(parentContext, llm, initialPrompt, source, correlationId, rootWorkId, goalIntent);
    }

    CommandAgentResponse lastResponse = null;
    List<ToolStep> toolSteps = new ArrayList<>();
    List<String> plannerHistory = new ArrayList<>();
    boolean awaitingContinuation = false;
    String awaitingCommand = "";

    for (int step = 1; step <= MAX_STEPS; step++) {
      AgentContext plannerContext = new AgentContext();
      parentContext.bindAgentLlmRegistry(plannerContext);
      populatePlannerContext(
          plannerContext,
          initialPrompt,
          source,
          correlationId,
          interactiveRequest,
          step,
          goalIntent,
          toolSteps,
          lastResponse,
          plannerHistory
      );
      String plannerPrompt = buildPlannerPrompt(
          initialPrompt,
          source,
          correlationId,
          interactiveRequest,
          step,
          goalIntent,
          toolSteps,
          lastResponse,
          plannerHistory,
          rootWorkId
      );
      long stepStartedAtNs = System.nanoTime();
      CommandAgentResponse response;
      long planStartedAtNs = System.nanoTime();
      try {
        response = planNextAction(llm, new AgentPrompt(plannerPrompt), plannerContext);
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
      action = coercePlannerAction(initialPrompt, goalIntent, action, step, interactiveRequest);
      action = coerceCurrentPlaylistSongAddAction(action, currentPlaylistSongTitle, step, interactiveRequest);
      action = coercePlaylistAddAction(action, toolSteps, playlistAddIntent, step, interactiveRequest);
      String actionSignature = normalizeActionSignature(action);
      if (!actionSignature.isEmpty()) {
        plannerHistory.add(actionSignature);
      }
      appendLedgerAction(rootWorkId, WorkActionType.PLAN,
          "step=" + step + " planner_action=" + safeTrim(action),
          null, null, null, plannerDurationMs, null);
      System.out.println("[DJ-AGENT] planner action correlationId=" + correlationId + " step=" + step + " action=" + action);
      if (action.isEmpty() || "none".equalsIgnoreCase(action)) {
        long stepDurationMs = elapsedMs(stepStartedAtNs);
        toolSteps.add(new ToolStep(step, "none", "none", "", "", plannerDurationMs, 0, stepDurationMs));
        if (awaitingContinuation) {
          stopReason = StopReason.TOOL_SUCCEEDED_BUT_GOAL_UNRESOLVED;
          lastResponse = failureResponse(initialPrompt, stopReason, awaitingCommand, correlationId,
              "Planner returned no next action after a non-terminal tool result.");
          appendLedgerAction(rootWorkId, WorkActionType.FAIL,
              "step=" + step + " action=none while goal unresolved",
              awaitingCommand, null, null, null, stopReason.code,
              "planner returned no follow-up action");
        } else {
          stopReason = StopReason.ACTION_NONE;
          appendLedgerAction(rootWorkId, WorkActionType.DECISION,
              "step=" + step + " action=none stopReason=" + stopReason.code,
              null, null, null, null, null);
        }
        break;
      }

      LoopTermination loopTermination = detectLoopTermination(actionSignature, plannerHistory, toolSteps);
      if (loopTermination != null) {
        stopReason = loopTermination.stopReason();
        lastResponse = failureResponse(initialPrompt, stopReason, awaitingCommand, correlationId, loopTermination.details());
        appendLedgerAction(rootWorkId, WorkActionType.FAIL,
            "step=" + step + " planner_loop stopReason=" + stopReason.code,
            awaitingCommand, null, loopTermination.details(), null, stopReason.code, loopTermination.details());
        break;
      }

      long toolStartedAtNs = System.nanoTime();
      ToolExecution tool = executeSpotifyAction(parentContext, action, goalIntent);
      long toolDurationMs = elapsedMs(toolStartedAtNs);
      long stepDurationMs = elapsedMs(stepStartedAtNs);
      toolSteps.add(new ToolStep(step, action, tool.command, tool.args, tool.output, plannerDurationMs, toolDurationMs, stepDurationMs));
      recordToolStepSafely(rootWorkId, tool, toolDurationMs);
      boolean authRequiredToolResult = interactiveRequest && isAuthRequiredToolResult(tool);
      ToolResponseDisposition disposition = interactiveRequest
          ? responseDispositionForSuccessfulTool(tool)
          : ToolResponseDisposition.CONTINUE;
      boolean completesAfterSuccessfulTool = disposition == ToolResponseDisposition.GOAL_COMPLETED;
      // Wire-only: preserve tool output for the user even when the tool is terminal.
      if (interactiveRequest && !authRequiredToolResult && !safeTrim(tool.output).isEmpty()) {
        emitToolProgressAsync(parentContext, tool, correlationId, step);
      }

      if (authRequiredToolResult) {
        System.out.println("[DJ-AGENT] auth escalation triggered correlationId=" + correlationId
            + " step=" + step + " command=" + tool.command + " reason=auth_required_tool_result");
        String authBeginArgs = "redirect-profile=app owner=" + LEDGER_OWNER_ID;
        ToolExecution authBegin = executeSpotifyInternal(parentContext, "auth-begin", authBeginArgs);
        System.out.println("[DJ-AGENT] auth escalation auth-begin executed="
            + authBegin.executed + " errorCode=" + safeTrim(authBegin.errorCode()));
        toolSteps.add(new ToolStep(step, "auth-begin", "auth-begin", authBeginArgs,
            authBegin.rawOutput(), plannerDurationMs, toolDurationMs, stepDurationMs));
        appendLedgerAction(rootWorkId, WorkActionType.NOTE,
            "AUTH_REQUIRED command=" + tool.command + " code=" + safeTrim(tool.errorCode()),
            tool.command, tool.args, redactedForLogs(tool.rawOutput()), toolDurationMs, tool.errorCode(), null);
        if (authBegin.executed) {
          AuthDirective authDirective = extractAuthDirective(authBegin.rawOutput());
          String sessionId = authDirective.sessionId();
          boolean authHandoff = isAuthHandoffToolExecution(authBegin);
          if (!authHandoff && !authDirective.valid()) {
            stopReason = StopReason.TOOL_EXECUTION_FAILED;
            String invalidMessage = "Authorization handshake failed: component returned incomplete auth metadata.";
            lastResponse = failureResponse(initialPrompt, stopReason, "auth-begin", correlationId, invalidMessage);
            appendLedgerAction(rootWorkId, WorkActionType.FAIL,
                "AUTH_BEGIN_INVALID message=" + invalidMessage,
                "auth-begin", authBeginArgs,
                redactedForLogs(authBegin.rawOutput()), null, "auth_contract_invalid", invalidMessage);
            break;
          }
          if (authDirective.valid()) {
            if (shouldAutoRetryAfterAuth(authBeginArgs)) {
              pendingAuthRetries.put(sessionId, new PendingAuthRetry(tool.command, tool.args, initialPrompt, rootWorkId));
            }
            appendLedgerAction(rootWorkId, WorkActionType.NOTE,
                "AUTH_BEGIN sessionId=" + sessionId,
                "auth-begin", authBeginArgs, null, null, null, null);
          } else {
            appendLedgerAction(rootWorkId, WorkActionType.NOTE,
                "AUTH_HANDOFF_STARTED command=auth-begin",
                "auth-begin", authBeginArgs, null, null, null, null);
          }
          lastResponse = new CommandAgentResponse(
              initialPrompt,
              "none",
              shouldAutoRetryAfterAuth(authBeginArgs)
                  ? "Spotify authorization required. Open the authorization link and complete authentication."
                  : "Spotify authorization required. Open the authorization link, complete authentication, then retry your request.",
              null
          );
          stopReason = StopReason.AUTH_HANDOFF;
          break;
        }
        stopReason = StopReason.TOOL_EXECUTION_FAILED;
        lastResponse = failureResponse(initialPrompt, stopReason, "auth-begin", correlationId, authBegin.output);
        appendLedgerAction(rootWorkId, WorkActionType.RECOVERY,
            "auth_begin_failed step=" + step + " stopReason=" + stopReason.code,
            "auth-begin", authBeginArgs, safeTrim(authBegin.output), null, safeTrim(authBegin.errorCode));
        break;
      }

      if (!tool.executed) {
        if ("unsupported-command".equals(tool.errorCode())) {
          stopReason = StopReason.UNSUPPORTED_ACTION;
        } else if ("unsupported_operation".equals(tool.errorCode())) {
          stopReason = StopReason.OUT_OF_SCOPE;
        } else if ("invalid-arguments".equals(tool.errorCode())) {
          stopReason = StopReason.INVALID_ARGUMENTS;
        } else {
          stopReason = StopReason.TOOL_EXECUTION_FAILED;
        }
        lastResponse = stopReason == StopReason.OUT_OF_SCOPE
            ? fallbackResponse(initialPrompt, stopReason, correlationId, tool.output)
            : failureResponse(initialPrompt, stopReason, tool.command, correlationId, tool.output);
        appendLedgerAction(rootWorkId, WorkActionType.RECOVERY,
            "tool_failure step=" + step + " stopReason=" + stopReason.code,
            tool.command, tool.args, safeTrim(tool.output), toolDurationMs, safeTrim(tool.errorCode));
        break;
      }

      if (completesAfterSuccessfulTool) {
        awaitingContinuation = false;
        awaitingCommand = "";
        stopReason = StopReason.ACTION_NONE;
        appendLedgerAction(rootWorkId, WorkActionType.DECISION,
            "step=" + step + " completion=tool_success command=" + tool.command,
            tool.command, tool.args, redactedForLogs(tool.rawOutput()), toolDurationMs, null);
        break;
      }

      if (disposition == ToolResponseDisposition.AWAIT_EXTERNAL_COMPLETION) {
        awaitingContinuation = false;
        awaitingCommand = "";
        stopReason = StopReason.AUTH_REQUIRED;
        lastResponse = new CommandAgentResponse(
            initialPrompt,
            "none",
            safeTrim(tool.output).isEmpty() ? "External completion required." : safeTrim(tool.output),
            null
        );
        appendLedgerAction(rootWorkId, WorkActionType.DECISION,
            "step=" + step + " completion=await_external command=" + tool.command,
            tool.command, tool.args, redactedForLogs(tool.rawOutput()), toolDurationMs, null);
        break;
      }

      awaitingContinuation = true;
      awaitingCommand = safeTrim(tool.command);

      if (!interactiveRequest && step >= 2) {
        // For background reactions, keep loops short and non-blocking.
        stopReason = StopReason.BACKGROUND_STEP_LIMIT;
        break;
      }

      if (step == MAX_STEPS) {
        stopReason = StopReason.MAX_STEPS_REACHED;
      }
    }

    if (lastResponse == null) {
      lastResponse = fallbackResponse(initialPrompt, stopReason, correlationId, "");
    }
    finalizeLedgerWork(rootWorkId, stopReason, lastResponse, toolSteps);
    System.out.println("[DJ-AGENT][LOOP] end stopReason=" + stopReason.code
        + " steps=" + toolSteps.size()
        + " durationMs=" + elapsedMs(startedAtNs)
        + " correlationId=" + safeTrim(correlationId));
    return new LoopResult(initialPrompt, lastResponse, toolSteps, stopReason, elapsedMs(startedAtNs), source, correlationId);
  }

  private ToolExecution executeSpotifyAction(CommandContext parentContext, String action, GoalIntent goalIntent) {
    LineParserUtil.ParsedLine parsed = LineParserUtil.parse(action);
    if (parsed == null || safeTrim(parsed.first).isEmpty()) {
      return ToolExecution.error("invalid-action", "invalid-action", "", "Unable to parse action: " + action, "", ToolResponseOutcome.FAILURE);
    }

    String command = safeTrim(parsed.first).toLowerCase();
    String args = joinArgs(parsed.second, parsed.remaining);
    System.out.println("[DJ-AGENT] executeSpotifyAction parsed command=" + command + " args=" + args);
    ValidatedAction validated = validateAndNormalizeAction(command, args);
    if (validated.error != null) {
      System.out.println("[DJ-AGENT] validateAndNormalizeAction failed command=" + command + " args=" + args + " error=" + validated.error());
      return ToolExecution.error(validated.errorCode(), command, args, validated.error(), "", ToolResponseOutcome.FAILURE);
    }
    if (goalIntent != null && !goalIntent.supportedByToolchain()) {
      String unsupportedMessage = firstNonBlank(
          goalIntent.unsupportedReason(),
          goalIntent.reason(),
          "This request is outside the DJ Spotify toolchain."
      );
      return ToolExecution.error(
          "unsupported_operation",
          command,
          args,
          unsupportedMessage,
          "",
          ToolResponseOutcome.UNSUPPORTED_OPERATION
      );
    }
    command = validated.command;
    args = validated.args;
    return executeSpotifyInternal(parentContext, command, args);
  }

  private ToolExecution executeSpotifyInternal(CommandContext parentContext, String command, String args) {
    String spotifyArgs = safeTrim(args);
    String internalRequestId = "dj-tool-" + newCorrelationId();
    CompletableFuture<SpotifyExecutionResult> outFuture = new CompletableFuture<>();
    SpotifyEventAggregate aggregate = new SpotifyEventAggregate();
    AiatpRequest internalRequest = AiatpRuntimeAdapter.request(
        "ACTION",
        "/" + SPOTIFY_COMPONENT + "/" + command,
        AiatpIO.Body.ofString(spotifyArgs, StandardCharsets.UTF_8)
    );
    internalRequest = inheritParentRouting(parentContext, internalRequest);
    internalRequest = AiatpRuntimeAdapter.withHeader(
        internalRequest,
        CommandContext.HDR_INTERNAL_EVENT_DELIVERY,
        "local"
    );

    CommandContext internalContext = parentContext.cloneBuilder()
        .aiatpRequest(internalRequest)
        .eventConsumer(wrapper -> {
          forwardSpotifyEvent(parentContext, wrapper, internalRequestId);
          recordSpotifyEvent(wrapper, internalRequestId, aggregate);
        })
        .responseConsumer(result -> {
          if (result != null) {
            String reason = safeTrim(result.getReasonPhrase());
            int status = responseStatus(result);
            outFuture.complete(new SpotifyExecutionResult(
                "response",
                "",
                "",
                responseBody(result),
                status >= 400 ? reason : "",
                "auth_required".equalsIgnoreCase(reason) ? "AUTH_REQUIRED" : "",
                status >= 400 ? "failure" : "success",
                reason,
                status,
                aggregate.snapshot()
            ));
          }
        })
        .build();

    try {
      System.out.println("[DJ-AGENT] dispatching context.execute component=" + SPOTIFY_COMPONENT + " command=" + command + " args=" + spotifyArgs);
      ExecutorService dispatchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dj-agent-tool-dispatch");
        t.setDaemon(true);
        return t;
      });
      try {
        dispatchExecutor.submit(() -> parentContext.execute(SPOTIFY_COMPONENT, command, internalContext));
      } finally {
        dispatchExecutor.shutdown();
      }
      SpotifyExecutionResult executionResult = outFuture.get(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      String safeOutput = safeTrim(executionResult.body);
      System.out.println("Tool response [" + command + "]: " + redactedForLogs(safeOutput));
      String authOutcome = safeTrim(executionResult.authOutcome);
      String observedAuthJson = safeTrim(executionResult.snapshot == null ? "" : executionResult.snapshot.lastAuthJson());
      String rawForTool = observedAuthJson.isEmpty() ? safeOutput : observedAuthJson;
      SpotifyEnvelope envelope = parseSpotifyEnvelope(safeOutput);
      String effectiveOutput = envelope.recognized ? envelope.message : safeOutput;
      boolean failed = executionResult.status >= 400
          || "failure".equalsIgnoreCase(safeTrim(executionResult.outcome))
          || (envelope.recognized && !envelope.ok)
          || (!envelope.recognized && isExecutionFailure(executionResult.status, safeOutput));
      if (failed) {
        System.out.println("[DJ-AGENT] tool execution classified failure command=" + command + " status=" + executionResult.status + " body=" + redactedForLogs(safeOutput));
        String errorCode = envelope.recognized ? safeTrim(envelope.errorCode) : "";
        if (errorCode.isEmpty()) {
          errorCode = safeTrim(executionResult.errorCode);
        }
        if (errorCode.isEmpty() && envelope.recognized && safeTrim(envelope.errorCode).length() > 0) {
          errorCode = envelope.errorCode;
        }
        if (errorCode.isEmpty() && "AUTH_REQUIRED".equalsIgnoreCase(authOutcome)) {
          errorCode = "auth_required";
        }
        if (errorCode.isEmpty()) {
          errorCode = "tool-execution-failed";
        }
        ToolResponseOutcome responseOutcome = "AUTH_REQUIRED".equalsIgnoreCase(authOutcome)
            ? ToolResponseOutcome.AWAIT_EXTERNAL_COMPLETION
            : (envelope.recognized ? envelope.responseOutcome : ToolResponseOutcome.FAILURE);
        return ToolExecution.error(errorCode, command, args, effectiveOutput, rawForTool, responseOutcome);
      }
      System.out.println("[DJ-AGENT] tool execution success command=" + command + " status=" + executionResult.status);
      ToolResponseOutcome responseOutcome = determineToolOutcome(command, envelope);
      return new ToolExecution(true, command, args, effectiveOutput, "", rawForTool, responseOutcome);
    } catch (TimeoutException e) {
      SpotifyEventSnapshot snapshot = aggregate.snapshot();
      if ("auth-begin".equals(command) && hasObservedAuthHandoff(snapshot)) {
        String handoffOutput = firstNonBlank(
            safeTrim(snapshot.lastStatus()),
            safeTrim(snapshot.lastChat()),
            "Spotify authorization required. Open the authorization link, complete authentication, then retry your request.");
        // Preserve directive detail via last auth JSON only (no channels envelope).
        String rawOutput = safeTrim(snapshot.lastAuthJson());
        System.out.println("[DJ-AGENT] tool execution timeout converted to auth handoff command=" + command
            + " after " + TOOL_TIMEOUT_SECONDS + "s");
        return new ToolExecution(true, command, args, handoffOutput, "", rawOutput, ToolResponseOutcome.AWAIT_EXTERNAL_COMPLETION);
      }
      System.out.println("[DJ-AGENT] tool execution timeout command=" + command + " after " + TOOL_TIMEOUT_SECONDS + "s");
      return ToolExecution.error(
          "tool-execution-failed",
          command,
          args,
          "Tool execution timed out after " + TOOL_TIMEOUT_SECONDS + "s",
          "",
          ToolResponseOutcome.FAILURE
      );
    } catch (Exception e) {
      System.out.println("Tool execution failure [" + command + "]: " + e.getMessage());
      return ToolExecution.error("tool-execution-failed", command, args, "Tool execution failed: " + e.getMessage(), "", ToolResponseOutcome.FAILURE);
    }
  }

  private void forwardSpotifyEvent(CommandContext parentContext,
                                   AiatpIORequestWrapper wrapper,
                                   String expectedRequestId) {
    if (parentContext == null || wrapper == null || wrapper.getAiatpEvent() == null) {
      return;
    }
    com.social100.todero.common.aiatpio.AiatpEvent event = wrapper.getAiatpEvent();
    String channel = safeTrim(event.getChannel()).toLowerCase();
    String body = safeTrim(AiatpIO.bodyToString(event.getBody(), StandardCharsets.UTF_8));
    String emitPhase = "progress";
    System.out.println("[DJ-AGENT][EMIT] forward_tool_event channel=" + channel
        + " phase=<wire-default>"
        + " bodyLen=" + body.length());
    switch (channel) {
      case "status" -> parentContext.emitChat(body, emitPhase);
      case "chat" -> parentContext.emitChat(body, emitPhase);
      case "html" -> {
        String mode = firstNonBlank(
            event.getHeaders() == null ? null : event.getHeaders().getFirst("Html-Mode"),
            "html"
        );
        boolean replace = event.getHeaders() == null
            || !"false".equalsIgnoreCase(safeTrim(event.getHeaders().getFirst("Html-Replace")));
        parentContext.emitHtml(body, emitPhase, mode, replace);
      }
      case "auth" -> parentContext.emitChat(body, emitPhase);
      case "error" -> parentContext.emitChat(body, emitPhase);
      default -> {
        // ignore non-user channels
      }
    }
  }

  private void recordSpotifyEvent(AiatpIORequestWrapper wrapper,
                                  String expectedRequestId,
                                  SpotifyEventAggregate aggregate) {
    if (wrapper == null || wrapper.getAiatpEvent() == null) {
      return;
    }
    com.social100.todero.common.aiatpio.AiatpEvent event = wrapper.getAiatpEvent();
    String channel = safeTrim(event.getChannel()).toLowerCase();
    String body = safeTrim(AiatpIO.bodyToString(event.getBody(), StandardCharsets.UTF_8));
    String phase = "";
    String errorCode = "auth".equals(channel) ? extractAuthErrorCode(body) : "";
    if ("error".equals(channel) && errorCode.isEmpty()) {
      errorCode = "tool-execution-failed";
    }
    System.out.println("[DJ-AGENT][EVENT] observed scope=" + event.getScope()
        + " channel=" + channel
        + " phase=" + (phase.isEmpty() ? "<none>" : phase)
        + " expectedRef=" + expectedRequestId);
    aggregate.record(channel, phase, body, errorCode);
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

  private static AuthDirective extractAuthDirective(String toolOutput) {
    String text = safeTrim(toolOutput);
    if (text.isEmpty()) {
      return AuthDirective.invalid();
    }
    try {
      JsonNode root = JSON.readTree(text);
      // Wire-only: auth directive is carried as auth-channel JSON (no channels envelope).
      // Legacy: some payloads may wrap directive under {"auth":{...}}.
      JsonNode auth = root.path("auth");
      JsonNode authNode = auth.isObject() ? auth : root;
      String provider = safeTrim(readPath(authNode, "provider"));
      String sessionId = firstNonBlank(
          readPath(authNode, "sessionId"),
          readPath(authNode, "session.sessionId"),
          readPath(root, "sessionId"),
          readPath(root, "session.sessionId")
      );
      String authorizeUrl = firstNonBlank(
          readPath(authNode, "authorizeUrl"),
          readPath(root, "authorizeUrl")
      );
      String completeCommand = firstNonBlank(
          readPath(authNode, "completeCommand"),
          readPath(authNode, "auth-complete"),
          readPath(root, "completeCommand")
      );
      boolean required = authNode.path("required").asBoolean(true);
      boolean hasSecureEnvelope = authNode.path("secureEnvelope").isObject() || root.path("secureEnvelope").isObject();
      String authJson = auth.isObject() ? auth.toString() : (root.isObject() ? root.toString() : "");
      boolean valid = required && !sessionId.isEmpty() && !authorizeUrl.isEmpty();
      return new AuthDirective(valid, provider, sessionId, authorizeUrl, completeCommand, hasSecureEnvelope, authJson);
    } catch (Exception ignored) {
      return AuthDirective.invalid();
    }
  }

  private CommandAgentResponse planNextAction(LLMClient llm, AgentPrompt prompt, AgentContext context) throws Exception {
    System.out.println("[DJ-AGENT][PLAN] planning with llmClass=" + (llm == null ? "<null>" : llm.getClass().getName()));
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

  private GoalIntent normalizeGoalIntent(LLMClient llm,
                                         String initialPrompt,
                                         String source,
                                         String correlationId,
                                         boolean interactiveRequest,
                                         String rootWorkId) {
    GoalIntent fallback = fallbackGoalIntent(initialPrompt);
    String normalizedPrompt = safeTrim(initialPrompt).toLowerCase(Locale.ROOT);
    boolean explicitPlaybackRequest = hasExplicitPlaybackRequest(normalizedPrompt);
    boolean recommendationCue = isRecommendationIntent(normalizedPrompt);
    try {
      Map<String, Object> context = new LinkedHashMap<>();
      context.put("source", safeTrim(source));
      context.put("correlationId", safeTrim(correlationId));
      context.put("interactive", interactiveRequest);
      String raw = llm.chat(
          loadSystemPrompt("prompts/intent-normalizer-prompt.md"),
          safeTrim(initialPrompt),
          mapper.writeValueAsString(context)
      );
      JsonNode root = extractFirstJsonBlockLocal(raw);
      String intent = firstNonBlank(
          safeTrim(readPath(root, "intent")),
          fallback.intent()
      );
      String targetScope = firstNonBlank(
          safeTrim(readPath(root, "target_scope")),
          safeTrim(readPath(root, "targetScope")),
          fallback.targetScope()
      );
      String seedHint = firstNonBlank(
          safeTrim(readPath(root, "seed_hint")),
          safeTrim(readPath(root, "seedHint")),
          fallback.seedHint()
      );
      boolean wantsPlayback = readBoolean(root, fallback.wantsPlayback(), "wants_playback", "wantsPlayback");
      if (safeTrim(intent).toLowerCase(Locale.ROOT).contains("recommend") && !explicitPlaybackRequest) {
        wantsPlayback = false;
      }
      boolean referencesCurrentPlayback = readBoolean(root, fallback.referencesCurrentPlayback(),
          "references_current_playback", "referencesCurrentPlayback");
      boolean needsDiscovery = readBoolean(root, fallback.needsDiscovery(), "needs_discovery", "needsDiscovery");
      int requestedCount = readRecommendationCount(root, fallback.requestedCount(), "requested_count", "requestedCount");
      boolean supportedByToolchain = readBoolean(root, fallback.supportedByToolchain(),
          "supported_by_toolchain", "supportedByToolchain");
      String unsupportedReason = firstNonBlank(
          safeTrim(readPath(root, "unsupported_reason")),
          safeTrim(readPath(root, "unsupportedReason")),
          fallback.unsupportedReason()
      );
      double confidence = readDouble(root, fallback.confidence(), "confidence");
      String reason = firstNonBlank(
          safeTrim(readPath(root, "reason")),
          fallback.reason()
      );
      String normalizedIntent = safeTrim(intent).toLowerCase(Locale.ROOT);
      // Direct play requests should not enter recommendation verification unless the prompt
      // actually asks for recommendations or similar music.
      if (normalizedIntent.contains("recommend")
          && explicitPlaybackRequest
          && !referencesCurrentPlayback
          && !recommendationCue) {
        intent = "general_spotify_control";
        targetScope = "explicit_request";
        wantsPlayback = true;
        reason = firstNonBlank(reason, "direct_play_request");
      }
      GoalIntent normalized = new GoalIntent(
          intent,
          targetScope,
          seedHint,
          wantsPlayback,
          referencesCurrentPlayback,
          needsDiscovery,
          requestedCount,
          supportedByToolchain,
          unsupportedReason,
          confidence,
          reason
      ).normalized();
      appendLedgerAction(rootWorkId, WorkActionType.NOTE,
          "goal_normalized intent=" + normalized.intent()
              + " targetScope=" + normalized.targetScope()
              + " wantsPlayback=" + normalized.wantsPlayback()
              + " referencesCurrentPlayback=" + normalized.referencesCurrentPlayback(),
          null, null, null, null, null, null);
      return normalized;
    } catch (Exception e) {
      appendLedgerAction(rootWorkId, WorkActionType.NOTE,
          "goal_normalization_fallback message=" + safeTrim(e.getMessage()),
          null, null, null, null, null, null);
      return fallback;
    }
  }

  private LoopResult runRecommendationFlow(CommandContext parentContext,
                                           LLMClient llm,
                                           String initialPrompt,
                                           String source,
                                           String correlationId,
                                           String rootWorkId,
                                           GoalIntent goalIntent) {
    long startedAtNs = System.nanoTime();
    List<ToolStep> toolSteps = new ArrayList<>();
    StopReason stopReason = StopReason.ACTION_NONE;
    CommandAgentResponse finalResponse;
    int step = 1;
    PlaybackFacts playback = PlaybackFacts.empty();

    if (goalIntent.referencesCurrentPlayback()) {
      long stepStartedAtNs = System.nanoTime();
      ToolExecution status = executeSpotifyInternal(parentContext, "status", "all");
      long toolDurationMs = elapsedMs(stepStartedAtNs);
      toolSteps.add(new ToolStep(step++, "status all", status.command, status.args, status.rawOutput(), 0, toolDurationMs, toolDurationMs));
      recordToolStepSafely(rootWorkId, status, toolDurationMs);
      if (!status.executed) {
        stopReason = StopReason.TOOL_EXECUTION_FAILED;
        finalResponse = failureResponse(initialPrompt, stopReason, status.command, correlationId, status.output);
        finalizeLedgerWork(rootWorkId, stopReason, finalResponse, toolSteps);
        return new LoopResult(initialPrompt, finalResponse, toolSteps, stopReason, elapsedMs(startedAtNs), source, correlationId);
      }
      playback = extractPlaybackFacts(toolSteps);
      if (safeTrim(playback.trackTitle()).isEmpty()) {
        stopReason = StopReason.TOOL_EXECUTION_FAILED;
        finalResponse = failureResponse(initialPrompt, stopReason, "status", correlationId,
            "Could not resolve the current playback track for recommendation.");
        finalizeLedgerWork(rootWorkId, stopReason, finalResponse, toolSteps);
        return new LoopResult(initialPrompt, finalResponse, toolSteps, stopReason, elapsedMs(startedAtNs), source, correlationId);
      }
    }

    RecommendationCandidates candidateBatch = generateRecommendationCandidates(llm, initialPrompt, goalIntent, playback, rootWorkId);
    if (candidateBatch.candidates().isEmpty()) {
      stopReason = StopReason.PLANNER_EXCEPTION;
      finalResponse = failureResponse(initialPrompt, stopReason, "recommendation-candidates", correlationId,
          "Could not generate recommendation candidates.");
      finalizeLedgerWork(rootWorkId, stopReason, finalResponse, toolSteps);
      return new LoopResult(initialPrompt, finalResponse, toolSteps, stopReason, elapsedMs(startedAtNs), source, correlationId);
    }

    int requestedCount = Math.max(1, goalIntent.requestedCount());
    List<VerifiedTrack> verified = new ArrayList<>();
    for (RecommendationCandidate candidate : candidateBatch.candidates()) {
      long stepStartedAtNs = System.nanoTime();
      ToolExecution resolved = executeSpotifyInternal(parentContext, "resolve-track", candidate.query());
      long toolDurationMs = elapsedMs(stepStartedAtNs);
      toolSteps.add(new ToolStep(step++, "resolve-track " + candidate.query(), resolved.command, resolved.args, resolved.rawOutput(), 0, toolDurationMs, toolDurationMs));
      recordToolStepSafely(rootWorkId, resolved, toolDurationMs);
      if (!resolved.executed) {
        continue;
      }
      VerifiedTrack track = parseVerifiedTrack(resolved.rawOutput(), candidate);
      if (track == null) {
        continue;
      }
      if (verified.stream().noneMatch(existing -> existing.uri().equalsIgnoreCase(track.uri()))) {
        verified.add(track);
      }
      if (verified.size() >= requestedCount) {
        break;
      }
    }

    if (verified.isEmpty()) {
      stopReason = StopReason.TOOL_EXECUTION_FAILED;
      finalResponse = failureResponse(initialPrompt, stopReason, "resolve-track", correlationId,
          "Could not resolve any Spotify tracks for this recommendation request.");
      finalizeLedgerWork(rootWorkId, stopReason, finalResponse, toolSteps);
      return new LoopResult(initialPrompt, finalResponse, toolSteps, stopReason, elapsedMs(startedAtNs), source, correlationId);
    }

    if (goalIntent.wantsPlayback()) {
      VerifiedTrack selected = verified.get(0);
      long stepStartedAtNs = System.nanoTime();
      ToolExecution play = executeSpotifyInternal(parentContext, "play", selected.uri());
      long toolDurationMs = elapsedMs(stepStartedAtNs);
      toolSteps.add(new ToolStep(step, "play " + selected.uri(), play.command, play.args, play.rawOutput(), 0, toolDurationMs, toolDurationMs));
      recordToolStepSafely(rootWorkId, play, toolDurationMs);
      if (!play.executed) {
        stopReason = StopReason.TOOL_EXECUTION_FAILED;
        finalResponse = failureResponse(initialPrompt, stopReason, play.command, correlationId, play.output);
        finalizeLedgerWork(rootWorkId, stopReason, finalResponse, toolSteps);
        return new LoopResult(initialPrompt, finalResponse, toolSteps, stopReason, elapsedMs(startedAtNs), source, correlationId);
      }
      finalResponse = new CommandAgentResponse(
          initialPrompt,
          "none",
          "Playing a recommended track: " + selected.title() + " — " + selected.artist() + ".",
          buildRecommendationHtml(playback, verified, requestedCount)
      );
    } else {
      finalResponse = new CommandAgentResponse(
          initialPrompt,
          "none",
          buildRecommendationSummary(playback, verified, requestedCount),
          buildRecommendationHtml(playback, verified, requestedCount)
      );
    }

    finalizeLedgerWork(rootWorkId, stopReason, finalResponse, toolSteps);
    return new LoopResult(initialPrompt, finalResponse, toolSteps, stopReason, elapsedMs(startedAtNs), source, correlationId);
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

  private static ToolResponseDisposition responseDispositionForSuccessfulTool(ToolExecution tool) {
    if (tool == null) {
      return ToolResponseDisposition.CONTINUE;
    }
    ToolResponseOutcome outcome = tool.responseOutcome == null ? ToolResponseOutcome.UNSPECIFIED : tool.responseOutcome;
    return switch (outcome) {
      case GOAL_COMPLETED -> ToolResponseDisposition.GOAL_COMPLETED;
      case AWAIT_EXTERNAL_COMPLETION -> ToolResponseDisposition.AWAIT_EXTERNAL_COMPLETION;
      case INTERMEDIATE_RESULT, UNSPECIFIED -> ToolResponseDisposition.CONTINUE;
      case FAILURE, UNSUPPORTED_OPERATION -> ToolResponseDisposition.FAIL;
    };
  }

  private SpotifyEnvelope parseSpotifyEnvelope(String output) {
    String text = safeTrim(output);
    if (text.isEmpty()) {
      return new SpotifyEnvelope(false, true, "", "", ToolResponseOutcome.UNSPECIFIED);
    }
    try {
      JsonNode root = mapper.readTree(text);
      if (!root.has("ok") || !root.has("message")) {
        return new SpotifyEnvelope(false, true, "", text, ToolResponseOutcome.UNSPECIFIED);
      }
      boolean ok = root.path("ok").asBoolean(true);
      String message = readPath(root, "message");
      String errorCode = readPath(root, "errorCode");
      ToolResponseOutcome responseOutcome = parseToolResponseOutcome(root);
      return new SpotifyEnvelope(true, ok, errorCode, message.isBlank() ? text : message, responseOutcome);
    } catch (Exception ignored) {
      return new SpotifyEnvelope(false, true, "", text, ToolResponseOutcome.UNSPECIFIED);
    }
  }

  private ToolResponseOutcome parseToolResponseOutcome(JsonNode root) {
    String outcome = firstNonBlank(
        readPath(root, "response.outcome"),
        readPath(root, "meta.outcome")
    ).toLowerCase(Locale.ROOT);
    return switch (outcome) {
      case "goal_completed", "success", "completed" -> ToolResponseOutcome.GOAL_COMPLETED;
      case "intermediate_result" -> ToolResponseOutcome.INTERMEDIATE_RESULT;
      case "await_external_completion", "auth_handoff" -> ToolResponseOutcome.AWAIT_EXTERNAL_COMPLETION;
      case "failure" -> ToolResponseOutcome.FAILURE;
      case "unsupported_operation", "unhandled_intent" -> ToolResponseOutcome.UNSUPPORTED_OPERATION;
      default -> ToolResponseOutcome.UNSPECIFIED;
    };
  }

  private static ToolResponseOutcome determineToolOutcome(String command, SpotifyEnvelope envelope) {
    if (envelope != null && envelope.recognized && envelope.responseOutcome != null
        && envelope.responseOutcome != ToolResponseOutcome.UNSPECIFIED) {
      return envelope.responseOutcome;
    }
    String normalized = safeTrim(command).toLowerCase(Locale.ROOT);
    if ("auth-begin".equals(normalized)) {
      return ToolResponseOutcome.AWAIT_EXTERNAL_COMPLETION;
    }
    // Default: successful tool invocations are terminal (the agent may still emit tool output as events).
    return ToolResponseOutcome.GOAL_COMPLETED;
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
      case "resolve-track" -> {
        if (args.isEmpty()) {
          return ValidatedAction.error(command, args, "invalid-arguments", "resolve-track requires a non-empty query.");
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

  private static String coercePlannerAction(String initialPrompt,
                                            GoalIntent goalIntent,
                                            String plannerAction,
                                            int step,
                                            boolean interactiveRequest) {
    String action = safeTrim(plannerAction);
    if (!interactiveRequest || step != 1) {
      return action;
    }

    boolean actionNone = action.isEmpty() || "none".equalsIgnoreCase(action);

    String prompt = safeTrim(initialPrompt).toLowerCase();
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
    String normalized = Normalizer.normalize(safeTrim(value), Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "");
    return normalized.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").trim();
  }

  private static boolean hasExplicitPlaybackRequest(String normalizedPrompt) {
    return normalizedPrompt.contains("play ")
        || normalizedPrompt.startsWith("play")
        || normalizedPrompt.contains("put on")
        || normalizedPrompt.contains("start ")
        || normalizedPrompt.contains("listen to")
        || normalizedPrompt.contains("pon ")
        || normalizedPrompt.contains("reproduce ")
        || normalizedPrompt.contains("toca ")
        || normalizedPrompt.contains("escuchar ");
  }

  private static int inferRequestedRecommendationCount(String prompt) {
    String normalized = safeTrim(prompt).toLowerCase(Locale.ROOT);
    Matcher numeric = EXPLICIT_COUNT_PATTERN.matcher(normalized);
    if (numeric.find()) {
      return clampRecommendationCount(parseSafeInt(numeric.group(1), DEFAULT_RECOMMENDATION_COUNT));
    }

    List<String> tokens = Arrays.asList(normalized.split("\\s+"));
    Map<String, Integer> wordCounts = Map.ofEntries(
        Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
        Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8),
        Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("una", 1), Map.entry("uno", 1),
        Map.entry("dos", 2), Map.entry("tres", 3), Map.entry("cuatro", 4), Map.entry("cinco", 5),
        Map.entry("seis", 6), Map.entry("siete", 7), Map.entry("ocho", 8), Map.entry("nueve", 9),
        Map.entry("diez", 10)
    );
    for (int i = 0; i < tokens.size() - 1; i++) {
      Integer count = wordCounts.get(tokens.get(i));
      if (count == null) {
        continue;
      }
      String next = tokens.get(i + 1);
      if (next.startsWith("song") || next.startsWith("track") || next.startsWith("cancion") || next.startsWith("canción")) {
        return clampRecommendationCount(count);
      }
    }
    return DEFAULT_RECOMMENDATION_COUNT;
  }

  private static int recommendationCandidateTarget(int requestedCount) {
    int normalized = clampRecommendationCount(requestedCount);
    return normalized <= 1 ? 1 : Math.min(MAX_RECOMMENDATION_COUNT, normalized * 2);
  }

  private static int clampRecommendationCount(int count) {
    return Math.max(DEFAULT_RECOMMENDATION_COUNT, Math.min(MAX_RECOMMENDATION_COUNT, count));
  }

  private static int parseSafeInt(String raw, int fallback) {
    try {
      return Integer.parseInt(safeTrim(raw));
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private static int readRecommendationCount(JsonNode root, int fallback, String... keys) {
    for (String key : keys) {
      JsonNode node = root.path(key);
      if (node.isInt() || node.isLong()) {
        return clampRecommendationCount(node.asInt());
      }
      String text = safeTrim(node.asText(""));
      if (!text.isEmpty()) {
        return clampRecommendationCount(parseSafeInt(text, fallback));
      }
    }
    return clampRecommendationCount(fallback);
  }

  private static boolean isRecommendationIntent(String prompt) {
    return prompt.contains("recommend")
        || prompt.contains("similar to")
        || prompt.contains("similar song")
        || prompt.contains("similar songs")
        || prompt.contains("simmilar song")
        || prompt.contains("simmilar songs")
        || prompt.contains("similar track")
        || prompt.contains("similar tracks")
        || prompt.contains("another similar song")
        || prompt.contains("another simmilar song")
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

  private static GoalIntent fallbackGoalIntent(String prompt) {
    String normalized = safeTrim(prompt).toLowerCase(Locale.ROOT);
    String intent = "general_spotify_control";
    String targetScope = "explicit_request";
    boolean wantsPlayback = hasExplicitPlaybackRequest(normalized);
    boolean referencesCurrentPlayback = normalized.contains("current-playback")
        || normalized.contains("currently playing")
        || normalized.contains("current song")
        || normalized.contains("current track")
        || normalized.contains("this song")
        || normalized.contains("this track")
        || normalized.contains("what's playing")
        || normalized.contains("what is playing")
        || normalized.contains("what's on now")
        || normalized.contains("what is on now")
        || normalized.contains("playing now")
        || normalized.contains("on now");
    String seedHint = "";
    if (isRecommendationIntent(normalized) || referencesCurrentPlayback) {
      intent = wantsPlayback ? "recommendation_playback" : "recommendation_info";
      targetScope = referencesCurrentPlayback ? "current_playback" : "explicit_seed";
      seedHint = referencesCurrentPlayback ? "current-playback" : safeTrim(prompt);
    } else if (normalized.contains("playlist")) {
      intent = "playlist_management";
    } else if (normalized.contains("status") || normalized.contains("what is playing") || normalized.contains("what's playing")) {
      intent = "playback_status";
    }
    return new GoalIntent(
        intent,
        targetScope,
        seedHint,
        wantsPlayback,
        referencesCurrentPlayback,
        referencesCurrentPlayback,
        inferRequestedRecommendationCount(prompt),
        true,
        "",
        0.35d,
        "heuristic_fallback"
    ).normalized();
  }

  private RecommendationCandidates generateRecommendationCandidates(LLMClient llm,
                                                                    String initialPrompt,
                                                                    GoalIntent goalIntent,
                                                                    PlaybackFacts playback,
                                                                    String rootWorkId) {
    try {
      Map<String, Object> context = new LinkedHashMap<>();
      context.put("goal", safeTrim(initialPrompt));
      context.put("normalized_goal", Map.of(
          "intent", goalIntent.intent(),
          "target_scope", goalIntent.targetScope(),
          "seed_hint", goalIntent.seedHint(),
          "wants_playback", goalIntent.wantsPlayback(),
          "references_current_playback", goalIntent.referencesCurrentPlayback(),
          "requested_count", goalIntent.requestedCount(),
          "candidate_count_target", recommendationCandidateTarget(goalIntent.requestedCount())
      ));
      context.put("known_facts", Map.of(
          "current_track", safeTrim(playback.trackTitle()),
          "current_track_uri", safeTrim(playback.trackUri()),
          "playback_active", playback.playing()
      ));
      String raw = llm.chat(
          loadSystemPrompt("prompts/recommendation-candidates-prompt.md"),
          safeTrim(initialPrompt),
          mapper.writeValueAsString(context)
      );
      JsonNode root = extractFirstJsonBlockLocal(raw);
      List<RecommendationCandidate> candidates = new ArrayList<>();
      JsonNode nodes = root.path("candidates");
      if (nodes.isArray()) {
        for (JsonNode node : nodes) {
          String query = firstNonBlank(
              safeTrim(readPath(node, "query")),
              joinCandidateQuery(safeTrim(readPath(node, "title")), safeTrim(readPath(node, "artist")))
          );
          if (query.isEmpty()) {
            continue;
          }
          candidates.add(new RecommendationCandidate(
              safeTrim(readPath(node, "title")),
              safeTrim(readPath(node, "artist")),
              query,
              safeTrim(readPath(node, "reason"))
          ));
          if (candidates.size() >= recommendationCandidateTarget(goalIntent.requestedCount())) {
            break;
          }
        }
      }
      appendLedgerAction(rootWorkId, WorkActionType.NOTE,
          "recommendation_candidates count=" + candidates.size(),
          null, null, null, null, null, null);
      return new RecommendationCandidates(candidates);
    } catch (Exception e) {
      appendLedgerAction(rootWorkId, WorkActionType.NOTE,
          "recommendation_candidates_failed message=" + safeTrim(e.getMessage()),
          null, null, null, null, null, null);
      return new RecommendationCandidates(List.of());
    }
  }

  private static String joinCandidateQuery(String title, String artist) {
    String safeTitle = safeTrim(title);
    String safeArtist = safeTrim(artist);
    if (safeTitle.isEmpty()) {
      return "";
    }
    return safeArtist.isEmpty() ? safeTitle : safeTitle + " " + safeArtist;
  }

  private static VerifiedTrack parseVerifiedTrack(String toolOutput, RecommendationCandidate candidate) {
    String text = extractToolText(toolOutput);
    if (text.isEmpty()) {
      return null;
    }
    Matcher matcher = RESOLVED_TRACK_PATTERN.matcher(text);
    if (!matcher.find()) {
      return null;
    }
    VerifiedTrack track = new VerifiedTrack(
        safeTrim(matcher.group(1)),
        safeTrim(matcher.group(2)),
        safeTrim(matcher.group(3)),
        safeTrim(candidate.reason())
    );
    return track;
  }

  private static String buildRecommendationSummary(PlaybackFacts playback, List<VerifiedTrack> verified, int requestedCount) {
    String anchor = safeTrim(playback.trackTitle()).isEmpty()
        ? "your request"
        : "the current track " + playback.trackTitle();
    StringBuilder message = new StringBuilder();
    if (verified.size() < requestedCount) {
      message.append("I could resolve ")
          .append(verified.size())
          .append(" of ")
          .append(requestedCount)
          .append(" requested tracks for ")
          .append(anchor)
          .append(':');
    } else {
      message.append("Here are recommended tracks for ").append(anchor).append(':');
    }
    for (int i = 0; i < verified.size(); i++) {
      VerifiedTrack track = verified.get(i);
      message.append("\n")
          .append(i + 1)
          .append(") ")
          .append(track.title())
          .append(" — ")
          .append(track.artist());
    }
    return message.toString();
  }

  private static String buildRecommendationHtml(PlaybackFacts playback, List<VerifiedTrack> verified, int requestedCount) {
    if (verified == null || verified.isEmpty()) {
      return "";
    }
    String title = safeTrim(playback.trackTitle()).isEmpty()
        ? "Recommendations"
        : "Recommended tracks related to " + playback.trackTitle();
    if (verified.size() < requestedCount) {
      title += " (" + verified.size() + " of " + requestedCount + ")";
    }
    StringBuilder rows = new StringBuilder();
    for (VerifiedTrack track : verified) {
      rows.append("<li style=\"margin-bottom:10px;\">")
          .append("<div style=\"font-size:14px; margin-bottom:4px;\">")
          .append(escapeHtml(track.title())).append(" — ").append(escapeHtml(track.artist()))
          .append("</div>");
      if (!safeTrim(track.reason()).isEmpty()) {
        rows.append("<div style=\"font-size:12px; color:#8b949e; margin-bottom:6px;\">")
            .append(escapeHtml(track.reason()))
            .append("</div>");
      }
      rows.append("<div style=\"font-size:12px; color:#8b949e;\">")
          .append(escapeHtml(track.uri()))
          .append("</div>")
          .append("</li>");
    }
    return "<html><body style=\"font-family:sans-serif;padding:12px;margin:0;background:#0d1117;color:#e6edf3;\">"
        + "<div style=\"border:1px solid #30363d;border-radius:10px;padding:12px;background:#161b22;\">"
        + "<div style=\"font-size:18px;font-weight:700;margin-bottom:10px;\">" + escapeHtml(title) + "</div>"
        + "<ol style=\"padding-left:18px;margin:0;\">" + rows + "</ol>"
        + "</div></body></html>";
  }

  private static String inferPlanState(GoalIntent goalIntent, List<ToolStep> toolSteps) {
    if (toolSteps == null || toolSteps.isEmpty()) {
      return goalIntent.isRecommendationFlow() ? "need_candidate_resolution" : "planning";
    }
    PlaybackFacts playback = extractPlaybackFacts(toolSteps);
    if (hasFailedTool(toolSteps)) {
      return "tool_failed";
    }
    if (hasSuccessfulTool(toolSteps, "auth-begin")) {
      return "awaiting_auth";
    }
    if (goalIntent.isRecommendationFlow() && safeTrim(playback.trackUri()).isEmpty()) {
      return "need_current_playback";
    }
    if (hasSuccessfulTool(toolSteps, "resolve-track")) {
      return goalIntent.wantsPlayback() ? "need_playback_action" : "have_recommended_tracks";
    }
    if (goalIntent.isRecommendationFlow() && !safeTrim(playback.trackUri()).isEmpty()) {
      return "need_candidate_resolution";
    }
    return "planning";
  }

  private void appendKnownFacts(StringBuilder prompt,
                                PlaybackFacts playback,
                                List<ToolStep> toolSteps,
                                CommandAgentResponse lastResponse) {
    if (!safeTrim(playback.device()).isEmpty()) {
      prompt.append("- device: ").append(playback.device()).append('\n');
    }
    if (!safeTrim(playback.trackTitle()).isEmpty()) {
      prompt.append("- current_track: ").append(playback.trackTitle()).append('\n');
    }
    if (!safeTrim(playback.trackUri()).isEmpty()) {
      prompt.append("- current_track_uri: ").append(playback.trackUri()).append('\n');
    }
    prompt.append("- playback_active: ").append(playback.playing()).append('\n');
    if (!safeTrim(playback.position()).isEmpty()) {
      prompt.append("- position: ").append(playback.position()).append('\n');
    }
    ToolStep lastToolStep = findLastToolStep(toolSteps);
    if (lastToolStep != null) {
      prompt.append("- last_tool: ").append(safeTrim(lastToolStep.toolCommand));
      String error = extractToolErrorCode(lastToolStep);
      if (!error.isEmpty()) {
        prompt.append(" (error=").append(error).append(')');
      }
      prompt.append('\n');
    }
    if (lastResponse != null && !safeTrim(lastResponse.getAction()).isEmpty()) {
      prompt.append("- last_planner_action: ").append(safeTrim(lastResponse.getAction())).append('\n');
    }
  }

  private void appendRecentSteps(StringBuilder prompt, List<ToolStep> toolSteps) {
    if (toolSteps == null || toolSteps.isEmpty()) {
      prompt.append("- none yet\n");
      return;
    }
    int from = Math.max(0, toolSteps.size() - PLANNER_RECENT_STEP_LIMIT);
    for (int i = from; i < toolSteps.size(); i++) {
      ToolStep step = toolSteps.get(i);
      prompt.append("- step ").append(step.step())
          .append(": planned=").append(safeTrim(step.agentAction()))
          .append(", tool=").append(safeTrim(step.toolCommand()));
      if (!safeTrim(step.toolArgs()).isEmpty()) {
        prompt.append(' ').append(safeTrim(step.toolArgs()));
      }
      String excerpt = summarizeToolOutput(step.toolOutput());
      if (!excerpt.isEmpty()) {
        prompt.append(", result=").append(excerpt);
      }
      prompt.append('\n');
    }
  }

  private Map<String, Object> buildKnownFactsMap(PlaybackFacts playback,
                                                 List<ToolStep> toolSteps,
                                                 CommandAgentResponse lastResponse) {
    Map<String, Object> facts = new LinkedHashMap<>();
    if (!safeTrim(playback.device()).isEmpty()) {
      facts.put("device", playback.device());
    }
    facts.put("playback_active", playback.playing());
    if (!safeTrim(playback.trackTitle()).isEmpty()) {
      facts.put("current_track", playback.trackTitle());
    }
    if (!safeTrim(playback.trackUri()).isEmpty()) {
      facts.put("current_track_uri", playback.trackUri());
    }
    if (!safeTrim(playback.position()).isEmpty()) {
      facts.put("position", playback.position());
    }
    ToolStep last = findLastToolStep(toolSteps);
    if (last != null) {
      facts.put("last_tool_command", safeTrim(last.toolCommand()));
      String errorCode = extractToolErrorCode(last);
      if (!errorCode.isEmpty()) {
        facts.put("last_tool_error", errorCode);
      }
    }
    if (lastResponse != null && !safeTrim(lastResponse.getAction()).isEmpty()) {
      facts.put("last_planner_action", safeTrim(lastResponse.getAction()));
    }
    return facts;
  }

  private List<Map<String, Object>> buildRecentStepsContext(List<ToolStep> toolSteps) {
    List<Map<String, Object>> recent = new ArrayList<>();
    if (toolSteps == null || toolSteps.isEmpty()) {
      return recent;
    }
    int from = Math.max(0, toolSteps.size() - PLANNER_RECENT_STEP_LIMIT);
    for (int i = from; i < toolSteps.size(); i++) {
      ToolStep step = toolSteps.get(i);
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("step", step.step());
      item.put("planned_action", safeTrim(step.agentAction()));
      item.put("tool_command", safeTrim(step.toolCommand()));
      item.put("tool_args", safeTrim(step.toolArgs()));
      item.put("tool_output_excerpt", summarizeToolOutput(step.toolOutput()));
      String errorCode = extractToolErrorCode(step);
      if (!errorCode.isEmpty()) {
        item.put("error_code", errorCode);
      }
      recent.add(item);
    }
    return recent;
  }

  private static PlaybackFacts extractPlaybackFacts(List<ToolStep> toolSteps) {
    if (toolSteps == null) {
      return PlaybackFacts.empty();
    }
    for (int i = toolSteps.size() - 1; i >= 0; i--) {
      ToolStep step = toolSteps.get(i);
      if (!"status".equalsIgnoreCase(safeTrim(step.toolCommand()))) {
        continue;
      }
      String output = extractToolText(step.toolOutput());
      if (output.isEmpty()) {
        continue;
      }
      String device = matchFirst(DEVICE_PATTERN, output);
      String track = matchFirst(TRACK_TITLE_PATTERN, output);
      String uri = matchFirst(TRACK_URI_PATTERN, output);
      String playingRaw = matchFirst(PLAYING_PATTERN, output);
      String position = matchFirst(POSITION_PATTERN, output);
      boolean playing = "true".equalsIgnoreCase(safeTrim(playingRaw));
      return new PlaybackFacts(device, track, uri, position, playing);
    }
    return PlaybackFacts.empty();
  }

  private static String summarizeToolOutput(String toolOutput) {
    String text = extractToolText(toolOutput);
    if (text.isEmpty()) {
      return "";
    }
    String firstLine = text.split("\\R", 2)[0].trim();
    return firstLine.length() > 180 ? firstLine.substring(0, 177) + "..." : firstLine;
  }

  private static boolean hasSuccessfulTool(List<ToolStep> toolSteps, String command) {
    if (toolSteps == null || safeTrim(command).isEmpty()) {
      return false;
    }
    for (int i = toolSteps.size() - 1; i >= 0; i--) {
      ToolStep step = toolSteps.get(i);
      if (!command.equalsIgnoreCase(safeTrim(step.toolCommand()))) {
        continue;
      }
      if (extractToolErrorCode(step).isEmpty()) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasFailedTool(List<ToolStep> toolSteps) {
    if (toolSteps == null) {
      return false;
    }
    for (int i = toolSteps.size() - 1; i >= 0; i--) {
      if (!extractToolErrorCode(toolSteps.get(i)).isEmpty()) {
        return true;
      }
    }
    return false;
  }

  private static String extractToolErrorCode(ToolStep step) {
    if (step == null) {
      return "";
    }
    String output = safeTrim(step.toolOutput());
    if (output.isEmpty()) {
      return "";
    }
    try {
      JsonNode root = JSON.readTree(output);
      return firstNonBlank(
          safeTrim(readPath(root, "errorCode")),
          safeTrim(readPath(root, "auth.errorCode"))
      );
    } catch (Exception ignored) {
      return "";
    }
  }

  private static String matchFirst(Pattern pattern, String value) {
    if (pattern == null || value == null) {
      return "";
    }
    Matcher matcher = pattern.matcher(value);
    if (!matcher.find()) {
      return "";
    }
    if (matcher.groupCount() >= 1) {
      return safeTrim(matcher.group(1));
    }
    return safeTrim(matcher.group());
  }

  private static String extractToolText(String toolOutput) {
    String text = safeTrim(toolOutput);
    if (text.isEmpty()) {
      return "";
    }
    try {
      JsonNode root = JSON.readTree(text);
      return firstNonBlank(
          safeTrim(readPath(root, "message")),
          text
      );
    } catch (Exception ignored) {
      return text;
    }
  }

  private String buildPlannerPrompt(String initialPrompt,
                                    String source,
                                    String correlationId,
                                    boolean interactiveRequest,
                                    int step,
                                    GoalIntent goalIntent,
                                    List<ToolStep> toolSteps,
                                    CommandAgentResponse lastResponse,
                                    List<String> plannerHistory,
                                    String rootWorkId) {
    PlaybackFacts playback = extractPlaybackFacts(toolSteps);
    StringBuilder prompt = new StringBuilder(1024);
    prompt.append("Continue solving the active Spotify goal.\n\n");
    prompt.append("Original goal:\n").append(safeTrim(initialPrompt)).append("\n\n");
    prompt.append("Request state:\n");
    prompt.append("- source: ").append(safeTrim(source)).append('\n');
    prompt.append("- correlationId: ").append(safeTrim(correlationId)).append('\n');
    prompt.append("- interactive: ").append(interactiveRequest).append('\n');
    prompt.append("- next step: ").append(step).append('\n');
    prompt.append("- normalized intent: ").append(goalIntent.intent()).append('\n');
    prompt.append("- target scope: ").append(goalIntent.targetScope()).append('\n');
    prompt.append("- wants playback: ").append(goalIntent.wantsPlayback()).append('\n');
    prompt.append("- references current playback: ").append(goalIntent.referencesCurrentPlayback()).append('\n');
    prompt.append("- plan state: ").append(inferPlanState(goalIntent, toolSteps)).append("\n\n");
    prompt.append("Known facts:\n");
    appendKnownFacts(prompt, playback, toolSteps, lastResponse);
    prompt.append("\nRecent steps:\n");
    appendRecentSteps(prompt, toolSteps);
    prompt.append("\nPlanner history:\n");
    appendPlannerHistory(prompt, plannerHistory);
    prompt.append("\nDecision rules:\n");
    prompt.append("- Do not forget the original goal.\n");
    prompt.append("- Do not repeat a successful tool step unless a required fact is still missing.\n");
    prompt.append("- If the latest tool result did not complete the goal, propose one concrete next command or explicitly explain why the request cannot continue.\n");
    prompt.append("- Never loop on the same command with the same arguments when no new facts were discovered.\n");
    if (goalIntent.isRecommendationFlow()) {
      prompt.append("- This is a recommendation/similar-song flow.\n");
      if (!safeTrim(playback.trackUri()).isEmpty()) {
        prompt.append("- Current playback is already known. Do not call `status all` again.\n");
        prompt.append("- Use concrete resolution or playback actions only.\n");
      } else {
        prompt.append("- If current playback facts are missing, `status all` is the correct discovery step.\n");
      }
      if (hasSuccessfulTool(toolSteps, "resolve-track") && goalIntent.wantsPlayback()) {
        prompt.append("- Resolved candidates already exist. Prefer a play-capable next step instead of more resolution.\n");
      }
    }
    prompt.append("- Keep the plan moving with exactly one next command, or return `none` if the goal is satisfied or this toolchain cannot fulfill the request.\n");
    return injectLedgerSummary(prompt.toString(), rootWorkId);
  }

  private void populatePlannerContext(AgentContext context,
                                      String initialPrompt,
                                      String source,
                                      String correlationId,
                                      boolean interactiveRequest,
                                      int step,
                                      GoalIntent goalIntent,
                                      List<ToolStep> toolSteps,
                                      CommandAgentResponse lastResponse,
                                      List<String> plannerHistory) {
    if (context == null) {
      return;
    }
    PlaybackFacts playback = extractPlaybackFacts(toolSteps);
    context.set("goal", Map.of(
        "original", safeTrim(initialPrompt),
        "intent", goalIntent.intent(),
        "interactive", interactiveRequest
    ));
    context.set("request", Map.of(
        "source", safeTrim(source),
        "correlationId", safeTrim(correlationId),
        "nextStep", step
    ));
    context.set("normalized_goal", Map.of(
        "intent", goalIntent.intent(),
        "target_scope", goalIntent.targetScope(),
        "seed_hint", goalIntent.seedHint(),
        "wants_playback", goalIntent.wantsPlayback(),
        "references_current_playback", goalIntent.referencesCurrentPlayback(),
        "needs_discovery", goalIntent.needsDiscovery(),
        "confidence", goalIntent.confidence(),
        "reason", goalIntent.reason()
    ));
    context.set("plan_state", inferPlanState(goalIntent, toolSteps));
    context.set("known_facts", buildKnownFactsMap(playback, toolSteps, lastResponse));
    context.set("recent_steps", buildRecentStepsContext(toolSteps));
    context.set("planner_history", buildPlannerHistoryContext(plannerHistory));
    if (lastResponse != null) {
      context.set("last_planner_response", Map.of(
          "request", safeTrim(lastResponse.getRequest()),
          "action", safeTrim(lastResponse.getAction()),
          "user", safeTrim(lastResponse.getUser())
      ));
    }
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
    AuthDirective authDirective = extractAuthDirective(output);
    return authDirective.valid() ? authDirective.sessionId() : firstNonBlank(
        safeTrim(extractAuthDirectiveLoose(output).sessionId()),
        ""
    );
  }

  private static AuthDirective extractAuthDirectiveLoose(String toolOutput) {
    String text = safeTrim(toolOutput);
    if (text.isEmpty()) {
      return AuthDirective.invalid();
    }
    try {
      JsonNode root = JSON.readTree(text);
      JsonNode auth = root.path("auth");
      JsonNode authNode = auth.isObject() ? auth : root;
      String provider = safeTrim(readPath(authNode, "provider"));
      String sessionId = firstNonBlank(
          readPath(authNode, "sessionId"),
          readPath(authNode, "session.sessionId"),
          readPath(root, "sessionId"),
          readPath(root, "session.sessionId")
      );
      String authorizeUrl = firstNonBlank(readPath(authNode, "authorizeUrl"), readPath(root, "authorizeUrl"));
      String completeCommand = firstNonBlank(readPath(authNode, "completeCommand"), readPath(root, "completeCommand"));
      boolean required = authNode.path("required").asBoolean(auth.isObject());
      boolean hasSecureEnvelope = authNode.path("secureEnvelope").isObject() || root.path("secureEnvelope").isObject();
      String authJson = auth.isObject() ? auth.toString() : (root.isObject() ? root.toString() : "");
      boolean valid = !sessionId.isEmpty();
      return new AuthDirective(valid, provider, sessionId, authorizeUrl, completeCommand, hasSecureEnvelope, authJson);
    } catch (Exception ignored) {
      return AuthDirective.invalid();
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
    json.append("\"response\":").append(renderResponseJson(result)).append(',');
    json.append("\"auth\":").append(renderAuthJson(result.toolSteps)).append(',');
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

  private static void completeWireTerminal(CommandContext context,
                                           String channel,
                                           String outcome,
                                           String responseReason,
                                           String body,
                                           String errorCode,
                                           boolean errorReroutable,
                                           String authOutcome) {
    completeWireTerminal(context, channel, outcome, responseReason, body, errorCode, errorReroutable, authOutcome,
        "text/plain; charset=utf-8");
  }

  private static void completeWireTerminal(CommandContext context,
                                           String channel,
                                           String outcome,
                                           String responseReason,
                                           String body,
                                           String errorCode,
                                           boolean errorReroutable,
                                           String authOutcome,
                                           String contentType) {
    if (context == null) {
      return;
    }
    AiatpIO.Headers headers = new AiatpIO.Headers();
    headers.set("Content-Type", safeTrim(contentType).isEmpty()
        ? "text/plain; charset=utf-8"
        : safeTrim(contentType));
    AiatpResponse response = AiatpResponse.builder()
        .statusCode("failure".equalsIgnoreCase(safeTrim(outcome)) ? 500 : 200)
        .reasonPhrase(safeTrim(responseReason).isEmpty() ? "completed" : safeTrim(responseReason))
        .headers(headers)
        .body(AiatpIO.Body.ofString(body == null ? "" : body, StandardCharsets.UTF_8))
        .build();
    context.complete(response);
  }

  private void emitWireFinal(CommandContext context, LoopResult result) {
    if (context == null || result == null) {
      completeWireTerminal(context, "error", "failure", "agent_failed", "Agent failed.", "agent_failed", false, null);
      return;
    }

    CommandAgentResponse finalResponse = result.finalResponse;
    String chat = finalResponse == null ? "" : safeTrim(finalResponse.getUser());
    String html = finalResponse == null ? "" : safeTrim(finalResponse.getHtml());
    if (!html.isEmpty()) {
      context.emitHtml(html, "final", "html", true);
    }

    String status = buildStatusMessage(findLastToolStep(result.toolSteps), result.stopReason);
    if (!safeTrim(status).isEmpty()) {
      context.emitChat(status, "final");
    }

    // Preserve full detail as a thought JSON trace (wire-only; no "channels" envelope).
    try {
      Map<String, Object> trace = new LinkedHashMap<>();
      trace.put("type", "dj.loop_result");
      trace.put("correlationId", safeTrim(result.correlationId));
      trace.put("stopReason", result.stopReason == null ? "" : safeTrim(result.stopReason.code));
      trace.put("stopMessage", result.stopReason == null ? "" : safeTrim(result.stopReason.message));
      trace.put("durationMs", result.totalDurationMs);
      List<Map<String, Object>> steps = new ArrayList<>();
      if (result.toolSteps != null) {
        for (ToolStep step : result.toolSteps) {
          if (step == null) continue;
          Map<String, Object> s = new LinkedHashMap<>();
          s.put("step", step.step);
          s.put("agentAction", safeTrim(step.agentAction));
          s.put("toolCommand", safeTrim(step.toolCommand));
          s.put("toolArgs", safeTrim(step.toolArgs));
          s.put("toolOutput", safeTrim(step.toolOutput));
          s.put("planningDurationMs", step.planningDurationMs);
          s.put("toolDurationMs", step.toolDurationMs);
          s.put("stepDurationMs", step.stepDurationMs);
          steps.add(s);
        }
      }
      trace.put("toolSteps", steps);
      trace.put("display", Map.of(
          "chat", chat,
          "status", status,
          "htmlPresent", !html.isEmpty()
      ));
      context.emitChat(JSON.writeValueAsString(trace), "final");
    } catch (Exception ignored) {
      // best-effort; do not fail the request
    }

    String outcome = "success";
    String responseReason = "completed";
    String errorCode = null;
    boolean reroutable = false;
    String authOutcome = null;
    String normalizedStop = result.stopReason == null ? "" : safeTrim(result.stopReason.code);
    if (StopReason.OUT_OF_SCOPE == result.stopReason || StopReason.UNSUPPORTED_ACTION == result.stopReason) {
      outcome = "failure";
      responseReason = "unsupported_operation";
      errorCode = "unsupported_operation";
      reroutable = true;
    } else if (StopReason.AUTH_REQUIRED == result.stopReason) {
      outcome = "failure";
      responseReason = "auth_required";
      errorCode = "auth_required";
      authOutcome = "AUTH_REQUIRED";
    } else if (StopReason.AUTH_HANDOFF == result.stopReason) {
      outcome = "auth_handoff";
      responseReason = "auth_pending";
      authOutcome = "AUTH_PENDING";
    } else if (isFailureStopReason(result.stopReason)) {
      outcome = "failure";
      responseReason = normalizedStop.isEmpty() ? "failed" : normalizedStop;
      errorCode = normalizedStop.isEmpty() ? "tool_execution_failed" : normalizedStop;
    }

    String terminalBody = !chat.isEmpty()
        ? chat
        : firstNonBlank(status, result.stopReason == null ? "" : safeTrim(result.stopReason.message), "Completed.");
    completeWireTerminal(
        context,
        "failure".equals(outcome) ? "error" : "chat",
        outcome,
        responseReason,
        terminalBody,
        errorCode,
        reroutable,
        authOutcome
    );
  }

  private static boolean isOutOfScopeResult(LoopResult result) {
    return result != null
        && (result.stopReason == StopReason.OUT_OF_SCOPE
        || result.stopReason == StopReason.UNSUPPORTED_ACTION);
  }

  private static boolean isAuthHandoff(LoopResult result, String authJson) {
    return result != null
        && (result.stopReason == StopReason.AUTH_HANDOFF
        || (result.stopReason == StopReason.AUTH_REQUIRED && hasValidAuthDirective(authJson)));
  }

  private static boolean hasAuthPayload(String authJson) {
    String text = safeTrim(authJson);
    return !text.isEmpty() && !"null".equalsIgnoreCase(text);
  }

  private static boolean hasValidAuthDirective(String authJson) {
    return extractAuthDirective(authJson).valid();
  }

  private static boolean isAuthHandoffToolExecution(ToolExecution tool) {
    return tool != null && tool.responseOutcome == ToolResponseOutcome.AWAIT_EXTERNAL_COMPLETION;
  }

  private static boolean hasObservedAuthHandoff(SpotifyEventSnapshot snapshot) {
    if (snapshot == null) {
      return false;
    }
    if (!safeTrim(snapshot.lastHtml()).isEmpty() || !safeTrim(snapshot.lastAuthJson()).isEmpty()) {
      return true;
    }
    String status = safeTrim(snapshot.lastStatus()).toLowerCase(Locale.ROOT);
    String chat = safeTrim(snapshot.lastChat()).toLowerCase(Locale.ROOT);
    return status.contains("authorization") || chat.contains("authorization");
  }

  private static boolean shouldAutoRetryAfterAuth(String authBeginArgs) {
    String args = safeTrim(authBeginArgs).toLowerCase(Locale.ROOT);
    return !args.contains("redirect-profile=app");
  }

  private static AiatpRequest inheritParentRouting(CommandContext parentContext, AiatpRequest childRequest) {
    if (parentContext == null || childRequest == null) {
      return childRequest;
    }
    AiatpRequest parentRequest = parentContext.getAiatpRequest();
    if (parentRequest == null) {
      return childRequest;
    }
    AiatpIO.Headers mergedHeaders = new AiatpIO.Headers();
    if (parentRequest.getHeaders() != null) {
      for (Map.Entry<String, String> header : parentRequest.getHeaders()) {
        mergedHeaders.set(header.getKey(), header.getValue());
      }
    }
    if (childRequest.getHeaders() != null) {
      for (Map.Entry<String, String> header : childRequest.getHeaders()) {
        mergedHeaders.set(header.getKey(), header.getValue());
      }
    }
    AiatpRequest effective = childRequest.toBuilder().headers(mergedHeaders).build();
    String host = requestHeader(parentContext, "Host");
    if (!host.isEmpty()) {
      effective = AiatpRuntimeAdapter.withHeader(effective, "Host", host);
    }
    return effective;
  }

  private static boolean readBoolean(JsonNode root, boolean fallback, String... paths) {
    if (root == null || paths == null) {
      return fallback;
    }
    for (String path : paths) {
      String value = safeTrim(readPath(root, path));
      if ("true".equalsIgnoreCase(value)) {
        return true;
      }
      if ("false".equalsIgnoreCase(value)) {
        return false;
      }
    }
    return fallback;
  }

  private static double readDouble(JsonNode root, double fallback, String... paths) {
    if (root == null || paths == null) {
      return fallback;
    }
    for (String path : paths) {
      String value = safeTrim(readPath(root, path));
      if (value.isEmpty()) {
        continue;
      }
      try {
        return Double.parseDouble(value);
      } catch (NumberFormatException ignored) {
        // ignore invalid values and keep fallback
      }
    }
    return fallback;
  }

  private static String firstNonBlank(String... values) {
    if (values == null) {
      return "";
    }
    for (String value : values) {
      String text = safeTrim(value);
      if (!text.isEmpty()) {
        return text;
      }
    }
    return "";
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
        + "\"errorCode\":\"unsupported_operation\""
        + "}";
  }

  private static String renderResponseJson(LoopResult result) {
    String outcome = "goal_completed";
    if (result != null) {
      if (isAuthHandoff(result, renderAuthJson(result.toolSteps))) {
        outcome = "await_external_completion";
      } else if (isOutOfScopeResult(result)) {
        outcome = "unsupported_operation";
      } else if (isFailureStopReason(result.stopReason)) {
        outcome = "failure";
      } else if (hasIntermediateToolResult(result.toolSteps)) {
        outcome = "intermediate_result";
      }
    }
    return "{"
        + "\"outcome\":" + quoteJson(outcome) + ","
        + "\"completed\":true"
        + "}";
  }

  private static boolean hasIntermediateToolResult(List<ToolStep> toolSteps) {
    ToolStep last = findLastToolStep(toolSteps);
    if (last == null) {
      return false;
    }
    try {
      JsonNode root = JSON.readTree(safeTrim(last.toolOutput));
      String outcome = firstNonBlank(readPath(root, "response.outcome"), readPath(root, "meta.outcome"));
      return "intermediate_result".equalsIgnoreCase(safeTrim(outcome));
    } catch (Exception ignored) {
      return false;
    }
  }

  private static boolean shouldEmitFailureMeta(LoopResult result) {
    if (result == null) {
      return false;
    }
    return isOutOfScopeResult(result);
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

  private static String extractToolMessage(String toolOutput) {
    // Wire-only: tools typically return plain text.
    if (toolOutput == null || toolOutput.isBlank()) {
      return "";
    }
    return safeTrim(toolOutput);
  }

  private static String normalizeActionSignature(String action) {
    String raw = safeTrim(action).toLowerCase(Locale.ROOT);
    if (raw.isEmpty() || "none".equals(raw)) {
      return "";
    }
    LineParserUtil.ParsedLine parsed = LineParserUtil.parse(raw);
    if (parsed == null || safeTrim(parsed.first).isEmpty()) {
      return raw.replaceAll("\\s+", " ");
    }
    String command = safeTrim(parsed.first).toLowerCase(Locale.ROOT);
    String args = joinArgs(parsed.second, parsed.remaining).trim().replaceAll("\\s+", " ");
    return args.isEmpty() ? command : command + " " + args;
  }

  private static LoopTermination detectLoopTermination(String currentActionSignature,
                                                       List<String> plannerHistory,
                                                       List<ToolStep> toolSteps) {
    String signature = safeTrim(currentActionSignature);
    if (signature.isEmpty()) {
      return null;
    }
    int repeatedPlans = 0;
    for (String prior : plannerHistory) {
      if (signature.equals(safeTrim(prior))) {
        repeatedPlans++;
      }
    }
    if (repeatedPlans >= 3) {
      return new LoopTermination(
          StopReason.PLANNER_LOOP_DETECTED,
          "Planner repeated the same action without enough change: " + signature
      );
    }
    int repeatedMatchingSteps = 0;
    String lastDigest = "";
    for (ToolStep step : toolSteps) {
      String priorSignature = normalizeActionSignature(step.agentAction);
      if (!signature.equals(priorSignature)) {
        continue;
      }
      repeatedMatchingSteps++;
      String digest = toolOutputDigest(step.toolOutput);
      if (!lastDigest.isEmpty() && lastDigest.equals(digest)) {
        return new LoopTermination(
            StopReason.NO_FORWARD_PROGRESS,
            "Repeated action produced the same result without new facts: " + signature
        );
      }
      lastDigest = digest;
    }
    if (repeatedMatchingSteps >= 2) {
      return new LoopTermination(
          StopReason.PLANNER_LOOP_DETECTED,
          "Planner returned the same action again after previous execution: " + signature
      );
    }
    return null;
  }

  private static String toolOutputDigest(String toolOutput) {
    String text = safeTrim(extractToolText(toolOutput)).toLowerCase(Locale.ROOT);
    if (text.isEmpty()) {
      return "";
    }
    return text.replaceAll("\\s+", " ");
  }

  private static List<Map<String, Object>> buildPlannerHistoryContext(List<String> plannerHistory) {
    List<Map<String, Object>> items = new ArrayList<>();
    if (plannerHistory == null || plannerHistory.isEmpty()) {
      return items;
    }
    int start = Math.max(0, plannerHistory.size() - PLANNER_HISTORY_LIMIT);
    for (int i = start; i < plannerHistory.size(); i++) {
      items.add(Map.of(
          "step", i + 1,
          "action", safeTrim(plannerHistory.get(i))
      ));
    }
    return items;
  }

  private static void appendPlannerHistory(StringBuilder prompt, List<String> plannerHistory) {
    if (plannerHistory == null || plannerHistory.isEmpty()) {
      prompt.append("- none\n");
      return;
    }
    int start = Math.max(0, plannerHistory.size() - PLANNER_HISTORY_LIMIT);
    for (int i = start; i < plannerHistory.size(); i++) {
      prompt.append("- plan ").append(i + 1).append(": ").append(safeTrim(plannerHistory.get(i))).append('\n');
    }
  }

  private static String readText(JsonNode node, String field) {
    if (node == null || node.isMissingNode()) {
      return "";
    }
    String value = safeTrim(node.path(field).asText(""));
    return "null".equalsIgnoreCase(value) ? "" : value;
  }

  private static String extractAuthJson(String toolOutput) {
    if (toolOutput == null || toolOutput.isBlank()) {
      return "";
    }
    try {
      JsonNode root = JSON.readTree(toolOutput);
      JsonNode auth = root.path("auth");
      if (!auth.isObject()) {
        return "";
      }
      return auth.toString();
    } catch (Exception ignored) {
      return "";
    }
  }

  private void emitToolProgress(CommandContext context, ToolExecution tool, String correlationId, int step) {
    if (context == null || tool == null) {
      return;
    }
    String contextId = "";
    String headerRequestId = "";
    try {
      contextId = safeTrim(context.getId());
    } catch (Exception ignored) {
      contextId = "";
    }
    try {
      headerRequestId = requestHeader(context, "X-Request-Id");
    } catch (Exception ignored) {
      headerRequestId = "";
    }
    String raw = safeTrim(tool.rawOutput());
    String output = safeTrim(tool.output);
    System.out.println("[DJ-AGENT][EMIT] tool_progress begin step=" + step
        + " command=" + safeTrim(tool.command)
        + " correlationId=" + safeTrim(correlationId)
        + " rawEmpty=" + raw.isEmpty()
        + " contextId=" + (contextId.isEmpty() ? "<none>" : contextId)
        + " X-Request-Id=" + (headerRequestId.isEmpty() ? "<none>" : headerRequestId));
    // Wire-only: tool progress is conveyed via tool events (status/chat/html/auth) forwarded by forwardSpotifyEvent.
    // We keep a minimal status hint here for interactive console users.
    if (!output.isEmpty()) {
      context.emitChat(output, "progress");
    }
    if (!raw.isEmpty() && raw.trim().startsWith("{")) {
      // If auth-begin timed out but we observed a directive, keep it available.
      context.emitChat(raw, "progress");
    }
    System.out.println("[DJ-AGENT][EMIT] tool_progress done hasStatus=" + !output.isEmpty()
        + " hasAuth=" + (!raw.isEmpty() && raw.trim().startsWith("{")));
  }

  private void emitToolProgressAsync(CommandContext context, ToolExecution tool, String correlationId, int step) {
    try {
      TOOL_PROGRESS_EXECUTOR.submit(() -> {
        try {
          emitToolProgress(context, tool, correlationId, step);
        } catch (Exception e) {
          System.out.println("[DJ-AGENT][EMIT] tool_progress failed: " + safeTrim(e.getMessage()));
        }
      });
    } catch (Exception e) {
      System.out.println("[DJ-AGENT][EMIT] tool_progress submit failed: " + safeTrim(e.getMessage()));
    }
  }

  private record ToolChannels(String statusMessage, String chatMessage, String html, String htmlMode, Boolean htmlReplace) {
    private static final ToolChannels EMPTY = new ToolChannels("", "", "", "", null);
  }

  /**
   * Wire-only: tools no longer return a JSON envelope with "channels".
   * This helper is retained for legacy code paths but intentionally does not parse or depend on JSON.
   */
  private static ToolChannels extractToolChannels(String toolOutput) {
    return ToolChannels.EMPTY;
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
      case TOOL_EXECUTION_FAILED, TOOL_SUCCEEDED_BUT_GOAL_UNRESOLVED -> AgentFailureResponseFactory.FailureKind.TOOL_EXECUTION_FAILED;
      case UNSUPPORTED_ACTION -> AgentFailureResponseFactory.FailureKind.UNSUPPORTED_ACTION;
      case OUT_OF_SCOPE -> AgentFailureResponseFactory.FailureKind.UNSUPPORTED_ACTION;
      case INVALID_ARGUMENTS -> AgentFailureResponseFactory.FailureKind.INVALID_ARGUMENTS;
      case MAX_STEPS_REACHED -> AgentFailureResponseFactory.FailureKind.MAX_STEPS_REACHED;
      case NO_FORWARD_PROGRESS, PLANNER_LOOP_DETECTED -> AgentFailureResponseFactory.FailureKind.INTERNAL_ERROR;
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

  private static String requestHeader(CommandContext context, String name) {
    AiatpRequest request = context == null ? null : context.getAiatpRequest();
    if (request == null || request.getHeaders() == null || name == null || name.isBlank()) {
      return "";
    }
    return safeTrim(request.getHeaders().getFirst(name));
  }

  private static String responseBody(AiatpResponse result) {
    if (result == null || result.getBody() == null) {
      return "";
    }
    String body = AiatpIO.bodyToString(result.getBody(), StandardCharsets.UTF_8);
    return body == null ? "" : body;
  }

  private static int responseStatus(AiatpResponse result) {
    if (result == null) {
      return 500;
    }
    return result.getStatusCode();
  }

  private String injectLedgerSummary(String prompt, String rootWorkId) {
    try {
      StringBuilder summary = new StringBuilder();
      List<WorkActionRecord> actions = ownerLedger().getActions(rootWorkId, new com.social100.todero.common.agent.work.QueryOptions(10, 0));
      if (actions == null || actions.isEmpty()) {
        summary.append("- no prior ledger actions\n");
      } else {
        int start = Math.max(0, actions.size() - 10);
        for (int i = start; i < actions.size(); i++) {
          WorkActionRecord action = actions.get(i);
          summary.append("- ")
              .append(action.actionType())
              .append(": ")
              .append(safeTrim(action.text()))
              .append('\n');
        }
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
    try {
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
    } catch (Exception e) {
      System.out.println("[DJ-AGENT] ledger finalize skipped workId=" + rootWorkId + " error=" + safeTrim(e.getMessage()));
    }
  }

  private static boolean isFailureStopReason(StopReason stopReason) {
    return stopReason == StopReason.PLANNER_EXCEPTION
        || stopReason == StopReason.TOOL_EXECUTION_FAILED
        || stopReason == StopReason.TOOL_SUCCEEDED_BUT_GOAL_UNRESOLVED
        || stopReason == StopReason.UNSUPPORTED_ACTION
        || stopReason == StopReason.INVALID_ARGUMENTS
        || stopReason == StopReason.MAX_STEPS_REACHED
        || stopReason == StopReason.NO_FORWARD_PROGRESS
        || stopReason == StopReason.PLANNER_LOOP_DETECTED;
  }

  private static String lastToolOutput(List<ToolStep> toolSteps) {
    ToolStep step = findLastToolStep(toolSteps);
    return step == null ? "" : safeTrim(step.toolOutput);
  }

  private void recordToolStepSafely(String rootWorkId, ToolExecution tool, long toolDurationMs) {
    try {
      ownerLedger().recordToolStep(rootWorkId, tool.command, tool.args, redactedForLogs(safeTrim(tool.rawOutput())),
          toolDurationMs, safeTrim(tool.errorCode), safeTrim(tool.executed ? null : tool.output));
    } catch (Exception e) {
      System.out.println("[DJ-AGENT] ledger tool-step skipped workId=" + rootWorkId + " error=" + safeTrim(e.getMessage()));
    }
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

  private static Path defaultLedgerPath() {
    String custom = System.getProperty("todero.agent.dj.ledger.dir");
    if (custom != null && !custom.isBlank()) {
      return Path.of(custom.trim());
    }
    return Path.of(System.getProperty("user.home"), ".todero", "data", "state", "agent-work-ledger", "dj-agent");
  }

  private static long elapsedMs(long startedAtNs) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNs);
  }

  private static void logThrowable(String prefix, Throwable throwable) {
    if (throwable == null) {
      return;
    }
    System.out.println(prefix + " type=" + throwable.getClass().getName());
    Throwable cursor = throwable;
    int depth = 0;
    while (cursor != null && depth < 8) {
      System.out.println(prefix + " cause[" + depth + "]=" + cursor.getClass().getName()
          + " message=" + safeTrim(cursor.getMessage()));
      cursor = cursor.getCause();
      depth++;
    }
    throwable.printStackTrace(System.out);
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

  private record ToolExecution(boolean executed,
                               String command,
                               String args,
                               String output,
                               String errorCode,
                               String rawOutput,
                               ToolResponseOutcome responseOutcome) {
    static ToolExecution error(String errorCode,
                               String command,
                               String args,
                               String output,
                               String rawOutput,
                               ToolResponseOutcome responseOutcome) {
      return new ToolExecution(false, command, args, output, errorCode, rawOutput, responseOutcome);
    }
  }

  private record SpotifyExecutionResult(String source,
                                        String channel,
                                        String phase,
                                        String body,
                                        String errorCode,
                                        String authOutcome,
                                        String outcome,
                                        String responseReason,
                                        int status,
                                        SpotifyEventSnapshot snapshot) {
  }

  private static final class SpotifyEventAggregate {
    private String lastStatus = "";
    private String lastChat = "";
    private String lastHtml = "";
    private String lastAuthJson = "";
    private String lastError = "";
    private boolean eventSeen = false;

    synchronized void record(String channel, String phase, String body, String errorCode) {
      eventSeen = true;
      if ("status".equals(channel) && !safeTrim(body).isEmpty()) {
        lastStatus = body;
      } else if ("chat".equals(channel) && !safeTrim(body).isEmpty()) {
        AuthDirective authDirective = extractAuthDirectiveLoose(body);
        if (authDirective.valid()) {
          lastAuthJson = body;
        } else {
          lastChat = body;
        }
      } else if ("html".equals(channel) && !safeTrim(body).isEmpty()) {
        lastHtml = body;
      } else if ("auth".equals(channel) && !safeTrim(body).isEmpty()) {
        lastAuthJson = body;
      } else if ("error".equals(channel) && !safeTrim(body).isEmpty()) {
        lastError = body;
      }
    }

    synchronized boolean hasEvent() {
      return eventSeen;
    }


    synchronized SpotifyEventSnapshot snapshot() {
      return new SpotifyEventSnapshot(
          lastStatus,
          lastChat,
          lastHtml,
          lastAuthJson,
          lastError
      );
    }
  }

  private record SpotifyEventSnapshot(String lastStatus,
                                      String lastChat,
                                      String lastHtml,
                                      String lastAuthJson,
                                      String lastError) {
  }

  private record SpotifyEnvelope(boolean recognized,
                                 boolean ok,
                                 String errorCode,
                                 String message,
                                 ToolResponseOutcome responseOutcome) {
  }

  private enum ToolResponseOutcome {
    GOAL_COMPLETED("goal_completed"),
    INTERMEDIATE_RESULT("intermediate_result"),
    AWAIT_EXTERNAL_COMPLETION("await_external_completion"),
    FAILURE("failure"),
    UNSUPPORTED_OPERATION("unsupported_operation"),
    UNSPECIFIED("unspecified");

    private final String jsonValue;

    ToolResponseOutcome(String jsonValue) {
      this.jsonValue = jsonValue;
    }
  }

  private enum ToolResponseDisposition {
    GOAL_COMPLETED,
    AWAIT_EXTERNAL_COMPLETION,
    CONTINUE,
    FAIL
  }

  private record PlaybackFacts(String device,
                               String trackTitle,
                               String trackUri,
                               String position,
                               boolean playing) {
    private static PlaybackFacts empty() {
      return new PlaybackFacts("", "", "", "", false);
    }
  }

  private record RecommendationCandidate(String title,
                                         String artist,
                                         String query,
                                         String reason) {
  }

  private record RecommendationCandidates(List<RecommendationCandidate> candidates) {
  }

  private record VerifiedTrack(String title,
                               String artist,
                               String uri,
                               String reason) {
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

  private record LoopTermination(StopReason stopReason, String details) {
  }

  private enum StopReason {
    ACTION_NONE("action_none", "planner concluded no further action is needed"),
    PLANNER_EXCEPTION("planner_exception", "planner failed with an internal exception"),
    TOOL_EXECUTION_FAILED("tool_execution_failed", "spotify command execution failed"),
    TOOL_SUCCEEDED_BUT_GOAL_UNRESOLVED("tool_succeeded_but_goal_unresolved", "a tool succeeded but the planner did not complete the goal"),
    OUT_OF_SCOPE("out_of_scope", "request cannot be fulfilled by the DJ Spotify toolchain"),
    UNSUPPORTED_ACTION("unsupported_action", "planner proposed a command outside the allowed spotify command set"),
    INVALID_ARGUMENTS("invalid_arguments", "planner proposed invalid arguments for a spotify command"),
    AUTH_REQUIRED("auth_required", "spotify authorization is required before continuing"),
    AUTH_HANDOFF("auth_handoff", "spotify authorization handoff was started"),
    BACKGROUND_STEP_LIMIT("background_step_limit", "background reaction reached step cap"),
    NO_FORWARD_PROGRESS("no_forward_progress", "planner could not make forward progress after prior tool steps"),
    PLANNER_LOOP_DETECTED("planner_loop_detected", "planner repeated prior actions without meaningful change"),
    MAX_STEPS_REACHED("max_steps_reached", "maximum loop step limit reached");

    private final String code;
    private final String message;

    StopReason(String code, String message) {
      this.code = code;
      this.message = message;
    }
  }

  private record PlaylistAddIntent(String playlistName) {
  }

  private record PendingAuthRetry(String command, String args, String initialPrompt, String rootWorkId) {
  }

  private record AuthCompletionIntent(String sessionId, String rawArgs) {
  }

  private record AuthDirective(boolean valid,
                               String provider,
                               String sessionId,
                               String authorizeUrl,
                               String completeCommand,
                               boolean hasSecureEnvelope,
                               String authJson) {
    private static AuthDirective invalid() {
      return new AuthDirective(false, "", "", "", "", false, "");
    }
  }

  private record GoalIntent(String intent,
                            String targetScope,
                            String seedHint,
                            boolean wantsPlayback,
                            boolean referencesCurrentPlayback,
                            boolean needsDiscovery,
                            int requestedCount,
                            boolean supportedByToolchain,
                            String unsupportedReason,
                            double confidence,
                            String reason) {
    GoalIntent normalized() {
      String normalizedIntent = safeTrim(intent).isEmpty() ? "general_spotify_control" : safeTrim(intent);
      String normalizedTargetScope = safeTrim(targetScope).isEmpty() ? "explicit_request" : safeTrim(targetScope);
      String normalizedSeedHint = safeTrim(seedHint);
      String normalizedUnsupportedReason = safeTrim(unsupportedReason);
      String normalizedReason = safeTrim(reason);
      boolean currentPlayback = referencesCurrentPlayback
          || "current_playback".equalsIgnoreCase(normalizedTargetScope)
          || "current-playback".equalsIgnoreCase(normalizedSeedHint);
      return new GoalIntent(
          normalizedIntent,
          normalizedTargetScope,
          currentPlayback && normalizedSeedHint.isEmpty() ? "current-playback" : normalizedSeedHint,
          wantsPlayback,
          currentPlayback,
          needsDiscovery || currentPlayback,
          clampRecommendationCount(requestedCount),
          supportedByToolchain,
          normalizedUnsupportedReason,
          confidence,
          normalizedReason
      );
    }

    boolean isRecommendationFlow() {
      return safeTrim(intent).toLowerCase(Locale.ROOT).contains("recommend");
    }
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
