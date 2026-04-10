package com.shellaia.agent.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.ai.llm.LLMClient;
import com.social100.todero.common.ai.llm.OpenAiLLM;
import com.social100.todero.common.aiatpio.AiatpEvent;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpIORequestWrapper;
import com.social100.todero.common.aiatpio.AiatpRequest;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.aiatpio.AiatpResponse;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.model.component.ComponentDescriptor;
import com.social100.todero.common.routing.AgentCapabilityManifest;
import com.social100.todero.common.routing.AgentCommandSchema;
import com.social100.todero.common.storage.Storage;
import com.social100.todero.console.base.OutputType;
import com.social100.todero.processor.EventDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.social100.todero.common.config.Util.parseDotenv;

@AIAController(name = "com.shellaia.agent.router",
    type = ServerType.AI,
    visible = true,
    description = "Dynamic router agent that selects and delegates to available internal agents",
    events = RouterAgentComponent.RouterEvent.class,
    capabilityProvider = RouterAgentCapabilities.class)
public class RouterAgentComponent {
  private static final String MAIN_GROUP = "Main";
  private static final String ROUTER_NAME = "com.shellaia.agent.router";
  private static final String UPSTREAM_CONTROL_HEADER = "X-AIATP-Upstream-Control";
  private static final long DELEGATE_TIMEOUT_SECONDS = 30;
  private static final long ROUTE_TTL_MS = 30 * 60 * 1000L;
  private static final Pattern ARG_PATTERN = Pattern.compile("--[a-zA-Z0-9-]+");
  private static final Pattern AUTH_COMPLETE_PREFIX = Pattern.compile("^\\s*auth-complete\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern STICKY_RESET_PREFIX = Pattern.compile(
      "^\\s*(?:go back to router|back to router|return to router|router reset|reset stickiness|reset sticky|clear sticky|"
          + "volver al router|regresar al router|reiniciar stickiness|resetear stickiness|salir del agente)\\b\\s*[:,-]?\\s*(.*)$",
      Pattern.CASE_INSENSITIVE);
  private static final Set<String> FAILURE_OUTCOMES = Set.of("unhandled_intent", "retry_by_router");
  private static final Set<String> FAILURE_ERROR_CODES = Set.of("agent_capability_mismatch", "agent_missing_args", "unsupported_operation");

  private final ObjectMapper mapper = new ObjectMapper();
  private final String openApiKey;
  private final String baseSystemPrompt;
  private final ConcurrentHashMap<String, StickyRoute> stickyBySession = new ConcurrentHashMap<>();

  public RouterAgentComponent(Storage storage) {
    this.baseSystemPrompt = loadSystemPrompt("prompts/default-system-prompt.md");
    String key;
    try {
      byte[] envBytes = storage.readFile(".env");
      Map<String, String> env = parseDotenv(envBytes);
      key = env.getOrDefault("OPENAI_API_KEY", "");
    } catch (IOException e) {
      key = "";
    }
    this.openApiKey = key;
  }

