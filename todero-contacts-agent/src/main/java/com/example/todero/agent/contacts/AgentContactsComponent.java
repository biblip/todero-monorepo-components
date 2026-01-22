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
      context.response(buildErrorResponse("Prompt is required. Usage: process <goal>", "invalid_request"));
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
        context.response(buildResponsePayload(planned, defaultChannels(planned.getUser(), "none", null, false), null, "none", ""));
        return true;
      }

      LineParserUtil.ParsedLine parsed = LineParserUtil.parse(action);
      String command = safeTrim(parsed.first).toLowerCase(Locale.ROOT);
      String args = safeTrim(parsed.second + (parsed.remaining == null ? "" : " " + parsed.remaining));
      if (!ALLOWED_COMMANDS.contains(command)) {
        context.response(buildErrorResponse("Unsupported Contacts action: " + command, "agent_capability_mismatch"));
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
      String toolStatus = toolResponse.status() >= 400 ? "error" : "ok";

      JsonNode root = parseJsonOrNull(toolBody);
      JsonNode channels = root == null ? null : root.path("channels");
      JsonNode data = root == null ? null : root.path("data");
      ObjectNode finalChannels = channels != null && channels.isObject()
          ? (ObjectNode) channels
          : defaultChannels(
          safeTrim(planned.getUser()).isBlank() ? "Contacts response ready." : planned.getUser(),
          "none",
          null,
          false
      );
      context.response(buildResponsePayload(planned, finalChannels, data, toolStatus, toolBody));
      return true;
    } catch (TimeoutException e) {
      context.response(buildErrorResponse("Contacts tool call timed out.", "timeout"));
      return true;
    } catch (Exception e) {
      context.response(buildErrorResponse("Contacts agent failed: " + safeTrim(e.getMessage()), "agent_failed"));
      return true;
    }
  }

  @Action(group = MAIN_GROUP,
      command = "capabilities",
      description = "Return runtime capability manifest for discovery")
  public Boolean capabilities(CommandContext context) {
    AgentCapabilityManifest manifest = new ContactsAgentCapabilities().manifest();
    context.response(renderCapabilitiesEnvelope(manifest));
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

  private String buildResponsePayload(CommandAgentResponse planned,
                                      ObjectNode channels,
                                      JsonNode data,
                                      String toolStatus,
                                      String toolResponse) {
    try {
      ObjectNode payload = mapper.createObjectNode();
      payload.put("request", safeTrim(planned.getRequest()));
      payload.put("action", safeTrim(planned.getAction()));
      payload.put("user", safeTrim(planned.getUser()));
      payload.put("html", safeTrim(planned.getHtml()));
      payload.set("channels", channels);
      if (data != null && data.isObject()) {
        payload.set("data", data);
      }
      payload.putNull("auth");
      payload.putNull("meta");
      payload.put("toolStatus", toolStatus);
      payload.put("toolResponse", toolResponse);
      payload.put("timestamp", Instant.now().toString());
      return mapper.writeValueAsString(payload);
    } catch (Exception e) {
      throw new RuntimeException("Failed to build Contacts agent response payload", e);
    }
  }

  private String buildErrorResponse(String message, String errorCode) {
    try {
      ObjectNode payload = mapper.createObjectNode();
      payload.put("request", "");
      payload.put("action", "none");
      payload.put("user", safeTrim(message));
      payload.put("html", "");
      payload.set("channels", defaultChannels(message, "none", null, false));
      payload.putNull("auth");
      ObjectNode meta = mapper.createObjectNode();
      meta.put("outcome", "error");
      meta.put("errorCode", safeTrim(errorCode));
      payload.set("meta", meta);
      payload.put("toolStatus", "error");
      payload.put("toolResponse", safeTrim(message));
      return mapper.writeValueAsString(payload);
    } catch (Exception e) {
      return "{\"request\":\"\",\"action\":\"none\",\"user\":\"Contacts agent failed.\",\"html\":\"\","
          + "\"channels\":{\"chat\":{\"message\":\"Contacts agent failed.\"},"
          + "\"status\":{\"message\":\"Contacts agent failed.\"},"
          + "\"webview\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}";
    }
  }

  private ObjectNode defaultChannels(String chatMessage, String webviewMode, String html, boolean replace) {
    ObjectNode channels = mapper.createObjectNode();
    ObjectNode chat = mapper.createObjectNode();
    chat.put("message", safeTrim(chatMessage));
    channels.set("chat", chat);
    ObjectNode status = mapper.createObjectNode();
    status.put("message", safeTrim(chatMessage));
    channels.set("status", status);
    ObjectNode webview = mapper.createObjectNode();
    if (html == null || html.isBlank()) {
      webview.putNull("html");
    } else {
      webview.put("html", html);
    }
    webview.put("mode", safeTrim(webviewMode).isBlank() ? "none" : webviewMode);
    webview.put("replace", replace);
    channels.set("webview", webview);
    return channels;
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
