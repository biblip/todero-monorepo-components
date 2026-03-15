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

  @Test
  void processForwardsDelegatedTerminalHtmlEvent() {
    HtmlEventManager manager = new HtmlEventManager();
    RouterAgentComponent router = new RouterAgentComponent(new EmptyStorage());
    AtomicReference<AiatpIORequestWrapper> last = new AtomicReference<>();
    List<AiatpIORequestWrapper> seen = new CopyOnWriteArrayList<>();

    CommandContext context = CommandContext.builder()
        .sourceId("sess-html-forward")
        .componentManager(manager)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.verbatim.agent.router/process",
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
  void processConsumesDelegatedTerminalControlEnvelope() {
    ControlEventManager manager = new ControlEventManager();
    RouterAgentComponent router = new RouterAgentComponent(new EmptyStorage());
    List<AiatpIORequestWrapper> seen = new CopyOnWriteArrayList<>();

    CommandContext context = CommandContext.builder()
        .sourceId("sess-control-forward")
        .componentManager(manager)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.verbatim.agent.router/process",
            AiatpIO.Body.ofString("suggest lions", StandardCharsets.UTF_8)))
        .eventConsumer(seen::add)
        .build();

    router.process(context);

    assertTrue(manager.sawUpstreamControl.get());
    assertTrue(seen.stream().anyMatch(wrapper -> "status".equals(wrapper.getAiatpEvent().getChannel())));
    assertTrue(seen.stream().anyMatch(wrapper -> "html".equals(wrapper.getAiatpEvent().getChannel())));
    AiatpIORequestWrapper terminal = seen.get(seen.size() - 1);
    assertEquals("html", terminal.getAiatpEvent().getChannel());
    assertEquals("<html>done</html>", AiatpIO.bodyToString(terminal.getAiatpEvent().getBody(), StandardCharsets.UTF_8));
  }

  @Test
  void processFinalizesImmediatelyWhenOutOfScopeHasNoAlternateRoute() {
    OutOfScopeControlManager manager = new OutOfScopeControlManager(false);
    RouterAgentComponent router = new RouterAgentComponent(new EmptyStorage());
    List<AiatpIORequestWrapper> seen = new CopyOnWriteArrayList<>();

    CommandContext context = CommandContext.builder()
        .sourceId("sess-out-of-scope")
        .componentManager(manager)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.verbatim.agent.router/process",
            AiatpIO.Body.ofString("send an email to june", StandardCharsets.UTF_8)))
        .eventConsumer(seen::add)
        .build();

    router.process(context);

    assertEquals(1, manager.processExecuteCount);
    assertTrue(seen.stream().noneMatch(wrapper ->
        "status".equals(wrapper.getAiatpEvent().getChannel())
            && "Rerouting to another agent.".equals(AiatpIO.bodyToString(wrapper.getAiatpEvent().getBody(), StandardCharsets.UTF_8))));
    assertTrue(seen.stream().anyMatch(wrapper ->
        "chat".equals(wrapper.getAiatpEvent().getChannel())
            && AiatpIO.bodyToString(wrapper.getAiatpEvent().getBody(), StandardCharsets.UTF_8).contains("can't send emails")));
  }

  @Test
  void processReroutesOnceOnCanonicalOutOfScopeSignalWhenAlternateExists() {
    OutOfScopeControlManager manager = new OutOfScopeControlManager(true);
    RouterAgentComponent router = new RouterAgentComponent(new EmptyStorage());
    List<AiatpIORequestWrapper> seen = new CopyOnWriteArrayList<>();

    CommandContext context = CommandContext.builder()
        .sourceId("sess-out-of-scope-alt")
        .componentManager(manager)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.verbatim.agent.router/process",
            AiatpIO.Body.ofString("send an email to june", StandardCharsets.UTF_8)))
        .eventConsumer(seen::add)
        .build();

    router.process(context);

    assertEquals(1, manager.djProcessExecuteCount);
    assertEquals(1, manager.altProcessExecuteCount);
    assertTrue(seen.stream().anyMatch(wrapper ->
        "status".equals(wrapper.getAiatpEvent().getChannel())
            && "Rerouting to another agent.".equals(AiatpIO.bodyToString(wrapper.getAiatpEvent().getBody(), StandardCharsets.UTF_8))));
    assertTrue(seen.stream().anyMatch(wrapper ->
        "chat".equals(wrapper.getAiatpEvent().getChannel())
            && AiatpIO.bodyToString(wrapper.getAiatpEvent().getBody(), StandardCharsets.UTF_8).contains("handled by alternate agent")));
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
          .name("com.shellaia.verbatim.agent.dj.v2")
          .type(ServerType.AI)
          .visible(false)
          .build());
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
      context.emitStatus("delegated-working", "progress");
      context.emitHtml("<html>done</html>", "final", "html", true);
    }
  }

  private static final class ControlEventManager implements ComponentManagerInterface {
    private final AtomicBoolean sawUpstreamControl = new AtomicBoolean(false);

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
          .name("com.shellaia.verbatim.agent.dj.v2")
          .type(ServerType.AI)
          .visible(false)
          .build());
    }

    @Override
    public ComponentDescriptor getComponent(String componentName, boolean includeHidden) {
      return null;
    }

    @Override
    public void execute(String componentName, String command, CommandContext context, boolean useComponentsAll) {
      sawUpstreamControl.set("true".equalsIgnoreCase(
          context.getAiatpRequest().getHeaders().getFirst("X-AIATP-Upstream-Control")));
      context.emitControlJson(
          "{\"kind\":\"progress\",\"outcome\":\"progress\",\"terminal\":false,"
              + "\"channels\":{\"status\":{\"message\":\"planning\"},\"chat\":{\"message\":\"\"},"
              + "\"webview\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}",
          "progress",
          "delegate_progress");
      context.emitControlJson(
          "{\"kind\":\"terminal\",\"outcome\":\"success\",\"terminal\":true,"
              + "\"meta\":{\"outcome\":\"success\"},"
              + "\"channels\":{\"status\":{\"message\":\"done\"},\"chat\":{\"message\":\"done\"},"
              + "\"webview\":{\"html\":\"<html>done</html>\",\"mode\":\"html\",\"replace\":true}}}",
          "final",
          "delegate_terminal");
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
          .name("com.shellaia.verbatim.agent.dj.v2")
          .type(ServerType.AI)
          .visible(false)
          .build());
      if (includeAlternate) {
        components.add(ComponentDescriptor.builder()
            .name("com.shellaia.verbatim.agent.zzzalt")
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
        String agentName = "com.shellaia.verbatim.agent.dj.v2".equals(componentName)
            ? "com.shellaia.verbatim.agent.dj.v2"
            : "com.shellaia.verbatim.agent.zzzalt";
        context.emitChat("{\"manifest\":{\"contractVersion\":1,\"agentName\":\"" + agentName + "\",\"commands\":[{\"name\":\"process\"},{\"name\":\"capabilities\"}]}}", "final");
        return;
      }
      if ("com.shellaia.verbatim.agent.dj.v2".equals(componentName)) {
        processExecuteCount++;
        djProcessExecuteCount++;
        context.emitControlJson(
            "{\"kind\":\"terminal\",\"outcome\":\"unhandled_intent\",\"terminal\":true,"
                + "\"payload\":{\"stopReason\":\"out_of_scope\",\"message\":\"I can't send emails.\"},"
                + "\"meta\":{\"outcome\":\"unhandled_intent\",\"errorCode\":\"agent_capability_mismatch\"},"
                + "\"channels\":{\"status\":{\"message\":\"I can't send emails. I can help with Spotify music playback.\"},"
                + "\"chat\":{\"message\":\"I can't send emails. I can help with Spotify music playback.\"},"
                + "\"webview\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}",
            "final",
            "delegate_terminal");
        return;
      }
      if ("com.shellaia.verbatim.agent.zzzalt".equals(componentName)) {
        altProcessExecuteCount++;
        context.emitControlJson(
            "{\"kind\":\"terminal\",\"outcome\":\"success\",\"terminal\":true,"
                + "\"payload\":{\"stopReason\":\"completed\",\"message\":\"Handled by alternate agent.\"},"
                + "\"meta\":{\"outcome\":\"success\"},"
                + "\"channels\":{\"status\":{\"message\":\"handled by alternate agent\"},"
                + "\"chat\":{\"message\":\"handled by alternate agent\"},"
                + "\"webview\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}",
            "final",
            "delegate_terminal");
        return;
      }
      context.emitControlJson(
          "{\"kind\":\"terminal\",\"outcome\":\"failure\",\"terminal\":true,"
              + "\"meta\":{\"outcome\":\"failure\",\"errorCode\":\"not_found\"},"
              + "\"channels\":{\"status\":{\"message\":\"not found\"},\"chat\":{\"message\":\"not found\"},"
              + "\"webview\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}",
          "error",
          "delegate_terminal");
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
