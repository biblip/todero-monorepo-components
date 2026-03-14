package com.example.todero.agent.contacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.ai.action.CommandAgentResponse;
import com.social100.todero.common.ai.agent.AgentContext;
import com.social100.todero.common.ai.agent.AgentDefinition;
import com.social100.todero.common.ai.llm.LLMClient;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.lineparser.LineParserUtil;
import com.social100.todero.common.routing.AgentCapabilityManifest;
import com.social100.todero.common.storage.Storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@AIAController(name = "com.shellaia.verbatim.agent.contacts",
    type = ServerType.AI,
    visible = false,
    description = "Contacts agent",
    capabilityProvider = ContactsAgentCapabilities.class)
public class AgentContactsComponent {
  private static final String MAIN_GROUP = "Main";
  private static final String CONTACTS_COMPONENT = "com.shellaia.verbatim.component.contacts";
  private static final Set<String> ALLOWED_COMMANDS = Set.of("add", "list", "find", "group", "remove");

  private final AgentDefinition agentDefinition;
  private final ObjectMapper mapper = new ObjectMapper();

  public AgentContactsComponent(Storage storage) {
    this.agentDefinition = AgentDefinition.builder()
        .name("Contacts Agent")
        .role("Assistant")
        .description("Contacts add/list/find/group/remove orchestration agent")
        .model("system")
        .systemPrompt(loadSystemPrompt("prompts/default-system-prompt.md"))
        .build();
  }

