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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterAgentEventNativeDelegationTest {

  @Test
  void processCompletesFromDelegatedTerminalEvent() {
    EventNativeManager manager = new EventNativeManager(true);
    RouterAgentComponent router = new RouterAgentComponent(new EmptyStorage());
    AtomicReference<AiatpIORequestWrapper> last = new AtomicReference<>();
    List<AiatpIORequestWrapper> seen = new CopyOnWriteArrayList<>();

    CommandContext context = CommandContext.builder()
        .sourceId("sess-event-native")
        .componentManager(manager)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.verbatim.agent.router/process",
            AiatpIO.Body.ofString("play music", StandardCharsets.UTF_8)))
        .eventConsumer(wrapper -> {
          seen.add(wrapper);
          last.set(wrapper);
        })
        .build();

    router.process(context);

    assertTrue(manager.processSawProgress.get());
    assertEquals("chat", last.get().getAiatpEvent().getChannel());
    assertEquals("done", AiatpIO.bodyToString(last.get().getAiatpEvent().getBody(), StandardCharsets.UTF_8));
    assertTrue(seen.stream().anyMatch(wrapper -> "status".equals(wrapper.getAiatpEvent().getChannel())));
  }

  @Test
  void capabilitiesProbeCompletesFromDelegatedTerminalEvent() {
    EventNativeManager manager = new EventNativeManager(false);
    RouterAgentComponent router = new RouterAgentComponent(new EmptyStorage());
    AtomicReference<AiatpIORequestWrapper> out = new AtomicReference<>();

    CommandContext context = CommandContext.builder()
        .sourceId("sess-capabilities-event")
        .componentManager(manager)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.verbatim.agent.router/process",
            AiatpIO.Body.ofString("play music", StandardCharsets.UTF_8)))
        .eventConsumer(out::set)
        .build();

    router.process(context);

    assertTrue(manager.capabilitiesSawProgress.get());
    assertEquals("chat", out.get().getAiatpEvent().getChannel());
    assertEquals("done", AiatpIO.bodyToString(out.get().getAiatpEvent().getBody(), StandardCharsets.UTF_8));
  }

  private static final class EventNativeManager implements ComponentManagerInterface {
    private final boolean descriptorHasManifest;
    private final AtomicBoolean processSawProgress = new AtomicBoolean(false);
    private final AtomicBoolean capabilitiesSawProgress = new AtomicBoolean(false);

    private EventNativeManager(boolean descriptorHasManifest) {
      this.descriptorHasManifest = descriptorHasManifest;
    }

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
      ComponentDescriptor.ComponentDescriptorBuilder builder = ComponentDescriptor.builder()
          .name("com.shellaia.verbatim.agent.dj.v2")
          .type(ServerType.AI)
          .visible(false);
      if (descriptorHasManifest) {
        builder.agentCapabilityManifest(AgentCapabilityManifest.builder()
            .contractVersion(1)
            .agentName("com.shellaia.verbatim.agent.dj.v2")
            .commands(List.of(
                AgentCommandSchema.builder().name("process").build(),
                AgentCommandSchema.builder().name("capabilities").build()
            ))
            .build());
      }
      return List.of(builder.build());
    }

    @Override
    public ComponentDescriptor getComponent(String componentName, boolean includeHidden) {
      return null;
    }

    @Override
    public void execute(String componentName, String command, CommandContext context, boolean useComponentsAll) {
      if (!"com.shellaia.verbatim.agent.dj.v2".equals(componentName)) {
        context.emitError("not_found");
        return;
      }
      if ("capabilities".equals(command)) {
        capabilitiesSawProgress.set(true);
        context.emitStatus("probing", "progress");
        context.emitChat("{\"manifest\":{\"contractVersion\":1,\"agentName\":\"com.shellaia.verbatim.agent.dj.v2\",\"commands\":[{\"name\":\"process\"},{\"name\":\"capabilities\"}]}}", "final");
        return;
      }
      if ("process".equals(command)) {
        processSawProgress.set(true);
        context.emitStatus("delegated-working", "progress");
        context.emitChat("{\"channels\":{\"chat\":{\"message\":\"done\"},\"status\":{\"message\":\"ok\"},\"webview\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}", "final");
        return;
      }
      context.emitError("not_found");
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
