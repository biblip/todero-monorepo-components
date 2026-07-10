package com.shellaia.agent.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.social100.todero.common.ai.agent.AgentContext;
import com.social100.todero.common.ai.llm.LLMClient;
import com.social100.todero.common.ai.llm.OpenAiLLM;
import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpRequest;
import com.social100.todero.common.aiatpio.AiatpResponse;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.config.Util;
import com.social100.todero.common.storage.Storage;
import com.shellaia.agent.context.runtime.ContextAgentRuntime;
import com.shellaia.agent.context.runtime.ContextConversationTurn;
import com.shellaia.agent.context.runtime.ContextRuntimeState;
import com.shellaia.agent.context.classify.ContextMessageClassification;
import com.shellaia.agent.context.classify.ContextMessageKind;
import com.shellaia.agent.context.model.ConversationDurableKind;
import com.shellaia.agent.context.model.ConversationDurableRecord;
import com.shellaia.agent.context.model.ConversationDurableStatus;
import com.shellaia.agent.context.store.ContextAgentWorkspace;
import com.shellaia.agent.context.model.ConversationTurnRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Function;

@AIAController(
    name = "com.shellaia.agent.context",
    type = ServerType.AI,
    visible = true,
    description = "Conversational context agent that curates subject memory and returns structured turn state.",
    capabilityProvider = ContextAgentCapabilities.class
)
public class ContextAgentComponent {
  private static final Logger LOG = LoggerFactory.getLogger(ContextAgentComponent.class);
  private static final String MAIN_GROUP = "Main";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int DEFAULT_MAX_RAW = 8;
  private static final int DEFAULT_MAX_DERIVED = 8;
  private static final int DEFAULT_MAX_REMEMBERED = 8;
  private static final int DEFAULT_GIT_HINTS_LIMIT = 4;
  private static final String HTML_TEMPLATE_PATH = "com/shellaia/agent/context/component.html";
  private static final String HTML_TEMPLATE = loadResourceText(HTML_TEMPLATE_PATH);
  private static final String DURABLE_DIRECTIVE_SYSTEM_PROMPT = """
      You are a durable routing assistant for a conversational context agent.

      Decide whether the latest user message should create a new durable, update an existing one, or do nothing.
      Use the current durable records as the source of truth.
      Prefer update when the user refers to an existing reminder/task, especially if they say close, finish, done, cancel, or reopen.
      Prefer create only for a brand-new reminder or task.
      Return JSON only with:
      - action: create, update, or none
      - kind: TASK or REMINDER when action is create, otherwise empty
      - target: the phrase or identifier to use when action is update, otherwise empty
      - status: open, done, or canceled when relevant, otherwise empty
      - content: the canonical content to store when action is create, otherwise empty
      - reason: a short explanation
      Do not invent record ids. If the user is not changing durable state, return action none.
      """;
  private static final String DURABLE_SELECTION_SYSTEM_PROMPT = """
      You are a durable selection assistant for a conversational context agent.

      Select exactly one durable record from the provided current thread records, or return none if there is no safe match.
      Use the user target and the current records as your only source of truth.
      Return JSON only with:
      - recordId: the selected durable record id, or empty if none
      - reason: a short explanation
      - ambiguous: true when more than one record would be equally plausible, otherwise false
      Do not invent record ids.
      """;
  private static final String RESPONSE_SYSTEM_PROMPT = """
      You are a conversational context agent.

      Reply to the user using the supplied conversation context.
      Use only information present in the provided context bundle.
      Do not invent storage state or branch history.
      If confirmation is required, make that clear.
      Return a JSON object with at least:
      - reply: the natural language reply for the user
      - confirmationRequired: true or false
      - confirmationMessage: a short confirmation message, or an empty string
      """;

  private final Storage storage;
  private final Path workspaceRoot;
  private final ContextAgentWorkspace workspace;
  private final ContextAgentRuntime runtime;
  private final Function<CommandContext, LLMClient> llmResolver;
  private final String openApiKey;

  public ContextAgentComponent(Storage storage) {
    this(storage, defaultWorkspaceRoot(), null);
  }

  ContextAgentComponent(Storage storage, Path workspaceRoot) {
    this(storage, workspaceRoot, null);
  }

  ContextAgentComponent(Storage storage, Path workspaceRoot, Function<CommandContext, LLMClient> llmResolver) {
    this.storage = Objects.requireNonNull(storage, "storage");
    this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot").toAbsolutePath().normalize();
    this.workspace = new ContextAgentWorkspace(this.workspaceRoot);
    this.runtime = new ContextAgentRuntime(this.workspace);
    this.llmResolver = llmResolver;
    this.openApiKey = loadOpenApiKey(storage);
  }

  @Action(group = MAIN_GROUP, command = "process", description = "Process one conversational turn and return the current context bundle.")
  public Boolean process(CommandContext context) {
    ProcessRequest request = parseRequest(requestBody(context));
    if (request.message().isBlank()) {
      completeText(context, 400, "invalid_request", "Message is required. Usage: process <message> or process {json}");
      return true;
    }

    String threadId = resolveThreadId(context);
    List<String> threadHistory = loadThreadHistory(threadId, request.subjectId(), 12);
    List<String> threadDurables = loadThreadDurables(threadId, request.subjectId(), 12);
    List<ConversationDurableRecord> durableRecords = workspace.readConversationDurables(threadId, 50);
    List<String> recentInteractions = mergeRecentInteractions(threadHistory, request.recentInteractions());

    ContextConversationTurn turn = runtime.prepareTurn(
        request.subjectId(),
        request.message(),
        recentInteractions,
        threadDurables,
        request.draftCandidates(),
        request.maxRaw(),
        request.maxDerived(),
        request.maxRemembered(),
        request.gitHintsLimit()
    );
    LLMClient llm = resolveSystemLlm(context);
    if (llm == null) {
      completeText(context, 503, "llm_unavailable", "No system LLM available for the context agent.");
      return true;
    }
    DurableDirective directive = resolveDurableDirective(llm, threadId, turn, request.message(), durableRecords);
    DurableOutcome durableOutcome = applyDurableDirective(llm, threadId, turn.state(), request.message(), directive, durableRecords);
    ConversationReply reply = invokeConversationLlm(llm, turn, request.message(), durableOutcome.summary());
    persistTurn(threadId, request.subjectId(), turn.state().branchId(), request.message(), reply.reply());

    if (request.includeMetadata()) {
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("ok", true);
      response.put("reply", reply.reply());
      response.put("subjectId", turn.state().subjectId());
      response.put("branchId", turn.state().branchId());
      response.put("mode", turn.state().mode().name());
      response.put("confirmationRequired", turn.confirmationRequired());
      response.put("confirmationMessage", turn.confirmationMessage());
      response.put("classification", turn.classification());
      response.put("prompt", Map.of(
          "renderedPrompt", turn.prompt().renderedPrompt(),
          "sections", turn.prompt().sections(),
          "branchContextIncluded", turn.prompt().branchContextIncluded()
      ));
      response.put("llm", Map.of(
          "provider", reply.provider(),
          "raw", reply.rawResponse()
      ));
      response.put("workspaceRoot", workspaceRoot.toString());
      completeJson(context, 200, "ok", response);
      return true;
    }
    completeText(context, 200, "ok", reply.reply());
    return true;
  }