  @Action(group = MAIN_GROUP,
      command = "process",
      description = "Route a user prompt to the best available internal agent")
  public Boolean process(CommandContext context) {
    String prompt = requestBody(context);
    if (prompt.isEmpty()) {
      context.emitChat("Prompt is required. Usage: process <text>", "progress");
      return true;
    }

    pruneExpiredRoutes();
    String sessionId = safe(context.getId());
    StickyResetDirective stickyResetDirective = parseStickyResetDirective(prompt);
    if (stickyResetDirective.resetRequested()) {
      stickyBySession.remove(sessionId);
      if (stickyResetDirective.remainingPrompt().isBlank()) {
        context.complete(wireTextResponse("chat", "success", "sticky_cleared",
            "Sticky session cleared. Routing will restart on the next prompt.", null, null, null));
        return true;
      }
      prompt = stickyResetDirective.remainingPrompt();
      System.out.println("[ROUTER-AGENT] sticky reset requested; continuing with fresh prompt=" + prompt);
    }
    boolean opaqueAuthRelay = isOpaqueAuthRelayPrompt(prompt);
    System.out.println("[ROUTER-AGENT] process start session=" + sessionId + " prompt=" + prompt);

    boolean rerouteAttempted = false;
    Set<String> excludedAgents = new HashSet<>();
    while (true) {
      List<AgentCapability> agents = discoverAgents(context);
      if (agents.isEmpty()) {
        context.complete(wireTextResponse(
            "error",
            "failure",
            "unhandled_intent",
            "No routable agent found in runtime. Agents must expose routingHints.skillSummary.",
            "unhandled_intent",
            Boolean.FALSE,
            null
        ));
        return true;
      }

      StickyRoute sticky = stickyBySession.get(sessionId);
      RouteDecision decision = opaqueAuthRelay
          ? decideOpaqueRelayRoute(prompt, sticky, agents, excludedAgents)
          : decideRoute(prompt, sticky, agents, excludedAgents);
      System.out.println("[ROUTER-AGENT] decision route=" + decision.route + " reason=" + decision.reason + " switched=" + decision.switched);
      context.emitChat("route=" + safe(decision.route)
          + " reason=" + safe(decision.reason)
          + " switched=" + decision.switched, "progress");
      if (decision.route == null || decision.route.isBlank()) {
        context.emitChat("Could not determine target agent.", "progress");
        return true;
      }

      AgentCapability selectedAgent = findAgent(agents, decision.route);
      System.out.println("[ROUTER-AGENT] selected agent=" + (selectedAgent == null ? "null" : selectedAgent.name()));
      PreDispatchResult preDispatch = PreDispatchResult.allow(prompt, opaqueAuthRelay ? "opaque-auth-relay" : "generic-runtime-skill-routing");
      if (!preDispatch.allowed) {
        context.emitChat(preDispatch.message, "progress");
        return true;
      }

      String delegatedPrompt = preDispatch.delegatedPrompt;
      System.out.println("[ROUTER-AGENT] delegating to " + decision.route + " prompt=" + delegatedPrompt);
      DelegatedAgentResult delegated = delegateToAgent(context, decision.route, "process", delegatedPrompt);
      if (delegated == null) {
        System.out.println("[ROUTER-AGENT] no response from agent=" + decision.route);
        context.complete(wireTextResponse("error", "failure", "delegate_failed",
            "Agent did not return a response.", "delegate_failed", Boolean.FALSE, null));
        return true;
      }

      String delegatedBody = delegated.body();
      System.out.println("[ROUTER-AGENT] received response body=" + delegatedBody.substring(0, Math.min(256, delegatedBody.length())));
      AiatpResponse delegatedResponse = delegated.response();
      JsonNode delegatedJson = extractFirstJsonBlock(delegatedBody);
      String delegatedErrorCode = readPath(delegatedJson.path("meta"), "errorCode");
      boolean delegatedReroutable = false;
      if (opaqueAuthRelay) {
        System.out.println("[ROUTER-AGENT] opaque auth relay route=" + decision.route);
        stickyBySession.put(sessionId, updateSticky(decision.route, delegatedPrompt, delegatedJson, sticky));
        if (delegatedResponse != null) {
          context.complete(delegatedResponse);
        } else {
          context.complete(wireTextResponse("error", "failure", "delegate_failed",
              "Delegated agent did not return a response.", "delegate_failed", Boolean.FALSE, null));
        }
        return true;
      }

      if (delegatedReroutable || indicatesFailureSignal(delegatedJson) || FAILURE_ERROR_CODES.contains(delegatedErrorCode)) {
        excludedAgents.add(decision.route);
        stickyBySession.remove(sessionId);
        if (excludeAgents(agents, excludedAgents).isEmpty()) {
          context.complete(wireTextResponse("error", "failure", "unhandled_intent",
              "No available agent can handle this request.", "unhandled_intent", Boolean.FALSE, null));
          return true;
        }
        rerouteAttempted = true;
        context.emitChat("Rerouting to another agent.", "progress");
        context.emitChat("reroute=true reason=reroutable_failure agent=" + safe(decision.route), "progress");
        System.out.println("[ROUTER-AGENT] fallback triggered by "
            + readPath(delegatedJson.path("meta"), "errorCode")
            + "; rerouting prompt: " + prompt);
        continue;
      }

      stickyBySession.put(sessionId, updateSticky(decision.route, delegatedPrompt, delegatedJson, sticky));
      System.out.println("[ROUTER-AGENT] final response reason=" + (rerouteAttempted ? "agent-fallback" : decision.reason));
      if (delegatedResponse != null) {
        context.complete(delegatedResponse);
      } else {
        context.complete(wireTextResponse("error", "failure", "delegate_failed",
            "Delegated agent did not return a response.", "delegate_failed", Boolean.FALSE, null));
      }
      return true;
    }
  }

  @Action(group = MAIN_GROUP,
      command = "capabilities",
      description = "Return router capability manifest for discovery")
  public Boolean capabilities(CommandContext context) {
    AgentCapabilityManifest manifest = new RouterAgentCapabilities().manifest();
    String payload = "{\"manifest\":" + toJson(manifest) + "}";
    context.complete(wireTextResponse("status", "success", "capabilities", payload, null, null, "application/json; charset=utf-8"));
    return true;
  }

  // Wire-only: delegated payloads are forwarded as-is; router does not require JSON "channels" envelopes.

  private static AiatpResponse wireTextResponse(String channel,
                                                String outcome,
                                                String responseReason,
                                                String body,
                                                String errorCode,
                                                Boolean errorReroutable,
                                                String contentType) {
    AiatpIO.Headers headers = new AiatpIO.Headers();
    headers.set("Content-Type", (contentType == null || contentType.isBlank())
        ? "text/plain; charset=utf-8"
        : contentType.trim());
    int statusCode = "failure".equalsIgnoreCase(outcome == null ? "" : outcome.trim()) ? 500 : 200;
    return AiatpResponse.builder()
        .statusCode(statusCode)
        .reasonPhrase(responseReason == null || responseReason.isBlank() ? "completed" : responseReason.trim())
        .headers(headers)
        .body(AiatpIO.Body.ofString(body == null ? "" : body, StandardCharsets.UTF_8))
        .build();
  }

  private RouteDecision decideRoute(String prompt, StickyRoute sticky, List<AgentCapability> agents, Set<String> excludedAgents) {
    List<AgentCapability> candidates = excludeAgents(agents, excludedAgents);
    if (candidates.isEmpty()) {
      return new RouteDecision("", false, "no-eligible-agent", "No eligible agent available.");
    }
    StickyRoute normalizedSticky = normalizeSticky(sticky, candidates);
    if (normalizedSticky != null && containsAgent(candidates, normalizedSticky.agent)) {
      return new RouteDecision(normalizedSticky.agent, false, "sticky-continue", "Continuing with current agent.");
    }

    String intentRoute = routeFromIntent(prompt, candidates);
    if (!intentRoute.isBlank()) {
      boolean switched = normalizedSticky == null || !safe(normalizedSticky.agent).equals(intentRoute);
      String reason = switched ? "intent-switch" : "intent-keep";
      String userMessage = switched ? "Switching to the best matching agent." : "Continuing with current agent.";
      return new RouteDecision(intentRoute, switched, reason, userMessage);
    }

    RouteDecision llmDecision = planWithLlm(prompt, normalizedSticky, candidates);
    if (llmDecision != null && isValidRoute(llmDecision.route, candidates)) {
      boolean switched = normalizedSticky == null || !safe(normalizedSticky.agent).equals(llmDecision.route);
      return new RouteDecision(llmDecision.route, switched, llmDecision.reason, llmDecision.userMessage);
    }

    String fallback = heuristicRoute(prompt, normalizedSticky, candidates);
    boolean switched = normalizedSticky == null || !safe(normalizedSticky.agent).equals(fallback);
    return new RouteDecision(fallback, switched, "heuristic-fallback", "Routing to the best matching agent.");
  }

