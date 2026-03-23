package com.shellaia.agent.dj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.social100.todero.common.ai.action.CommandAgentResponse;
import com.social100.todero.common.ai.agent.AgentContext;
import com.social100.todero.common.ai.agent.AgentPrompt;
import com.social100.todero.common.ai.llm.LLMClient;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentDJSpotifyEventExecutionTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void populatePlannerContextIncludesPlaybackFactsAndRecentSteps() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    AgentContext context = new AgentContext();
    List<Object> toolSteps = new ArrayList<>();
    toolSteps.add(newToolStep(
        1,
        "status all",
        "status",
        "all",
        "{\"ok\":true,\"message\":\"Device: Arturo’s Mac mini\\nPlaying: false\\nTrack: You're The One That I Want - From “Grease” — John Travolta\\nURI: spotify:track:0B9x2BRHqj3Qer7biM3pU3\\nPosition: 00:00 / 02:49\",\"channels\":{\"chat\":{\"message\":\"ok\"}}}",
        10L,
        20L,
        30L
    ));

    Method method = AgentDJComponent.class.getDeclaredMethod(
        "populatePlannerContext",
        AgentContext.class,
        String.class,
        String.class,
        String.class,
        boolean.class,
        int.class,
        Class.forName("com.shellaia.agent.dj.AgentDJComponent$GoalIntent"),
        List.class,
        CommandAgentResponse.class);
    method.setAccessible(true);
    method.invoke(component, context, "play a simmilar song like that", "process", "corr-1", true, 2,
        newGoalIntent("recommendation_playback", "current_playback", "current-playback", true, true, true, 0.92d, "test"),
        toolSteps, null);

    @SuppressWarnings("unchecked")
    Map<String, Object> knownFacts = (Map<String, Object>) context.get("known_facts");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> recentSteps = (List<Map<String, Object>>) context.get("recent_steps");

    assertEquals("spotify:track:0B9x2BRHqj3Qer7biM3pU3", knownFacts.get("current_track_uri"));
    assertEquals("You're The One That I Want - From “Grease” — John Travolta", knownFacts.get("current_track"));
    assertEquals(Boolean.FALSE, knownFacts.get("playback_active"));
    assertEquals("need_candidate_verification", context.get("plan_state"));
    assertEquals(1, recentSteps.size());
    assertEquals("status", recentSteps.get(0).get("tool_command"));
    assertEquals("recommendation_playback", ((Map<?, ?>) context.get("normalized_goal")).get("intent"));
  }

  @Test
  void normalizeGoalIntentUsesLlmClassificationInsteadOfStringHeuristics() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    CapturingLlm llm = new CapturingLlm();
    llm.rawResponse.set("{\"intent\":\"recommendation_playback\",\"target_scope\":\"current_playback\",\"seed_hint\":\"current-playback\",\"wants_playback\":true,\"references_current_playback\":true,\"needs_discovery\":true,\"confidence\":0.97,\"reason\":\"User wants something like the current track.\"}");

    Method method = AgentDJComponent.class.getDeclaredMethod(
        "normalizeGoalIntent",
        LLMClient.class,
        String.class,
        String.class,
        String.class,
        boolean.class,
        String.class);
    method.setAccessible(true);
    Object result = method.invoke(component, llm, "pon otra parecida a esta", "process", "corr-2", true, "work-1");

    assertEquals("recommendation_playback", accessor(result, "intent"));
    assertEquals("current_playback", accessor(result, "targetScope"));
    assertEquals("current-playback", accessor(result, "seedHint"));
    assertEquals(Boolean.TRUE, accessor(result, "wantsPlayback"));
    assertEquals(Boolean.TRUE, accessor(result, "referencesCurrentPlayback"));
  }

  @Test
  void planNextActionReceivesStructuredContextForRecommendationFlow() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    AgentContext context = new AgentContext();
    context.set("goal", Map.of("original", "play a simmilar song like that", "intent", "recommendation_playback"));
    context.set("plan_state", "need_candidate_verification");
    context.set("known_facts", Map.of(
        "current_track", "You're The One That I Want - From “Grease” — John Travolta",
        "current_track_uri", "spotify:track:0B9x2BRHqj3Qer7biM3pU3",
        "playback_active", false
    ));
    context.set("recent_steps", List.of(Map.of(
        "step", 1,
        "planned_action", "status all",
        "tool_command", "status",
        "tool_output_excerpt", "Device: Arturo’s Mac mini"
    )));
    CapturingLlm llm = new CapturingLlm();

    Method method = AgentDJComponent.class.getDeclaredMethod("planNextAction", LLMClient.class, AgentPrompt.class, AgentContext.class);
    method.setAccessible(true);
    method.invoke(component, llm, new AgentPrompt("planner prompt"), context);

    JsonNode ctx = JSON.readTree(llm.contextJson.get());
    assertEquals("recommendation_playback", ctx.path("goal").path("intent").asText());
    assertEquals("need_candidate_verification", ctx.path("plan_state").asText());
    assertEquals("spotify:track:0B9x2BRHqj3Qer7biM3pU3", ctx.path("known_facts").path("current_track_uri").asText());
    assertEquals("status", ctx.path("recent_steps").get(0).path("tool_command").asText());
  }

  @Test
  void recommendationFlowVerifiesCandidatesAndPlaysResolvedTrack() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    CapturingLlm llm = new CapturingLlm();
    llm.rawResponse.set("{\"candidates\":[{\"title\":\"Caribbean Blue\",\"artist\":\"Enya\",\"query\":\"Caribbean Blue Enya\",\"reason\":\"Similar dreamy vocal mood.\"}]}");
    CommandContext parent = CommandContext.builder()
        .sourceId("source-1")
        .componentManager(new EventOnlyManager((cmd, ctx) -> {
          if ("status".equals(cmd)) {
            ctx.emitChat("Device: Arturo’s Mac mini\nPlaying: false\nTrack: You're The One That I Want - From “Grease” — John Travolta\nURI: spotify:track:0B9x2BRHqj3Qer7biM3pU3", "final");
            return;
          }
          if ("resolve-track".equals(cmd)) {
            ctx.emitChat("Resolved track: Caribbean Blue — Enya [uri=spotify:track:1234567890ABCDEabcde1]", "final");
            return;
          }
          if ("play".equals(cmd)) {
            ctx.emitStatus("Playing: spotify:track:1234567890ABCDEabcde1", "final");
          }
        }))
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.dj/process",
            AiatpIO.Body.ofString("play a simmilar song like that", StandardCharsets.UTF_8)))
        .build();

    Method method = AgentDJComponent.class.getDeclaredMethod(
        "runRecommendationFlow",
        CommandContext.class,
        LLMClient.class,
        String.class,
        String.class,
        String.class,
        String.class,
        Class.forName("com.shellaia.agent.dj.AgentDJComponent$GoalIntent"));
    method.setAccessible(true);
    Object loopResult = method.invoke(component, parent, llm, "play a simmilar song like that", "process", "corr-3", "work-2",
        newGoalIntent("recommendation_playback", "current_playback", "current-playback", true, true, true, 0.97d, "test"));

    Object response = accessor(loopResult, "finalResponse");
    assertEquals("Playing a verified similar track: Caribbean Blue — Enya.", accessor(response, "user"));
  }

  @Test
  void executeSpotifyInternalConsumesFinalStatusEvent() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    CommandContext parent = CommandContext.builder()
        .sourceId("source-1")
        .componentManager(new EventOnlyManager((cmd, ctx) -> ctx.emitStatus("Playing search for Enya.", "final")))
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.dj/process",
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
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.dj/process",
            AiatpIO.Body.ofString("play enya", StandardCharsets.UTF_8)))
        .build();

    Object result = invokeExecuteSpotifyInternal(component, parent, "play", "enya");

    assertFalse((Boolean) accessor(result, "executed"));
    assertEquals("auth_required", accessor(result, "errorCode"));
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
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.dj/process",
            AiatpIO.Body.ofString("auth-begin", StandardCharsets.UTF_8)))
        .build();

    Object result = invokeExecuteSpotifyInternal(component, parent, "auth-begin", "");

    assertTrue((Boolean) accessor(result, "executed"));
    assertEquals("Open the Spotify link.", accessor(result, "output"));
    JsonNode root = JSON.readTree((String) accessor(result, "rawOutput"));
    assertEquals("sess-1", root.path("auth").path("session").path("sessionId").asText());
  }

  @Test
  void executeSpotifyInternalConsumesTerminalResponseAfterProgressEvent() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    CommandContext parent = CommandContext.builder()
        .sourceId("source-1")
        .componentManager(new EventOnlyManager((cmd, ctx) -> {
          ctx.emitStatus("Open the Spotify link.", "progress");
          ctx.completeJson(200,
              "{\"ok\":true,\"message\":\"Authorization required.\",\"auth\":{\"required\":true,\"provider\":\"spotify\",\"sessionId\":\"sess-1\",\"authorizeUrl\":\"https://accounts.spotify.com/authorize?x=1\"},\"channels\":{\"status\":{\"message\":\"Open the Spotify link.\"},\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}");
        }))
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.dj/process",
            AiatpIO.Body.ofString("auth-begin", StandardCharsets.UTF_8)))
        .build();

    Object result = invokeExecuteSpotifyInternal(component, parent, "auth-begin", "");

    assertTrue((Boolean) accessor(result, "executed"));
    JsonNode root = JSON.readTree((String) accessor(result, "rawOutput"));
    assertEquals("spotify", root.path("auth").path("provider").asText());
    assertEquals("sess-1", root.path("auth").path("sessionId").asText());
  }

  @Test
  void executeSpotifyInternalConsumesErrorEventAsFailure() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    CommandContext parent = CommandContext.builder()
        .sourceId("source-1")
        .componentManager(new EventOnlyManager((cmd, ctx) -> ctx.emitError("No devices available.")))
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.dj/process",
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
    try {
      Method method = target.getClass().getDeclaredMethod(name);
      method.setAccessible(true);
      return method.invoke(target);
    } catch (NoSuchMethodException ignored) {
      String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
      Method method = target.getClass().getMethod(getter);
      method.setAccessible(true);
      return method.invoke(target);
    }
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
    var ctor = clazz.getDeclaredConstructor(int.class, String.class, String.class, String.class, String.class, long.class, long.class, long.class);
    ctor.setAccessible(true);
    return ctor.newInstance(step, agentAction, toolCommand, toolArgs, toolOutput, planningDurationMs, toolDurationMs, stepDurationMs);
  }

  private static Object newGoalIntent(String intent,
                                      String targetScope,
                                      String seedHint,
                                      boolean wantsPlayback,
                                      boolean referencesCurrentPlayback,
                                      boolean needsDiscovery,
                                      double confidence,
                                      String reason) throws Exception {
    Class<?> clazz = Class.forName("com.shellaia.agent.dj.AgentDJComponent$GoalIntent");
    var ctor = clazz.getDeclaredConstructor(String.class, String.class, String.class, boolean.class, boolean.class, boolean.class, double.class, String.class);
    ctor.setAccessible(true);
    return ctor.newInstance(intent, targetScope, seedHint, wantsPlayback, referencesCurrentPlayback, needsDiscovery, confidence, reason);
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

  private static final class CapturingLlm implements LLMClient {
    private final AtomicReference<String> systemPrompt = new AtomicReference<>();
    private final AtomicReference<String> userPrompt = new AtomicReference<>();
    private final AtomicReference<String> contextJson = new AtomicReference<>();
    private final AtomicReference<String> rawResponse = new AtomicReference<>();

    @Override
    public String chat(String systemPrompt, String userPrompt, String contextJson) {
      this.systemPrompt.set(systemPrompt);
      this.userPrompt.set(userPrompt);
      this.contextJson.set(contextJson);
      String response = rawResponse.get();
      if (response != null) {
        return response;
      }
      return "{\"request\":\"Play a similar song to the current track\",\"action\":\"status all\",\"user\":\"Finding a similar song.\",\"html\":\"\"}";
    }
  }
}