  @Action(group = MAIN_GROUP, command = "html", description = "Render an interactive HTML surface for the conversational context agent.")
  public Boolean html(CommandContext context) {
    String host = resolveRequestHost(context == null ? null : context.getAiatpRequest());
    String title = safeTrim(host).isBlank() ? "Context Agent" : "Context Agent (" + host + ")";
    String html = HTML_TEMPLATE
        .replace("${TITLE}", escapeHtml(title))
        .replace("${HEADING}", escapeHtml("Conversational Context Agent"))
        .replace("${SUBHEADING}", escapeHtml("Track durable reminders, tasks, and active subject context in one place."))
        .replace("${GENERATED_AT}", escapeHtml(Instant.now().toString()));
    completeHtml(context, 200, "ok", html);
    return true;
  }

  @Action(group = MAIN_GROUP, command = "durables", description = "List durable reminders and tasks for the current thread.")
  public Boolean durables(CommandContext context) {
    String threadId = resolveThreadId(context);
    DurablesRequest request = parseDurablesRequest(requestBody(context));
    int limit = request.limit();
    List<ConversationDurableRecord> records = filterDurables(threadId, workspace.readConversationDurables(threadId, limit), request);
    if (records.isEmpty()) {
      if (request.json()) {
        completeJson(context, 200, "ok", Map.of(
            "ok", true,
            "threadId", threadId,
            "limit", limit,
            "view", request.view(),
            "finalizedDays", request.finalizedDays(),
            "records", List.of()
        ));
      } else {
        completeText(context, 200, "ok", emptyDurableMessage(request));
      }
      return true;
    }
    if (request.json()) {
      List<Map<String, Object>> jsonRecords = new ArrayList<>();
      for (ConversationDurableRecord record : records) {
        jsonRecords.add(durableRecordMap(record));
      }
      completeJson(context, 200, "ok", Map.of(
          "ok", true,
          "threadId", threadId,
          "limit", limit,
          "view", request.view(),
          "finalizedDays", request.finalizedDays(),
          "records", jsonRecords
      ));
      return true;
    }
    List<String> lines = new ArrayList<>();
    for (ConversationDurableRecord record : records) {
      lines.add(formatDurableRecord(record));
    }
    completeText(context, 200, "ok", String.join("\n", lines));
    return true;
  }

  @Action(group = MAIN_GROUP, command = "durable-set", description = "Update a durable reminder/task status for the current thread. Usage: durable-set <recordId|phrase> <open|done|canceled>")
  public Boolean durableSet(CommandContext context) {
    String threadId = resolveThreadId(context);
    LLMClient llm = resolveSystemLlm(context);
    if (llm == null) {
      completeText(context, 503, "llm_unavailable", "No system LLM available for durable selection.");
      return true;
    }
    DurableSetRequest request = parseDurableSetRequest(requestBody(context));
    LOG.info("[ContextAgent][Durable] durable-set requested threadId={} target={} status={} refresh={} limit={}",
        threadId, request.target(), request.status(), request.refresh(), request.limit());
    if (request.target().isBlank()) {
      LOG.warn("[ContextAgent][Durable] durable-set rejected threadId={} reason=missing_target", threadId);
      completeText(context, 400, "invalid_request", "recordId or phrase is required. Usage: durable-set <recordId|phrase> <open|done|canceled>");
      return true;
    }
    ConversationDurableStatus status = parseDurableStatus(request.status());
    if (status == null) {
      LOG.warn("[ContextAgent][Durable] durable-set rejected threadId={} target={} reason=invalid_status rawStatus={}", threadId, request.target(), request.status());
      completeText(context, 400, "invalid_request", "status must be open, done, or canceled.");
      return true;
    }
    List<ConversationDurableRecord> records = workspace.readConversationDurables(threadId, 128);
    DurableResolution resolution = resolveDurableRecord(llm, threadId, request.target(), records);
    if (resolution.ambiguous()) {
      LOG.warn("[ContextAgent][Durable] durable-set ambiguous threadId={} target={} message={}", threadId, request.target(), resolution.message());
      completeText(context, 409, "ambiguous", resolution.message());
      return true;
    }
    if (resolution.record() == null) {
      LOG.warn("[ContextAgent][Durable] durable-set not_found threadId={} target={}", threadId, request.target());
      completeText(context, 404, "not_found", "No durable record found for that recordId or phrase in this thread.");
      return true;
    }
    LOG.info("[ContextAgent][Durable] durable-set resolved threadId={} target={} recordId={} currentStatus={} nextStatus={}",
        threadId, request.target(), resolution.record().recordId(), resolution.record().status(), status);
    ConversationDurableRecord updated = workspace.updateConversationDurableStatus(threadId, resolution.record().recordId(), status, Instant.now());
    if (updated == null) {
      LOG.warn("[ContextAgent][Durable] durable-set update_failed threadId={} recordId={} target={}", threadId, resolution.record().recordId(), request.target());
      completeText(context, 404, "not_found", "No durable record found for that recordId in this thread.");
      return true;
    }
    if (request.refresh()) {
      List<ConversationDurableRecord> refreshed = workspace.readConversationDurables(threadId, request.limit());
      boolean verified = refreshed.stream().anyMatch(record ->
          Objects.equals(record.recordId(), updated.recordId()) && record.status() == status
      );
      LOG.info("[ContextAgent][Durable] durable-set refresh threadId={} recordId={} verified={} refreshedCount={} status={}",
          threadId, updated.recordId(), verified, refreshed.size(), status);
      if (!verified) {
        LOG.warn("[ContextAgent][Durable] durable-set verification_failed threadId={} recordId={} expectedStatus={} refreshedCount={}",
            threadId, updated.recordId(), status, refreshed.size());
        completeJson(context, 500, "verification_failed", Map.of(
            "ok", false,
            "verified", false,
            "threadId", threadId,
            "limit", request.limit(),
            "updated", durableRecordMap(updated),
            "records", refreshed.stream().map(ContextAgentComponent::durableRecordMap).toList(),
            "message", "Durable update did not verify against the refreshed thread view."
        ));
        return true;
      }
      completeJson(context, 200, "ok", Map.of(
          "ok", true,
          "verified", true,
          "threadId", threadId,
          "limit", request.limit(),
          "updated", durableRecordMap(updated),
          "records", refreshed.stream().map(ContextAgentComponent::durableRecordMap).toList()
      ));
      return true;
    }
    LOG.info("[ContextAgent][Durable] durable-set completed threadId={} recordId={} status={} refresh=false", threadId, updated.recordId(), status);
    completeText(context, 200, "ok", formatDurableRecord(updated));
    return true;
  }

