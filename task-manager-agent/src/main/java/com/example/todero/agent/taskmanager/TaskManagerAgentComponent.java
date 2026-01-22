package com.example.todero.agent.taskmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.ai.agent.AgentDefinition;
import com.social100.todero.common.ai.llm.LLMClient;
import com.social100.todero.common.ai.llm.OpenAiLLM;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.routing.AgentCapabilityManifest;
import com.social100.todero.common.storage.Storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
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

import static com.social100.todero.common.config.Util.parseDotenv;

@AIAController(name = "com.shellaia.verbatim.agent.task.manager",
    type = ServerType.AI,
    visible = false,
    description = "Task manager AI agent",
    capabilityProvider = TaskManagerAgentCapabilities.class)
public class TaskManagerAgentComponent {
  private static final String MAIN_GROUP = "Main";
  private static final String TASK_MANAGER_COMPONENT = "com.shellaia.verbatim.component.task.manager";
  private static final String PHASE = "10";
  private static final int MEMORY_CAPACITY = 80;
  private static final int LLM_MEMORY_CONTEXT_ITEMS = 12;
  private static final int MAX_STEPS = 4;
  private static final long REQUEST_TIMEOUT_SECONDS = 45;
  private static final int TRACE_MAX_CHARS = 1400;
  private static final boolean VERBOSE_TRACE = Boolean.parseBoolean(
      System.getProperty("todero.taskmanager.agent.verbose-trace",
          System.getenv().getOrDefault("TODERO_TASKMANAGER_AGENT_VERBOSE_TRACE", "false"))
  );
  private static final Pattern ASSIGNED_PATTERN = Pattern.compile(
      "(?i)\\b(?:for|assigned\\s+to(?:\\s+agent)?)\\s+([A-Za-z0-9_.:-]+)"
  );
  private static final Pattern TITLE_TITLED_PATTERN = Pattern.compile(
      "(?i)\\btitle(?:d)?\\s+(.+?)(?=\\s+(?:assigned\\s+to(?:\\s+agent)?|for|due)\\b|$)"
  );
  private static final Pattern TITLE_AFTER_TO_PATTERN = Pattern.compile("(?i)\\bto\\s+(.+)$");
  private static final String PLANNER_OUTPUT_SCHEMA_CONTRACT =
      "{\"request\":\"string\",\"action\":\"none|execute ...\",\"user\":\"string\",\"html\":\"string\"}";
  private static final Set<String> ALLOWED_COMMANDS = Set.of(
      "create", "get", "list", "update",
      "claim", "renew-claim", "start", "complete", "fail", "cancel", "snooze",
      "attempt", "attempts",
      "evaluate", "ack-event",
      "health", "metrics",
      "subscribe", "unsubscribe"
  );
  private static final Map<String, Set<String>> REQUIRED_OPTIONS = Map.ofEntries(
      Map.entry("create", Set.of("--title", "--assigned")),
      Map.entry("get", Set.of("--task-id")),
      Map.entry("attempt", Set.of("--task-id", "--attempt-number")),
      Map.entry("attempts", Set.of("--task-id")),
      Map.entry("update", Set.of("--task-id")),
      Map.entry("claim", Set.of("--task-id", "--agent", "--lease-seconds")),
      Map.entry("renew-claim", Set.of("--task-id", "--agent", "--lease-seconds")),
      Map.entry("start", Set.of("--task-id", "--agent")),
      Map.entry("complete", Set.of("--task-id", "--agent")),
      Map.entry("fail", Set.of("--task-id", "--agent")),
      Map.entry("cancel", Set.of("--task-id", "--actor")),
      Map.entry("snooze", Set.of("--task-id", "--schedule-at")),
      Map.entry("ack-event", Set.of("--agent", "--event-id")),
      Map.entry("subscribe", Set.of("--agent")),
      Map.entry("unsubscribe", Set.of("--agent"))
  );

  private final AgentDefinition agentDefinition;
  private final String openApiKey;
  private final ExecutorService cognitionExecutor;
  private final AgentMemoryBuffer memoryBuffer;
  private final ObjectMapper mapper;
  private final Set<String> processedEventKeys;

