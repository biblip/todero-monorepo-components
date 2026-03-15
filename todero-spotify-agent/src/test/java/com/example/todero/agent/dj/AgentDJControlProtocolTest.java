package com.example.todero.agent.dj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.social100.todero.common.ai.action.CommandAgentResponse;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpIORequestWrapper;
import com.social100.todero.common.aiatpio.AiatpRequest;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.storage.Storage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentDJControlProtocolTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void processEmptyPromptEmitsCanonicalFailureControlWhenUpstreamControlEnabled() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    AtomicReference<AiatpIORequestWrapper> seen = new AtomicReference<>();
    AiatpIO.Headers headers = new AiatpIO.Headers();
    headers.set("X-AIATP-Upstream-Control", "true");
    headers.set(CommandContext.HDR_INTERACTIVE_MODE, "event");

    CommandContext context = CommandContext.builder()
        .sourceId("src-1")
        .aiatpRequest(AiatpRequest.builder()
            .method("ACTION")
            .target("/com.shellaia.verbatim.agent.dj/process")
            .requestId("r-1")
            .headers(headers)
            .body(AiatpIO.Body.ofString("", StandardCharsets.UTF_8))
            .build())
        .eventConsumer(seen::set)
        .build();

    assertTrue(component.process(context));

    AiatpIORequestWrapper wrapper = seen.get();
    assertNotNull(wrapper);
    assertEquals("control", wrapper.getAiatpEvent().getChannel());
    assertTrue(wrapper.getAiatpEvent().isTerminal());

    JsonNode root = JSON.readTree(AiatpIO.bodyToString(wrapper.getAiatpEvent().getBody(), StandardCharsets.UTF_8));
    assertEquals("failure", root.path("outcome").asText());
    assertEquals("invalid_request", root.path("payload").path("stopReason").asText());
    assertEquals("Prompt is required. Usage: process <goal>", root.path("channels").path("status").path("message").asText());
  }

  @Test
  void upstreamControlUsesCanonicalUnhandledIntentForUnsupportedAction() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    AtomicReference<AiatpIORequestWrapper> seen = new AtomicReference<>();
    AiatpIO.Headers headers = new AiatpIO.Headers();
    headers.set("X-AIATP-Upstream-Control", "true");
    headers.set(CommandContext.HDR_INTERACTIVE_MODE, "event");

    CommandContext context = CommandContext.builder()
        .sourceId("src-2")
        .aiatpRequest(AiatpRequest.builder()
            .method("ACTION")
            .target("/com.shellaia.verbatim.agent.dj/process")
            .requestId("r-2")
            .headers(headers)
            .body(AiatpIO.Body.ofString("send an email", StandardCharsets.UTF_8))
            .build())
        .eventConsumer(seen::set)
        .build();

    Object stopReason = enumConstant("com.example.todero.agent.dj.AgentDJComponent$StopReason", "UNSUPPORTED_ACTION");
    Object loopResult = newLoopResult(
        "send an email",
        new CommandAgentResponse("send an email", "none", "I can't send emails.", ""),
        List.of(),
        stopReason,
        12L,
        "process",
        "corr-2");
    String json = (String) declaredMethod(AgentDJComponent.class, "renderLoopResultAsJson", loopResult.getClass())
        .invoke(null, loopResult);
    Method emitLoopResult = declaredMethod(AgentDJComponent.class, "emitLoopResult",
        CommandContext.class, loopResult.getClass(), String.class, String.class, String.class);
    emitLoopResult.invoke(component, context, loopResult, json, "process", "corr-2");

    AiatpIORequestWrapper wrapper = seen.get();
    assertNotNull(wrapper);
    JsonNode root = JSON.readTree(AiatpIO.bodyToString(wrapper.getAiatpEvent().getBody(), StandardCharsets.UTF_8));
    assertEquals("unhandled_intent", root.path("outcome").asText());
    assertEquals("out_of_scope", root.path("payload").path("stopReason").asText());
    assertEquals("agent_capability_mismatch", root.path("meta").path("errorCode").asText());
    assertFalse(root.path("terminal").isMissingNode());
  }

  private static Method declaredMethod(Class<?> owner, String name, Class<?>... types) throws Exception {
    Method method = owner.getDeclaredMethod(name, types);
    method.setAccessible(true);
    return method;
  }

  private static Object enumConstant(String className, String constant) throws Exception {
    @SuppressWarnings("unchecked")
    Class<? extends Enum> enumClass = (Class<? extends Enum>) Class.forName(className);
    return Enum.valueOf(enumClass, constant);
  }

  private static Object newLoopResult(String request,
                                      CommandAgentResponse response,
                                      List<?> steps,
                                      Object stopReason,
                                      long totalDurationMs,
                                      String source,
                                      String correlationId) throws Exception {
    Class<?> clazz = Class.forName("com.example.todero.agent.dj.AgentDJComponent$LoopResult");
    Constructor<?> ctor = clazz.getDeclaredConstructor(
        String.class,
        CommandAgentResponse.class,
        List.class,
        stopReason.getClass(),
        long.class,
        String.class,
        String.class);
    ctor.setAccessible(true);
    return ctor.newInstance(request, response, steps, stopReason, totalDurationMs, source, correlationId);
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