  private ProcessRequest parseRequest(String body) {
    String trimmed = safeTrim(body);
    if (trimmed.isEmpty()) {
      return ProcessRequest.empty();
    }
    if (!looksLikeJson(trimmed)) {
      return new ProcessRequest("", trimmed, List.of(), List.of(), false, DEFAULT_MAX_RAW, DEFAULT_MAX_DERIVED, DEFAULT_MAX_REMEMBERED, DEFAULT_GIT_HINTS_LIMIT);
    }
    try {
      JsonNode root = JSON.readTree(trimmed);
      if (root == null || !root.isObject()) {
        return new ProcessRequest("", trimmed, List.of(), List.of(), false, DEFAULT_MAX_RAW, DEFAULT_MAX_DERIVED, DEFAULT_MAX_REMEMBERED, DEFAULT_GIT_HINTS_LIMIT);
      }
      String subjectId = firstNonBlank(root.path("subjectId").asText(""), root.path("subject").asText(""));
      String message = firstNonBlank(
          root.path("message").asText(""),
          firstNonBlank(root.path("prompt").asText(""), root.path("text").asText(""))
      );
      List<String> recentInteractions = readStringList(root.path("recentInteractions"));
      List<String> draftCandidates = readStringList(root.path("draftCandidates"));
      boolean includeMetadata = root.path("includeMetadata").asBoolean(false);
      int maxRaw = boundedInt(root.path("maxRaw").asInt(DEFAULT_MAX_RAW), 0, 128);
      int maxDerived = boundedInt(root.path("maxDerived").asInt(DEFAULT_MAX_DERIVED), 0, 128);
      int maxRemembered = boundedInt(root.path("maxRemembered").asInt(DEFAULT_MAX_REMEMBERED), 0, 128);
      int gitHintsLimit = boundedInt(root.path("gitHintsLimit").asInt(DEFAULT_GIT_HINTS_LIMIT), 0, 128);
      if (message.isBlank()) {
        message = trimmed;
      }
      return new ProcessRequest(subjectId, message, recentInteractions, draftCandidates, includeMetadata, maxRaw, maxDerived, maxRemembered, gitHintsLimit);
    } catch (IOException e) {
      return new ProcessRequest("", trimmed, List.of(), List.of(), false, DEFAULT_MAX_RAW, DEFAULT_MAX_DERIVED, DEFAULT_MAX_REMEMBERED, DEFAULT_GIT_HINTS_LIMIT);
    }
  }

  private static List<String> readStringList(JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (JsonNode item : node) {
      String value = safeTrim(item == null ? "" : item.asText(""));
      if (!value.isBlank()) {
        values.add(value);
      }
    }
    return List.copyOf(values);
  }

