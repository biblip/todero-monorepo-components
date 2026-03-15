package com.example.todero.agent.router;

import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpIORequestWrapper;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
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

class RouterAgentPreDispatchRequiredArgsTest {

  @Test
  void doesNotBlockPositionalRequiredArgsInNaturalLanguagePrompt() {
    StubManager manager = new StubManager();
    RouterAgentComponent router = new RouterAgentComponent(new EmptyStorage());
    AtomicReference<AiatpIORequestWrapper> out = new AtomicReference<>();

    String prompt = "play music";
    CommandContext context = CommandContext.builder()
        .sourceId("sess-positional")
        .componentManager(manager)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.verbatim.agent.router/process",
            AiatpIO.Body.ofString(prompt, StandardCharsets.UTF_8)))
        .eventConsumer(out::set)
        .build();

    router.process(context);

    assertEquals("chat", out.get().getAiatpEvent().getChannel());
    assertEquals("ok", AiatpIO.bodyToString(out.get().getAiatpEvent().getBody(), StandardCharsets.UTF_8));
    assertEquals(prompt, manager.lastDelegatedPrompt.get());
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
          .agentName("com.shellaia.verbatim.agent.dj.v2")
          .commands(List.of(
              AgentCommandSchema.builder().name("play").requiredArgs(List.of("<query|uri>")).build(),
              AgentCommandSchema.builder().name("process").requiredArgs(List.of()).build(),
              AgentCommandSchema.builder().name("capabilities").requiredArgs(List.of()).build()
          ))
          .build();
      return List.of(ComponentDescriptor.builder()
          .name("com.shellaia.verbatim.agent.dj.v2")
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
      if ("com.shellaia.verbatim.agent.dj.v2".equals(componentName) && "process".equals(command)) {
        lastDelegatedPrompt.set(AiatpIO.bodyToString(context.getAiatpRequest().getBody(), StandardCharsets.UTF_8));
        context.completeJson(200,
            "{\"channels\":{\"chat\":{\"message\":\"ok\"},\"status\":{\"message\":\"ok\"},\"webview\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}");
        return;
      }
      if ("com.shellaia.verbatim.agent.dj.v2".equals(componentName) && "capabilities".equals(command)) {
        context.completeJson(200,
            "{\"manifest\":{\"contractVersion\":1,\"agentName\":\"com.shellaia.verbatim.agent.dj.v2\",\"commands\":[{\"name\":\"play\",\"requiredArgs\":[\"<query|uri>\"]},{\"name\":\"process\"},{\"name\":\"capabilities\"}]}}");
        return;
      }
      context.completeJson(404, "{\"error\":\"not_found\"}");
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
