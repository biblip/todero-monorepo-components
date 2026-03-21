package com.shellaia.agent.contacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.ai.action.CommandAgentResponse;
import com.social100.todero.common.ai.agent.AgentContext;
import com.social100.todero.common.ai.agent.AgentDefinition;
import com.social100.todero.common.ai.llm.LLMClient;
import com.social100.todero.common.ai.llm.OpenAiLLM;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.aiatpio.AiatpTerminalResult;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.lineparser.LineParserUtil;
import com.social100.todero.common.routing.AgentCapabilityManifest;
import com.social100.todero.common.routing.ToolAgentCapabilitySupport;
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

import static com.social100.todero.common.config.Util.parseDotenv;

@AIAController(name = "com.shellaia.agent.contacts",
    type = ServerType.AI,
    visible = false,
    description = "Contacts agent",
    capabilityProvider = ContactsAgentCapabilities.class)
public class AgentContactsComponent {
  private static final String MAIN_GROUP = "Main";
  private static final String CONTACTS_COMPONENT = "com.shellaia.contacts";

  private final AgentDefinition agentDefinition;
  private final ObjectMapper mapper = new ObjectMapper();
  private final Storage storage;
  private final String openApiKey;

  public AgentContactsComponent(Storage storage) {
    this.storage = storage;
    this.agentDefinition = AgentDefinition.builder()
        .name("Contacts Agent")
        .role("Assistant")
        .description("Contacts add/list/find/group/remove orchestration agent")
        .model("system")
        .systemPrompt(loadSystemPrompt("prompts/default-system-prompt.md"))
        .build();
    String key;
    try {
      byte[] envBytes = storage.readFile(".env");
      key = parseDotenv(envBytes).getOrDefault("OPENAI_API_KEY", "");
    } catch (IOException e) {
      key = "";
    }
    this.openApiKey = key;
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
    String prompt = requestBody(context);
    if (prompt.isBlank()) {
      context.completeJson(400, renderEnvelope(
          "Prompt is required. Usage: process <goal>",
          "Prompt is required. Usage: process <goal>",
          null,
          "invalid_request"
      ));
      return true;
    }

    try {
      AgentContext agentContext = new AgentContext();
      context.bindAgentLlmRegistry(agentContext);
      LLMClient llm = agentContext.systemLlm()
          .map(instance -> instance.client())
          .orElseThrow(() -> new IllegalStateException(
              "No system-wide LLM available in registry for agent com.shellaia.agent.contacts"));

      CommandAgentResponse planned = plan(llm, prompt, agentContext);
      String action = safeTrim(planned.getAction());
      if (action.isBlank() || "none".equalsIgnoreCase(action)) {
        String message = safeTrim(planned.getUser()).isBlank() ? "No action required." : planned.getUser();
        context.completeJson(200, renderEnvelope(message, message, null, null));
        return true;
      }

      LineParserUtil.ParsedLine parsed = LineParserUtil.parse(action);
      String command = safeTrim(parsed.first).toLowerCase(Locale.ROOT);
      String args = safeTrim(parsed.second + (parsed.remaining == null ? "" : " " + parsed.remaining));
      Set<String> allowedCommands = ToolAgentCapabilitySupport.commandNames(
          ToolAgentCapabilitySupport.toolManifest(context, CONTACTS_COMPONENT));
      if (!allowedCommands.contains(command)) {
        String message = "Unsupported Contacts action: " + command;
        context.completeJson(400, renderEnvelope(message, message, null, "unsupported_action"));
        return true;
      }

      CompletableFuture<AiatpTerminalResult> outFuture = new CompletableFuture<>();
      CommandContext internalContext = CommandContext.builder()
          .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/" + CONTACTS_COMPONENT + "/" + command,
              AiatpIO.Body.ofString(args, StandardCharsets.UTF_8)))
          .terminalConsumer(outFuture::complete)
          .build();
      context.execute(CONTACTS_COMPONENT, command, internalContext);

      AiatpTerminalResult toolResponse = outFuture.get(25, TimeUnit.SECONDS);
      String toolBody = terminalBody(toolResponse);

