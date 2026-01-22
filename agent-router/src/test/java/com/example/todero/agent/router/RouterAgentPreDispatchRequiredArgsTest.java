package com.example.todero.agent.router;

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
import static org.junit.jupiter.api.Assertions.assertNull;

class RouterAgentPreDispatchRequiredArgsTest {

  @Test
  void doesNotBlockPositionalRequiredArgsInNaturalLanguagePrompt() {
    StubManager manager = new StubManager();
    RouterAgentComponent router = new RouterAgentComponent(new EmptyStorage());
    AtomicReference<String> out = new AtomicReference<>();

    String prompt = "play music";
    CommandContext context = CommandContext.builder()
        .sourceId("sess-positional")
        .componentManager(manager)
        .httpRequest(AiatpIO.HttpRequest.newBuilder("ACTION", "/com.shellaia.verbatim.agent.router/process")
            .body(AiatpIO.Body.ofString(prompt, StandardCharsets.UTF_8))
            .build())
        .consumer(r -> out.set(AiatpIO.bodyToString(r.body(), StandardCharsets.UTF_8)))
        .build();

    router.process(context);

    String body = out.get();
    com.fasterxml.jackson.databind.JsonNode root = parse(body);
    assertNull(root.path("error").isNull() ? null : root.path("error").asText(null));
    assertEquals(prompt, manager.lastDelegatedPrompt.get());
  }

  private static com.fasterxml.jackson.databind.JsonNode parse(String json) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
    } catch (Exception e) {
      throw new AssertionError("Invalid JSON: " + json, e);
    }
  }

  private static final class StubManager implements ComponentManagerInterface {
    private final AtomicReference<String> lastDelegatedPrompt = new AtomicReference<>("");

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
              AgentCommandSchema.builder().name("play").requiredArgs(List.of("<query|uri>")).build(),
              AgentCommandSchema.builder().name("process").requiredArgs(List.of()).build(),
              AgentCommandSchema.builder().name("capabilities").requiredArgs(List.of()).build()
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
      if ("com.shellaia.verbatim.agent.dj".equals(componentName) && "process".equals(command)) {
        lastDelegatedPrompt.set(AiatpIO.bodyToString(context.getHttpRequest().body(), StandardCharsets.UTF_8));
        context.response(AiatpIO.HttpResponse.newBuilder(200)
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .body(AiatpIO.Body.ofString(
                "{\"channels\":{\"chat\":{\"message\":\"ok\"},\"status\":{\"message\":\"ok\"},\"webview\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}",
                StandardCharsets.UTF_8))
            .build());
        return;
      }
      if ("com.shellaia.verbatim.agent.dj".equals(componentName) && "capabilities".equals(command)) {
        context.response(AiatpIO.HttpResponse.newBuilder(200)
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .body(AiatpIO.Body.ofString(
                "{\"manifest\":{\"contractVersion\":1,\"agentName\":\"com.shellaia.verbatim.agent.dj\",\"commands\":[{\"name\":\"play\",\"requiredArgs\":[\"<query|uri>\"]},{\"name\":\"process\"},{\"name\":\"capabilities\"}]}}",
                StandardCharsets.UTF_8))
            .build());
        return;
      }
      context.response(AiatpIO.HttpResponse.newBuilder(404)
          .setHeader("Content-Type", "application/json; charset=utf-8")
          .body(AiatpIO.Body.ofString("{\"error\":\"not_found\"}", StandardCharsets.UTF_8))
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