  private RouteDecision decideOpaqueRelayRoute(String prompt, StickyRoute sticky, List<AgentCapability> agents, Set<String> excludedAgents) {
    List<AgentCapability> candidates = excludeAgents(agents, excludedAgents);
    if (candidates.isEmpty()) {
      return new RouteDecision("", false, "no-eligible-agent", "No eligible agent available.");
    }
    StickyRoute normalizedSticky = normalizeSticky(sticky, candidates);
    if (normalizedSticky != null && containsAgent(candidates, normalizedSticky.agent)) {
      return new RouteDecision(normalizedSticky.agent, false, "opaque-auth-sticky", "Continuing with sticky auth relay agent.");
    }
    List<AgentCapability> authCapable = candidates.stream()
        .filter(AgentCapability::canHandleOpaqueRelay)
        .toList();
    if (authCapable.isEmpty()) {
      return new RouteDecision("", false, "opaque-auth-no-runtime-support", "No available agent declares delegated auth continuation support.");
    }
    String byIntent = routeFromIntent(prompt, authCapable);
    if (!byIntent.isBlank()) {
      return new RouteDecision(byIntent, true, "opaque-auth-skill-match", "Routing auth relay by runtime skill.");
    }
    return new RouteDecision(authCapable.get(0).name, true, "opaque-auth-runtime-fallback", "Routing auth relay to an auth-capable agent.");
  }