  private static DurableSetRequest parseDurableSetRequest(String body) {
    String trimmed = safeTrim(body);
    if (trimmed.isEmpty()) {
      LOG.warn("[ContextAgent][Durable] durable-set parse empty body");
      return DurableSetRequest.empty();
    }
    if (!looksLikeJson(trimmed)) {
      String[] parts = trimmed.split("\\s+");
      if (parts.length == 1) {
        LOG.info("[ContextAgent][Durable] durable-set parsed plain-text target={} status=missing", parts[0]);
        return new DurableSetRequest(parts[0], "", false, 20);
      }
      String first = safeTrim(parts[0]);
      String last = safeTrim(parts[parts.length - 1]);
      if (parseDurableStatus(last) != null) {
        String target = String.join(" ", java.util.Arrays.copyOf(parts, parts.length - 1));
        LOG.info("[ContextAgent][Durable] durable-set parsed plain-text target={} status={}", target, last);
        return new DurableSetRequest(target, last, false, 20);
      }
      if (parseDurableStatus(first) != null) {
        String target = String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length));
        LOG.info("[ContextAgent][Durable] durable-set parsed plain-text target={} status={}", target, first);
        return new DurableSetRequest(target, first, false, 20);
      }
      LOG.info("[ContextAgent][Durable] durable-set parsed plain-text target={} status={}", first, String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length)));
      return new DurableSetRequest(first, String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length)), false, 20);
    }
    try {
      JsonNode root = JSON.readTree(trimmed);
      if (root == null || !root.isObject()) {
        LOG.warn("[ContextAgent][Durable] durable-set parse json non-object body={}", trimmed);
        return DurableSetRequest.empty();
      }
      String target = firstNonBlank(
          root.path("recordId").asText(""),
          firstNonBlank(root.path("id").asText(""), firstNonBlank(root.path("target").asText(""), root.path("query").asText("")))
      );
      String status = firstNonBlank(root.path("status").asText(""), root.path("state").asText(""));
      boolean refresh = root.path("refresh").asBoolean(false);
      int limit = boundedInt(root.path("limit").asInt(20), 0, 128);
      LOG.info("[ContextAgent][Durable] durable-set parsed json target={} status={} refresh={} limit={}", target, status, refresh, limit);
      return new DurableSetRequest(target, status, refresh, limit);
    } catch (Exception e) {
      LOG.warn("[ContextAgent][Durable] durable-set parse failed body={} error={}", trimmed, e.getMessage());
      return DurableSetRequest.empty();
    }
  }

  private static DurablesRequest parseDurablesRequest(String body) {
    String trimmed = safeTrim(body);
    if (trimmed.isEmpty()) {
      return DurablesRequest.empty();
    }
    if (!looksLikeJson(trimmed)) {
      try {
      return new DurablesRequest(boundedInt(Integer.parseInt(trimmed), 0, 128), false, "active", 30);
      } catch (NumberFormatException ignored) {
        return DurablesRequest.empty();
      }
    }
    try {
      JsonNode root = JSON.readTree(trimmed);
      if (root == null || !root.isObject()) {
        return DurablesRequest.empty();
      }
      int limit = boundedInt(root.path("limit").asInt(20), 0, 128);
      String format = safeTrim(root.path("format").asText(""));
      boolean json = "json".equalsIgnoreCase(format) || root.path("json").asBoolean(false);
      String view = normalizeDurableView(firstNonBlank(root.path("view").asText(""), root.path("scope").asText("")));
      int finalizedDays = boundedInt(readDurableDays(root), 1, 3650);
      return new DurablesRequest(limit, json, view, finalizedDays);
    } catch (Exception e) {
      return DurablesRequest.empty();
    }
  }

  private static int parseLimit(String body, int defaultLimit) {
    String trimmed = safeTrim(body);
    if (trimmed.isEmpty()) {
      return defaultLimit;
    }
    if (looksLikeJson(trimmed)) {
      try {
        JsonNode root = JSON.readTree(trimmed);
        if (root != null && root.isObject()) {
          return boundedInt(root.path("limit").asInt(defaultLimit), 0, 128);
        }
      } catch (Exception ignored) {
      }
      return defaultLimit;
    }
    try {
      return boundedInt(Integer.parseInt(trimmed), 0, 128);
    } catch (NumberFormatException ignored) {
      return defaultLimit;
    }
  }

  private List<ConversationDurableRecord> filterDurables(String threadId, List<ConversationDurableRecord> records, DurablesRequest request) {
    if (records == null || records.isEmpty()) {
      return List.of();
    }
    String view = normalizeDurableView(request.view());
    if ("all".equals(view)) {
      return List.copyOf(records);
    }
    List<ConversationDurableRecord> filtered = new ArrayList<>();
    Instant cutoff = Instant.now().minus(Math.max(1, request.finalizedDays()), ChronoUnit.DAYS);
    for (ConversationDurableRecord record : records) {
      if (record == null) {
        continue;
      }
      if ("active".equals(view)) {
        if (record.status() == ConversationDurableStatus.OPEN) {
          filtered.add(record);
        }
        continue;
      }
      if (record.status() != ConversationDurableStatus.OPEN && !record.capturedAt().isBefore(cutoff)) {
        filtered.add(record);
      }
    }
    LOG.info("[ContextAgent][Durable] durables filtered threadId={} view={} finalizedDays={} inputCount={} outputCount={}",
        threadId, view, request.finalizedDays(), records.size(), filtered.size());
    return List.copyOf(filtered);
  }

  private static String emptyDurableMessage(DurablesRequest request) {
    String view = normalizeDurableView(request.view());
    return switch (view) {
      case "finalized" -> "No finalized durable reminders or tasks found for this thread in the last " + request.finalizedDays() + " days.";
      case "all" -> "No durable reminders or tasks found for this thread.";
      default -> "No active durable reminders or tasks found for this thread.";
    };
  }

  private static String normalizeDurableView(String value) {
    String normalized = safeTrim(value).toLowerCase(java.util.Locale.ROOT);
    return switch (normalized) {
      case "finalized", "closed", "done", "completed", "history" -> "finalized";
      case "all", "any" -> "all";
      default -> "active";
    };
  }

  private static int readDurableDays(JsonNode root) {
    int days = root.path("days").asInt(-1);
    if (days > 0) {
      return days;
    }
    days = root.path("finalizedDays").asInt(-1);
    if (days > 0) {
      return days;
    }
    days = root.path("recentDays").asInt(-1);
    if (days > 0) {
      return days;
    }
    String window = safeTrim(root.path("window").asText(""));
    return switch (window.toLowerCase(java.util.Locale.ROOT)) {
      case "week", "1w", "lastweek", "last-week" -> 7;
      case "month", "30d", "lastmonth", "last-month" -> 30;
      default -> 30;
    };
  }

  private static int boundedInt(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private static boolean looksLikeJson(String value) {
    return value.startsWith("{") && value.endsWith("}");
  }

  private static String requestBody(CommandContext context) {
    AiatpRequest request = context == null ? null : context.getAiatpRequest();
    if (request == null || request.getBody() == null) {
      return "";
    }
    String body = AiatpIO.bodyToString(request.getBody(), StandardCharsets.UTF_8);
    return body == null ? "" : body.trim();
  }

  private static void complete(CommandContext context, int statusCode, String reason, String body) {
    if (context == null) {
      return;
    }
    AiatpIO.Headers headers = new AiatpIO.Headers();
    headers.set("Content-Type", "application/json; charset=utf-8");
    context.complete(AiatpResponse.builder()
        .statusCode(statusCode)
        .reasonPhrase(safeTrim(reason).isEmpty() ? "completed" : safeTrim(reason))
        .headers(headers)
        .body(AiatpIO.Body.ofString(body == null ? "" : body, StandardCharsets.UTF_8))
        .build());
  }

  private static void completeText(CommandContext context, int statusCode, String reason, String body) {
    if (context == null) {
      return;
    }
    AiatpIO.Headers headers = new AiatpIO.Headers();
    headers.set("Content-Type", "text/plain; charset=utf-8");
    context.complete(AiatpResponse.builder()
        .statusCode(statusCode)
        .reasonPhrase(safeTrim(reason).isEmpty() ? "completed" : safeTrim(reason))
        .headers(headers)
        .body(AiatpIO.Body.ofString(body == null ? "" : body, StandardCharsets.UTF_8))
        .build());
  }

  private static void completeJson(CommandContext context, int statusCode, String reason, Map<String, Object> payload) {
    if (context == null) {
      return;
    }
    AiatpIO.Headers headers = new AiatpIO.Headers();
    headers.set("Content-Type", "application/json; charset=utf-8");
    context.complete(AiatpResponse.builder()
        .statusCode(statusCode)
        .reasonPhrase(safeTrim(reason).isEmpty() ? "completed" : safeTrim(reason))
        .headers(headers)
        .body(AiatpIO.Body.ofString(jsonStatic(payload), StandardCharsets.UTF_8))
        .build());
  }

  private static void completeHtml(CommandContext context, int statusCode, String reason, String body) {
    if (context == null) {
      return;
    }
    AiatpIO.Headers headers = new AiatpIO.Headers();
    headers.set("Content-Type", "text/html; charset=utf-8");
    context.complete(AiatpResponse.builder()
        .statusCode(statusCode)
        .reasonPhrase(safeTrim(reason).isEmpty() ? "completed" : safeTrim(reason))
        .headers(headers)
        .body(AiatpIO.Body.ofString(body == null ? "" : body, StandardCharsets.UTF_8))
        .build());
  }

  private String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      return "{\"ok\":false,\"message\":\"Failed to serialize response.\"}";
    }
  }

  private static String jsonStatic(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      return "{\"ok\":false,\"message\":\"Failed to serialize response.\"}";
    }
  }

  private static String safeTrim(String value) {
    return value == null ? "" : value.trim();
  }

  private static String firstNonBlank(String first, String second) {
    String trimmed = safeTrim(first);
    return trimmed.isEmpty() ? safeTrim(second) : trimmed;
  }

  private static String resolveRequestHost(AiatpRequest request) {
    if (request == null || request.getHeaders() == null) {
      return "";
    }
    String host = request.getHeaders().getFirst("Host");
    return host == null ? "" : host.trim();
  }

  private String resolveThreadId(CommandContext context) {
    if (context == null) {
      return "default";
    }
    String threadId = safeTrim(context.getId());
    return threadId.isEmpty() ? "default" : threadId;
  }

  private List<String> loadThreadHistory(String threadId, String subjectId, int limit) {
    List<ConversationTurnRecord> records = workspace.readConversationHistory(threadId, limit);
    if (records.isEmpty()) {
      return List.of();
    }
    List<String> lines = new ArrayList<>();
    for (ConversationTurnRecord record : records) {
      StringBuilder line = new StringBuilder();
      line.append(record.role()).append(": ").append(record.text());
      if (!safeTrim(record.subjectId()).isEmpty()) {
        line.append(" [subject=").append(record.subjectId()).append("]");
      } else if (!safeTrim(subjectId).isEmpty()) {
        line.append(" [subject=").append(subjectId).append("]");
      }
      if (!safeTrim(record.branchId()).isEmpty()) {
        line.append(" [branch=").append(record.branchId()).append("]");
      }
      lines.add(line.toString());
    }
    return List.copyOf(lines);
  }

  private List<String> loadThreadDurables(String threadId, String subjectId, int limit) {
    List<ConversationDurableRecord> records = workspace.readConversationDurables(threadId, limit);
    if (records.isEmpty()) {
      return List.of();
    }
    List<String> lines = new ArrayList<>();
    for (ConversationDurableRecord record : records) {
      StringBuilder line = new StringBuilder();
      line.append(record.kind()).append(": ").append(record.content());
      if (!safeTrim(record.subjectId()).isBlank()) {
        line.append(" [subject=").append(record.subjectId()).append("]");
      } else if (!safeTrim(subjectId).isBlank()) {
        line.append(" [subject=").append(subjectId).append("]");
      }
      if (!safeTrim(record.branchId()).isBlank()) {
        line.append(" [branch=").append(record.branchId()).append("]");
      }
      line.append(" [status=").append(record.status()).append("]");
      lines.add(line.toString());
    }
    return List.copyOf(lines);
  }

  private static List<String> mergeRecentInteractions(List<String> threadHistory, List<String> requestRecentInteractions) {
    List<String> merged = new ArrayList<>();
    if (threadHistory != null) {
      for (String item : threadHistory) {
        if (!safeTrim(item).isBlank()) {
          merged.add(safeTrim(item));
        }
      }
    }
    if (requestRecentInteractions != null) {
      for (String item : requestRecentInteractions) {
        if (!safeTrim(item).isBlank()) {
          merged.add(safeTrim(item));
        }
      }
    }
    return List.copyOf(merged);
  }

  private void persistTurn(String threadId, String subjectId, String branchId, String userMessage, String assistantMessage) {
    Instant now = Instant.now();
    workspace.appendConversationTurn(threadId, ConversationTurnRecord.user(threadId, userMessage, subjectId, branchId, now));
    workspace.appendConversationTurn(threadId, ConversationTurnRecord.assistant(threadId, assistantMessage, subjectId, branchId, now));
  }

  private void persistDurableItem(String threadId,
                                  String subjectId,
                                  String branchId,
                                  String userMessage,
                                  ConversationDurableKind kind,
                                  String durableContent) {
    if (kind == null) {
      return;
    }
    String content = safeTrim(durableContent);
    if (content.isBlank()) {
      content = safeTrim(userMessage);
    }
    Instant now = Instant.now();
    ConversationDurableRecord record = switch (kind) {
      case REMINDER -> ConversationDurableRecord.reminder(
          nextDurableId(),
          threadId,
          subjectId,
          branchId,
          content,
          safeTrim(userMessage),
          now,
          now
      );
      case TASK -> ConversationDurableRecord.task(
          nextDurableId(),
          threadId,
          subjectId,
          branchId,
          content,
          safeTrim(userMessage),
          now,
          now
      );
    };
    workspace.appendConversationDurable(threadId, record);
  }

  private static String formatDurableRecord(ConversationDurableRecord record) {
    StringBuilder out = new StringBuilder();
    out.append("- [").append(record.kind()).append("] ");
    out.append("[id=").append(record.recordId()).append("] ");
    out.append(record.content());
    out.append(" (status=").append(record.status()).append(")");
    if (!safeTrim(record.subjectId()).isBlank()) {
      out.append(" [subject=").append(record.subjectId()).append("]");
    }
    if (!safeTrim(record.branchId()).isBlank()) {
      out.append(" [branch=").append(record.branchId()).append("]");
    }
    if (!safeTrim(record.sourceText()).isBlank()) {
      out.append("\n  source: ").append(record.sourceText());
    }
    return out.toString();
  }

  private static Map<String, Object> durableRecordMap(ConversationDurableRecord record) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("recordId", record.recordId());
    out.put("kind", record.kind().name());
    out.put("status", record.status().name());
    out.put("threadId", record.threadId());
    out.put("subjectId", record.subjectId());
    out.put("branchId", record.branchId());
    out.put("content", record.content());
    out.put("sourceText", record.sourceText());
    out.put("occurredAt", record.occurredAt().toString());
    out.put("capturedAt", record.capturedAt().toString());
    return out;
  }

  private DurableResolution resolveDurableRecord(LLMClient llm, String threadId, String target, List<ConversationDurableRecord> records) {
    if (llm == null) {
      LOG.warn("[ContextAgent][Durable] resolve skipped threadId={} target={} reason=no_llm", threadId, target);
      return DurableResolution.none();
    }
    if (safeTrim(target).isBlank()) {
      LOG.warn("[ContextAgent][Durable] resolve target empty threadId={}", threadId);
      return DurableResolution.none();
    }
    List<ConversationDurableRecord> safeRecords = records == null ? List.of() : records;
    LOG.info("[ContextAgent][Durable] resolve llm start threadId={} target={} recordCount={}", threadId, target, safeRecords.size());
    try {
      String rawResponse = llm.chat(
          DURABLE_SELECTION_SYSTEM_PROMPT,
          target,
          buildDurableSelectionContextJson(threadId, target, safeRecords)
      );
      DurableSelectionResponse selection = parseDurableSelectionResponse(rawResponse);
      LOG.info("[ContextAgent][Durable] resolve llm response threadId={} target={} recordId={} ambiguous={} reason={}",
          threadId, target, selection.recordId(), selection.ambiguous(), selection.reason());
      if (selection.ambiguous()) {
        return DurableResolution.ambiguous(selection.reason());
      }
      String recordId = safeTrim(selection.recordId());
      if (recordId.isBlank()) {
        LOG.warn("[ContextAgent][Durable] resolve llm none threadId={} target={}", threadId, target);
        return DurableResolution.none();
      }
      for (ConversationDurableRecord record : safeRecords) {
        if (Objects.equals(record.recordId(), recordId)) {
          LOG.info("[ContextAgent][Durable] resolve llm exact threadId={} target={} recordId={} status={}", threadId, target, record.recordId(), record.status());
          return DurableResolution.resolved(record);
        }
      }
      LOG.warn("[ContextAgent][Durable] resolve llm record missing threadId={} target={} recordId={}", threadId, target, recordId);
      return DurableResolution.none();
    } catch (Exception e) {
      LOG.warn("[ContextAgent][Durable] resolve llm failed threadId={} target={} error={}", threadId, target, e.getMessage());
      return DurableResolution.none();
    }
  }

  private static ConversationDurableStatus parseDurableStatus(String value) {
    String normalized = safeTrim(value).toLowerCase(java.util.Locale.ROOT);
    return switch (normalized) {
      case "open", "todo", "pending" -> ConversationDurableStatus.OPEN;
      case "done", "completed", "complete", "finished" -> ConversationDurableStatus.DONE;
      case "canceled", "cancelled", "cancel" -> ConversationDurableStatus.CANCELED;
      default -> null;
    };
  }

  private LLMClient resolveSystemLlm(CommandContext context) {
    if (llmResolver != null) {
      return llmResolver.apply(context);
    }
    AgentContext agentContext = new AgentContext();
    if (context != null) {
      context.bindAgentLlmRegistry(agentContext);
    }
    return agentContext.systemLlm()
        .map(instance -> instance.client())
        .orElseGet(() -> safeTrim(openApiKey).isBlank() ? null : new OpenAiLLM(openApiKey, "gpt-4.1-mini"));
  }

  private ConversationReply invokeConversationLlm(LLMClient llm, ContextConversationTurn turn, String userMessage, String durableOutcome) {
    String contextJson = buildConversationContextJson(turn, userMessage, durableOutcome);
    try {
      String rawResponse = llm.chat(RESPONSE_SYSTEM_PROMPT + "\n\n" + turn.prompt().renderedPrompt(), userMessage, contextJson);
      String reply = extractReplyText(rawResponse);
      return new ConversationReply(reply, llm.getClass().getSimpleName(), rawResponse);
    } catch (Exception e) {
      String fallback = "I’m sorry, I could not complete that turn right now.";
      return new ConversationReply(fallback, llm.getClass().getSimpleName(), "{\"reply\":\"" + escapeJson(fallback) + "\"}");
    }
  }

  private String buildConversationContextJson(ContextConversationTurn turn, String userMessage, String durableOutcome) {
    try {
      java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
      payload.put("userMessage", safeTrim(userMessage));
      payload.put("state", java.util.Map.of(
          "mode", turn.state().mode().name(),
          "subjectId", turn.state().subjectId(),
          "branchId", turn.state().branchId()
      ));
      payload.put("classification", java.util.Map.of(
          "kind", turn.classification().kind().name(),
          "confidence", turn.classification().confidence(),
          "branchSignalKind", turn.classification().branchSignalKind().name(),
          "subjectSwitchSuggested", turn.classification().subjectSwitchSuggested(),
          "confirmationRequired", turn.classification().confirmationRequired(),
          "candidateSubject", turn.classification().candidateSubject(),
          "rationale", turn.classification().rationale()
      ));
      payload.put("confirmation", java.util.Map.of(
          "required", turn.confirmationRequired(),
          "message", turn.confirmationMessage()
      ));
      payload.put("promptSections", turn.prompt().sections());
      payload.put("durableOutcome", safeTrim(durableOutcome));
      return JSON.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      return "{\"userMessage\":\"" + escapeJson(userMessage) + "\",\"durableOutcome\":\"" + escapeJson(durableOutcome) + "\"}";
    }
  }

  private DurableDirective resolveDurableDirective(LLMClient llm,
                                                   String threadId,
                                                   ContextConversationTurn turn,
                                                   String userMessage,
                                                   List<ConversationDurableRecord> currentDurables) {
    if (llm == null) {
      return DurableDirective.none();
    }
    try {
      String rawResponse = llm.chat(
          DURABLE_DIRECTIVE_SYSTEM_PROMPT,
          safeTrim(userMessage),
          buildDurableDirectiveContextJson(threadId, turn, userMessage, currentDurables)
      );
      DurableDirective directive = parseDurableDirective(rawResponse);
      LOG.info("[ContextAgent][Durable] directive threadId={} action={} kind={} target={} status={} reason={}",
          threadId, directive.action(), directive.kind(), directive.target(), directive.status(), directive.reason());
      return directive;
    } catch (Exception e) {
      LOG.warn("[ContextAgent][Durable] directive failed threadId={} error={}", threadId, e.getMessage());
      return DurableDirective.none();
    }
  }

  private DurableOutcome applyDurableDirective(LLMClient llm,
                                               String threadId,
                                               ContextRuntimeState state,
                                               String userMessage,
                                               DurableDirective directive,
                                               List<ConversationDurableRecord> currentDurables) {
    if (directive == null || directive.isNone()) {
      return DurableOutcome.none();
    }
    String subjectId = state == null ? "" : state.subjectId();
    String branchId = state == null ? "" : state.branchId();
    if (directive.isCreate()) {
      ConversationDurableKind kind = parseDurableKind(directive.kind());
      if (kind == null) {
        LOG.warn("[ContextAgent][Durable] directive create rejected threadId={} reason=invalid_kind kind={}", threadId, directive.kind());
        return DurableOutcome.none();
      }
      String content = safeTrim(directive.content());
      if (content.isBlank()) {
        content = safeTrim(userMessage);
      }
      persistDurableItem(threadId, subjectId, branchId, userMessage, kind, content);
      LOG.info("[ContextAgent][Durable] directive create applied threadId={} kind={} content={}", threadId, kind, content);
      return DurableOutcome.created("created durable kind=" + kind + " content=" + content);
    }
    if (directive.isUpdate()) {
      ConversationDurableStatus status = parseDurableStatus(directive.status());
      if (status == null) {
        LOG.warn("[ContextAgent][Durable] directive update rejected threadId={} target={} reason=invalid_status status={}", threadId, directive.target(), directive.status());
        return DurableOutcome.none();
      }
      DurableResolution resolution = resolveDurableRecord(llm, threadId, directive.target(), currentDurables);
      if (resolution.record() == null || resolution.ambiguous()) {
        LOG.warn("[ContextAgent][Durable] directive update unresolved threadId={} target={} reason={}", threadId, directive.target(),
            resolution.ambiguous() ? resolution.message() : "no_match");
        return DurableOutcome.none();
      }
      ConversationDurableRecord updated = workspace.updateConversationDurableStatus(threadId, resolution.record().recordId(), status, Instant.now());
      if (updated == null) {
        LOG.warn("[ContextAgent][Durable] directive update failed threadId={} recordId={} target={}", threadId, resolution.record().recordId(), directive.target());
        return DurableOutcome.none();
      }
      List<ConversationDurableRecord> refreshed = workspace.readConversationDurables(threadId, 50);
      boolean verified = refreshed.stream().anyMatch(record ->
          Objects.equals(record.recordId(), updated.recordId()) && record.status() == status
      );
      LOG.info("[ContextAgent][Durable] directive update verified threadId={} recordId={} status={} verified={} refreshedCount={}",
          threadId, updated.recordId(), status, verified, refreshed.size());
      if (!verified) {
        return DurableOutcome.changed("update attempted but verification failed for record " + updated.recordId());
      }
      return DurableOutcome.changed("updated durable record " + updated.recordId() + " to " + status);
    }
    return DurableOutcome.none();
  }

  private static String buildDurableDirectiveContextJson(String threadId,
                                                         ContextConversationTurn turn,
                                                         String userMessage,
                                                         List<ConversationDurableRecord> currentDurables) {
    try {
      java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
      payload.put("threadId", threadId);
      payload.put("userMessage", safeTrim(userMessage));
      payload.put("state", java.util.Map.of(
          "mode", turn.state().mode().name(),
          "subjectId", turn.state().subjectId(),
          "branchId", turn.state().branchId()
      ));
      payload.put("classification", java.util.Map.of(
          "kind", turn.classification().kind().name(),
          "confidence", turn.classification().confidence(),
          "branchSignalKind", turn.classification().branchSignalKind().name(),
          "subjectSwitchSuggested", turn.classification().subjectSwitchSuggested(),
          "confirmationRequired", turn.classification().confirmationRequired(),
          "candidateSubject", turn.classification().candidateSubject(),
          "rationale", turn.classification().rationale()
      ));
      List<Map<String, Object>> records = new ArrayList<>();
      if (currentDurables != null) {
        for (ConversationDurableRecord record : currentDurables) {
          records.add(durableRecordMap(record));
        }
      }
      payload.put("durables", records);
      return JSON.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      return "{\"threadId\":\"" + escapeJson(threadId) + "\",\"userMessage\":\"" + escapeJson(userMessage) + "\"}";
    }
  }

  private static String buildDurableSelectionContextJson(String threadId,
                                                         String target,
                                                         List<ConversationDurableRecord> currentDurables) {
    try {
      java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
      payload.put("threadId", threadId);
      payload.put("target", safeTrim(target));
      List<Map<String, Object>> records = new ArrayList<>();
      if (currentDurables != null) {
        for (ConversationDurableRecord record : currentDurables) {
          records.add(durableRecordMap(record));
        }
      }
      payload.put("durables", records);
      return JSON.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      return "{\"threadId\":\"" + escapeJson(threadId) + "\",\"target\":\"" + escapeJson(target) + "\"}";
    }
  }

  private static DurableDirective parseDurableDirective(String rawResponse) {
    String trimmed = safeTrim(rawResponse);
    if (trimmed.isEmpty()) {
      return DurableDirective.none();
    }
    try {
      JsonNode root = JSON.readTree(trimmed);
      if (root == null || !root.isObject()) {
        return DurableDirective.none();
      }
      String action = firstNonBlank(root.path("action").asText(""), root.path("op").asText(""));
      String kind = firstNonBlank(root.path("kind").asText(""), root.path("durableKind").asText(""));
      String target = firstNonBlank(root.path("target").asText(""), root.path("recordId").asText(""));
      String status = firstNonBlank(root.path("status").asText(""), root.path("state").asText(""));
      String content = firstNonBlank(root.path("content").asText(""), root.path("text").asText(""));
      String reason = firstNonBlank(root.path("reason").asText(""), root.path("message").asText(""));
      return new DurableDirective(action, kind, target, status, content, reason);
    } catch (Exception e) {
      return DurableDirective.none();
    }
  }

  private static DurableSelectionResponse parseDurableSelectionResponse(String rawResponse) {
    String trimmed = safeTrim(rawResponse);
    if (trimmed.isEmpty()) {
      return DurableSelectionResponse.none();
    }
    try {
      JsonNode root = JSON.readTree(trimmed);
      if (root == null || !root.isObject()) {
        return DurableSelectionResponse.none();
      }
      String recordId = firstNonBlank(root.path("recordId").asText(""), root.path("id").asText(""));
      boolean ambiguous = root.path("ambiguous").asBoolean(false);
      String reason = firstNonBlank(root.path("reason").asText(""), root.path("message").asText(""));
      return new DurableSelectionResponse(recordId, ambiguous, reason);
    } catch (Exception e) {
      return DurableSelectionResponse.none();
    }
  }

  private static ConversationDurableKind parseDurableKind(String value) {
    String normalized = safeTrim(value).toUpperCase(java.util.Locale.ROOT);
    try {
      return ConversationDurableKind.valueOf(normalized);
    } catch (Exception e) {
      return null;
    }
  }

  private String extractReplyText(String rawResponse) {
    String trimmed = safeTrim(rawResponse);
    if (trimmed.isEmpty()) {
      return "";
    }
    try {
      JsonNode root = JSON.readTree(trimmed);
      if (root != null && root.isObject()) {
        String reply = firstNonBlank(root.path("reply").asText(""), root.path("message").asText(""));
        if (!reply.isBlank()) {
          return reply;
        }
      }
    } catch (Exception ignored) {
    }
    return trimmed;
  }

  private static String loadOpenApiKey(Storage storage) {
    if (storage == null) {
      return "";
    }
    try {
      byte[] envBytes = storage.readFile(".env");
      if (envBytes == null || envBytes.length == 0) {
        return "";
      }
      return Util.parseDotenv(envBytes).getOrDefault("OPENAI_API_KEY", "");
    } catch (IOException | RuntimeException e) {
      return "";
    }
  }

  private static String escapeJson(String value) {
    String s = value == null ? "" : value;
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
  }

  private static Path defaultWorkspaceRoot() {
    return Path.of(System.getProperty("user.home"), ".todero", "data", "state", "agent-context");
  }

  private static String nextDurableId() {
    return java.util.UUID.randomUUID().toString();
  }

  private static String loadResourceText(String path) {
    try (var in = ContextAgentComponent.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("Resource not found: " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load resource: " + path, e);
    }
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

  private record ConversationReply(String reply, String provider, String rawResponse) {
    private ConversationReply {
      reply = safeTrim(reply);
      provider = safeTrim(provider);
      rawResponse = rawResponse == null ? "" : rawResponse.trim();
    }
  }

  private record DurableDirective(String action, String kind, String target, String status, String content, String reason) {
    private DurableDirective {
      action = safeTrim(action).toLowerCase(java.util.Locale.ROOT);
      kind = safeTrim(kind).toUpperCase(java.util.Locale.ROOT);
      target = safeTrim(target);
      status = safeTrim(status).toLowerCase(java.util.Locale.ROOT);
      content = safeTrim(content);
      reason = safeTrim(reason);
    }

    private static DurableDirective none() {
      return new DurableDirective("none", "", "", "", "", "");
    }

    private boolean isCreate() {
      return "create".equals(action);
    }

    private boolean isUpdate() {
      return "update".equals(action);
    }

    private boolean isNone() {
      return !isCreate() && !isUpdate();
    }
  }

  private record DurableSelectionResponse(String recordId, boolean ambiguous, String reason) {
    private DurableSelectionResponse {
      recordId = safeTrim(recordId);
      reason = safeTrim(reason);
    }

    private static DurableSelectionResponse none() {
      return new DurableSelectionResponse("", false, "");
    }
  }

  private record DurableOutcome(boolean changed, String summary) {
    private DurableOutcome {
      summary = safeTrim(summary);
    }

    private static DurableOutcome none() {
      return new DurableOutcome(false, "");
    }

    private static DurableOutcome created(String summary) {
      return new DurableOutcome(true, summary);
    }

    private static DurableOutcome changed(String summary) {
      return new DurableOutcome(true, summary);
    }
  }

  private record DurableSetRequest(String target, String status, boolean refresh, int limit) {
    private DurableSetRequest {
      target = safeTrim(target);
      status = safeTrim(status);
      limit = Math.max(0, limit);
    }

    private static DurableSetRequest empty() {
      return new DurableSetRequest("", "", false, 20);
    }
  }

  private record DurableResolution(ConversationDurableRecord record, boolean ambiguous, String message) {
    private static DurableResolution resolved(ConversationDurableRecord record) {
      return new DurableResolution(record, false, "");
    }

    private static DurableResolution ambiguous(String message) {
      return new DurableResolution(null, true, safeTrim(message));
    }

    private static DurableResolution none() {
      return new DurableResolution(null, false, "");
    }
  }

  private record DurablesRequest(int limit, boolean json, String view, int finalizedDays) {
    private DurablesRequest {
      limit = Math.max(0, limit);
      view = normalizeDurableView(view);
      finalizedDays = Math.max(1, finalizedDays);
    }

    private static DurablesRequest empty() {
      return new DurablesRequest(20, false, "active", 30);
    }
  }

  private record ProcessRequest(
      String subjectId,
      String message,
      List<String> recentInteractions,
      List<String> draftCandidates,
      boolean includeMetadata,
      int maxRaw,
      int maxDerived,
      int maxRemembered,
      int gitHintsLimit
  ) {
    private ProcessRequest {
      subjectId = safeTrim(subjectId);
      message = safeTrim(message);
      recentInteractions = List.copyOf(recentInteractions == null ? List.of() : recentInteractions);
      draftCandidates = List.copyOf(draftCandidates == null ? List.of() : draftCandidates);
      includeMetadata = includeMetadata;
      maxRaw = Math.max(0, maxRaw);
      maxDerived = Math.max(0, maxDerived);
      maxRemembered = Math.max(0, maxRemembered);
      gitHintsLimit = Math.max(0, gitHintsLimit);
    }

    private static ProcessRequest empty() {
      return new ProcessRequest("", "", List.of(), List.of(), false, DEFAULT_MAX_RAW, DEFAULT_MAX_DERIVED, DEFAULT_MAX_REMEMBERED, DEFAULT_GIT_HINTS_LIMIT);
    }
  }
}
