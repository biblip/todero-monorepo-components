package com.shellaia.agent.router;

import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpIORequestWrapper;
import com.social100.todero.common.aiatpio.AiatpResponse;
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
    AtomicReference<AiatpResponse> response = new AtomicReference<>();

    CommandContext context = CommandContext.builder()
        .sourceId("sess-event-native")
        .componentManager(manager)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.router/process",
            AiatpIO.Body.ofString("play music", StandardCharsets.UTF_8)))
        .eventConsumer(wrapper -> {
          seen.add(wrapper);
          last.set(wrapper);
        })
        .responseConsumer(response::set)
        .build();

    router.process(context);

    assertTrue(manager.processSawProgress.get());
    assertEquals("chat", last.get().getAiatpEvent().getChannel());
    assertTrue(seen.stream().anyMatch(wrapper -> "chat".equals(wrapper.getAiatpEvent().getChannel())));
    assertEquals("chat", response.get().getChannel());
    assertTrue(AiatpIO.bodyToString(response.get().getBody(), StandardCharsets.UTF_8).contains("\"done\""));
  }

  @Test
  void capabilitiesProbeCompletesFromDelegatedTerminalEvent() {
    EventNativeManager manager = new EventNativeManager(false);
    RouterAgentComponent router = new RouterAgentComponent(new EmptyStorage());
    AtomicReference<AiatpIORequestWrapper> out = new AtomicReference<>();
    AtomicReference<AiatpResponse> response = new AtomicReference<>();

    CommandContext context = CommandContext.builder()
        .sourceId("sess-capabilities-event")
        .componentManager(manager)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.router/process",
            AiatpIO.Body.ofString("play music", StandardCharsets.UTF_8)))
        .eventConsumer(out::set)
        .responseConsumer(response::set)
        .build();

    router.process(context);

    assertTrue(manager.capabilitiesSawProgress.get());
    assertEquals("chat", out.get().getAiatpEvent().getChannel());
    assertEquals("chat", response.get().getChannel());
    assertTrue(AiatpIO.bodyToString(response.get().getBody(), StandardCharsets.UTF_8).contains("\"done\""));
  }

  @Test
  void processForwardsDelegatedTerminalHtmlEvent() {
    HtmlEventManager manager = new HtmlEventManager();
    RouterAgentComponent router = new RouterAgentComponent(new EmptyStorage());
    AtomicReference<AiatpIORequestWrapper> last = new AtomicReference<>();
    List<AiatpIORequestWrapper> seen = new CopyOnWriteArrayList<>();

    CommandContext context = CommandContext.builder()
        .sourceId("sess-html-forward")
        .componentManager(manager)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.router/process",
            AiatpIO.Body.ofString("play music", StandardCharsets.UTF_8)))
        .eventConsumer(wrapper -> {
          seen.add(wrapper);
          last.set(wrapper);
        })
        .build();

    router.process(context);

    assertTrue(seen.stream().anyMatch(wrapper -> "html".equals(wrapper.getAiatpEvent().getChannel())));
    assertEquals("html", last.get().getAiatpEvent().getChannel());
    assertEquals("<html>done</html>", AiatpIO.bodyToString(last.get().getAiatpEvent().getBody(), StandardCharsets.UTF_8));
  }

  @Test
  void processFinalizesImmediatelyWhenOutOfScopeHasNoAlternateRoute() {
    OutOfScopeControlManager manager = new OutOfScopeControlManager(false);
    RouterAgentComponent router = new RouterAgentComponent(new EmptyStorage());
    List<AiatpIORequestWrapper> seen = new CopyOnWriteArrayList<>();
    AtomicReference<AiatpResponse> response = new AtomicReference<>();

    CommandContext context = CommandContext.builder()
        .sourceId("sess-out-of-scope")
        .componentManager(manager)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.router/process",
            AiatpIO.Body.ofString("send an email to june", StandardCharsets.UTF_8)))
        .eventConsumer(seen::add)
        .responseConsumer(response::set)
        .build();

    router.process(context);

    assertEquals(1, manager.processExecuteCount);
    assertTrue(seen.stream().noneMatch(wrapper ->
        "chat".equals(wrapper.getAiatpEvent().getChannel())
            && "Rerouting to another agent.".equals(AiatpIO.bodyToString(wrapper.getAiatpEvent().getBody(), StandardCharsets.UTF_8))));
    String responseBody = AiatpIO.bodyToString(response.get().getBody(), StandardCharsets.UTF_8);
    assertTrue(responseBody.contains("No available agent can handle"));
  }

  @Test
  void processReroutesOnceOnCanonicalOutOfScopeSignalWhenAlternateExists() {
    OutOfScopeControlManager manager = new OutOfScopeControlManager(true);
    RouterAgentComponent router = new RouterAgentComponent(new EmptyStorage());
    List<AiatpIORequestWrapper> seen = new CopyOnWriteArrayList<>();
    AtomicReference<AiatpResponse> response = new AtomicReference<>();

    CommandContext context = CommandContext.builder()
        .sourceId("sess-out-of-scope-alt")
        .componentManager(manager)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.router/process",
            AiatpIO.Body.ofString("send an email to june", StandardCharsets.UTF_8)))
        .eventConsumer(seen::add)
        .responseConsumer(response::set)
        .build();

    router.process(context);

    assertEquals(1, manager.djProcessExecuteCount);
    assertEquals(1, manager.altProcessExecuteCount);
    assertTrue(seen.stream().anyMatch(wrapper ->
        "chat".equals(wrapper.getAiatpEvent().getChannel())
            && "Rerouting to another agent.".equals(AiatpIO.bodyToString(wrapper.getAiatpEvent().getBody(), StandardCharsets.UTF_8))));
    assertTrue(AiatpIO.bodyToString(response.get().getBody(), StandardCharsets.UTF_8).contains("handled by alternate agent"));
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
          .name("com.shellaia.agent.dj")
          .type(ServerType.AI)
          .visible(false);
      if (descriptorHasManifest) {
        builder.agentCapabilityManifest(AgentCapabilityManifest.builder()
            .contractVersion(1)
            .agentName("com.shellaia.agent.dj")
            .routingHints(java.util.Map.of(
                "skillSummary", "Handles music playback requests and playlist control."
            ))
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
      if (!"com.shellaia.agent.dj".equals(componentName)) {
        context.emitChat("not_found", "error");
        return;
      }
      if ("capabilities".equals(command)) {
        capabilitiesSawProgress.set(true);
        context.emitChat("probing", "progress");
        context.completeJson(200, "{\"channels\":{\"chat\":{\"message\":\"capabilities ready\"},\"status\":{\"message\":\"capabilities ready\"},\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}},\"manifest\":{\"contractVersion\":1,\"agentName\":\"com.shellaia.agent.dj\",\"routingHints\":{\"skillSummary\":\"Handles music playback requests and playlist control.\"},\"commands\":[{\"name\":\"process\"},{\"name\":\"capabilities\"}]}}");
        return;
      }
      if ("process".equals(command)) {
        processSawProgress.set(true);
        context.emitChat("delegated-working", "progress");
        context.completeJson(200, "{\"channels\":{\"chat\":{\"message\":\"done\"},\"status\":{\"message\":\"ok\"},\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}");
        return;
      }
      context.emitChat("not_found", "error");
    }
  }

  private static final class HtmlEventManager implements ComponentManagerInterface {
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
      return List.of(ComponentDescriptor.builder()
          .name("com.shellaia.agent.dj")
          .type(ServerType.AI)
          .visible(false)
          .agentCapabilityManifest(AgentCapabilityManifest.builder()
              .contractVersion(1)
              .agentName("com.shellaia.agent.dj")
              .routingHints(java.util.Map.of(
                  "skillSummary", "Handles music playback requests and rich HTML responses."
              ))
              .build())
          .build());
    }

    @Override
    public ComponentDescriptor getComponent(String componentName, boolean includeHidden) {
      return null;
    }

    @Override
    public void execute(String componentName, String command, CommandContext context, boolean useComponentsAll) {
      if (!"com.shellaia.agent.dj".equals(componentName)) {
        context.emitChat("not_found", "error");
        return;
      }
      context.emitChat("delegated-working", "progress");
      context.emitHtml("<html>done</html>", "final", "html", true);
    }
  }

  private static final class OutOfScopeControlManager implements ComponentManagerInterface {
    private final boolean includeAlternate;
    private int processExecuteCount = 0;
    private int djProcessExecuteCount = 0;
    private int altProcessExecuteCount = 0;

    private OutOfScopeControlManager(boolean includeAlternate) {
      this.includeAlternate = includeAlternate;
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
      List<ComponentDescriptor> components = new java.util.ArrayList<>();
      components.add(ComponentDescriptor.builder()
          .name("com.shellaia.agent.dj")
          .type(ServerType.AI)
          .visible(false)
          .build());
      if (includeAlternate) {
        components.add(ComponentDescriptor.builder()
            .name("com.shellaia.agent.zzzalt")
            .type(ServerType.AI)
            .visible(false)
            .build());
      }
      return components;
    }

    @Override
    public ComponentDescriptor getComponent(String componentName, boolean includeHidden) {
      return null;
    }

    @Override
    public void execute(String componentName, String command, CommandContext context, boolean useComponentsAll) {
      if (!"process".equals(command)) {
        String agentName = "com.shellaia.agent.dj".equals(componentName)
            ? "com.shellaia.agent.dj"
            : "com.shellaia.agent.zzzalt";
        String skillSummary = "com.shellaia.agent.dj".equals(componentName)
            ? "Handles music playback requests and playlist control."
            : "Handles alternate communication workflows.";
        context.completeJson(200, "{\"channels\":{\"chat\":{\"message\":\"capabilities ready\"},\"status\":{\"message\":\"capabilities ready\"},\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}},\"manifest\":{\"contractVersion\":1,\"agentName\":\"" + agentName + "\",\"routingHints\":{\"skillSummary\":\"" + skillSummary + "\"},\"commands\":[{\"name\":\"process\"},{\"name\":\"capabilities\"}]}}");
        return;
      }
      if ("com.shellaia.agent.dj".equals(componentName)) {
        processExecuteCount++;
        djProcessExecuteCount++;
        context.completeJson(500,
            "{\"response\":{\"outcome\":\"unsupported_operation\",\"completed\":true},"
                + "\"meta\":{\"outcome\":\"unhandled_intent\",\"errorCode\":\"unsupported_operation\"},"
                + "\"channels\":{\"status\":{\"message\":\"I can't send emails. I can help with Spotify music playback.\"},"
                + "\"chat\":{\"message\":\"I can't send emails. I can help with Spotify music playback.\"},"
                + "\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}");
        return;
      }
      if ("com.shellaia.agent.zzzalt".equals(componentName)) {
        altProcessExecuteCount++;
        context.completeJson(200,
            "{\"response\":{\"outcome\":\"goal_completed\",\"completed\":true},"
                + "\"meta\":{\"outcome\":\"success\"},"
                + "\"channels\":{\"status\":{\"message\":\"handled by alternate agent\"},"
                + "\"chat\":{\"message\":\"handled by alternate agent\"},"
                + "\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}");
        return;
      }
      context.completeJson(500,
          "{\"response\":{\"outcome\":\"failure\",\"completed\":true},"
              + "\"meta\":{\"outcome\":\"failure\",\"errorCode\":\"not_found\"},"
              + "\"channels\":{\"status\":{\"message\":\"not found\"},\"chat\":{\"message\":\"not found\"},"
              + "\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}");
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