  public TaskManagerAgentComponent(Storage storage) {
    this.agentDefinition = AgentDefinition.builder()
        .name("Task Manager Agent")
        .role("Assistant")
        .description("Plans and orchestrates task-manager component actions")
        .model("gpt-4.1-mini")
        .systemPrompt(loadSystemPrompt("prompts/default-system-prompt.md"))
        .build();
    this.agentDefinition.setMetadata("region", "US");

    String key;
    try {
      byte[] envBytes = storage.readFile(".env");
      Map<String, String> env = parseDotenv(envBytes);
      key = env.getOrDefault("OPENAI_API_KEY", "");
    } catch (IOException e) {
      key = "";
    }
    this.openApiKey = key;
    this.cognitionExecutor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "task-manager-agent-cognition");
      t.setDaemon(true);
      return t;
    });
    this.memoryBuffer = new AgentMemoryBuffer(MEMORY_CAPACITY);
    this.mapper = new ObjectMapper();
    this.processedEventKeys = ConcurrentHashMap.newKeySet();
  }

  private static String loadSystemPrompt(String resourcePath) {
    try (InputStream in = TaskManagerAgentComponent.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new RuntimeException("Missing system prompt resource: " + resourcePath);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read system prompt: " + resourcePath, e);
    }
  }

  private static String newCorrelationId() {
    return UUID.randomUUID().toString();
  }

  private enum StopReason {
    ACTION_NONE,
    TOOL_EXECUTION_FAILED,
    INVALID_ARGUMENTS,
    UNSUPPORTED_ACTION,
    MAX_STEPS_REACHED,
    PLANNER_EXCEPTION,
    TIMEOUT
  }

  private static final class AgentMemoryBuffer {
    private final int capacity;
    private final Deque<String> entries;

    private AgentMemoryBuffer(int capacity) {
      this.capacity = capacity;
      this.entries = new ArrayDeque<>(capacity);
    }

    synchronized void add(String category, String source, String value) {
      String line = Instant.now() + " [" + safe(category) + "] [" + safe(source) + "] " + safe(value);
      if (entries.size() >= capacity) {
        entries.removeFirst();
      }
      entries.addLast(line);
    }

    synchronized String summarize(int maxItems) {
      if (entries.isEmpty()) {
        return "";
      }
      StringBuilder sb = new StringBuilder();
      int start = Math.max(0, entries.size() - Math.max(1, maxItems));
      int i = 0;
      for (String item : entries) {
        if (i++ < start) {
          continue;
        }
        if (!sb.isEmpty()) {
          sb.append('\n');
        }
        sb.append(item);
      }
      return sb.toString();
    }
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  @Action(group = MAIN_GROUP,
      command = "process",
      description = "Process a user goal synchronously")
  public Boolean process(CommandContext context) {
    String correlationId = newCorrelationId();
    String prompt = AiatpIO.bodyToString(context.getHttpRequest().body(), StandardCharsets.UTF_8).trim();
    if (prompt.isEmpty()) {
      context.response(renderEnvelope(
          "process",
          correlationId,
          "error",
          StopReason.INVALID_ARGUMENTS,
          "",
          "none",
          "Prompt is required. Usage: process <goal>",
          "",
          "invalid_request"
      ));
      return true;
    }

    if (prompt.startsWith("react ")) {
      String payload = prompt.substring("react ".length()).trim();
      context.response(handleReactPayload(correlationId, payload, "process-react"));
      return true;
    }

    memoryBuffer.add("goal", "process", prompt);
    try {
      CompletableFuture<LoopResult> future = CompletableFuture.supplyAsync(
          () -> runGoalLoop(context, prompt, correlationId),
          cognitionExecutor
      );
      LoopResult result = future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      context.response(renderEnvelope(
          "process",
          correlationId,
          result.status,
          result.stopReason,
          prompt,
          result.action,
          result.userMessage,
          "",
          result.errorCode
      ));
    } catch (TimeoutException e) {
      context.response(renderEnvelope(
          "process",
          correlationId,
          "error",
          StopReason.TIMEOUT,
          prompt,
          "none",
          "Processing timed out before completing the goal loop.",
          "",
          "timeout"
      ));
    } catch (Exception e) {
      context.response(renderEnvelope(
          "process",
          correlationId,
          "error",
          StopReason.PLANNER_EXCEPTION,
          prompt,
          "none",
          "Processing failed: " + safe(e.getMessage()),
          "",
          "planner_exception"
      ));
    }
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "capabilities",
      description = "Return runtime capability manifest for discovery")
  public Boolean capabilities(CommandContext context) {
    AgentCapabilityManifest manifest = new TaskManagerAgentCapabilities().manifest();
    context.response(renderCapabilitiesEnvelope(manifest));
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "react",
      description = "Accept event payload and queue asynchronous reaction")
  public Boolean react(CommandContext context) {
    String correlationId = newCorrelationId();
    String payload = AiatpIO.bodyToString(context.getHttpRequest().body(), StandardCharsets.UTF_8).trim();
    context.response(handleReactPayload(correlationId, payload, "react"));
    return true;
  }

  private String handleReactPayload(String correlationId, String payload, String source) {
    if (payload.isEmpty()) {
      return renderEnvelope(
          source,
          correlationId,
          "error",
          StopReason.INVALID_ARGUMENTS,
          "",
          "none",
          "Event payload is required. Usage: react <event-json>",
          "",
          "invalid_request"
      );
    }

    ParsedReactEvent parsedEvent = parseReactEvent(payload);
    if (!parsedEvent.valid) {
      return renderEnvelope(
          source,
          correlationId,
          "error",
          StopReason.INVALID_ARGUMENTS,
          payload,
          "none",
          parsedEvent.errorMessage,
          "",
          "invalid_event_payload"
      );
    }

    String dedupeKey = parsedEvent.eventId + "#" + parsedEvent.seq;
    if (!processedEventKeys.add(dedupeKey)) {
      return renderEnvelope(
          source,
          correlationId,
          "accepted",
          StopReason.ACTION_NONE,
          payload,
          "none",
          "Duplicate event ignored (already seen).",
          "",
          null
      );
    }

    memoryBuffer.add("event-in", source, parsedEvent.summary());
    CompletableFuture.runAsync(() -> {
      String reaction = runConservativeReactionPolicy(parsedEvent);
      memoryBuffer.add("event-processing", source, parsedEvent.summary());
      memoryBuffer.add("event-result", source, reaction);
    }, cognitionExecutor);

    return renderEnvelope(
        source,
        correlationId,
        "accepted",
        StopReason.ACTION_NONE,
        payload,
        "none",
        "Event accepted for asynchronous processing (observe/report policy).",
        "",
        null
    );
  }

  private ParsedReactEvent parseReactEvent(String payload) {
    try {
      JsonNode root = mapper.readTree(payload);
      String eventId = firstNonBlank(readPath(root, "event_id"), readPath(root, "eventId"));
      String eventType = firstNonBlank(readPath(root, "event_type"), readPath(root, "eventType"), readPath(root, "type"));
      String taskId = firstNonBlank(readPath(root, "task_id"), readPath(root, "taskId"));
      long seq = parseLongStrict(firstNonBlank(readPath(root, "seq"), readPath(root, "sequence")));
      String targetAgentId = firstNonBlank(readPath(root, "target_agent_id"), readPath(root, "targetAgentId"), readPath(root, "agent_id"));

      if (eventId.isBlank()) {
        return ParsedReactEvent.invalid("Missing required field: event_id");
      }
      if (seq < 0) {
        return ParsedReactEvent.invalid("Missing or invalid required field: seq");
      }
      if (eventType.isBlank()) {
        return ParsedReactEvent.invalid("Missing required field: event_type");
      }
      if (taskId.isBlank()) {
        return ParsedReactEvent.invalid("Missing required field: task_id");
      }
      return ParsedReactEvent.valid(eventId, seq, eventType, taskId, targetAgentId);
    } catch (Exception e) {
      return ParsedReactEvent.invalid("Event payload must be valid JSON.");
    }
  }

  private String runConservativeReactionPolicy(ParsedReactEvent event) {
    String normalizedType = safe(event.eventType).toLowerCase();
    if (normalizedType.contains("due")) {
      return "observed_due_event task_id=" + event.taskId + " target_agent_id=" + event.targetAgentId
          + " action=report_only";
    }
    if (normalizedType.contains("expired")) {
      return "observed_expiry_event task_id=" + event.taskId + " action=report_only";
    }
    return "observed_event event_type=" + event.eventType + " task_id=" + event.taskId + " action=report_only";
  }

  @Action(group = MAIN_GROUP,
      command = "health",
      description = "Task-manager-agent health and core status")
  public Boolean health(CommandContext context) {
    String correlationId = newCorrelationId();
    long timeoutMillis = TimeUnit.SECONDS.toMillis(REQUEST_TIMEOUT_SECONDS);
    String summary = memoryBuffer.summarize(4);
    String details = "model=" + safe(agentDefinition.getModel())
        + ", timeoutMs=" + timeoutMillis
        + ", hasApiKey=" + (!safe(openApiKey).isBlank())
        + ", executor=single-thread"
        + ", lastMemory=" + summary;
    context.response(renderEnvelope(
        "health",
        correlationId,
        "ok",
        StopReason.ACTION_NONE,
        "health",
        "none",
        details,
        "",
        null
    ));
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "memory",
      description = "Inspect recent in-memory traces")
  public Boolean memory(CommandContext context) {
    String correlationId = newCorrelationId();
    context.response(renderEnvelope(
        "memory",
        correlationId,
        "ok",
        StopReason.ACTION_NONE,
        "memory",
        "none",
        memoryBuffer.summarize(20),
        "",
        null
    ));
    return true;
  }

  private String renderEnvelope(String source,
                                String correlationId,
                                String status,
                                StopReason stopReason,
                                String request,
                                String action,
                                String user,
                                String html,
                                String errorCode) {
    String errorValue = errorCode == null ? "null" : quote(errorCode);
    String chatMessage = quote(user);
    String statusMessage = quote(user);
    String webHtml = safe(html).isBlank() ? "null" : quote(html);
    return "{"
        + "\"request\":" + quote(request) + ","
        + "\"action\":" + quote(action) + ","
        + "\"user\":" + quote(user) + ","
        + "\"html\":" + quote(html) + ","
        + "\"channels\":{"
        + "\"chat\":{\"message\":" + chatMessage + "},"
        + "\"status\":{\"message\":" + statusMessage + "},"
        + "\"webview\":{\"html\":" + webHtml + ",\"mode\":\"none\",\"replace\":false}"
        + "},"
        + "\"meta\":{"
        + "\"source\":" + quote(source) + ","
        + "\"status\":" + quote(status) + ","
        + "\"correlationId\":" + quote(correlationId) + ","
        + "\"stopReason\":" + quote(stopReason.name()) + ","
        + "\"phase\":" + quote(PHASE) + ","
        + "\"errorCode\":" + errorValue + ","
        + "\"timestamp\":" + quote(Instant.now().toString())
        + "}"
        + "}";
  }

  private ToolCallResult executeTaskManager(CommandContext context, String command, String args) {
    String normalizedArgs = ensureJsonFormat(args);
    CompletableFuture<AiatpIO.HttpResponse> outFuture = new CompletableFuture<>();
    CommandContext internalContext = CommandContext.builder()
        .httpRequest(AiatpIO.HttpRequest.newBuilder("ACTION", "/" + TASK_MANAGER_COMPONENT + "/" + command)
            .body(AiatpIO.Body.ofString(normalizedArgs, StandardCharsets.UTF_8))
            .build())
        .consumer(outFuture::complete)
        .build();

    try {
      context.execute(TASK_MANAGER_COMPONENT, command, internalContext);
      AiatpIO.HttpResponse response = outFuture.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      String body = AiatpIO.bodyToString(response.body(), StandardCharsets.UTF_8);
      return parseToolEnvelope(command, normalizedArgs, response.status(), body);
    } catch (TimeoutException e) {
      return ToolCallResult.failure(command, normalizedArgs, "timeout", "Tool execution timed out.", "", "");
    } catch (Exception e) {
      return ToolCallResult.failure(command, normalizedArgs, "execution_failed",
          "Tool execution failed: " + safe(e.getMessage()), "", "");
    }
  }

  private LoopResult runGoalLoop(CommandContext context, String prompt, String correlationId) {
    StringBuilder loopTrace = new StringBuilder();
    String workingPrompt = prompt;
    String lastAction = "none";
    String lastExecutedAction = "";
    LLMClient llm = buildLlmClient();
    try {
      for (int step = 1; step <= MAX_STEPS; step++) {
        PlannerDecision decision = planNextAction(llm, workingPrompt, step);
        String action = safe(decision.action);
        lastAction = action.isBlank() ? "none" : action;
        if (step > 1 && !lastExecutedAction.isBlank() && lastExecutedAction.equals(lastAction)) {
          appendTrace(loopTrace, correlationId, step, "stop", "repeated_action_after_success");
          rememberLoopTrace(loopTrace, correlationId, "ok");
          return LoopResult.of("ok", StopReason.ACTION_NONE, "none", "Operation completed.", null);
        }
        appendTrace(loopTrace, correlationId, step, "plannedAction", lastAction);
        if (decision.stop) {
          appendTrace(loopTrace, correlationId, step, "stop", decision.stopReason.name());
          rememberLoopTrace(loopTrace, correlationId, "ok");
          return LoopResult.of("ok", decision.stopReason, lastAction, decision.userMessage, null);
        }
        ParsedCommand execution = extractExecutionCommand(action);
        if (execution.command.isEmpty()) {
          appendTrace(loopTrace, correlationId, step, "invalidAction", action);
          rememberLoopTrace(loopTrace, correlationId, "error");
          return LoopResult.of("error", StopReason.UNSUPPORTED_ACTION, lastAction,
              "Planner action must use 'execute <command> [args]'.", "unsupported_action");
        }
        ValidationResult validation = validateCommand(execution.command, execution.args);
        if (!validation.valid) {
          StopReason reason = "unsupported_action".equals(validation.errorCode)
              ? StopReason.UNSUPPORTED_ACTION
              : StopReason.INVALID_ARGUMENTS;
          appendTrace(loopTrace, correlationId, step, "validationError", validation.errorCode + ":" + validation.message);
          rememberLoopTrace(loopTrace, correlationId, "error");
          return LoopResult.of("error", reason, lastAction, validation.message, validation.errorCode);
        }

        ToolCallResult tool = executeTaskManager(context, execution.command, execution.args);
        memoryBuffer.add("tool", execution.command, tool.summaryForMemory());
        appendTrace(loopTrace, correlationId, step, "toolResult", tool.summaryForMemory());
        if (!tool.ok) {
          rememberLoopTrace(loopTrace, correlationId, "error");
          return LoopResult.of("error", StopReason.TOOL_EXECUTION_FAILED, lastAction, tool.userMessage(), tool.errorCode);
        }

        lastExecutedAction = "execute " + execution.command + (execution.args.isBlank() ? "" : " " + execution.args);
        workingPrompt = buildFollowUpPrompt(prompt, action, tool);
        appendTrace(loopTrace, correlationId, step, "followUpPrompt", workingPrompt);
      }
      appendTrace(loopTrace, correlationId, MAX_STEPS, "stop", StopReason.MAX_STEPS_REACHED.name());
      rememberLoopTrace(loopTrace, correlationId, "error");
      return LoopResult.of("error", StopReason.MAX_STEPS_REACHED, lastAction,
          "Goal loop reached max steps without explicit completion.", "max_steps_reached");
    } catch (Exception e) {
      appendTrace(loopTrace, correlationId, 0, "exception", safe(e.getMessage()));
      rememberLoopTrace(loopTrace, correlationId, "error");
      return LoopResult.of("error", StopReason.PLANNER_EXCEPTION, lastAction,
          "Planner loop exception: " + safe(e.getMessage()), "planner_exception");
    }
  }

  private void rememberLoopTrace(StringBuilder loopTrace, String correlationId, String status) {
    String trace = safe(loopTrace.toString());
    if (trace.isBlank()) {
      return;
    }
    if ("error".equalsIgnoreCase(status) || VERBOSE_TRACE) {
      memoryBuffer.add("loop-trace", "process", truncate(trace, TRACE_MAX_CHARS));
      return;
    }
    memoryBuffer.add("loop-trace", "process",
        "correlationId=" + correlationId + " summary=ok trace_suppressed verbose=" + VERBOSE_TRACE);
  }

  private PlannerDecision planNextAction(LLMClient llm, String workingPrompt, int step) {
    String prompt = safe(workingPrompt).trim();
    if (prompt.isEmpty()) {
      return PlannerDecision.stop(StopReason.ACTION_NONE, "No operation requested.");
    }

    PlannerDecision llmDecision = planWithLlm(llm, prompt, step);
    if (llmDecision != null) {
      return llmDecision;
    }

    if (prompt.startsWith("execute ")) {
      return PlannerDecision.continueWith(prompt);
    }

    String inferred = inferActionFromNaturalLanguage(prompt);
    if (!inferred.isBlank()) {
      return PlannerDecision.continueWith(inferred);
    }
    ParsedCommand parsed = parseCommand(prompt);
    if (ALLOWED_COMMANDS.contains(parsed.command)) {
      return PlannerDecision.continueWith("execute " + parsed.command + (parsed.args.isBlank() ? "" : " " + parsed.args));
    }
    if ("debug memory".equalsIgnoreCase(prompt) || "show memory".equalsIgnoreCase(prompt)) {
      return PlannerDecision.stop(StopReason.ACTION_NONE, memoryBuffer.summarize(20));
    }
    return PlannerDecision.stop(StopReason.ACTION_NONE,
        "Task manager request captured. Use 'execute <command> [args]' or direct supported command syntax.");
  }

  private LLMClient buildLlmClient() {
    if (safe(openApiKey).isBlank()) {
      return null;
    }
    try {
      return new OpenAiLLM(openApiKey, agentDefinition.getModel());
    } catch (Exception ignored) {
      return null;
    }
  }

  private PlannerDecision planWithLlm(LLMClient llm, String prompt, int step) {
    if (llm == null) {
      return null;
    }
    try {
      String contextJson = mapper.writeValueAsString(Map.of(
          "phase", PHASE,
          "step", step,
          "memory", memoryBuffer.summarize(LLM_MEMORY_CONTEXT_ITEMS),
          "planner_output_schema", PLANNER_OUTPUT_SCHEMA_CONTRACT
      ));
      String raw = llm.chat(agentDefinition.getSystemPrompt(), prompt, contextJson);
      JsonNode root = extractFirstJsonBlock(raw);
      String action = safe(readPath(root, "action")).trim();
      String user = safe(readPath(root, "user")).trim();
      if (action.isEmpty()) {
        return null;
      }
      if ("none".equalsIgnoreCase(action)) {
        return PlannerDecision.stop(StopReason.ACTION_NONE, user.isBlank() ? "Operation completed." : user);
      }
      if (action.startsWith("execute ")) {
        return PlannerDecision.continueWith(action);
      }
      return PlannerDecision.stop(StopReason.UNSUPPORTED_ACTION,
          "Planner action must use 'none' or 'execute <command> [args]'.");
    } catch (Exception ignored) {
      return null;
    }
  }

  private String inferActionFromNaturalLanguage(String prompt) {
    String lower = safe(prompt).toLowerCase();
    if ((lower.contains("create") || lower.contains("add")) && lower.contains("task")) {
      String assigned = extractAssigned(prompt);
      if (assigned.isBlank()) {
        return "";
      }
      String title = extractTitle(prompt);
      if (title.isBlank()) {
        title = "Untitled task";
      }
      return "execute create --title \"" + escapeForQuotedArg(title) + "\" --assigned " + assigned;
    }
    if (lower.contains("list") && lower.contains("task")) {
      String assigned = extractAssigned(prompt);
      if (!assigned.isBlank()) {
        return "execute list --assigned " + assigned + " --limit 10";
      }
      return "execute list --limit 10";
    }
    return "";
  }

  private static String extractAssigned(String prompt) {
    Matcher matcher = ASSIGNED_PATTERN.matcher(safe(prompt));
    if (matcher.find()) {
      return safe(matcher.group(1)).trim();
    }
    return "";
  }

  private static String extractTitle(String prompt) {
    Matcher titledMatcher = TITLE_TITLED_PATTERN.matcher(safe(prompt));
    if (titledMatcher.find()) {
      return safe(titledMatcher.group(1)).trim();
    }
    Matcher matcher = TITLE_AFTER_TO_PATTERN.matcher(safe(prompt));
    if (matcher.find()) {
      return safe(matcher.group(1))
          .replaceAll("(?i)\\bfor\\s+[A-Za-z0-9_.:-]+\\b", "")
          .replaceAll("(?i)\\bassigned\\s+to(?:\\s+agent)?\\s+[A-Za-z0-9_.:-]+\\b", "")
          .trim();
    }
    String cleaned = safe(prompt)
        .replaceAll("(?i)\\b(create|add)\\b", "")
        .replaceAll("(?i)\\btask\\b", "")
        .replaceAll("(?i)\\bfor\\s+[A-Za-z0-9_.:-]+\\b", "")
        .replaceAll("(?i)\\bassigned\\s+to(?:\\s+agent)?\\s+[A-Za-z0-9_.:-]+\\b", "")
        .replaceAll("(?i)\\bdue\\s+in\\s+\\d+\\s+\\w+\\b", "")
        .trim();
    return cleaned;
  }

  private static ParsedCommand extractExecutionCommand(String action) {
    String trimmed = safe(action).trim();
    if (!trimmed.startsWith("execute ")) {
      return new ParsedCommand("", "");
    }
    return parseCommand(trimmed.substring("execute ".length()).trim());
  }

  private static String buildFollowUpPrompt(String originalPrompt, String action, ToolCallResult tool) {
    return "ORIGINAL: " + safe(originalPrompt)
        + "\nLAST_ACTION: " + safe(action)
        + "\nTOOL_OK: " + tool.ok
        + "\nTOOL_MESSAGE: " + safe(tool.message)
        + "\nOUTPUT_SCHEMA: " + PLANNER_OUTPUT_SCHEMA_CONTRACT
        + "\nDecide next action or 'none'.";
  }

  private static void appendTrace(StringBuilder trace, String correlationId, int step, String key, String value) {
    if (!trace.isEmpty()) {
      trace.append('\n');
    }
    trace.append("correlationId=").append(correlationId)
        .append(" step=").append(step)
        .append(" ").append(key).append("=")
        .append(safe(value));
  }

  private ToolCallResult parseToolEnvelope(String command, String args, int status, String body) {
    String payload = safe(body).trim();
    try {
      JsonNode root = mapper.readTree(payload);
      boolean ok = root.path("ok").asBoolean(status < 400);
      String errorCode = readPath(root, "errorCode");
      String message = readPath(root, "message");
      String data = root.has("data") ? root.get("data").toString() : "";
      String meta = root.has("meta") ? root.get("meta").toString() : "";
      if (message.isBlank()) {
        message = ok ? "Tool call succeeded." : "Tool call failed.";
      }
      if (ok) {
        return ToolCallResult.success(command, args, message, data, meta);
      }
      String code = errorCode.isBlank() ? "tool_error" : errorCode;
      return ToolCallResult.failure(command, args, code, message, data, meta);
    } catch (Exception parseError) {
      if (status >= 400) {
        return ToolCallResult.failure(command, args, "malformed_tool_error_response",
            "Tool returned non-JSON error response.", payload, "");
      }
      return ToolCallResult.failure(command, args, "malformed_tool_response",
          "Tool returned non-JSON response.", payload, "");
    }
  }

  private static String ensureJsonFormat(String args) {
    String normalized = safe(args).trim();
    if (normalized.contains("--format")) {
      return normalized;
    }
    if (normalized.isEmpty()) {
      return "--format json";
    }
    return normalized + " --format json";
  }

  private static ParsedCommand parseCommand(String raw) {
    String input = safe(raw).trim();
    if (input.isEmpty()) {
      return new ParsedCommand("", "");
    }
    int space = input.indexOf(' ');
    if (space < 0) {
      return new ParsedCommand(input, "");
    }
    return new ParsedCommand(input.substring(0, space).trim(), input.substring(space + 1).trim());
  }

  private ValidationResult validateCommand(String command, String args) {
    String normalizedCommand = safe(command).trim();
    if (!ALLOWED_COMMANDS.contains(normalizedCommand)) {
      return ValidationResult.error(
          "unsupported_action",
          "Unsupported command '" + normalizedCommand + "'. Allowed: " + String.join(", ", ALLOWED_COMMANDS)
      );
    }

    Set<String> required = REQUIRED_OPTIONS.getOrDefault(normalizedCommand, Set.of());
    if (required.isEmpty()) {
      return ValidationResult.ok();
    }
    Map<String, String> options = parseOptions(args);
    for (String key : required) {
      if (!options.containsKey(key) || options.get(key).isBlank()) {
        return ValidationResult.error(
            "invalid_arguments",
            "Command '" + normalizedCommand + "' requires option " + key + "."
        );
      }
    }
    return ValidationResult.ok();
  }

  private Map<String, String> parseOptions(String args) {
    Map<String, String> options = new LinkedHashMap<>();
    String input = safe(args);
    int i = 0;
    while (i < input.length()) {
      while (i < input.length() && Character.isWhitespace(input.charAt(i))) {
        i++;
      }
      if (i >= input.length() || input.charAt(i) != '-') {
        while (i < input.length() && !Character.isWhitespace(input.charAt(i))) {
          i++;
        }
        continue;
      }
      int start = i;
      while (i < input.length() && !Character.isWhitespace(input.charAt(i))) {
        i++;
      }
      String key = input.substring(start, i);
      while (i < input.length() && Character.isWhitespace(input.charAt(i))) {
        i++;
      }
      if (i >= input.length() || input.charAt(i) == '-') {
        options.put(key, "true");
        continue;
      }
      String value;
      if (input.charAt(i) == '"' || input.charAt(i) == '\'') {
        char quote = input.charAt(i++);
        int valueStart = i;
        while (i < input.length() && input.charAt(i) != quote) {
          i++;
        }
        value = input.substring(valueStart, Math.min(i, input.length()));
        if (i < input.length() && input.charAt(i) == quote) {
          i++;
        }
      } else {
        int valueStart = i;
        while (i < input.length() && !Character.isWhitespace(input.charAt(i))) {
          i++;
        }
        value = input.substring(valueStart, i);
      }
      options.put(key, value);
    }
    return options;
  }

  private static String readPath(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() ? "" : value.asText("");
  }

  private JsonNode extractFirstJsonBlock(String raw) {
    String text = safe(raw).trim();
    if (text.isEmpty()) {
      return mapper.createObjectNode();
    }
    try {
      return mapper.readTree(text);
    } catch (Exception ignored) {
    }
    int first = text.indexOf('{');
    int last = text.lastIndexOf('}');
    if (first >= 0 && last > first) {
      try {
        return mapper.readTree(text.substring(first, last + 1));
      } catch (Exception ignored) {
      }
    }
    return mapper.createObjectNode();
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private static String escapeForQuotedArg(String value) {
    return safe(value).replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String truncate(String value, int maxChars) {
    String text = safe(value);
    if (text.length() <= maxChars) {
      return text;
    }
    return text.substring(0, Math.max(0, maxChars - 3)) + "...";
  }

  private static long parseLongStrict(String value) {
    String text = safe(value).trim();
    if (text.isEmpty()) {
      return -1L;
    }
    try {
      return Long.parseLong(text);
    } catch (Exception ignored) {
      return -1L;
    }
  }

  private record ParsedCommand(String command, String args) {
  }

  private static final class ParsedReactEvent {
    private final boolean valid;
    private final String errorMessage;
    private final String eventId;
    private final long seq;
    private final String eventType;
    private final String taskId;
    private final String targetAgentId;

    private ParsedReactEvent(boolean valid,
                             String errorMessage,
                             String eventId,
                             long seq,
                             String eventType,
                             String taskId,
                             String targetAgentId) {
      this.valid = valid;
      this.errorMessage = errorMessage;
      this.eventId = eventId;
      this.seq = seq;
      this.eventType = eventType;
      this.taskId = taskId;
      this.targetAgentId = targetAgentId;
    }

    private static ParsedReactEvent valid(String eventId, long seq, String eventType, String taskId, String targetAgentId) {
      return new ParsedReactEvent(true, "", eventId, seq, eventType, taskId, targetAgentId);
    }

    private static ParsedReactEvent invalid(String message) {
      return new ParsedReactEvent(false, message, "", -1L, "", "", "");
    }

    private String summary() {
      return "event_id=" + eventId
          + " seq=" + seq
          + " event_type=" + eventType
          + " task_id=" + taskId
          + " target_agent_id=" + targetAgentId;
    }
  }

  private static final class ValidationResult {
    private final boolean valid;
    private final String errorCode;
    private final String message;

    private ValidationResult(boolean valid, String errorCode, String message) {
      this.valid = valid;
      this.errorCode = errorCode;
      this.message = message;
    }

    private static ValidationResult ok() {
      return new ValidationResult(true, null, "");
    }

    private static ValidationResult error(String errorCode, String message) {
      return new ValidationResult(false, errorCode, message);
    }
  }

  private record PlannerDecision(String action, boolean stop, StopReason stopReason, String userMessage) {
    private static PlannerDecision continueWith(String action) {
      return new PlannerDecision(action, false, null, "");
    }

    private static PlannerDecision stop(StopReason stopReason, String userMessage) {
      return new PlannerDecision("none", true, stopReason, userMessage);
    }
  }

  private record LoopResult(String status, StopReason stopReason, String action, String userMessage, String errorCode) {
    private static LoopResult of(String status, StopReason stopReason, String action, String userMessage, String errorCode) {
      return new LoopResult(status, stopReason, action, userMessage, errorCode);
    }
  }

  private static final class ToolCallResult {
    private final boolean ok;
    private final String command;
    private final String args;
    private final String errorCode;
    private final String message;
    private final String data;
    private final String meta;

    private ToolCallResult(boolean ok,
                           String command,
                           String args,
                           String errorCode,
                           String message,
                           String data,
                           String meta) {
      this.ok = ok;
      this.command = command;
      this.args = args;
      this.errorCode = errorCode;
      this.message = message;
      this.data = data;
      this.meta = meta;
    }

    private static ToolCallResult success(String command, String args, String message, String data, String meta) {
      return new ToolCallResult(true, command, args, null, message, data, meta);
    }

    private static ToolCallResult failure(String command, String args, String errorCode, String message, String data, String meta) {
      return new ToolCallResult(false, command, args, errorCode, message, data, meta);
    }

    private String userMessage() {
      return message + " [command=" + command + ", args=" + args + "]";
    }

    private String summaryForMemory() {
      return (ok ? "ok" : "error")
          + " command=" + command
          + " errorCode=" + safe(errorCode)
          + " message=" + safe(message)
          + " data=" + safe(data)
          + " meta=" + safe(meta);
    }
  }

  private static String quote(String value) {
    return "\"" + escapeJson(value) + "\"";
  }

  private static String escapeJson(String value) {
    String text = safe(value);
    return text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n");
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
}