  private static boolean containsAnyToken(AgentCapability agent, String... tokens) {
    StringBuilder hay = new StringBuilder();
    hay.append(safe(agent.name)).append(' ')
        .append(safe(agent.label)).append(' ')
        .append(safe(agent.skillSummary)).append(' ');
    for (String keyword : agent.routingKeywords) {
      hay.append(safe(keyword)).append(' ');
    }
    String normalized = hay.toString().toLowerCase(Locale.ROOT);
    for (String token : tokens) {
      if (normalized.contains(token.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  private String routeFromIntent(String prompt, List<AgentCapability> agents) {
    Set<String> promptTokens = tokenize(safe(prompt));
    String bestAgent = "";
    int bestScore = 0;
    for (AgentCapability agent : agents) {
      int score = intentScore(promptTokens, agent);
      if (score > bestScore) {
        bestScore = score;
        bestAgent = agent.name;
      }
    }
    return bestScore > 0 ? bestAgent : "";
  }

  private static String findAgentByAnyToken(List<AgentCapability> agents, List<String> tokens) {
    for (String token : tokens) {
      String hit = findAgentByToken(agents, token);
      if (!hit.isBlank()) {
        return hit;
      }
    }
    return "";
  }

  private static String findAgentByToken(List<AgentCapability> agents, String token) {
    for (AgentCapability a : agents) {
      if (a.name.toLowerCase(Locale.ROOT).contains(token)) {
        return a.name;
      }
    }
    return "";
  }

  private RouteDecision planWithLlm(String prompt, StickyRoute sticky, List<AgentCapability> agents) {
    if (openApiKey == null || openApiKey.isBlank()) {
      return null;
    }
    try {
      String dynamicPrompt = buildDynamicSystemPrompt(sticky, agents);
      LLMClient llm = new OpenAiLLM(openApiKey, "gpt-4.1-mini");
      String raw = llm.chat(dynamicPrompt, prompt, "{}");
      JsonNode root = extractFirstJsonBlock(raw);
      String route = readPath(root, "route");
      String reason = readPath(root, "reason");
      String user = readPath(root, "user");
      boolean switched = root.path("switch").asBoolean(false);
      return new RouteDecision(route, switched, reason, user);
    } catch (Exception e) {
      System.out.println("[ROUTER-AGENT] LLM routing failed: " + e.getMessage());
      return null;
    }
  }

  private String buildDynamicSystemPrompt(StickyRoute sticky, List<AgentCapability> agents) {
    StringBuilder sb = new StringBuilder(baseSystemPrompt);
    sb.append("\n\nRuntime agents:\n");
    for (AgentCapability a : agents) {
      sb.append("- ").append(a.name)
          .append(" skill=").append(a.skillSummary)
          .append(" keywords=").append(a.routingKeywords)
          .append(" authRelay=").append(a.canHandleOpaqueRelay)
          .append("\n");
    }
    if (sticky != null) {
      sb.append("Current sticky agent: ").append(sticky.agent).append("\n");
      sb.append("Last user prompt: ").append(sticky.lastPrompt).append("\n");
    } else {
      sb.append("Current sticky agent: none\n");
    }
    return sb.toString();
  }

  private String heuristicRoute(String prompt, StickyRoute sticky, List<AgentCapability> agents) {
    String normalized = safe(prompt).toLowerCase(Locale.ROOT);
    if (sticky != null && containsAgent(agents, sticky.agent) && !mentionsSwitchWords(normalized)) {
      return sticky.agent;
    }
    String byIntent = routeFromIntent(prompt, agents);
    if (!byIntent.isBlank()) {
      return byIntent;
    }
    if (sticky != null && containsAgent(agents, sticky.agent)) {
      return sticky.agent;
    }
    return agents.get(0).name;
  }

  private DelegatedAgentResult delegateToAgent(CommandContext context,
                                              String agentName,
                                              String command,
                                              String args) {
    AiatpIO.Body body = AiatpIO.Body.ofString(args, StandardCharsets.UTF_8);
    var delegatedRequestBase = AiatpRuntimeAdapter.withHeader(
        AiatpRuntimeAdapter.request("ACTION", "/" + agentName + "/" + command, body),
        UPSTREAM_CONTROL_HEADER,
        "true");
    final var delegatedRequest = AiatpRuntimeAdapter.withHeader(
        delegatedRequestBase,
        CommandContext.HDR_INTERNAL_EVENT_DELIVERY,
        "local");
    System.out.println("[ROUTER-AGENT][TRACE][delegate-request] agent=" + agentName
        + " command=" + command
        + " target=" + safe(delegatedRequest.getTarget())
        + " upstreamControl=" + headerValue(delegatedRequest, UPSTREAM_CONTROL_HEADER)
        + " internalDelivery=" + headerValue(delegatedRequest, CommandContext.HDR_INTERNAL_EVENT_DELIVERY));
    CompletableFuture<DelegatedAgentResult> out = new CompletableFuture<>();
    CommandContext internal = context.toBuilder()
        .aiatpRequest(delegatedRequest)
        .eventConsumer(wrapper -> handleDelegatedEvent(context, wrapper, delegatedRequest))
        .responseConsumer(result -> {
          if (result != null && !out.isDone()) {
            out.complete(new DelegatedAgentResult(result, responseBody(result)));
          }
        })
        .build();
    try {
      context.execute(agentName, command, internal);
      return out.get(DELEGATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      return failureDelegatedResult(delegatedRequest, agentName, "delegate_timeout", null);
    } catch (Exception e) {
      return failureDelegatedResult(delegatedRequest, agentName, "delegate_failed", e.getMessage());
    }
  }

  private void handleDelegatedEvent(CommandContext routerContext,
                                    AiatpIORequestWrapper wrapper,
                                    com.social100.todero.common.aiatpio.AiatpRequest delegatedRequest) {
    if (wrapper == null) {
      return;
    }
    AiatpEvent event = wrapper.getAiatpEvent();
    System.out.println("[ROUTER-AGENT][TRACE][delegate-event] delegatedTarget=" + safe(delegatedRequest.getTarget())
        + " channel=" + safe(event == null ? null : event.getChannel()));
    if (event == null) {
      return;
    }
    if (isControlEvent(event)) {
      forwardDelegatedControlProgress(routerContext, extractFirstJsonBlock(eventBody(event)));
      return;
    }
    forwardDelegatedEvent(routerContext, event);
  }

  private DelegatedAgentResult failureDelegatedResult(com.social100.todero.common.aiatpio.AiatpRequest delegatedRequest,
                                                      String agentName,
                                                      String errorCode,
                                                      String message) {
    String json = failureEnvelopeJson(agentName, errorCode, message);
    AiatpResponse result = AiatpRuntimeAdapter.textResponse(
        500,
        errorCode,
        json,
        "application/json; charset=utf-8");
    return new DelegatedAgentResult(result, responseBody(result));
  }

  private static boolean isHtmlEvent(AiatpEvent event) {
    return event != null && "html".equalsIgnoreCase(safe(event.getChannel()));
  }

  private static boolean isControlEvent(AiatpEvent event) {
    return event != null && "control".equalsIgnoreCase(safe(event.getChannel()));
  }

  private void forwardDelegatedControlProgress(CommandContext context, JsonNode delegatedJson) {
    if (context == null || delegatedJson == null || delegatedJson.isMissingNode()) {
      return;
    }
    String status = readPath(delegatedJson, "channels.status.message");
    if (!status.isBlank()) {
      context.emitChat(status, "progress");
    }
    String thought = readPath(delegatedJson, "channels.thought.message");
    if (!thought.isBlank()) {
      context.emitChat(thought, "progress");
    }
    String chat = readPath(delegatedJson, "channels.chat.message");
    if (!chat.isBlank()) {
      context.emitChat(chat, "progress");
    }
    JsonNode htmlNode = delegatedJson.path("channels").path("html");
    String html = readPath(htmlNode, "html");
    String htmlMode = readPath(htmlNode, "mode");
    boolean htmlReplace = !htmlNode.isMissingNode() && htmlNode.path("replace").asBoolean(true);
    if (!html.isBlank() || "suggestions_from_toolsteps".equalsIgnoreCase(htmlMode)) {
      context.emitHtml(html, "progress", htmlMode.isBlank() ? "html" : htmlMode, htmlReplace);
    }
    JsonNode auth = delegatedJson.path("channels").path("auth");
    if (auth.isObject()) {
      context.emitChat(auth.toString(), "progress");
    }
  }

  private void forwardDelegatedEvent(CommandContext context, AiatpEvent event) {
    if (context == null || event == null) {
      return;
    }
    String emitPhase = "progress";
    String channel = safe(event.getChannel()).toLowerCase(Locale.ROOT);
    switch (channel) {
      case "status" -> context.emitChat(eventBody(event), emitPhase);
      case "thought" -> context.emitChat(eventBody(event), emitPhase);
      case "chat" -> context.emitChat(eventBody(event), emitPhase);
      case "html" -> {
        String mode = firstNonBlank(
            event.getHeaders() == null ? null : event.getHeaders().getFirst("Html-Mode"),
            "html");
        boolean replace = event.getHeaders() == null || !"false".equalsIgnoreCase(safe(event.getHeaders().getFirst("Html-Replace")));
        context.emitHtml(eventBody(event), emitPhase, mode, replace);
      }
      case "auth" -> context.emitChat(eventBody(event), emitPhase);
      case "error" -> context.emitChat(eventBody(event), "error");
      default -> {
      }
    }
  }

  private static String eventBody(AiatpEvent event) {
    if (event == null || event.getBody() == null) {
      return "";
    }
    String body = AiatpIO.bodyToString(event.getBody(), StandardCharsets.UTF_8);
    return body == null ? "" : body;
  }

  private List<AgentCapability> discoverAgents(CommandContext context) {
    Map<String, ComponentDescriptor> descriptorsByName = context.getComponents(true, ServerType.AI).stream()
        .filter(d -> d != null && d.getName() != null)
        .collect(Collectors.toMap(ComponentDescriptor::getName, d -> d, (a, b) -> a, LinkedHashMap::new));
    List<String> agentNames = new ArrayList<>(descriptorsByName.keySet());
    if (context.getAgents() != null) {
      for (String agent : context.getAgents()) {
        if (agent != null && !agent.isBlank() && !agentNames.contains(agent)) {
          agentNames.add(agent);
        }
      }
    }
    List<AgentCapability> discovered = new ArrayList<>();

    for (String name : agentNames) {
      if (name == null || name.isBlank() || ROUTER_NAME.equals(name)) {
        continue;
      }
      ComponentDescriptor descriptor = descriptorsByName.get(name);
      AgentCapability cap = fromDescriptor(descriptor);
      if (cap == null) {
        cap = fromCapabilitiesAction(context, name, descriptor);
      }
      if (cap != null) {
        discovered.add(cap);
      }
    }

    return discovered.stream()
        .sorted(Comparator.comparing(a -> a.name))
        .toList();
  }

  private AgentCapability fromDescriptor(ComponentDescriptor descriptor) {
    if (descriptor == null || descriptor.getName() == null || descriptor.getName().isBlank()) {
      return null;
    }
    AgentCapabilityManifest manifest = descriptor.getAgentCapabilityManifest();
    String skillSummary = explicitSkillSummary(manifest);
    if (skillSummary.isBlank()) {
      return null;
    }
    return new AgentCapability(
        descriptor.getName(),
        compactName(descriptor.getName()),
        skillSummary,
        explicitOneLineSkillSummary(manifest),
        resolveRoutingKeywords(manifest),
        descriptor.isVisible(),
        canHandleOpaqueRelay(manifest),
        manifest
    );
  }

  private AgentCapability fromCapabilitiesAction(CommandContext context,
                                                 String agentName,
                                                 ComponentDescriptor descriptor) {
    try {
      DelegatedAgentResult response = delegateToAgent(context, agentName, "capabilities", "");
      if (response == null || response.isFailure()) {
        return null;
      }
      String body = response.body();
      JsonNode root = extractFirstJsonBlock(body);
      JsonNode manifestNode = firstManifestNode(root);
      if (manifestNode == null || manifestNode.isMissingNode() || manifestNode.isNull()) {
        return null;
      }
      AgentCapabilityManifest manifest = mapper.treeToValue(manifestNode, AgentCapabilityManifest.class);
      String skillSummary = explicitSkillSummary(manifest);
      if (skillSummary.isBlank()) {
        return null;
      }
      boolean visible = descriptor != null && descriptor.isVisible();
      return new AgentCapability(
          agentName,
          compactName(agentName),
          skillSummary,
          explicitOneLineSkillSummary(manifest),
          resolveRoutingKeywords(manifest),
          visible,
          canHandleOpaqueRelay(manifest),
          manifest
      );
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String responseBody(AiatpResponse result) {
    if (result == null || result.getBody() == null) {
      return "";
    }
    String body = AiatpIO.bodyToString(result.getBody(), StandardCharsets.UTF_8);
    return body == null ? "" : body;
  }

  private static String requestBody(CommandContext context) {
    if (context == null || context.getAiatpRequest() == null || context.getAiatpRequest().getBody() == null) {
      return "";
    }
    String body = AiatpIO.bodyToString(context.getAiatpRequest().getBody(), StandardCharsets.UTF_8);
    return body == null ? "" : body.trim();
  }

  private static JsonNode firstManifestNode(JsonNode root) {
    if (root == null || root.isMissingNode() || root.isNull()) {
      return null;
    }
    if (root.hasNonNull("agentName")) {
      return root;
    }
    JsonNode direct = root.path("manifest");
    if (!direct.isMissingNode() && !direct.isNull()) {
      return direct;
    }
    JsonNode dataManifest = root.path("data").path("manifest");
    if (!dataManifest.isMissingNode() && !dataManifest.isNull()) {
      return dataManifest;
    }
    return null;
  }

  private static Map<String, Set<String>> requiredArgsFromManifest(AgentCapabilityManifest manifest) {
    if (manifest == null || manifest.getCommands() == null || manifest.getCommands().isEmpty()) {
      return Map.of();
    }
    Map<String, Set<String>> requiredArgs = new LinkedHashMap<>();
    for (AgentCommandSchema commandSchema : manifest.getCommands()) {
      if (commandSchema == null || commandSchema.getName() == null || commandSchema.getName().isBlank()) {
        continue;
      }
      requiredArgs.put(commandSchema.getName(), new HashSet<>(safeList(commandSchema.getRequiredArgs())));
    }
    return requiredArgs;
  }

  private void pruneExpiredRoutes() {
    long now = System.currentTimeMillis();
    stickyBySession.entrySet().removeIf(e -> now - e.getValue().updatedAtMs > ROUTE_TTL_MS);
  }

  private static boolean mentionsSwitchWords(String normalized) {
    return containsAny(normalized, "switch", "change agent", "another agent", "different agent", "cambiar", "otro agente");
  }

  private static StickyResetDirective parseStickyResetDirective(String prompt) {
    Matcher matcher = STICKY_RESET_PREFIX.matcher(safe(prompt));
    if (!matcher.matches()) {
      return StickyResetDirective.none();
    }
    String remaining = safe(matcher.group(1));
    return new StickyResetDirective(true, remaining);
  }

  private static boolean containsAny(String text, String... needles) {
    for (String n : needles) {
      if (text.contains(n)) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsAgent(List<AgentCapability> agents, String name) {
    if (name == null || name.isBlank()) {
      return false;
    }
    return agents.stream().anyMatch(a -> name.equals(a.name));
  }

  private static List<AgentCapability> excludeAgents(List<AgentCapability> agents, Set<String> excludedAgents) {
    if (excludedAgents == null || excludedAgents.isEmpty()) {
      return agents;
    }
    return agents.stream()
        .filter(agent -> !excludedAgents.contains(agent.name))
        .toList();
  }

  private StickyRoute normalizeSticky(StickyRoute sticky, List<AgentCapability> agents) {
    if (sticky == null) {
      return null;
    }
    return containsAgent(agents, sticky.agent) ? sticky : null;
  }

  private static boolean isValidRoute(String route, List<AgentCapability> agents) {
    if (route == null || route.isBlank()) {
      return false;
    }
    return containsAgent(agents, route);
  }

  private static AgentCapability findAgent(List<AgentCapability> agents, String route) {
    if (route == null || route.isBlank()) {
      return null;
    }
    return agents.stream().filter(a -> route.equals(a.name)).findFirst().orElse(null);
  }

  private static boolean mentionsAnaphora(String normalized) {
    return containsAny(normalized, " it ", " that ", " this ", " them ", " latest ", " newest ", " last ", " previous ");
  }

  static boolean isOpaqueAuthRelayPrompt(String prompt) {
    String p = safe(prompt);
    if (p.isEmpty()) {
      return false;
    }
    if (!AUTH_COMPLETE_PREFIX.matcher(p).find()) {
      return false;
    }
    String lower = p.toLowerCase(Locale.ROOT);
    return lower.contains("session-id=")
        && lower.contains("code=")
        && (lower.contains("secureenvelope") || lower.contains("opaquepayload") || lower.contains("integrity="));
  }

  private boolean indicatesFailureSignal(JsonNode delegatedJson) {
    if (delegatedJson == null) {
      return false;
    }
    JsonNode meta = delegatedJson.path("meta");
    String responseOutcome = readPath(delegatedJson, "response.outcome");
    // Reroute only on explicit "out of scope" signals, never by heuristics.
    if ("unsupported_operation".equalsIgnoreCase(responseOutcome)) {
      return true;
    }
    if (meta != null && !meta.isMissingNode()) {
      String outcome = readPath(meta, "outcome");
      if (FAILURE_OUTCOMES.contains(outcome)) {
        return true;
      }
      String errorCode = readPath(meta, "errorCode");
      if (FAILURE_ERROR_CODES.contains(errorCode)) {
        return true;
      }
    }
    return false;
  }

  private static Set<String> tokenize(String value) {
    String normalized = safe(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", " ");
    if (normalized.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(normalized.split("\\s+"))
        .map(String::trim)
        .filter(s -> !s.isBlank())
        .collect(Collectors.toCollection(HashSet::new));
  }

  private static int overlapScore(Set<String> a, Set<String> b) {
    if (a.isEmpty() || b.isEmpty()) {
      return 0;
    }
    int score = 0;
    for (String token : a) {
      if (b.contains(token)) {
        score++;
      }
    }
    return score;
  }

  private static int intentScore(Set<String> promptTokens, AgentCapability agent) {
    int score = 0;
    Set<String> capabilityTokens = new HashSet<>();
    capabilityTokens.addAll(tokenize(agent.label));
    capabilityTokens.addAll(tokenize(agent.name));
    capabilityTokens.addAll(tokenize(agent.skillSummary));
    capabilityTokens.addAll(agent.routingKeywords.stream()
        .flatMap(keyword -> tokenize(keyword).stream())
        .collect(Collectors.toSet()));
    if (agent.manifest != null) {
      if (agent.manifest.getIntents() != null) {
        for (String intent : agent.manifest.getIntents()) {
          capabilityTokens.addAll(tokenize(intent.replace('.', ' ')));
        }
      }
    }
    score += overlapScore(promptTokens, capabilityTokens);
    return score;
  }

  private static String explicitSkillSummary(AgentCapabilityManifest manifest) {
    if (manifest == null || manifest.getRoutingHints() == null) {
      return "";
    }
    return safe(manifest.getRoutingHints().get("skillSummary"));
  }

  private static String explicitOneLineSkillSummary(AgentCapabilityManifest manifest) {
    if (manifest == null || manifest.getRoutingHints() == null) {
      return "";
    }
    return safe(manifest.getRoutingHints().get("oneLineSkillSummary"));
  }

  private String toJson(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      return "{}";
    }
  }

  private static List<String> resolveRoutingKeywords(AgentCapabilityManifest manifest) {
    if (manifest == null || manifest.getRoutingHints() == null) {
      return List.of();
    }
    String raw = firstNonBlank(
        manifest.getRoutingHints().get("routingKeywords"),
        manifest.getRoutingHints().get("keywords"),
        manifest.getRoutingHints().get("tags"));
    if (raw.isBlank()) {
      return List.of();
    }
    return Arrays.stream(raw.split("[,|]"))
        .map(String::trim)
        .filter(s -> !s.isBlank())
        .distinct()
        .toList();
  }

  private static boolean canHandleOpaqueRelay(AgentCapabilityManifest manifest) {
    if (manifest == null || manifest.getRoutingHints() == null) {
      return false;
    }
    return "true".equalsIgnoreCase(safe(manifest.getRoutingHints().get("canHandleOpaqueRelay")));
  }

  private StickyRoute updateSticky(String selectedAgent, String delegatedPrompt, JsonNode delegatedJson, StickyRoute current) {
    if (delegatedJson == null || delegatedJson.isMissingNode() || delegatedJson.isNull()) {
      return new StickyRoute(
          selectedAgent,
          System.currentTimeMillis(),
          delegatedPrompt,
          current == null ? "" : safe(current.lastTaskId),
          current == null ? "" : safe(current.lastTaskAgent)
      );
    }
    String taskId = firstNonBlank(
        readPath(delegatedJson, "meta.entities.task_id"),
        readPath(delegatedJson, "meta.entities.taskId"),
        readPath(delegatedJson, "data.task_id"),
        readPath(delegatedJson, "data.taskId"),
        readPath(delegatedJson, "toolResponse.data.task_id"),
        readPath(delegatedJson, "toolResponse.data.taskId"),
        firstTaskIdFromData(delegatedJson)
    );
    String taskAgent = firstNonBlank(
        readPath(delegatedJson, "meta.entities.agent_id"),
        readPath(delegatedJson, "meta.entities.target_agent_id"),
        readPath(delegatedJson, "data.agent_id"),
        readPath(delegatedJson, "data.target_agent_id"),
        readPath(delegatedJson, "data.claimedBy"),
        firstAssignedAgentFromData(delegatedJson)
    );
    String finalTaskId = firstNonBlank(taskId, current == null ? "" : current.lastTaskId);
    String finalTaskAgent = firstNonBlank(taskAgent, current == null ? "" : current.lastTaskAgent);
    return new StickyRoute(selectedAgent, System.currentTimeMillis(), delegatedPrompt, finalTaskId, finalTaskAgent);
  }

  private static String firstTaskIdFromData(JsonNode delegatedJson) {
    JsonNode data = delegatedJson.path("data");
    if (data.isArray() && !data.isEmpty()) {
      for (JsonNode item : data) {
        String id = firstNonBlank(readPath(item, "taskId"), readPath(item, "task_id"));
        if (!id.isBlank()) {
          return id;
        }
      }
    }
    JsonNode tasks = data.path("tasks");
    if (tasks.isArray() && !tasks.isEmpty()) {
      for (JsonNode item : tasks) {
        String id = firstNonBlank(readPath(item, "taskId"), readPath(item, "task_id"));
        if (!id.isBlank()) {
          return id;
        }
      }
    }
    return "";
  }

  private static String firstAssignedAgentFromData(JsonNode delegatedJson) {
    JsonNode data = delegatedJson.path("data");
    JsonNode assignedTo = data.path("assignedTo");
    if (assignedTo.isArray() && !assignedTo.isEmpty()) {
      String candidate = safe(assignedTo.get(0).asText(""));
      if (!candidate.isBlank()) {
        return candidate;
      }
    }
    if (data.isArray() && !data.isEmpty()) {
      for (JsonNode item : data) {
        JsonNode itemAssigned = item.path("assignedTo");
        if (itemAssigned.isArray() && !itemAssigned.isEmpty()) {
          String candidate = safe(itemAssigned.get(0).asText(""));
          if (!candidate.isBlank()) {
            return candidate;
          }
        }
      }
    }
    return "";
  }

  private static List<String> safeList(List<String> values) {
    return values == null ? List.of() : values.stream().filter(v -> v != null && !v.isBlank()).toList();
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.trim().isEmpty()) {
        return value.trim();
      }
    }
    return "";
  }

  private static String loadSystemPrompt(String resourcePath) {
    try (InputStream in = RouterAgentComponent.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new RuntimeException("Missing system prompt resource: " + resourcePath);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read system prompt: " + resourcePath, e);
    }
  }

  private JsonNode extractFirstJsonBlock(String raw) {
    String s = safe(raw);
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
      try {
        return mapper.readTree(s.substring(first, last + 1));
      } catch (Exception ignored) {
      }
    }
    return mapper.createObjectNode();
  }

  private static String readPath(JsonNode root, String path) {
    JsonNode current = root;
    for (String p : path.split("\\.")) {
      if (current == null || current.isMissingNode() || current.isNull()) {
        return "";
      }
      current = current.path(p);
    }
    if (current == null || current.isMissingNode() || current.isNull()) {
      return "";
    }
    if (current.isTextual()) {
      return safe(current.asText());
    }
    return safe(current.toString());
  }

  private static String compactName(String fullName) {
    String s = safe(fullName);
    int idx = s.lastIndexOf('.');
    if (idx >= 0 && idx + 1 < s.length()) {
      return s.substring(idx + 1);
    }
    return s;
  }

  private static String quote(String value) {
    if (value == null) {
      return "null";
    }
    StringBuilder out = new StringBuilder(value.length() + 2);
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"':
          out.append("\\\"");
          break;
        case '\\':
          out.append("\\\\");
          break;
        case '\b':
          out.append("\\b");
          break;
        case '\f':
          out.append("\\f");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        default:
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
      }
    }
    out.append('"');
    return out.toString();
  }

  private static String safe(String value) {
    return value == null ? "" : value.trim();
  }

  private static String headerValue(AiatpRequest request, String name) {
    if (request == null || request.getHeaders() == null || name == null || name.isBlank()) {
      return "";
    }
    String value = request.getHeaders().getFirst(name);
    return value == null ? "" : value;
  }

  private String infoEnvelopeJson(String message) {
    try {
      var root = mapper.createObjectNode();
      root.put("status", "ok");
      var channels = root.putObject("channels");
      channels.putObject("chat").put("message", safe(message));
      channels.putObject("status").put("message", safe(message));
      var html = channels.putObject("html");
      html.putNull("html");
      html.put("mode", "none");
      html.put("replace", false);
      return mapper.writeValueAsString(root);
    } catch (Exception e) {
      return "{\"status\":\"ok\",\"channels\":{\"chat\":{\"message\":" + quote(safe(message))
          + "},\"status\":{\"message\":" + quote(safe(message))
          + "},\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}";
    }
  }

  private String failureEnvelopeJson(String agentName, String errorCode, String message) {
    try {
      JsonNode authNode = mapper.nullNode();
      var root = mapper.createObjectNode();
      root.put("kind", "terminal");
      root.put("outcome", "failure");
      root.put("terminal", true);
      var response = root.putObject("response");
      response.put("outcome", "failure");
      response.put("completed", true);
      var payload = root.putObject("payload");
      payload.put("message", firstNonBlank(message, "Delegated agent failed."));
      payload.put("agent", safe(agentName));
      var meta = root.putObject("meta");
      meta.put("outcome", "failure");
      meta.put("errorCode", safe(errorCode));
      meta.put("agent", safe(agentName));
      var projections = root.putObject("projections");
      projections.putObject("status").put("message", firstNonBlank(message, "Delegated agent failed."));
      projections.putObject("chat").put("message", firstNonBlank(message, "Delegated agent failed."));
      projections.set("auth", authNode);
      var projectionWebview = projections.putObject("html");
      projectionWebview.putNull("html");
      projectionWebview.put("mode", "none");
      projectionWebview.put("replace", false);
      var channels = root.putObject("channels");
      channels.putObject("status").put("message", firstNonBlank(message, "Delegated agent failed."));
      channels.putObject("chat").put("message", firstNonBlank(message, "Delegated agent failed."));
      channels.set("auth", authNode);
      var htmlChannel = channels.putObject("html");
      htmlChannel.putNull("html");
      htmlChannel.put("mode", "none");
      htmlChannel.put("replace", false);
      return mapper.writeValueAsString(root);
    } catch (Exception e) {
      return "{\"kind\":\"terminal\",\"outcome\":\"failure\",\"terminal\":true,\"response\":{\"outcome\":\"failure\",\"completed\":true},\"meta\":{\"outcome\":\"failure\",\"errorCode\":"
          + quote(errorCode)
          + "},\"channels\":{\"status\":{\"message\":"
          + quote(firstNonBlank(message, "Delegated agent failed."))
          + "},\"chat\":{\"message\":\"\"},\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}";
    }
  }

  private String unhandledIntentEnvelopeJson(String message) {
    try {
      JsonNode authNode = mapper.nullNode();
      var root = mapper.createObjectNode();
      root.put("kind", "terminal");
      root.put("outcome", "failure");
      root.put("terminal", true);
      var response = root.putObject("response");
      response.put("outcome", "failure");
      response.put("completed", true);
      var payload = root.putObject("payload");
      payload.put("message", firstNonBlank(message, "No agent can handle this request."));
      var meta = root.putObject("meta");
      meta.put("outcome", "unhandled_intent");
      meta.put("errorCode", "no_agent_support");
      var channels = root.putObject("channels");
      channels.putObject("status").put("message", payload.path("message").asText());
      channels.putObject("chat").put("message", payload.path("message").asText());
      channels.set("auth", authNode);
      var htmlChannel = channels.putObject("html");
      htmlChannel.putNull("html");
      htmlChannel.put("mode", "none");
      htmlChannel.put("replace", false);
      return mapper.writeValueAsString(root);
    } catch (Exception e) {
      return "{\"kind\":\"terminal\",\"outcome\":\"failure\",\"terminal\":true,\"response\":{\"outcome\":\"failure\",\"completed\":true},"
          + "\"meta\":{\"outcome\":\"unhandled_intent\",\"errorCode\":\"no_agent_support\"},"
          + "\"channels\":{\"status\":{\"message\":" + quote(firstNonBlank(message, "No agent can handle this request.")) + "},"
          + "\"chat\":{\"message\":" + quote(firstNonBlank(message, "No agent can handle this request.")) + "},"
          + "\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}";
    }
  }

  public enum RouterEvent implements EventDefinition {
    chat("Chat message channel"),
    status("Status message channel"),
    html("HTML payload channel"),
    auth("Delegated auth payload channel"),
    error("Protocol error channel"),
    thought("Reasoning/thought channel"),
    control("Control channel");

    private final String description;

    RouterEvent(String description) {
      this.description = description;
    }

    @Override
    public String getDescription() {
      return description;
    }
  }

  private record AgentCapability(String name,
                                 String label,
                                 String skillSummary,
                                 String oneLineSkillSummary,
                                 List<String> routingKeywords,
                                 boolean helpVisible,
                                 boolean canHandleOpaqueRelay,
                                 AgentCapabilityManifest manifest) {
  }

  private record StickyRoute(String agent,
                             long updatedAtMs,
                             String lastPrompt,
                             String lastTaskId,
                             String lastTaskAgent) {
  }

  private record RouteDecision(String route, boolean switched, String reason, String userMessage) {
  }

  private record DelegatedAgentResult(AiatpResponse response,
                                      String body) {
    private boolean isFailure() {
      return response == null || response.getStatusCode() >= 400;
    }
  }

  private record PreDispatchResult(boolean allowed, String delegatedPrompt, String reason, String message) {
    private static PreDispatchResult allow(String delegatedPrompt, String reason) {
      return new PreDispatchResult(true, delegatedPrompt, reason, "");
    }

    private static PreDispatchResult block(String reason, String message) {
      return new PreDispatchResult(false, "", reason, message);
    }
  }

  private record StickyResetDirective(boolean resetRequested, String remainingPrompt) {
    private static StickyResetDirective none() {
      return new StickyResetDirective(false, "");
    }
  }
}
