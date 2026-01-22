package com.example.todero.agent.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.base.ComponentManagerInterface;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.model.component.ComponentDescriptor;
import com.social100.todero.common.routing.AgentCapabilityManifest;
import com.social100.todero.common.routing.AgentCommandSchema;
import com.social100.todero.common.storage.Storage;
import com.social100.todero.console.base.OutputType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterAgentDelegatedAuthE2ETest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void androidStyleCallbackFlowViaRouterAgent() throws Exception {
    StubManager manager = new StubManager();
    RouterAgentComponent router = new RouterAgentComponent(new EmptyStorage());

    AtomicReference<String> firstResponse = new AtomicReference<>();
    CommandContext firstContext = baseContext(manager, "android-sess-1", "play caribbean blue by enya", firstResponse);
    router.process(firstContext);

    JsonNode firstJson = MAPPER.readTree(firstResponse.get());
    assertTrue(firstJson.path("auth").path("required").asBoolean(false));
    assertEquals("spotify", firstJson.path("auth").path("provider").asText());
    assertTrue(firstJson.path("channels").path("status").path("message").asText().length() > 0);

    String callbackPrompt = "auth-complete session-id=sess-1 state=st1 code=code1 "
        + "secureEnvelope={\"opaquePayload\":\"xyz\",\"integrity\":\"sig\"}";

    AtomicReference<String> secondResponse = new AtomicReference<>();
    CommandContext secondContext = baseContext(manager, "android-sess-1", callbackPrompt, secondResponse);
    router.process(secondContext);

    assertEquals(manager.authCompleteDelegatedBody, secondResponse.get());
    assertEquals(callbackPrompt, manager.lastDelegatedPrompt.get());
  }

  @Test
  void consoleDelegatedCallbackFlowViaRouterAgent() {
    StubManager manager = new StubManager();
    RouterAgentComponent router = new RouterAgentComponent(new EmptyStorage());

    String callbackPrompt = "auth-complete session-id=sess-1 state=st1 code=code1 "
        + "secureEnvelope={\"opaquePayload\":\"xyz\",\"integrity\":\"sig\"}";

    AtomicReference<String> response = new AtomicReference<>();
    CommandContext context = baseContext(manager, "console-sess-1", callbackPrompt, response);
    router.process(context);

    assertEquals(manager.authCompleteDelegatedBody, response.get());
    assertEquals(callbackPrompt, manager.lastDelegatedPrompt.get());
  }

  private static CommandContext baseContext(StubManager manager,
                                            String sourceId,
                                            String prompt,
                                            AtomicReference<String> out) {
    return CommandContext.builder()
        .sourceId(sourceId)
        .componentManager(manager)
        .httpRequest(AiatpIO.HttpRequest.newBuilder("ACTION", "/com.shellaia.verbatim.agent.router/process")
            .body(AiatpIO.Body.ofString(prompt, StandardCharsets.UTF_8))
            .build())
        .consumer(r -> out.set(AiatpIO.bodyToString(r.body(), StandardCharsets.UTF_8)))
        .build();
  }

  private static final class StubManager implements ComponentManagerInterface {
    private final AtomicReference<String> lastDelegatedPrompt = new AtomicReference<>("");

    private final String authRequiredDelegatedBody = "{"
        + "\"request\":\"play caribbean blue by enya\","
        + "\"action\":\"none\","
        + "\"user\":\"Authorization required.\","
        + "\"auth\":{"
        + "\"required\":true,"
        + "\"provider\":\"spotify\","
        + "\"sessionId\":\"sess-1\","
        + "\"authorizeUrl\":\"https://accounts.spotify.com/authorize?x=1\","
        + "\"expiresAtMs\":1770000000000,"
        + "\"secureEnvelope\":{\"opaquePayload\":\"xyz\",\"integrity\":\"sig\"},"
        + "\"relayPolicy\":{\"opaque\":true,\"inspectAllowed\":false,\"maxTtlSec\":300}"
        + "},"
        + "\"channels\":{"
        + "\"chat\":{\"message\":\"Authorization required.\"},"
        + "\"status\":{\"message\":\"Open authorization URL.\"},"
        + "\"webview\":{\"html\":null,\"mode\":\"none\",\"replace\":false}"
        + "}"
        + "}";

    private final String authCompleteDelegatedBody = "{"
        + "\"request\":\"auth-complete\","
        + "\"action\":\"none\","
        + "\"user\":\"Authorization completed.\","
        + "\"channels\":{"
        + "\"chat\":{\"message\":\"Authorization completed.\"},"
        + "\"status\":{\"message\":\"Retrying requested command.\"},"
        + "\"webview\":{\"html\":null,\"mode\":\"none\",\"replace\":false}"
        + "}"
        + "}";

    @Override
    public List<String> generateAutocompleteStrings() {
      return List.of();
    }

    @Override
    public String getHelp(String componentName, String commandName, OutputType outputType) {
      return "{}";
    }

    @Override
    public List<ComponentDescriptor> getComponents(boolean includeHidden, ServerType typeFilter) {
      AgentCapabilityManifest manifest = AgentCapabilityManifest.builder()
          .contractVersion(1)
          .agentName("com.shellaia.verbatim.agent.dj")
          .commands(List.of(
              AgentCommandSchema.builder().name("process").build(),
              AgentCommandSchema.builder().name("capabilities").build()
          ))
          .build();
      return List.of(ComponentDescriptor.builder()
          .name("com.shellaia.verbatim.agent.dj")
          .type(ServerType.AI)
          .visible(false)
          .agentCapabilityManifest(manifest)
          .build());
    }

    @Override
    public ComponentDescriptor getComponent(String componentName, boolean includeHidden) {
      return null;
    }

    @Override
    public void execute(String componentName, String command, CommandContext context, boolean useComponentsAll) {
      if (!"com.shellaia.verbatim.agent.dj".equals(componentName) || !"process".equals(command)) {
        context.response(AiatpIO.HttpResponse.newBuilder(404)
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .body(AiatpIO.Body.ofString("{\"error\":\"not_found\"}", StandardCharsets.UTF_8))
            .build());
        return;
      }

      String prompt = AiatpIO.bodyToString(context.getHttpRequest().body(), StandardCharsets.UTF_8);
      lastDelegatedPrompt.set(prompt);

      String body = prompt.startsWith("auth-complete ")
          ? authCompleteDelegatedBody
          : authRequiredDelegatedBody;

      context.response(AiatpIO.HttpResponse.newBuilder(200)
          .setHeader("Content-Type", "application/json; charset=utf-8")
          .body(AiatpIO.Body.ofString(body, StandardCharsets.UTF_8))
          .build());
    }
  }

  private static final class EmptyStorage implements Storage {
    @Override
    public void writeFile(String relativePath, byte[] bytes) {
    }

    @Override
    public byte[] readFile(String relativePath) throws IOException {
      throw new IOException("not found");
    }

    @Override
    public void deleteFile(String relativePath) {
    }

    @Override
    public List<String> listFiles(String relativeDir) {
      return List.of();
    }

    @Override
    public void putSecret(String key, String value) {
    }

    @Override
    public String getSecret(String key) {
      return null;
    }

    @Override
    public void deleteSecret(String key) {
    }
  }
}