  private static String loadSystemPrompt(String resourcePath) {
    try (InputStream in = AgentContactsComponent.class.getClassLoader().getResourceAsStream(resourcePath)) {
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
      description = "Process a prompt and execute contacts component command")
  public Boolean process(CommandContext context) {
    String prompt = AiatpIO.bodyToString(context.getHttpRequest().body(), StandardCharsets.UTF_8).trim();
    if (prompt.isBlank()) {
      context.emitError("Prompt is required. Usage: process <goal>");
      return true;
    }

    try {
      AgentContext agentContext = new AgentContext();
      context.bindAgentLlmRegistry(agentContext);
      LLMClient llm = agentContext.systemLlm()
          .map(instance -> instance.client())
          .orElseThrow(() -> new IllegalStateException(
              "No system-wide LLM available in registry for agent com.shellaia.verbatim.agent.contacts"));

      CommandAgentResponse planned = plan(llm, prompt, agentContext);
      String action = safeTrim(planned.getAction());
      if (action.isBlank() || "none".equalsIgnoreCase(action)) {
        String message = safeTrim(planned.getUser()).isBlank() ? "No action required." : planned.getUser();
        context.emitChat(message, "final");
        return true;
      }

      LineParserUtil.ParsedLine parsed = LineParserUtil.parse(action);
      String command = safeTrim(parsed.first).toLowerCase(Locale.ROOT);
      String args = safeTrim(parsed.second + (parsed.remaining == null ? "" : " " + parsed.remaining));
      if (!ALLOWED_COMMANDS.contains(command)) {
        context.emitError("Unsupported Contacts action: " + command);
        return true;
      }

      CompletableFuture<AiatpIO.HttpResponse> outFuture = new CompletableFuture<>();
      CommandContext internalContext = CommandContext.builder()
          .httpRequest(AiatpIO.HttpRequest.newBuilder("ACTION", "/" + CONTACTS_COMPONENT + "/" + command)
              .body(AiatpIO.Body.ofString(args, StandardCharsets.UTF_8))
              .build())
          .consumer(outFuture::complete)
          .build();
      context.execute(CONTACTS_COMPONENT, command, internalContext);

      AiatpIO.HttpResponse toolResponse = outFuture.get(25, TimeUnit.SECONDS);
      String toolBody = AiatpIO.bodyToString(toolResponse.body(), StandardCharsets.UTF_8);

      JsonNode root = parseJsonOrNull(toolBody);
      if (root != null && root.path("channels").isObject()) {
        emitChannels(context, root.path("channels"), root.path("auth"));
      } else if (toolResponse.status() >= 400) {
        context.emitError(
            safeTrim(toolBody).isBlank() ? "Contacts tool returned an error." : toolBody.trim()
        );
      } else {
        String message = safeTrim(planned.getUser()).isBlank() ? "Contacts response ready." : planned.getUser();
        context.emitChat(message, "final");
      }
      return true;
    } catch (TimeoutException e) {
      context.emitError("Contacts tool call timed out.");
      return true;
    } catch (Exception e) {
      context.emitError("Contacts agent failed: " + safeTrim(e.getMessage()));
      return true;
    }
  }

  @Action(group = MAIN_GROUP,
      command = "capabilities",
      description = "Return runtime capability manifest for discovery")
  public Boolean capabilities(CommandContext context) {
    AgentCapabilityManifest manifest = new ContactsAgentCapabilities().manifest();
    context.emitStatus(renderCapabilitiesEnvelope(manifest), "final");
    return true;
  }

  private CommandAgentResponse plan(LLMClient llm, String prompt, AgentContext context) throws Exception {
    String raw = llm.chat(
        agentDefinition.getSystemPrompt(),
        prompt,
        mapper.writeValueAsString(context.getAll())
    );
    JsonNode root = extractFirstJsonBlock(raw);
    String request = readPath(root, "request");
    String action = readPath(root, "action");
    String user = readPath(root, "user");
    String html = readPath(root, "html");
    return new CommandAgentResponse(request, action, user, html);
  }

  private void emitChannels(CommandContext context, JsonNode channels, JsonNode auth) {
    String chatMessage = readPath(channels.path("chat"), "message");
    String statusMessage = readPath(channels.path("status"), "message");
    JsonNode htmlNode = channels.has("html") ? channels.path("html") : channels.path("webview");
    String html = readPath(htmlNode, "html");
    String htmlMode = readPath(htmlNode, "mode");
    boolean replace = !htmlNode.isMissingNode() && htmlNode.path("replace").asBoolean(true);
    String authJson = auth != null && auth.isObject() ? auth.toString() : null;

    String finalChannel = authJson != null ? "auth"
        : (!html.isBlank() || "suggestions_from_toolsteps".equalsIgnoreCase(htmlMode)) ? "html"
        : !chatMessage.isBlank() ? "chat"
        : !statusMessage.isBlank() ? "status"
        : "";

    if (!statusMessage.isBlank()) {
      context.emitStatus(statusMessage, "status".equals(finalChannel) ? "final" : "progress");
    }
    if (!chatMessage.isBlank()) {
      context.emitChat(chatMessage, "chat".equals(finalChannel) ? "final" : "progress");
    }
    if (!html.isBlank() || "suggestions_from_toolsteps".equalsIgnoreCase(htmlMode)) {
      context.emitHtml(html, "html".equals(finalChannel) ? "final" : "progress",
          htmlMode.isBlank() ? "html" : htmlMode, replace);
    }
    if (authJson != null) {
      context.emitAuthJson(authJson, "final");
    }
    if (finalChannel.isBlank()) {
      context.emitError("Contacts response is missing supported channel content.");
    }
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

  private JsonNode parseJsonOrNull(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return mapper.readTree(text);
    } catch (Exception e) {
      return null;
    }
  }

  private JsonNode extractFirstJsonBlock(String raw) {
    if (raw == null) {
      return mapper.createObjectNode();
    }
    int start = raw.indexOf('{');
    int end = raw.lastIndexOf('}');
    if (start < 0 || end < start) {
      return mapper.createObjectNode();
    }
    String candidate = raw.substring(start, end + 1);
    try {
      return mapper.readTree(candidate);
    } catch (IOException ignored) {
      return mapper.createObjectNode();
    }
  }

  private String readPath(JsonNode root, String dottedPath) {
    if (root == null || dottedPath == null || dottedPath.isBlank()) {
      return "";
    }
    JsonNode current = root;
    for (String part : dottedPath.split("\\.")) {
      if (current == null || current.isMissingNode()) {
        return "";
      }
      current = current.path(part);
    }
    if (current == null || current.isMissingNode() || current.isNull()) {
      return "";
    }
    String value = current.asText("");
    return value == null ? "" : value.trim();
  }

  private String safeTrim(String value) {
    return value == null ? "" : value.trim();
  }
}
