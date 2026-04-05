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
import com.social100.todero.common.ai.llm.LLMInstance;
import com.social100.todero.common.ai.llm.LLMProviderDefinition;
import com.social100.todero.common.ai.llm.LLMRegistry;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpRequest;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.base.ComponentManagerInterface;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.model.component.ComponentDescriptor;
import com.social100.todero.common.storage.Storage;
import com.social100.todero.console.base.OutputType;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
        CommandAgentResponse.class,
        List.class);
    method.setAccessible(true);
    method.invoke(component, context, "play a simmilar song like that", "process", "corr-1", true, 2,
        newGoalIntent("recommendation_playback", "current_playback", "current-playback", true, true, true, 1, 0.92d, "test"),
        toolSteps, null, List.of("status all"));

    @SuppressWarnings("unchecked")
    Map<String, Object> knownFacts = (Map<String, Object>) context.get("known_facts");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> recentSteps = (List<Map<String, Object>>) context.get("recent_steps");

    assertEquals("spotify:track:0B9x2BRHqj3Qer7biM3pU3", knownFacts.get("current_track_uri"));
    assertEquals("You're The One That I Want - From “Grease” — John Travolta", knownFacts.get("current_track"));
    assertEquals(Boolean.FALSE, knownFacts.get("playback_active"));
    assertEquals("need_candidate_resolution", context.get("plan_state"));
    assertEquals(1, recentSteps.size());
    assertEquals("status", recentSteps.get(0).get("tool_command"));
    assertEquals("recommendation_playback", ((Map<?, ?>) context.get("normalized_goal")).get("intent"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> plannerHistory = (List<Map<String, Object>>) context.get("planner_history");
    assertEquals(1, plannerHistory.size());
    assertEquals("status all", plannerHistory.get(0).get("action"));
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
  void normalizeGoalIntentOverridesRecommendationForDirectPlayRequests() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    CapturingLlm llm = new CapturingLlm();
    llm.rawResponse.set("{\"intent\":\"recommendation_playback\",\"target_scope\":\"explicit_seed\",\"seed_hint\":\"Tan Natural Pipe Pelaez\",\"wants_playback\":true,\"references_current_playback\":false,\"needs_discovery\":true,\"confidence\":0.92,\"reason\":\"Need to find the requested song before playback.\"}");

    Method method = AgentDJComponent.class.getDeclaredMethod(
        "normalizeGoalIntent",
        LLMClient.class,
        String.class,
        String.class,
        String.class,
        boolean.class,
        String.class);
    method.setAccessible(true);
    Object result = method.invoke(component, llm, "play tan natural, by pipe pelaez", "process", "corr-2b", true, "work-1b");

    assertEquals("general_spotify_control", accessor(result, "intent"));
    assertEquals("explicit_request", accessor(result, "targetScope"));
    assertEquals(Boolean.TRUE, accessor(result, "wantsPlayback"));
    assertEquals(Boolean.FALSE, accessor(result, "referencesCurrentPlayback"));
  }

  @Test
  void planNextActionReceivesStructuredContextForRecommendationFlow() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    AgentContext context = new AgentContext();
    context.set("goal", Map.of("original", "play a simmilar song like that", "intent", "recommendation_playback"));
    context.set("plan_state", "need_candidate_resolution");
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
    assertEquals("need_candidate_resolution", ctx.path("plan_state").asText());
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
            ctx.completeJson(200, "{\"ok\":true,\"message\":\"Device: Arturo’s Mac mini\\nPlaying: false\\nTrack: You're The One That I Want - From \\u201cGrease\\u201d \\u2014 John Travolta\\nURI: spotify:track:0B9x2BRHqj3Qer7biM3pU3\",\"response\":{\"outcome\":\"intermediate_result\",\"completed\":true},\"channels\":{\"chat\":{\"message\":\"Device: Arturo’s Mac mini\\nPlaying: false\\nTrack: You're The One That I Want - From \\u201cGrease\\u201d \\u2014 John Travolta\\nURI: spotify:track:0B9x2BRHqj3Qer7biM3pU3\"},\"status\":{\"message\":\"Playback status ready.\"},\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}");
            return;
          }
          if ("resolve-track".equals(cmd)) {
            ctx.completeJson(200, "{\"ok\":true,\"message\":\"Resolved track: Caribbean Blue \\u2014 Enya [uri=spotify:track:1234567890ABCDEabcde1]\",\"response\":{\"outcome\":\"intermediate_result\",\"completed\":true},\"channels\":{\"chat\":{\"message\":\"Resolved track: Caribbean Blue \\u2014 Enya [uri=spotify:track:1234567890ABCDEabcde1]\"},\"status\":{\"message\":\"Track resolved.\"},\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}");
            return;
          }
          if ("play".equals(cmd)) {
            ctx.completeJson(200, "{\"ok\":true,\"message\":\"Playing: spotify:track:1234567890ABCDEabcde1\",\"response\":{\"outcome\":\"goal_completed\",\"completed\":true},\"channels\":{\"chat\":{\"message\":\"Playing: spotify:track:1234567890ABCDEabcde1\"},\"status\":{\"message\":\"Playing: spotify:track:1234567890ABCDEabcde1\"},\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}");
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
        newGoalIntent("recommendation_playback", "current_playback", "current-playback", true, true, true, 1, 0.97d, "test"));

    Object response = accessor(loopResult, "finalResponse");
    assertEquals("Playing a recommended track: Caribbean Blue — Enya.", accessor(response, "user"));
  }

  @Test
  void recommendationFlowReturnsListWithoutPlaybackAndRejectsMismatchedResolutions() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    CapturingLlm llm = new CapturingLlm();
    llm.rawResponse.set("""
        {"candidates":[
          {"title":"Me haces tanto bien","artist":"Amistades Peligrosas","query":"Me haces tanto bien Amistades Peligrosas","reason":"Classic hit."},
          {"title":"Estoy por ti","artist":"Amistades Peligrosas","query":"Estoy por ti Amistades Peligrosas","reason":"Another strong single."},
          {"title":"Los Amantes de Estacion","artist":"Amistades Peligrosas","query":"Los Amantes de Estacion Amistades Peligrosas","reason":"Fits the requested artist catalog."},
          {"title":"Africanos en Madrid","artist":"Amistades Peligrosas","query":"Africanos en Madrid Amistades Peligrosas","reason":"Widely recognized track."}
        ]}
        """);
    List<String> commands = new ArrayList<>();
    int[] resolveIndex = {0};
    CommandContext parent = CommandContext.builder()
        .sourceId("source-1")
        .componentManager(new EventOnlyManager((cmd, ctx) -> {
          commands.add(cmd);
          if ("resolve-track".equals(cmd)) {
            int current = resolveIndex[0]++;
            switch (current) {
              case 0 -> {
                ctx.completeJson(200, "{\"ok\":true,\"message\":\"Resolved track: Me haces tanto bien — Amistades Peligrosas [uri=spotify:track:1111111111111111111111]\",\"response\":{\"outcome\":\"intermediate_result\",\"completed\":true},\"channels\":{\"chat\":{\"message\":\"Resolved track: Me haces tanto bien — Amistades Peligrosas [uri=spotify:track:1111111111111111111111]\"},\"status\":{\"message\":\"Track resolved.\"},\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}");
                return;
              }
              case 1 -> {
                ctx.completeJson(200, "{\"ok\":true,\"message\":\"Resolved track: Estoy por ti — Amistades Peligrosas [uri=spotify:track:2222222222222222222222]\",\"response\":{\"outcome\":\"intermediate_result\",\"completed\":true},\"channels\":{\"chat\":{\"message\":\"Resolved track: Estoy por ti — Amistades Peligrosas [uri=spotify:track:2222222222222222222222]\"},\"status\":{\"message\":\"Track resolved.\"},\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}");
                return;
              }
              case 2 -> {
                ctx.completeJson(200, "{\"ok\":true,\"message\":\"Resolved track: Con Que Fin? — Convicto de Musa [uri=spotify:track:3333333333333333333333]\",\"response\":{\"outcome\":\"intermediate_result\",\"completed\":true},\"channels\":{\"chat\":{\"message\":\"Resolved track: Con Que Fin? — Convicto de Musa [uri=spotify:track:3333333333333333333333]\"},\"status\":{\"message\":\"Track resolved.\"},\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}");
                return;
              }
              case 3 -> {
                ctx.completeJson(200, "{\"ok\":true,\"message\":\"Resolved track: Africanos en Madrid — Amistades Peligrosas [uri=spotify:track:4444444444444444444444]\",\"response\":{\"outcome\":\"intermediate_result\",\"completed\":true},\"channels\":{\"chat\":{\"message\":\"Resolved track: Africanos en Madrid — Amistades Peligrosas [uri=spotify:track:4444444444444444444444]\"},\"status\":{\"message\":\"Track resolved.\"},\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}");
                return;
              }
            }
          }
          throw new AssertionError("Unexpected command: " + cmd + " #" + resolveIndex[0]);
        }))
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.dj/process",
            AiatpIO.Body.ofString("give me a list of songs for amistades peligrosas 5 songs", StandardCharsets.UTF_8)))
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
    Object loopResult = method.invoke(component, parent, llm, "give me a list of songs for amistades peligrosas 5 songs", "process", "corr-4", "work-4",
        newGoalIntent("recommendation_info", "explicit_seed", "amistades peligrosas", false, false, false, 5, 0.97d, "test"));

    Object response = accessor(loopResult, "finalResponse");
    String user = (String) accessor(response, "user");
    String html = (String) accessor(response, "html");
    assertTrue(user.contains("I could resolve 4 of 5 requested tracks"));
    assertTrue(html.contains("Recommendations (4 of 5)"));
    assertTrue(html.contains("Me haces tanto bien"));
    assertTrue(html.contains("Estoy por ti"));
    assertTrue(html.contains("Africanos en Madrid"));
    assertTrue(html.contains("Convicto de Musa"));
    assertEquals(4L, commands.stream().filter("resolve-track"::equals).count());
    assertTrue(commands.stream().noneMatch("play"::equals));
  }

  @Test
  void executeSpotifyInternalConsumesFinalStatusEvent() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    CommandContext parent = CommandContext.builder()
        .sourceId("source-1")
        .componentManager(new EventOnlyManager((cmd, ctx) -> ctx.completeJson(200, "{\"ok\":true,\"message\":\"Playing search for Enya.\",\"response\":{\"outcome\":\"goal_completed\",\"completed\":true},\"channels\":{\"chat\":{\"message\":\"Playing search for Enya.\"},\"status\":{\"message\":\"Playing search for Enya.\"},\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}")))
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
            ctx.completeJson(500, "{\"ok\":false,\"errorCode\":\"auth_required\",\"message\":\"Login required\",\"response\":{\"outcome\":\"failure\",\"completed\":true},\"auth\":{\"required\":true,\"provider\":\"spotify\"},\"channels\":{\"chat\":{\"message\":\"Login required\"},\"status\":{\"message\":\"Login required\"},\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}")))
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
          ctx.completeJson(200, "{\"ok\":true,\"message\":\"Authorization required.\",\"response\":{\"outcome\":\"await_external_completion\",\"completed\":true},\"auth\":{\"session\":{\"sessionId\":\"sess-1\"}},\"channels\":{\"chat\":{\"message\":\"Authorization required.\"},\"status\":{\"message\":\"Open the Spotify link.\"},\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}");
        }))
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.dj/process",
            AiatpIO.Body.ofString("auth-begin", StandardCharsets.UTF_8)))
        .build();

    Object result = invokeExecuteSpotifyInternal(component, parent, "auth-begin", "");

    assertTrue((Boolean) accessor(result, "executed"));
    assertEquals("Authorization required.", accessor(result, "output"));
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
  void executeSpotifyInternalTreatsObservedAuthHandoffAsSuccessAfterTimeout() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    CommandContext parent = CommandContext.builder()
        .sourceId("source-1")
        .componentManager(new EventOnlyManager((cmd, ctx) -> {
          ctx.emitStatus("Authorization session created.", "progress");
          ctx.emitHtml("<a href=\"https://accounts.spotify.com/authorize\">Authorize Spotify</a>", "progress", "html", true);
          try {
            Thread.sleep(3500L);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }))
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.dj/process",
            AiatpIO.Body.ofString("auth-begin", StandardCharsets.UTF_8)))
        .build();

    Object result = invokeExecuteSpotifyInternal(component, parent, "auth-begin", "");

    assertTrue((Boolean) accessor(result, "executed"));
    assertEquals("Authorization session created.", accessor(result, "output"));
    assertEquals("AWAIT_EXTERNAL_COMPLETION", String.valueOf(accessor(result, "responseOutcome")));
    JsonNode root = JSON.readTree((String) accessor(result, "rawOutput"));
    assertEquals("await_external_completion", root.path("response").path("outcome").asText());
    assertEquals("html", root.path("channels").path("html").path("mode").asText());
  }

  @Test
  void executeSpotifyInternalPreservesParentRoutingHeaders() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    AtomicReference<AiatpRequest> seenRequest = new AtomicReference<>();
    AiatpIO.Headers headers = new AiatpIO.Headers();
    headers.set("Host", "brumor.pbxkey.com");
    CommandContext parent = CommandContext.builder()
        .sourceId("source-1")
        .componentManager(new EventOnlyManager((cmd, ctx) -> {
          seenRequest.set(ctx.getAiatpRequest());
          ctx.completeJson(200,
              "{\"ok\":true,\"message\":\"Authorization required.\",\"auth\":{\"required\":true,\"provider\":\"spotify\",\"sessionId\":\"sess-1\",\"authorizeUrl\":\"https://accounts.spotify.com/authorize?x=1\"},\"channels\":{\"status\":{\"message\":\"Open the Spotify link.\"},\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}");
        }))
        .aiatpRequest(AiatpRuntimeAdapter.request(
            "ACTION",
            "/com.shellaia.agent.dj/process",
            headers,
            AiatpIO.Body.ofString("auth-begin", StandardCharsets.UTF_8)))
        .build();

    Object result = invokeExecuteSpotifyInternal(component, parent, "auth-begin", "");

    assertTrue((Boolean) accessor(result, "executed"));
    assertEquals("brumor.pbxkey.com", seenRequest.get().getHeaders().getFirst("Host"));
  }

  @Test
  void executeSpotifyInternalConsumesErrorEventAsFailure() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    CommandContext parent = CommandContext.builder()
        .sourceId("source-1")
        .componentManager(new EventOnlyManager((cmd, ctx) -> ctx.completeJson(500, "{\"ok\":false,\"errorCode\":\"tool-execution-failed\",\"message\":\"No devices available.\",\"response\":{\"outcome\":\"failure\",\"completed\":true},\"channels\":{\"chat\":{\"message\":\"No devices available.\"},\"status\":{\"message\":\"No devices available.\"},\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}")))
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.dj/process",
            AiatpIO.Body.ofString("play enya", StandardCharsets.UTF_8)))
        .build();

    Object result = invokeExecuteSpotifyInternal(component, parent, "play", "enya");

    assertFalse((Boolean) accessor(result, "executed"));
    assertEquals("tool-execution-failed", accessor(result, "errorCode"));
    assertEquals("No devices available.", accessor(result, "output"));
  }

  @Test
  void runGoalLoopFailsWhenPlannerReturnsNoneAfterNonTerminalTool() throws Exception {
    AgentDJComponent component = newLedgerIsolatedComponent();
    SequencedLlm llm = new SequencedLlm(
        "{\"intent\":\"general_spotify_control\",\"target_scope\":\"current_playback\",\"seed_hint\":\"current-playback\",\"wants_playback\":true,\"references_current_playback\":true,\"needs_discovery\":true,\"confidence\":0.97,\"reason\":\"Replay current track.\"}",
        "{\"request\":\"Check playback\",\"action\":\"status all\",\"user\":\"Checking playback.\",\"html\":\"\"}",
        "{\"request\":\"Replay current track\",\"action\":\"none\",\"user\":\"No further action.\",\"html\":\"\"}"
    );
    CommandContext parent = CommandContext.builder()
        .sourceId("source-1")
        .componentManager(new EventOnlyManager((cmd, ctx) -> {
          if ("status".equals(cmd)) {
            ctx.completeJson(200, "{\"ok\":true,\"message\":\"Device: Arturo’s Mac mini\\nPlaying: false\\nTrack: Tan Natural — Felipe Peláez\\nURI: spotify:track:5CiiBycd20QB9YsK95byV6\\nPosition: 00:00 / 04:11\",\"response\":{\"outcome\":\"intermediate_result\",\"completed\":true},\"channels\":{\"chat\":{\"message\":\"Device: Arturo’s Mac mini\\nPlaying: false\\nTrack: Tan Natural — Felipe Peláez\\nURI: spotify:track:5CiiBycd20QB9YsK95byV6\\nPosition: 00:00 / 04:11\"},\"status\":{\"message\":\"Playback status ready.\"},\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}");
            return;
          }
          throw new AssertionError("Unexpected command: " + cmd);
        }))
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.dj/process",
            AiatpIO.Body.ofString("play again the same song", StandardCharsets.UTF_8)))
        .llmRegistry(singleLlmRegistry(llm))
        .build();

    Method method = AgentDJComponent.class.getDeclaredMethod(
        "runGoalLoop",
        CommandContext.class,
        String.class,
        boolean.class,
        String.class,
        String.class,
        String.class);
    method.setAccessible(true);
    Object root = openRootLedgerWork(component, "process", "play again the same song", true, "corr-loop-none");
    String workId = (String) accessor(root, "workId");
    Object loopResult = method.invoke(component, parent, "play again the same song", true, "process", "corr-loop-none", workId);

    assertEquals("tool_succeeded_but_goal_unresolved", fieldAccessor(accessor(loopResult, "stopReason"), "code"));
    Object response = accessor(loopResult, "finalResponse");
    assertTrue(((String) accessor(response, "user")).contains("Planner returned no next action"));
  }

  @Test
  void runGoalLoopDetectsRepeatedPlannerLoop() throws Exception {
    AgentDJComponent component = newLedgerIsolatedComponent();
    SequencedLlm llm = new SequencedLlm(
        "{\"intent\":\"general_spotify_control\",\"target_scope\":\"current_playback\",\"seed_hint\":\"current-playback\",\"wants_playback\":true,\"references_current_playback\":true,\"needs_discovery\":true,\"confidence\":0.97,\"reason\":\"Replay current track.\"}",
        "{\"request\":\"Check playback\",\"action\":\"status all\",\"user\":\"Checking playback.\",\"html\":\"\"}",
        "{\"request\":\"Check playback again\",\"action\":\"status all\",\"user\":\"Checking playback again.\",\"html\":\"\"}",
        "{\"request\":\"Check playback once more\",\"action\":\"status all\",\"user\":\"Checking playback once more.\",\"html\":\"\"}"
    );
    CommandContext parent = CommandContext.builder()
        .sourceId("source-1")
        .componentManager(new EventOnlyManager((cmd, ctx) -> {
          if ("status".equals(cmd)) {
            ctx.completeJson(200, "{\"ok\":true,\"message\":\"Device: Arturo’s Mac mini\\nPlaying: false\\nTrack: Tan Natural — Felipe Peláez\\nURI: spotify:track:5CiiBycd20QB9YsK95byV6\\nPosition: 00:00 / 04:11\",\"response\":{\"outcome\":\"intermediate_result\",\"completed\":true},\"channels\":{\"chat\":{\"message\":\"Device: Arturo’s Mac mini\\nPlaying: false\\nTrack: Tan Natural — Felipe Peláez\\nURI: spotify:track:5CiiBycd20QB9YsK95byV6\\nPosition: 00:00 / 04:11\"},\"status\":{\"message\":\"Playback status ready.\"},\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}");
            return;
          }
          throw new AssertionError("Unexpected command: " + cmd);
        }))
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.dj/process",
            AiatpIO.Body.ofString("play again the same song", StandardCharsets.UTF_8)))
        .llmRegistry(singleLlmRegistry(llm))
        .build();

    Method method = AgentDJComponent.class.getDeclaredMethod(
        "runGoalLoop",
        CommandContext.class,
        String.class,
        boolean.class,
        String.class,
        String.class,
        String.class);
    method.setAccessible(true);
    Object root = openRootLedgerWork(component, "process", "play again the same song", true, "corr-loop-repeat");
    String workId = (String) accessor(root, "workId");
    Object loopResult = method.invoke(component, parent, "play again the same song", true, "process", "corr-loop-repeat", workId);

    String stopCode = (String) fieldAccessor(accessor(loopResult, "stopReason"), "code");
    assertTrue(Set.of("planner_loop_detected", "no_forward_progress").contains(stopCode));
    Object response = accessor(loopResult, "finalResponse");
    assertTrue(((String) accessor(response, "user")).contains("Reference: corr-loop-repeat"));
  }

  @Test
  void executeSpotifyActionRejectsUnsupportedToolchainBeforeDispatch() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    CommandContext parent = CommandContext.builder()
        .sourceId("source-1")
        .componentManager(new EventOnlyManager((cmd, ctx) -> {
          throw new AssertionError("Spotify command should not be dispatched");
        }))
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.dj/process",
            AiatpIO.Body.ofString("lyrics", StandardCharsets.UTF_8)))
        .build();

    Method method = AgentDJComponent.class.getDeclaredMethod("executeSpotifyAction",
        CommandContext.class, String.class, Class.forName("com.shellaia.agent.dj.AgentDJComponent$GoalIntent"));
    method.setAccessible(true);
    Object result = method.invoke(component, parent, "resolve-track La Marseillaise",
        newGoalIntent("unsupported_request", "explicit_request", "", false, false, false, 1, 0.99d,
            "The Spotify DJ toolchain cannot provide lyrics or full song text.", false));

    assertFalse((Boolean) accessor(result, "executed"));
    assertEquals("unsupported_operation", accessor(result, "errorCode"));
    assertTrue(((String) accessor(result, "output")).contains("cannot provide lyrics"));
  }

  @Test
  void executeSpotifyInternalTimesOutAfterThreeSeconds() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
    CommandContext parent = CommandContext.builder()
        .sourceId("source-1")
        .componentManager(new EventOnlyManager((cmd, ctx) -> {
          // Intentionally never complete the delegated command.
        }))
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.dj/process",
            AiatpIO.Body.ofString("play enya", StandardCharsets.UTF_8)))
        .build();

    long started = System.nanoTime();
    Object result = invokeExecuteSpotifyInternal(component, parent, "play", "enya");
    long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);

    assertFalse((Boolean) accessor(result, "executed"));
    assertEquals("tool-execution-failed", accessor(result, "errorCode"));
    assertEquals("Tool execution timed out after 3s", accessor(result, "output"));
    assertTrue(elapsedSeconds >= 3 && elapsedSeconds < 6, "elapsedSeconds=" + elapsedSeconds);
  }

  @Test
  void toolTimeoutConstantIsThreeSeconds() throws Exception {
    var field = AgentDJComponent.class.getDeclaredField("TOOL_TIMEOUT_SECONDS");
    field.setAccessible(true);
    assertEquals(3L, field.getLong(null));
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

  private static Object fieldAccessor(Object target, String name) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static Object openRootLedgerWork(AgentDJComponent component,
                                           String source,
                                           String prompt,
                                           boolean interactive,
                                           String correlationId) throws Exception {
    Method m = AgentDJComponent.class.getDeclaredMethod("openRootLedgerWork", String.class, String.class, boolean.class, String.class);
    m.setAccessible(true);
    return m.invoke(component, source, prompt, interactive, correlationId);
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
                                      int requestedCount,
                                      double confidence,
                                      String reason) throws Exception {
    return newGoalIntent(intent, targetScope, seedHint, wantsPlayback, referencesCurrentPlayback,
        needsDiscovery, requestedCount, confidence, reason, true);
  }

  private static Object newGoalIntent(String intent,
                                      String targetScope,
                                      String seedHint,
                                      boolean wantsPlayback,
                                      boolean referencesCurrentPlayback,
                                      boolean needsDiscovery,
                                      int requestedCount,
                                      double confidence,
                                      String reason,
                                      boolean supportedByToolchain) throws Exception {
    Class<?> clazz = Class.forName("com.shellaia.agent.dj.AgentDJComponent$GoalIntent");
    var ctor = clazz.getDeclaredConstructor(String.class, String.class, String.class, boolean.class, boolean.class,
        boolean.class, int.class, boolean.class, String.class, double.class, String.class);
    ctor.setAccessible(true);
    return ctor.newInstance(intent, targetScope, seedHint, wantsPlayback, referencesCurrentPlayback,
        needsDiscovery, requestedCount, supportedByToolchain, supportedByToolchain ? "" : reason, confidence, reason);
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

  private static final class SequencedLlm implements LLMClient {
    private final List<String> responses;
    private int index = 0;

    private SequencedLlm(String... responses) {
      this.responses = List.of(responses);
    }

    @Override
    public String chat(String systemPrompt, String userPrompt, String contextJson) {
      if (index >= responses.size()) {
        return responses.get(responses.size() - 1);
      }
      return responses.get(index++);
    }
  }

  private static LLMRegistry singleLlmRegistry(LLMClient client) {
    LLMInstance instance = new LLMInstance(
        new LLMProviderDefinition("test", "external", "planner", "test", true, 1,
            new LinkedHashSet<>(Set.of("system")), Map.of()),
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

  private static AgentDJComponent newLedgerIsolatedComponent() throws Exception {
    Path ledgerDir = Files.createTempDirectory("dj-agent-ledger-test");
    System.setProperty("todero.agent.dj.ledger.dir", ledgerDir.toString());
    return new AgentDJComponent(new InMemoryStorage());
  }
}
