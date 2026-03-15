package com.example.todero.agent.dj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.base.ComponentManagerInterface;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.model.component.ComponentDescriptor;
import com.social100.todero.common.storage.Storage;
import com.social100.todero.console.base.OutputType;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class AgentDJSpotifyEventExecutionTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void executeSpotifyInternalConsumesFinalStatusEvent() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    CommandContext parent = CommandContext.builder()
        .sourceId("source-1")
        .componentManager(new EventOnlyManager((cmd, ctx) -> ctx.emitStatus("Playing search for Enya.", "final")))
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.verbatim.agent.dj/process",
            AiatpIO.Body.ofString("play enya", StandardCharsets.UTF_8)))
        .build();

    Object result = invokeExecuteSpotifyInternal(component, parent, "play", "enya");

    assertTrue((Boolean) accessor(result, "executed"));
    assertEquals("Playing search for Enya.", accessor(result, "output"));
    assertEquals("", accessor(result, "errorCode"));
  }

  @Test
  void executeSpotifyInternalConsumesAuthEventAsFailure() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    CommandContext parent = CommandContext.builder()
        .sourceId("source-1")
        .componentManager(new EventOnlyManager((cmd, ctx) ->
            ctx.emitAuthJson("{\"ok\":false,\"errorCode\":\"auth_required\",\"message\":\"Login required\"}", "final")))
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.verbatim.agent.dj/process",
            AiatpIO.Body.ofString("play enya", StandardCharsets.UTF_8)))
        .build();

    Object result = invokeExecuteSpotifyInternal(component, parent, "play", "enya");

    assertFalse((Boolean) accessor(result, "executed"));
    assertEquals("auth_required", accessor(result, "errorCode"));
  }

  @Test
  void executeSpotifyInternalConsumesFinalHtmlEventWithStatus() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    CommandContext parent = CommandContext.builder()
        .sourceId("source-1")
        .componentManager(new EventOnlyManager((cmd, ctx) -> {
          ctx.emitStatus("Suggestions ready.", "progress");
          ctx.emitHtml("<html>suggestions</html>", "final", "html", true);
        }))
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.verbatim.agent.dj/process",
            AiatpIO.Body.ofString("suggest enya 5", StandardCharsets.UTF_8)))
        .build();

    Object result = invokeExecuteSpotifyInternal(component, parent, "suggest", "enya 5");

    assertTrue((Boolean) accessor(result, "executed"));
    assertEquals("Suggestions ready.", accessor(result, "output"));
    JsonNode root = JSON.readTree((String) accessor(result, "rawOutput"));
    assertEquals("<html>suggestions</html>", root.path("channels").path("webview").path("html").asText());
  }

  @Test
  void executeSpotifyInternalConsumesAuthEventAsSuccess() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    CommandContext parent = CommandContext.builder()
        .sourceId("source-1")
        .componentManager(new EventOnlyManager((cmd, ctx) -> {
          ctx.emitStatus("Open the Spotify link.", "progress");
          ctx.emitAuthJson("{\"session\":{\"sessionId\":\"sess-1\"}}", "final");
        }))
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.verbatim.agent.dj/process",
            AiatpIO.Body.ofString("auth-begin", StandardCharsets.UTF_8)))
        .build();

    Object result = invokeExecuteSpotifyInternal(component, parent, "auth-begin", "");

    assertTrue((Boolean) accessor(result, "executed"));
    assertEquals("Open the Spotify link.", accessor(result, "output"));
    JsonNode root = JSON.readTree((String) accessor(result, "rawOutput"));
    assertEquals("sess-1", root.path("auth").path("session").path("sessionId").asText());
  }

  @Test
  void executeSpotifyInternalConsumesErrorEventAsFailure() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    CommandContext parent = CommandContext.builder()
        .sourceId("source-1")
        .componentManager(new EventOnlyManager((cmd, ctx) -> ctx.emitError("No devices available.")))
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.verbatim.agent.dj/process",
            AiatpIO.Body.ofString("play enya", StandardCharsets.UTF_8)))
        .build();

    Object result = invokeExecuteSpotifyInternal(component, parent, "play", "enya");

    assertFalse((Boolean) accessor(result, "executed"));
    assertEquals("tool-execution-failed", accessor(result, "errorCode"));
    assertEquals("No devices available.", accessor(result, "output"));
  }

  private static Object invokeExecuteSpotifyInternal(AgentDJComponent component,
                                                     CommandContext context,
                                                     String command,
                                                     String args) throws Exception {
    Method m = AgentDJComponent.class.getDeclaredMethod("executeSpotifyInternal", CommandContext.class, String.class, String.class);
    m.setAccessible(true);
    return m.invoke(component, context, command, args);
  }

  private static Object accessor(Object target, String name) throws Exception {
    Method method = target.getClass().getDeclaredMethod(name);
    method.setAccessible(true);
    return method.invoke(target);
  }

  @FunctionalInterface
  private interface EventOnlyBehavior {
    void execute(String command, CommandContext context);
  }

  private static final class EventOnlyManager implements ComponentManagerInterface {
    private final EventOnlyBehavior behavior;

    private EventOnlyManager(EventOnlyBehavior behavior) {
      this.behavior = behavior;
    }

    @Override
    public List<String> generateAutocompleteStrings() {
      return List.of();
    }

    @Override
    public String getHelp(String componentName, String commandName, OutputType outputType) {
      return "";
    }

    @Override
    public List<ComponentDescriptor> getComponents(boolean includeHidden, ServerType typeFilter) {
      return List.of();
    }

    @Override
    public ComponentDescriptor getComponent(String componentName, boolean includeHidden) {
      return null;
    }

    @Override
    public void execute(String componentName, String command, CommandContext context, boolean useComponentsAll) {
      behavior.execute(command, context);
    }
  }

  private static final class InMemoryStorage implements Storage {
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Map<String, String> secrets = new ConcurrentHashMap<>();

    @Override
    public void writeFile(String relativePath, byte[] bytes) {
      files.put(relativePath, bytes == null ? new byte[0] : bytes);
    }

    @Override
    public byte[] readFile(String relativePath) {
      if (".env".equals(relativePath)) {
        return new byte[0];
      }
      byte[] bytes = files.get(relativePath);
      if (bytes == null) {
        throw new RuntimeException("file not found: " + relativePath);
      }
      return bytes;
    }

    @Override
    public void deleteFile(String relativePath) {
      files.remove(relativePath);
    }

    @Override
    public List<String> listFiles(String relativeDir) {
      return List.copyOf(files.keySet());
    }

    @Override
    public void putSecret(String key, String value) {
      secrets.put(key, value);
    }

    @Override
    public String getSecret(String key) {
      return secrets.get(key);
    }

    @Override
    public void deleteSecret(String key) {
      secrets.remove(key);
    }
  }
}
