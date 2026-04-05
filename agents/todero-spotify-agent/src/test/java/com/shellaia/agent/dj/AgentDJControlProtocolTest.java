package com.shellaia.agent.dj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.social100.todero.common.aiatpio.AiatpEvent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.social100.todero.common.ai.action.CommandAgentResponse;
import com.social100.todero.common.ai.llm.LLMClient;
import com.social100.todero.common.ai.llm.LLMInstance;
import com.social100.todero.common.ai.llm.LLMProviderDefinition;
import com.social100.todero.common.ai.llm.LLMRegistry;
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
import java.util.Optional;
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
            .target("/com.shellaia.agent.dj/process")
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
    assertEquals("final", wrapper.getAiatpEvent().getPhase());

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
            .target("/com.shellaia.agent.dj/process")
            .requestId("r-2")
            .headers(headers)
            .body(AiatpIO.Body.ofString("send an email", StandardCharsets.UTF_8))
            .build())
        .eventConsumer(seen::set)
        .build();

    Object stopReason = enumConstant("com.shellaia.agent.dj.AgentDJComponent$StopReason", "UNSUPPORTED_ACTION");
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
    assertEquals("unsupported_operation", root.path("meta").path("errorCode").asText());
    assertFalse(root.path("terminal").isMissingNode());
  }

  @Test
  void upstreamControlUsesCanonicalAuthHandoffWhenAuthPayloadIsValid() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    AtomicReference<AiatpIORequestWrapper> seen = new AtomicReference<>();
    AiatpIO.Headers headers = new AiatpIO.Headers();
    headers.set("X-AIATP-Upstream-Control", "true");
    headers.set(CommandContext.HDR_INTERACTIVE_MODE, "event");

    CommandContext context = CommandContext.builder()
        .sourceId("src-3")
        .aiatpRequest(AiatpRequest.builder()
            .method("ACTION")
            .target("/com.shellaia.agent.dj/process")
            .requestId("r-3")
            .headers(headers)
            .body(AiatpIO.Body.ofString("play music", StandardCharsets.UTF_8))
            .build())
        .eventConsumer(seen::set)
        .build();

    Object stopReason = enumConstant("com.shellaia.agent.dj.AgentDJComponent$StopReason", "AUTH_REQUIRED");
    Object toolStep = newToolStep(
        1,
        "auth-begin",
        "auth-begin",
        "redirect-profile=app owner=com.shellaia.agent.dj",
        """
        {"ok":true,"message":"Authorization required.","channels":{"status":{"message":"Open authorization URL."},"html":{"html":null,"mode":"none","replace":false}},"auth":{"required":true,"provider":"spotify","sessionId":"sess-1","authorizeUrl":"https://accounts.spotify.com/authorize?x=1"}}
        """,
        10L,
        20L,
        30L);
    Object loopResult = newLoopResult(
        "play music",
        new CommandAgentResponse("play music", "none", "Spotify authorization required. Open the authorization link and complete authentication.", ""),
        List.of(toolStep),
        stopReason,
        30L,
        "process",
        "corr-3");
    String json = (String) declaredMethod(AgentDJComponent.class, "renderLoopResultAsJson", loopResult.getClass())
        .invoke(null, loopResult);
    Method emitLoopResult = declaredMethod(AgentDJComponent.class, "emitLoopResult",
        CommandContext.class, loopResult.getClass(), String.class, String.class, String.class);
    emitLoopResult.invoke(component, context, loopResult, json, "process", "corr-3");

    AiatpIORequestWrapper wrapper = seen.get();
    assertNotNull(wrapper);
    JsonNode root = JSON.readTree(AiatpIO.bodyToString(wrapper.getAiatpEvent().getBody(), StandardCharsets.UTF_8));
    assertEquals("auth_handoff", root.path("outcome").asText());
    assertEquals("auth_required", root.path("payload").path("stopReason").asText());
    assertEquals("spotify", root.path("channels").path("auth").path("provider").asText());
    assertEquals("sess-1", root.path("channels").path("auth").path("sessionId").asText());
  }

  @Test
  void upstreamControlRejectsMalformedAuthPayloadOnAuthRequiredResult() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    AtomicReference<AiatpIORequestWrapper> seen = new AtomicReference<>();
    AiatpIO.Headers headers = new AiatpIO.Headers();
    headers.set("X-AIATP-Upstream-Control", "true");
    headers.set(CommandContext.HDR_INTERACTIVE_MODE, "event");

    CommandContext context = CommandContext.builder()
        .sourceId("src-4")
        .aiatpRequest(AiatpRequest.builder()
            .method("ACTION")
            .target("/com.shellaia.agent.dj/process")
            .requestId("r-4")
            .headers(headers)
            .body(AiatpIO.Body.ofString("play music", StandardCharsets.UTF_8))
            .build())
        .eventConsumer(seen::set)
        .build();

    Object stopReason = enumConstant("com.shellaia.agent.dj.AgentDJComponent$StopReason", "AUTH_REQUIRED");
    Object toolStep = newToolStep(
        1,
        "auth-begin",
        "auth-begin",
        "redirect-profile=app owner=com.shellaia.agent.dj",
        """
        {"ok":true,"message":"Authorization required.","channels":{"status":{"message":"Open authorization URL."},"html":{"html":null,"mode":"none","replace":false}},"auth":{"required":true,"provider":"spotify"}}
        """,
        10L,
        20L,
        30L);
    Object loopResult = newLoopResult(
        "play music",
        new CommandAgentResponse("play music", "none", "Spotify authorization required. Open the authorization link and complete authentication.", ""),
        List.of(toolStep),
        stopReason,
        30L,
        "process",
        "corr-4");
    String json = (String) declaredMethod(AgentDJComponent.class, "renderLoopResultAsJson", loopResult.getClass())
        .invoke(null, loopResult);
    Method emitLoopResult = declaredMethod(AgentDJComponent.class, "emitLoopResult",
        CommandContext.class, loopResult.getClass(), String.class, String.class, String.class);
    emitLoopResult.invoke(component, context, loopResult, json, "process", "corr-4");

    AiatpIORequestWrapper wrapper = seen.get();
    assertNotNull(wrapper);
    JsonNode root = JSON.readTree(AiatpIO.bodyToString(wrapper.getAiatpEvent().getBody(), StandardCharsets.UTF_8));
    assertEquals("failure", root.path("outcome").asText());
    assertEquals("auth_contract_invalid", root.path("payload").path("stopReason").asText());
  }

  @Test
  void upstreamControlAcceptsAuthHandoffWithoutAuthPayloadWhenHtmlIsPresent() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    AtomicReference<AiatpIORequestWrapper> seen = new AtomicReference<>();
    AiatpIO.Headers headers = new AiatpIO.Headers();
    headers.set("X-AIATP-Upstream-Control", "true");
    headers.set(CommandContext.HDR_INTERACTIVE_MODE, "event");

    CommandContext context = CommandContext.builder()
        .sourceId("src-5")
        .aiatpRequest(AiatpRequest.builder()
            .method("ACTION")
            .target("/com.shellaia.agent.dj/process")
            .requestId("r-5")
            .headers(headers)
            .body(AiatpIO.Body.ofString("play music", StandardCharsets.UTF_8))
            .build())
        .eventConsumer(seen::set)
        .build();

    Object stopReason = enumConstant("com.shellaia.agent.dj.AgentDJComponent$StopReason", "AUTH_HANDOFF");
    Object toolStep = newToolStep(
        1,
        "auth-begin",
        "auth-begin",
        "redirect-profile=app owner=com.shellaia.agent.dj",
        """
        {"ok":true,"message":"Authorization session created.","response":{"outcome":"await_external_completion","completed":true},"channels":{"chat":{"message":"Authorization session created."},"status":{"message":"Authorization session created."},"html":{"html":"<a href=\\"https://accounts.spotify.com/authorize\\">Authorize Spotify</a>","mode":"html","replace":true}}}
        """,
        10L,
        20L,
        30L);
    Object loopResult = newLoopResult(
        "play music",
        new CommandAgentResponse("play music", "none", "Spotify authorization required. Open the authorization link, complete authentication, then retry your request.", ""),
        List.of(toolStep),
        stopReason,
        30L,
        "process",
        "corr-5");
    String json = (String) declaredMethod(AgentDJComponent.class, "renderLoopResultAsJson", loopResult.getClass())
        .invoke(null, loopResult);
    Method emitLoopResult = declaredMethod(AgentDJComponent.class, "emitLoopResult",
        CommandContext.class, loopResult.getClass(), String.class, String.class, String.class);
    emitLoopResult.invoke(component, context, loopResult, json, "process", "corr-5");

    AiatpIORequestWrapper wrapper = seen.get();
    assertNotNull(wrapper);
    JsonNode root = JSON.readTree(AiatpIO.bodyToString(wrapper.getAiatpEvent().getBody(), StandardCharsets.UTF_8));
    assertEquals("auth_handoff", root.path("outcome").asText());
    assertEquals("auth_handoff", root.path("payload").path("stopReason").asText());
    assertEquals("none", root.path("channels").path("html").path("mode").asText());
  }

  @Test
  void upstreamControlToolProgressUsesControlEnvelope() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    AtomicReference<AiatpIORequestWrapper> seen = new AtomicReference<>();
    AiatpIO.Headers headers = new AiatpIO.Headers();
    headers.set("X-AIATP-Upstream-Control", "true");
    headers.set(CommandContext.HDR_INTERACTIVE_MODE, "event");

    CommandContext context = CommandContext.builder()
        .sourceId("src-5")
        .aiatpRequest(AiatpRequest.builder()
            .method("ACTION")
            .target("/com.shellaia.agent.dj/process")
            .requestId("r-5")
            .headers(headers)
            .body(AiatpIO.Body.ofString("play music", StandardCharsets.UTF_8))
            .build())
        .eventConsumer(seen::set)
        .build();

    Object tool = newToolExecution(
        true,
        "auth-begin",
        "redirect-profile=app owner=com.shellaia.agent.dj",
        "Authorization required.",
        "",
        """
        {"ok":true,"message":"Authorization required.","channels":{"status":{"message":"Open authorization URL."},"chat":{"message":"Authorization required."},"html":{"html":null,"mode":"none","replace":false}},"auth":{"required":true,"provider":"spotify","session":{"sessionId":"sess-5"},"authorizeUrl":"https://accounts.spotify.com/authorize?x=5"}}
        """,
        "AWAIT_EXTERNAL_COMPLETION"
    );
    Method emitToolProgress = declaredMethod(AgentDJComponent.class, "emitToolProgress", CommandContext.class, tool.getClass(), String.class, int.class);
    emitToolProgress.invoke(component, context, tool, "corr-5", 1);

    AiatpIORequestWrapper wrapper = seen.get();
    assertNotNull(wrapper);
    AiatpEvent event = wrapper.getAiatpEvent();
    assertEquals("control", event.getChannel());
    JsonNode root = JSON.readTree(AiatpIO.bodyToString(event.getBody(), StandardCharsets.UTF_8));
    assertEquals("progress", root.path("outcome").asText());
    assertEquals("spotify", root.path("channels").path("auth").path("provider").asText());
  }

  @Test
  void normalizeGoalIntentCanClassifyUnsupportedToolchainRequests() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    Method normalize = declaredMethod(AgentDJComponent.class, "normalizeGoalIntent",
        LLMClient.class, String.class, String.class, String.class, boolean.class, String.class);

    Object result = normalize.invoke(component,
        unsupportedRequestRegistry().system().orElseThrow().client(),
        "give me the lyrics of the French national anthem",
        "process",
        "corr-6",
        true,
        "work-6");

    Method supported = result.getClass().getDeclaredMethod("supportedByToolchain");
    Method unsupportedReason = result.getClass().getDeclaredMethod("unsupportedReason");
    supported.setAccessible(true);
    unsupportedReason.setAccessible(true);

    assertEquals(false, supported.invoke(result));
    assertTrue(((String) unsupportedReason.invoke(result)).contains("cannot provide lyrics"));
  }

  @Test
  void renderLoopResultUsesRouterCompatibleFallbackForOutOfScope() throws Exception {
    Object stopReason = enumConstant("com.shellaia.agent.dj.AgentDJComponent$StopReason", "OUT_OF_SCOPE");
    Object loopResult = newLoopResult(
        "give me the lyrics of the French national anthem",
        new CommandAgentResponse("give me the lyrics of the French national anthem", "none",
            "I can't handle that request in this agent.", ""),
        List.of(),
        stopReason,
        12L,
        "process",
        "corr-6");

    String json = (String) declaredMethod(AgentDJComponent.class, "renderLoopResultAsJson", loopResult.getClass())
        .invoke(null, loopResult);
    JsonNode root = JSON.readTree(json);
    assertEquals("unsupported_operation", root.path("response").path("outcome").asText());
    assertEquals("unhandled_intent", root.path("meta").path("outcome").asText());
    assertEquals("unsupported_operation", root.path("meta").path("errorCode").asText());
    assertEquals("out_of_scope", root.path("stopReason").asText());
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
    Class<?> clazz = Class.forName("com.shellaia.agent.dj.AgentDJComponent$LoopResult");
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

  private static Object newToolStep(int step,
                                    String agentAction,
                                    String toolCommand,
                                    String toolArgs,
                                    String toolOutput,
                                    long planningDurationMs,
                                    long toolDurationMs,
                                    long stepDurationMs) throws Exception {
    Class<?> clazz = Class.forName("com.shellaia.agent.dj.AgentDJComponent$ToolStep");
    Constructor<?> ctor = clazz.getDeclaredConstructor(int.class, String.class, String.class, String.class, String.class, long.class, long.class, long.class);
    ctor.setAccessible(true);
    return ctor.newInstance(step, agentAction, toolCommand, toolArgs, toolOutput, planningDurationMs, toolDurationMs, stepDurationMs);
  }

  private static Object newToolExecution(boolean executed,
                                         String command,
                                         String args,
                                         String output,
                                         String errorCode,
                                         String rawOutput,
                                         String responseOutcomeName) throws Exception {
    Class<?> outcomeClass = Class.forName("com.shellaia.agent.dj.AgentDJComponent$ToolResponseOutcome");
    Object outcome = Enum.valueOf((Class<Enum>) outcomeClass.asSubclass(Enum.class), responseOutcomeName);
    Class<?> clazz = Class.forName("com.shellaia.agent.dj.AgentDJComponent$ToolExecution");
    Constructor<?> ctor = clazz.getDeclaredConstructor(boolean.class, String.class, String.class, String.class, String.class, String.class, outcomeClass);
    ctor.setAccessible(true);
    return ctor.newInstance(executed, command, args, output, errorCode, rawOutput, outcome);
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

  private static LLMRegistry unsupportedRequestRegistry() {
    LLMClient client = (systemPrompt, userPrompt, contextJson) -> {
      if (systemPrompt.contains("compact JSON intent")) {
        return "{\"intent\":\"unsupported_request\",\"target_scope\":\"explicit_request\",\"seed_hint\":\"\","
            + "\"wants_playback\":false,\"references_current_playback\":false,\"needs_discovery\":false,"
            + "\"supported_by_toolchain\":false,"
            + "\"unsupported_reason\":\"The Spotify DJ toolchain cannot provide lyrics or full song text.\","
            + "\"confidence\":0.98,\"reason\":\"The request needs content the Spotify playback toolchain cannot supply.\"}";
      }
      return "{\"request\":\"Unsupported request\",\"action\":\"none\",\"user\":\"This DJ agent cannot fulfill that request.\",\"html\":\"\"}";
    };
    LLMInstance instance = new LLMInstance(
        new LLMProviderDefinition("test", "external", "planner", "test", true, 100, java.util.Set.of("system"), Map.of()),
        client
    );
    return new LLMRegistry() {
      @Override
      public List<LLMInstance> list() {
        return List.of(instance);
      }

      @Override
      public List<LLMInstance> list(String category) {
        return List.of(instance);
      }

      @Override
      public Optional<LLMInstance> get(String name) {
        return Optional.of(instance);
      }

      @Override
      public Optional<LLMInstance> select(String category, String explicitName) {
        return Optional.of(instance);
      }

      @Override
      public Optional<LLMInstance> system() {
        return Optional.of(instance);
      }
    };
  }
}
