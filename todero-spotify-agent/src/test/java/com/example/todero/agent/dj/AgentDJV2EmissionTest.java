package com.example.todero.agent.dj;

import com.example.todero.agent.dj.loop.AgentDecisionLoop;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpIORequestWrapper;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.storage.Storage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDJV2EmissionTest {

  @Test
  void emitLoopResultDoesNotReplayForwardedSuccessChannels() throws Exception {
    AgentDJV2Component component = new AgentDJV2Component(new InMemoryStorage());
    List<String> channels = new ArrayList<>();
    CommandContext context = CommandContext.builder()
        .sourceId("src-1")
        .aiatpRequest(AiatpRuntimeAdapter.withHeader(
            AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.verbatim.agent.dj.v2/process", AiatpIO.Body.none()),
            "X-Request-Id",
            "r-1"))
        .eventConsumer(wrapper -> channels.add(wrapper.getXEvent().channel))
        .build();

    AgentDecisionLoop.LoopResult result = new AgentDecisionLoop.LoopResult(
        AgentDecisionLoop.StopReason.toolTerminal(),
        new AgentDecisionLoop.PlannerResult("play enya", "Playing Enya.", ""),
        null,
        List.of(),
        new AgentDecisionLoop.Channels("Playing Enya.", "Playing Enya.", "", "none", false, ""),
        new AgentDecisionLoop.DeliverySummary(true, true, false, false, false)
    );

    invokeEmitLoopResult(component, context, result);

    assertEquals(List.of("thought"), channels);
  }

  @Test
  void emitLoopResultStillEmitsFailureChannels() throws Exception {
    AgentDJV2Component component = new AgentDJV2Component(new InMemoryStorage());
    List<String> channels = new ArrayList<>();
    List<String> bodies = new ArrayList<>();
    CommandContext context = CommandContext.builder()
        .sourceId("src-1")
        .aiatpRequest(AiatpRuntimeAdapter.withHeader(
            AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.verbatim.agent.dj.v2/process", AiatpIO.Body.none()),
            "X-Request-Id",
            "r-2"))
        .eventConsumer(wrapper -> {
          AiatpIO.XProto.Event event = wrapper.getXEvent();
          channels.add(event.channel);
          bodies.add(AiatpIO.bodyToString(event.body(), StandardCharsets.UTF_8));
        })
        .build();

    AgentDecisionLoop.LoopResult result = new AgentDecisionLoop.LoopResult(
        AgentDecisionLoop.StopReason.plannerException("No active system LLM available."),
        null,
        null,
        List.of(),
        new AgentDecisionLoop.Channels("No active system LLM available.", "No active system LLM available.", "", "none", false, ""),
        AgentDecisionLoop.DeliverySummary.none()
    );

    invokeEmitLoopResult(component, context, result);

    assertEquals(List.of("status", "chat", "thought"), channels);
    assertEquals("No active system LLM available.", bodies.get(0));
    assertEquals("No active system LLM available.", bodies.get(1));
  }

  @Test
  void emitLoopResultUsesControlEnvelopeWhenUpstreamControlRequested() throws Exception {
    AgentDJV2Component component = new AgentDJV2Component(new InMemoryStorage());
    List<AiatpIORequestWrapper> seen = new ArrayList<>();
    CommandContext context = CommandContext.builder()
        .sourceId("src-1")
        .aiatpRequest(AiatpRuntimeAdapter.withHeader(
            AiatpRuntimeAdapter.withHeader(
                AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.verbatim.agent.dj.v2/process", AiatpIO.Body.none()),
                "X-Request-Id",
                "r-3"),
            "X-AIATP-Upstream-Control",
            "true"))
        .eventConsumer(seen::add)
        .build();

    AgentDecisionLoop.LoopResult result = new AgentDecisionLoop.LoopResult(
        AgentDecisionLoop.StopReason.toolTerminal(),
        new AgentDecisionLoop.PlannerResult("suggest lions", "Suggestions ready.", ""),
        null,
        List.of(),
        new AgentDecisionLoop.Channels("Suggestions ready.", "Suggestions ready.", "<html>done</html>", "html", true, ""),
        AgentDecisionLoop.DeliverySummary.none()
    );

    invokeEmitLoopResult(component, context, result);

    assertEquals(1, seen.size());
    AiatpIO.XProto.Event event = seen.get(0).getXEvent();
    String body = AiatpIO.bodyToString(event.body(), StandardCharsets.UTF_8);
    assertEquals("control", event.channel);
    assertEquals("true", event.headers().getFirst("Event-Terminal"));
    assertTrue(body.contains("\"channels\""));
    assertTrue(body.contains("\"webview\""));
    assertTrue(body.contains("<html>done</html>"));
  }

  @Test
  void emitLoopResultUsesCanonicalOutOfScopeControlWhenActionIsNoneOutsideDomain() throws Exception {
    AgentDJV2Component component = new AgentDJV2Component(new InMemoryStorage());
    List<AiatpIORequestWrapper> seen = new ArrayList<>();
    CommandContext context = CommandContext.builder()
        .sourceId("src-1")
        .aiatpRequest(AiatpRuntimeAdapter.withHeader(
            AiatpRuntimeAdapter.withHeader(
                AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.verbatim.agent.dj.v2/process", AiatpIO.Body.none()),
                "X-Request-Id",
                "r-4"),
            "X-AIATP-Upstream-Control",
            "true"))
        .eventConsumer(seen::add)
        .build();

    AgentDecisionLoop.LoopResult result = new AgentDecisionLoop.LoopResult(
        AgentDecisionLoop.StopReason.actionNone(),
        new AgentDecisionLoop.PlannerResult("none", "I can't send emails. I can help with Spotify music playback.", ""),
        null,
        List.of(),
        new AgentDecisionLoop.Channels(
            "I can't send emails. I can help with Spotify music playback.",
            "I can't send emails. I can help with Spotify music playback.",
            "",
            "none",
            false,
            ""),
        AgentDecisionLoop.DeliverySummary.none()
    );

    Method method = AgentDJV2Component.class.getDeclaredMethod(
        "emitLoopResult", CommandContext.class, AgentDecisionLoop.LoopResult.class, String.class, String.class, String.class);
    method.setAccessible(true);
    method.invoke(component, context, result, "send an email to june", "process", "corr-2");

    String body = AiatpIO.bodyToString(seen.get(0).getXEvent().body(), StandardCharsets.UTF_8);
    assertTrue(body.contains("\"outcome\":\"unhandled_intent\""));
    assertTrue(body.contains("\"errorCode\":\"agent_capability_mismatch\""));
    assertTrue(body.contains("\"stopReason\":\"out_of_scope\""));
  }

  private static void invokeEmitLoopResult(AgentDJV2Component component,
                                           CommandContext context,
                                           AgentDecisionLoop.LoopResult result) throws Exception {
    Method method = AgentDJV2Component.class.getDeclaredMethod(
        "emitLoopResult", CommandContext.class, AgentDecisionLoop.LoopResult.class, String.class, String.class, String.class);
    method.setAccessible(true);
    method.invoke(component, context, result, "send an email to june", "process", "corr-1");
  }

  private static final class InMemoryStorage implements Storage {
    private final java.util.Map<String, byte[]> data = new java.util.HashMap<>();
    private final java.util.Map<String, String> secrets = new java.util.HashMap<>();

    @Override
    public void writeFile(String relativePath, byte[] bytes) {
      data.put(relativePath, bytes);
    }

    @Override
    public byte[] readFile(String relativePath) {
      return data.get(relativePath);
    }

    @Override
    public void deleteFile(String relativePath) {
      data.remove(relativePath);
    }

    @Override
    public java.util.List<String> listFiles(String relativeDir) {
      return data.keySet().stream().sorted().toList();
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
