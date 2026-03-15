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

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterAgentOpaqueRelayTest {

  @Test
  void detectsOpaqueAuthRelayPrompt() {
    String prompt = "auth-complete session-id=s123 code=abc state=st "
        + "secureEnvelope={\"opaquePayload\":\"xyz\",\"integrity\":\"sig\"}";
    assertTrue(RouterAgentComponent.isOpaqueAuthRelayPrompt(prompt));
  }

  @Test
  void rejectsNonAuthPrompts() {
    assertFalse(RouterAgentComponent.isOpaqueAuthRelayPrompt("play caribbean blue by enya"));
    assertFalse(RouterAgentComponent.isOpaqueAuthRelayPrompt("auth-complete session-id=s123 state=st"));
    assertFalse(RouterAgentComponent.isOpaqueAuthRelayPrompt("auth-complete code=abc opaquePayload=x integrity=y"));
  }

  @Test
  void treatsSecureEnvelopeAsOpaquePayload() {
    String prompt = "auth-complete session-id=s123 code=abc state=st "
        + "secureEnvelope={\"opaquePayload\":\"eyJzZWNyZXQiOiJ2YWx1ZSJ9\",\"integrity\":\"sig.with.symbols-_=+\"}";
    assertTrue(RouterAgentComponent.isOpaqueAuthRelayPrompt(prompt));
  }

  @Test
  void opaqueRelayPassesPromptAndResponseUnchanged() {
    String prompt = "auth-complete session-id=s123 code=abc state=st "
        + "secureEnvelope={\"opaquePayload\":\"xyz\",\"integrity\":\"sig\"}";
    String delegatedBody = "{\"ok\":true,\"channels\":{\"chat\":{\"message\":\"done\"},\"status\":{\"message\":\"ok\"},\"webview\":{\"html\":null,\"mode\":\"none\",\"replace\":false}},\"auth\":{\"required\":false}}";

    StubManager manager = new StubManager(delegatedBody);
    RouterAgentComponent router = new RouterAgentComponent(new EmptyStorage());
    AtomicReference<AiatpIO.HttpResponse> responseRef = new AtomicReference<>();

    CommandContext context = CommandContext.builder()
        .sourceId("sess-opaque")
        .componentManager(manager)
        .httpRequest(AiatpIO.HttpRequest.newBuilder("ACTION", "/com.shellaia.verbatim.agent.router/process")
            .body(AiatpIO.Body.ofString(prompt, StandardCharsets.UTF_8))
            .build())
        .consumer(responseRef::set)
        .build();

    router.process(context);

    assertEquals(prompt, manager.lastDelegatedPrompt.get());
    assertEquals("auth", responseRef.get().headers().getFirst("X-AIATP-Event-Channel"));
    assertEquals("{\"required\":false}", AiatpIO.bodyToString(responseRef.get().body(), StandardCharsets.UTF_8));
  }

  @Test
  void opaqueRelayDoesNotIntrospectMalformedEnvelopePayload() {
    String prompt = "auth-complete session-id=s123 code=abc state=st "
        + "secureEnvelope=<<<NOT_JSON_BUT_OPAQUE>>> integrity=sig.with-specials_-=+";
    String delegatedBody = "{\"ok\":true,\"channels\":{\"chat\":{\"message\":\"done\"},\"status\":{\"message\":\"ok\"},\"webview\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}";

    StubManager manager = new StubManager(delegatedBody);
    RouterAgentComponent router = new RouterAgentComponent(new EmptyStorage());
    AtomicReference<AiatpIO.HttpResponse> responseRef = new AtomicReference<>();

    CommandContext context = CommandContext.builder()
        .sourceId("sess-opaque-malformed")
        .componentManager(manager)
        .httpRequest(AiatpIO.HttpRequest.newBuilder("ACTION", "/com.shellaia.verbatim.agent.router/process")
            .body(AiatpIO.Body.ofString(prompt, StandardCharsets.UTF_8))
            .build())
        .consumer(responseRef::set)
        .build();

    router.process(context);

    assertEquals(prompt, manager.lastDelegatedPrompt.get());
    assertEquals("chat", responseRef.get().headers().getFirst("X-AIATP-Event-Channel"));
    assertEquals("done", AiatpIO.bodyToString(responseRef.get().body(), StandardCharsets.UTF_8));
  }

  private static final class StubManager implements ComponentManagerInterface {
    private final String delegatedBody;
    private final AtomicReference<String> lastDelegatedPrompt = new AtomicReference<>();

    private StubManager(String delegatedBody) {
      this.delegatedBody = delegatedBody;
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
      AgentCapabilityManifest manifest = AgentCapabilityManifest.builder()
          .contractVersion(1)
          .agentName("com.shellaia.verbatim.agent.dj.v2")
          .commands(List.of(
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
        lastDelegatedPrompt.set(AiatpIO.bodyToString(context.getHttpRequest().body(), StandardCharsets.UTF_8));
        context.response(AiatpIO.HttpResponse.newBuilder(200)
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .body(AiatpIO.Body.ofString(delegatedBody, StandardCharsets.UTF_8))
            .build());
        return;
      }
      if ("com.shellaia.verbatim.agent.dj.v2".equals(componentName) && "capabilities".equals(command)) {
        context.response(AiatpIO.HttpResponse.newBuilder(200)
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .body(AiatpIO.Body.ofString("{\"manifest\":{\"contractVersion\":1,\"agentName\":\"com.shellaia.verbatim.agent.dj.v2\",\"commands\":[{\"name\":\"process\"},{\"name\":\"capabilities\"}]}}", StandardCharsets.UTF_8))
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
