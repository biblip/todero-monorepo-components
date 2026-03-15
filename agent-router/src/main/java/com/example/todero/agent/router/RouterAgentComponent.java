package com.example.todero.agent.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.ai.llm.LLMClient;
import com.social100.todero.common.ai.llm.OpenAiLLM;
import com.social100.todero.common.aiatpio.AiatpIO;
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

@AIAController(name = "com.shellaia.verbatim.agent.router",
    type = ServerType.AI,
    visible = true,
    description = "Dynamic router agent that selects and delegates to available internal agents",
    events = RouterAgentComponent.RouterEvent.class,
    capabilityProvider = RouterAgentCapabilities.class)
public class RouterAgentComponent {
  private static final String MAIN_GROUP = "Main";
  private static final String ROUTER_NAME = "com.shellaia.verbatim.agent.router";
  private static final String DJ_AGENT_V2 = "com.shellaia.verbatim.agent.dj.v2";
  private static final String DJ_AGENT_LEGACY = "com.shellaia.verbatim.agent.dj";
  private static final long DELEGATE_TIMEOUT_SECONDS = 30;
  private static final long ROUTE_TTL_MS = 30 * 60 * 1000L;
  private static final Pattern ARG_PATTERN = Pattern.compile("--[a-zA-Z0-9-]+");
  private static final Pattern AUTH_COMPLETE_PREFIX = Pattern.compile("^\\s*auth-complete\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern STICKY_RESET_PREFIX = Pattern.compile(
      "^\\s*(?:go back to router|back to router|return to router|router reset|reset stickiness|reset sticky|clear sticky|"
          + "volver al router|regresar al router|reiniciar stickiness|resetear stickiness|salir del agente)\\b\\s*[:,-]?\\s*(.*)$",
      Pattern.CASE_INSENSITIVE);
  private static final Set<String> FAILURE_OUTCOMES = Set.of("unhandled_intent", "retry_by_router");
  private static final Set<String> FAILURE_ERROR_CODES = Set.of("agent_capability_mismatch", "agent_missing_args");

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
    String prompt = AiatpIO.bodyToString(context.getHttpRequest().body(), StandardCharsets.UTF_8).trim();
    if (prompt.isEmpty()) {
      context.emitError("Prompt is required. Usage: process <text>");
      return true;
    }

    pruneExpiredRoutes();
    String sessionId = safe(context.getId());
    StickyResetDirective stickyResetDirective = parseStickyResetDirective(prompt);
    if (stickyResetDirective.resetRequested()) {
      stickyBySession.remove(sessionId);
      if (stickyResetDirective.remainingPrompt().isBlank()) {
        context.emitStatus("Sticky session cleared. Routing will restart on the next prompt.", "final");
        return true;
      }
      prompt = stickyResetDirective.remainingPrompt();
      System.out.println("[ROUTER-AGENT] sticky reset requested; continuing with fresh prompt=" + prompt);
    }
    boolean opaqueAuthRelay = isOpaqueAuthRelayPrompt(prompt);
    System.out.println("[ROUTER-AGENT] process start session=" + sessionId + " prompt=" + prompt);