      JsonNode root = parseJsonOrNull(toolBody);
      if (root != null && root.path("channels").isObject()) {
        context.completeJson(200, toolBody);
      } else if ("failure".equalsIgnoreCase(safeTrim(toolResponse.getOutcome()))) {
        String message = safeTrim(toolBody).isBlank() ? "Contacts tool returned an error." : toolBody.trim();
        context.completeJson(500, renderEnvelope(message, message, null, "tool_failure"));
      } else {
        String message = safeTrim(planned.getUser()).isBlank() ? "Contacts response ready." : planned.getUser();
        context.completeJson(200, renderEnvelope(message, message, null, null));
      }
      return true;
    } catch (TimeoutException e) {
      context.completeJson(504, renderEnvelope("Contacts tool call timed out.", "Contacts tool call timed out.", null, "timeout"));
      return true;
    } catch (Exception e) {
      String message = "Contacts agent failed: " + safeTrim(e.getMessage());
      context.completeJson(500, renderEnvelope(message, message, null, "agent_failed"));
      return true;
    }
  }

  @Action(group = MAIN_GROUP,
      command = "capabilities",
      description = "Return runtime capability manifest for discovery")
  public Boolean capabilities(CommandContext context) {
    try {
      AgentCapabilityManifest manifest = ToolAgentCapabilitySupport.resolveManifest(
          storage,
          context,
          new ContactsAgentCapabilities().manifest(),
          CONTACTS_COMPONENT,
          buildLlmClient(context)
      );
      context.completeJson(200, renderCapabilitiesEnvelope(manifest));
    } catch (Exception e) {
      context.completeJson(500, renderEnvelope(
          "Contacts capability manifest could not be generated.",
          "Contacts capability manifest could not be generated.",
          null,
          "capability_manifest_generate_failed"
      ));
    }
    return true;
  }

  private LLMClient buildLlmClient(CommandContext context) {
    AgentContext agentContext = new AgentContext();
    if (context != null) {
      context.bindAgentLlmRegistry(agentContext);
    }
    return agentContext.systemLlm()
        .map(instance -> instance.client())
        .orElseGet(() -> safeTrim(openApiKey).isBlank() ? null : new OpenAiLLM(openApiKey, "gpt-4.1-mini"));
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

  private static String requestBody(CommandContext context) {
    if (context == null || context.getAiatpRequest() == null || context.getAiatpRequest().getBody() == null) {
      return "";
    }
    String body = AiatpIO.bodyToString(context.getAiatpRequest().getBody(), StandardCharsets.UTF_8);
    return body == null ? "" : body.trim();
  }

  private static String terminalBody(AiatpTerminalResult result) {
    if (result == null || result.getBody() == null) {
      return "";
    }
    String body = AiatpIO.bodyToString(result.getBody(), StandardCharsets.UTF_8);
    return body == null ? "" : body;
  }

  private String renderCapabilitiesEnvelope(AgentCapabilityManifest manifest) {
    try {
      return "{"
          + "\"status\":\"ok\","
          + "\"source\":\"runtime_capabilities_action\","
          + "\"channels\":{"
          + "\"chat\":{\"message\":\"Contacts agent capabilities ready.\"},"
          + "\"status\":{\"message\":\"Capabilities ready.\"},"
          + "\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}"
          + "},"
          + "\"manifest\":" + mapper.writeValueAsString(manifest)
          + "}";
    } catch (Exception e) {
      return renderEnvelope(
          "Contacts capability manifest could not be encoded.",
          "Contacts capability manifest could not be encoded.",
          null,
          "capability_manifest_encode_failed"
      );
    }
  }

  private String renderEnvelope(String chatMessage, String statusMessage, String html, String errorCode) {
    ObjectNode root = mapper.createObjectNode();
    ObjectNode channels = root.putObject("channels");
    channels.putObject("chat").put("message", safeTrim(chatMessage));
    channels.putObject("status").put("message", safeTrim(statusMessage));
    ObjectNode webview = channels.putObject("html");
    if (html == null || html.isBlank()) {
      webview.putNull("html");
      webview.put("mode", "none");
    } else {
      webview.put("html", html);
      webview.put("mode", "html");
    }
    webview.put("replace", false);
    if (!safeTrim(errorCode).isEmpty()) {
      ObjectNode meta = root.putObject("meta");
      meta.put("errorCode", safeTrim(errorCode));
    }
    return root.toString();
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

  private static String safeTrim(String value) {
    return value == null ? "" : value.trim();
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

}