    boolean rerouteAttempted = false;
    while (true) {
      List<AgentCapability> agents = discoverAgents(context);
      if (agents.isEmpty()) {
        context.emitError("No routable agent found in runtime.");
        return true;
      }

      StickyRoute sticky = stickyBySession.get(sessionId);
      RouteDecision decision = opaqueAuthRelay
          ? decideOpaqueRelayRoute(prompt, sticky, agents)
          : decideRoute(prompt, sticky, agents);
      System.out.println("[ROUTER-AGENT] decision route=" + decision.route + " reason=" + decision.reason + " switched=" + decision.switched);
      context.emitThought("route=" + safe(decision.route)
          + " reason=" + safe(decision.reason)
          + " switched=" + decision.switched, "progress");
      if (decision.route == null || decision.route.isBlank()) {
        context.emitError("Could not determine target agent.");
        return true;
      }

      AgentCapability selectedAgent = findAgent(agents, decision.route);
      System.out.println("[ROUTER-AGENT] selected agent=" + (selectedAgent == null ? "null" : selectedAgent.name()));
      PreDispatchResult preDispatch = opaqueAuthRelay
          ? PreDispatchResult.allow(prompt, "opaque-auth-relay")
          : preDispatch(prompt, selectedAgent, sticky);
      if (!preDispatch.allowed) {
        context.emitError(preDispatch.message);
        return true;
      }

      String delegatedPrompt = preDispatch.delegatedPrompt;
      System.out.println("[ROUTER-AGENT] delegating to " + decision.route + " prompt=" + delegatedPrompt);
      AiatpIO.HttpResponse delegated = delegateToAgent(context, decision.route, "process", delegatedPrompt);
      if (delegated == null) {
        System.out.println("[ROUTER-AGENT] no response from agent=" + decision.route);
        context.emitError("Agent did not return a response.");
        return true;
      }

      String delegatedBody = AiatpIO.bodyToString(delegated.body(), StandardCharsets.UTF_8);
      System.out.println("[ROUTER-AGENT] received response body=" + delegatedBody.substring(0, Math.min(256, delegatedBody.length())));
      JsonNode delegatedJson = extractFirstJsonBlock(delegatedBody);
      if (opaqueAuthRelay) {
        System.out.println("[ROUTER-AGENT] opaque auth relay route=" + decision.route);
        stickyBySession.put(sessionId, updateSticky(decision.route, delegatedPrompt, delegatedJson, sticky));
        if (!emitDelegatedPayload(context, delegatedJson, delegatedBody)) {
          context.emitError("Delegated agent response is missing required channels metadata.");
        }
        return true;
      }

      if (!rerouteAttempted && indicatesFailureSignal(delegatedJson)) {
        rerouteAttempted = true;
        stickyBySession.remove(sessionId);
        context.emitStatus("Rerouting to another agent.", "progress");
        context.emitThought("reroute=true reason=failure_signal agent=" + safe(decision.route), "progress");
        System.out.println("[ROUTER-AGENT] fallback triggered by "
            + readPath(delegatedJson.path("meta"), "errorCode")
            + "; rerouting prompt: " + prompt);
        continue;
      }

      if (delegatedJson == null || !delegatedJson.has("channels") || !delegatedJson.path("channels").isObject()) {
        System.out.println("[ROUTER-AGENT] delegated response missing channels");
        context.emitError("Delegated agent response is missing required channels metadata.");
        return true;
      }
      stickyBySession.put(sessionId, updateSticky(decision.route, delegatedPrompt, delegatedJson, sticky));
      System.out.println("[ROUTER-AGENT] final response reason=" + (rerouteAttempted ? "agent-fallback" : decision.reason));
      emitDelegatedPayload(context, delegatedJson, delegatedBody);
      return true;
    }
  }

  private boolean emitDelegatedPayload(CommandContext context, JsonNode delegatedJson, String delegatedBody) {
    if (delegatedJson == null) {
      return false;
    }
    JsonNode channels = delegatedJson.path("channels");
    if (!channels.isObject()) {
      return false;
    }
    String chatMessage = readPath(channels.path("chat"), "message");
    String statusMessage = readPath(channels.path("status"), "message");
    String thoughtMessage = readPath(channels.path("thought"), "message");
    JsonNode htmlNode = channels.has("html") ? channels.path("html") : channels.path("webview");
    String html = readPath(htmlNode, "html");
    String htmlMode = readPath(htmlNode, "mode");
    boolean htmlReplace = !htmlNode.isMissingNode() && htmlNode.path("replace").asBoolean(true);
    JsonNode auth = delegatedJson.path("auth");
    String authJson = auth.isObject() ? auth.toString() : null;

    String finalChannel = authJson != null ? "auth"
        : (!html.isBlank() || "suggestions_from_toolsteps".equalsIgnoreCase(htmlMode)) ? "html"
        : !thoughtMessage.isBlank() ? "thought"
        : !chatMessage.isBlank() ? "chat"
        : !statusMessage.isBlank() ? "status"
        : "";

    if (!statusMessage.isBlank()) {
      context.emitStatus(statusMessage, "status".equals(finalChannel) ? "final" : "progress");
    }
    if (!thoughtMessage.isBlank()) {
      context.emitThought(thoughtMessage, "thought".equals(finalChannel) ? "final" : "progress");
    }
    if (!chatMessage.isBlank()) {
      context.emitChat(chatMessage, "chat".equals(finalChannel) ? "final" : "progress");
    }
    if (!html.isBlank() || "suggestions_from_toolsteps".equalsIgnoreCase(htmlMode)) {
      context.emitHtml(html, "html".equals(finalChannel) ? "final" : "progress",
          htmlMode.isBlank() ? "html" : htmlMode, htmlReplace);
    }
    if (authJson != null) {
      context.emitAuthJson(authJson, "final");
    }
    if (finalChannel.isBlank()) {
      String fallback = delegatedBody == null ? "" : delegatedBody.trim();
      context.emitError(fallback.isBlank()
          ? "Delegated agent response is missing supported channel content."
          : fallback);
    }
    return true;
  }

  private RouteDecision decideRoute(String prompt, StickyRoute sticky, List<AgentCapability> agents) {
    StickyRoute normalizedSticky = normalizeSticky(sticky, agents);
    if (normalizedSticky != null && containsAgent(agents, normalizedSticky.agent)) {
      return new RouteDecision(normalizedSticky.agent, false, "sticky-continue", "Continuing with current agent.");
    }

    String intentRoute = routeFromIntent(prompt, agents);
    if (!intentRoute.isBlank()) {
      boolean switched = normalizedSticky == null || !safe(normalizedSticky.agent).equals(intentRoute);
      String reason = switched ? "intent-switch" : "intent-keep";
      String userMessage = switched ? "Switching to the best matching agent." : "Continuing with current agent.";
      return new RouteDecision(intentRoute, switched, reason, userMessage);
    }

    RouteDecision llmDecision = planWithLlm(prompt, normalizedSticky, agents);
    if (llmDecision != null && isValidRoute(llmDecision.route, agents)) {
      boolean switched = normalizedSticky == null || !safe(normalizedSticky.agent).equals(llmDecision.route);
      return new RouteDecision(llmDecision.route, switched, llmDecision.reason, llmDecision.userMessage);
    }

    String fallback = heuristicRoute(prompt, normalizedSticky, agents);
    boolean switched = normalizedSticky == null || !safe(normalizedSticky.agent).equals(fallback);
    return new RouteDecision(fallback, switched, "heuristic-fallback", "Routing to the best matching agent.");
  }

  private RouteDecision decideOpaqueRelayRoute(String prompt, StickyRoute sticky, List<AgentCapability> agents) {
    StickyRoute normalizedSticky = normalizeSticky(sticky, agents);
    if (normalizedSticky != null && containsAgent(agents, normalizedSticky.agent)) {
      return new RouteDecision(normalizedSticky.agent, false, "opaque-auth-sticky", "Continuing with sticky auth relay agent.");
    }
    String byIntent = routeFromIntent(prompt, agents);
    if (!byIntent.isBlank()) {
      return new RouteDecision(byIntent, true, "opaque-auth-intent", "Routing auth relay by intent.");
    }
    String preferred = preferredOpaqueRelayAgent(agents);
    if (!preferred.isBlank()) {
      return new RouteDecision(preferred, true, "opaque-auth-preferred", "Routing auth relay to preferred agent.");
    }
    return new RouteDecision(agents.get(0).name, true, "opaque-auth-fallback", "Routing auth relay to fallback agent.");
  }

  private static String preferredOpaqueRelayAgent(List<AgentCapability> agents) {
    for (AgentCapability agent : agents) {
      if (DJ_AGENT_V2.equals(agent.name)) {
        return agent.name;
      }
    }
    for (AgentCapability agent : agents) {
      String n = safe(agent.name).toLowerCase(Locale.ROOT);
      if (n.contains(".agent.dj.v2") || n.endsWith(".dj.v2")) {
        return agent.name;
      }
    }
    for (AgentCapability agent : agents) {
      if (containsAnyToken(agent, "spotify", "music", "dj")) {
        return agent.name;
      }
    }
    return "";
  }

  private static boolean containsAnyToken(AgentCapability agent, String... tokens) {
    StringBuilder hay = new StringBuilder();
    hay.append(safe(agent.name)).append(' ').append(safe(agent.label)).append(' ');
    if (agent.manifest != null) {
      if (agent.manifest.getIntents() != null) {
        for (String i : agent.manifest.getIntents()) {
          hay.append(safe(i)).append(' ');
        }
      }
      if (agent.manifest.getRoutingHints() != null) {
        for (Map.Entry<String, String> e : agent.manifest.getRoutingHints().entrySet()) {
          hay.append(safe(e.getKey())).append(' ').append(safe(e.getValue())).append(' ');
        }
      }
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
      sb.append("- ").append(a.name).append(" commands=").append(a.commands).append("\n");
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

  private AiatpIO.HttpResponse delegateToAgent(CommandContext context,
                                               String agentName,
                                               String command,
                                               String args) {
    CompletableFuture<AiatpIO.HttpResponse> out = new CompletableFuture<>();
    CommandContext internal = context.toBuilder()
        .httpRequest(AiatpIO.HttpRequest.newBuilder("ACTION", "/" + agentName + "/" + command)
            .body(AiatpIO.Body.ofString(args, StandardCharsets.UTF_8))
            .build())
        .consumer(out::complete)
        .build();
    try {
      context.execute(agentName, command, internal);
      return out.get(DELEGATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      return AiatpIO.HttpResponse.newBuilder(504)
          .setHeader("Content-Type", "application/json; charset=utf-8")
          .body(AiatpIO.Body.ofString("{\"error\":\"delegate_timeout\",\"agent\":" + quote(agentName) + "}", StandardCharsets.UTF_8))
          .build();
    } catch (Exception e) {
      return AiatpIO.HttpResponse.newBuilder(500)
          .setHeader("Content-Type", "application/json; charset=utf-8")
          .body(AiatpIO.Body.ofString("{\"error\":\"delegate_failed\",\"agent\":" + quote(agentName)
              + ",\"message\":" + quote(e.getMessage()) + "}", StandardCharsets.UTF_8))
          .build();
    }
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
      if (DJ_AGENT_LEGACY.equals(name)) {
        continue;
      }
      ComponentDescriptor descriptor = descriptorsByName.get(name);
      AgentCapability cap = fromDescriptor(descriptor);
      if (cap == null) {
        cap = fromCapabilitiesAction(context, name, descriptor);
      }
      if (cap == null) {
        cap = fromHelp(context, name);
      }
      if (cap == null) {
        cap = new AgentCapability(name, compactName(name), List.of("process"), false, null, Map.of());
      }
      if (!cap.commands.contains("process")) {
        List<String> withProcess = new ArrayList<>(cap.commands);
        withProcess.add("process");
        cap = new AgentCapability(cap.name, cap.label, withProcess.stream().distinct().toList(),
            cap.helpVisible, cap.manifest, cap.requiredArgsByCommand);
      }
      discovered.add(cap);
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
    if (manifest == null || manifest.getCommands() == null || manifest.getCommands().isEmpty()) {
      return null;
    }
    List<String> commands = manifest.getCommands().stream()
        .map(AgentCommandSchema::getName)
        .filter(cmd -> cmd != null && !cmd.isBlank())
        .distinct()
        .toList();
    if (commands.isEmpty()) {
      return null;
    }
    Map<String, Set<String>> requiredArgs = new LinkedHashMap<>();
    for (AgentCommandSchema commandSchema : manifest.getCommands()) {
      if (commandSchema == null || commandSchema.getName() == null || commandSchema.getName().isBlank()) {
        continue;
      }
      requiredArgs.put(commandSchema.getName(), new HashSet<>(safeList(commandSchema.getRequiredArgs())));
    }
    return new AgentCapability(
        descriptor.getName(),
        compactName(descriptor.getName()),
        commands,
        descriptor.isVisible(),
        manifest,
        requiredArgs
    );
  }

  private AgentCapability fromCapabilitiesAction(CommandContext context,
                                                 String agentName,
                                                 ComponentDescriptor descriptor) {
    try {
      CompletableFuture<AiatpIO.HttpResponse> out = new CompletableFuture<>();
      CommandContext internal = CommandContext.builder()
          .httpRequest(AiatpIO.HttpRequest.newBuilder("ACTION", "/" + agentName + "/capabilities")
              .body(AiatpIO.Body.ofString("", StandardCharsets.UTF_8))
              .build())
          .consumer(out::complete)
          .build();
      context.execute(agentName, "capabilities", internal);
      AiatpIO.HttpResponse response = out.get(DELEGATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (response.status() >= 400) {
        return null;
      }
      String body = AiatpIO.bodyToString(response.body(), StandardCharsets.UTF_8);
      JsonNode root = extractFirstJsonBlock(body);
      JsonNode manifestNode = firstManifestNode(root);
      if (manifestNode == null || manifestNode.isMissingNode() || manifestNode.isNull()) {
        return null;
      }
      AgentCapabilityManifest manifest = mapper.treeToValue(manifestNode, AgentCapabilityManifest.class);
      if (manifest == null || manifest.getCommands() == null || manifest.getCommands().isEmpty()) {
        return null;
      }
      List<String> commands = manifest.getCommands().stream()
          .map(AgentCommandSchema::getName)
          .filter(cmd -> cmd != null && !cmd.isBlank())
          .distinct()
          .toList();
      if (commands.isEmpty()) {
        return null;
      }
      boolean visible = descriptor != null && descriptor.isVisible();
      return new AgentCapability(
          agentName,
          compactName(agentName),
          commands,
          visible,
          manifest,
          requiredArgsFromManifest(manifest)
      );
    } catch (Exception ignored) {
      return null;
    }
  }

  private static JsonNode firstManifestNode(JsonNode root) {
    if (root == null || root.isMissingNode() || root.isNull()) {
      return null;
    }
    if (root.hasNonNull("commands") && root.hasNonNull("agentName")) {
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

  private AgentCapability fromHelp(CommandContext context, String agentName) {
    try {
      String helpJson = context.getHelp(agentName, null, OutputType.JSON);
      JsonNode root = mapper.readTree(helpJson);
      JsonNode node = root.path(agentName);
      if (node.isMissingNode() || node.isNull()) {
        return null;
      }

      List<String> commands = new ArrayList<>();
      node.fields().forEachRemaining(group -> {
        JsonNode arr = group.getValue();
        if (!arr.isArray()) {
          return;
        }
        for (JsonNode commandNode : arr) {
          String cmd = readPath(commandNode, "command");
          if (!cmd.isBlank()) {
            commands.add(cmd);
          }
        }
      });
      if (commands.isEmpty()) {
        return null;
      }
      return new AgentCapability(agentName, compactName(agentName), commands.stream().distinct().toList(), true, null, Map.of());
    } catch (Exception ignored) {
      return null;
    }
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

  private StickyRoute normalizeSticky(StickyRoute sticky, List<AgentCapability> agents) {
    if (sticky == null) {
      return null;
    }
    if (DJ_AGENT_LEGACY.equals(sticky.agent) && containsAgent(agents, DJ_AGENT_V2)) {
      return new StickyRoute(DJ_AGENT_V2, sticky.updatedAtMs, sticky.lastPrompt, sticky.lastTaskId, sticky.lastTaskAgent);
    }
    return sticky;
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

  private PreDispatchResult preDispatch(String prompt, AgentCapability selected, StickyRoute sticky) {
    if (selected == null || selected.requiredArgsByCommand == null || selected.requiredArgsByCommand.isEmpty()) {
      return PreDispatchResult.allow(prompt, "no-manifest-required-args");
    }
    String command = inferCommandFromPrompt(prompt, selected);
    if (command.isBlank()) {
      return PreDispatchResult.allow(prompt, "no-command-inferred");
    }
    Set<String> required = selected.requiredArgsByCommand.get(command);
    if (required == null || required.isEmpty()) {
      return PreDispatchResult.allow(prompt, "no-required-args");
    }
    Set<String> present = parsePresentArgs(prompt);
    List<String> missing = required.stream()
        // Positional placeholders (e.g. <query|uri>) are validated by delegated agent/tool, not router.
        .filter(RouterAgentComponent::isFlagStyleRequiredArg)
        .filter(arg -> !present.contains(arg))
        .sorted()
        .collect(Collectors.toCollection(ArrayList::new));
    if (missing.isEmpty()) {
      return PreDispatchResult.allow(prompt, "all-required-present");
    }

    String delegatedPrompt = prompt;
    if (isSlotFillCandidate(prompt, selected, command)) {
      String taskId = sticky == null ? "" : safe(sticky.lastTaskId);
      String taskAgent = sticky == null ? "" : safe(sticky.lastTaskAgent);
      if (missing.contains("--task-id")) {
        if (!taskId.isBlank()) {
          delegatedPrompt += " --task-id " + taskId;
          missing.remove("--task-id");
        }
      }
      if (missing.contains("--agent") && !taskAgent.isBlank()) {
        delegatedPrompt += " --agent " + taskAgent;
        missing.remove("--agent");
      }
      if (missing.contains("--actor") && !taskAgent.isBlank()) {
        delegatedPrompt += " --actor " + taskAgent;
        missing.remove("--actor");
      }
    }

    if (missing.isEmpty()) {
      return PreDispatchResult.allow(delegatedPrompt, "slot-filled");
    }
    return PreDispatchResult.block(
        "missing-required-args",
        "Missing required arguments for " + selected.label + "/" + command + ": " + String.join(", ", missing)
    );
  }

  private static boolean isSlotFillCandidate(String prompt, AgentCapability selected, String command) {
    if (selected == null || selected.manifest == null) {
      return false;
    }
    Set<String> required = selected.requiredArgsByCommand == null
        ? Set.of()
        : selected.requiredArgsByCommand.getOrDefault(command, Set.of());
    boolean needsEntityFromSticky = required.contains("--task-id")
        || required.contains("--agent")
        || required.contains("--actor");
    if (!needsEntityFromSticky) {
      return false;
    }
    String supportsLatest = selected.manifest.getFollowUpPolicyHints() == null
        ? ""
        : safe(selected.manifest.getFollowUpPolicyHints().getOrDefault("supports_latest", ""));
    if (!"true".equalsIgnoreCase(supportsLatest)) {
      return false;
    }
    String n = safe(prompt).toLowerCase(Locale.ROOT);
    return mentionsAnaphora(n);
  }

  private static String inferCommandFromPrompt(String prompt, AgentCapability selected) {
    String normalized = safe(prompt).toLowerCase(Locale.ROOT);
    if (normalized.isBlank()) {
      return "";
    }
    if (normalized.startsWith("execute ")) {
      String rest = normalized.substring("execute ".length()).trim();
      String first = rest.split("\\s+", 2)[0];
      if (selected.commands.contains(first)) {
        return first;
      }
    }
    String firstToken = normalized.split("\\s+", 2)[0];
    for (String command : selected.commands) {
      if (command != null && !command.isBlank() && firstToken.equalsIgnoreCase(command)) {
        return command;
      }
    }
    Set<String> promptTokens = tokenize(normalized);
    String bestCommand = "";
    int bestScore = 0;
    for (String command : selected.commands) {
      Set<String> commandTokens = tokenize(command);
      int score = overlapScore(promptTokens, commandTokens);
      if (score > bestScore) {
        bestScore = score;
        bestCommand = command;
      }
    }
    return bestScore > 0 ? bestCommand : "";
  }

  private static Set<String> parsePresentArgs(String prompt) {
    Set<String> out = new HashSet<>();
    Matcher matcher = ARG_PATTERN.matcher(safe(prompt));
    while (matcher.find()) {
      out.add(matcher.group());
    }
    return out;
  }

  private static boolean isFlagStyleRequiredArg(String arg) {
    String value = safe(arg);
    return value.startsWith("--");
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
    String status = readPath(delegatedJson, "channels.status.message");
    return containsFailureHint(status);
  }

  private static boolean containsFailureHint(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    String lowered = text.toLowerCase(Locale.ROOT);
    return lowered.contains("cannot") || lowered.contains("can't") || lowered.contains("unable") || lowered.contains("not handled") || lowered.contains("cannot handle");
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
    if (agent.commands != null) {
      for (String command : agent.commands) {
        capabilityTokens.addAll(tokenize(command));
      }
    }
    if (agent.manifest != null) {
      if (agent.manifest.getIntents() != null) {
        for (String intent : agent.manifest.getIntents()) {
          capabilityTokens.addAll(tokenize(intent.replace('.', ' ')));
        }
      }
      if (agent.manifest.getRoutingHints() != null) {
        for (Map.Entry<String, String> hint : agent.manifest.getRoutingHints().entrySet()) {
          capabilityTokens.addAll(tokenize(hint.getKey()));
          capabilityTokens.addAll(tokenize(hint.getValue()));
        }
      }
    }
    score += overlapScore(promptTokens, capabilityTokens);
    return score;
  }

  private StickyRoute updateSticky(String selectedAgent, String delegatedPrompt, JsonNode delegatedJson, StickyRoute current) {
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

  public enum RouterEvent implements EventDefinition {
    chat("Chat message channel"),
    status("Status message channel"),
    html("HTML payload channel"),
    auth("Delegated auth payload channel"),
    error("Protocol error channel"),
    thought("Reasoning/thought channel");

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
                                 List<String> commands,
                                 boolean helpVisible,
                                 AgentCapabilityManifest manifest,
                                 Map<String, Set<String>> requiredArgsByCommand) {
  }

  private record StickyRoute(String agent,
                             long updatedAtMs,
                             String lastPrompt,
                             String lastTaskId,
                             String lastTaskAgent) {
  }

  private record RouteDecision(String route, boolean switched, String reason, String userMessage) {
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
