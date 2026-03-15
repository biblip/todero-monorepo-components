package com.example.todero.agent.dj;

import com.example.todero.agent.dj.loop.AgentDecisionLoop;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpIORequestWrapper;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.storage.Storage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentDJV2EmissionTest {

  @Test
  void emitLoopResultDoesNotReplayForwardedSuccessChannels() throws Exception {
    AgentDJV2Component component = new AgentDJV2Component(new InMemoryStorage());
    List<String> channels = new ArrayList<>();
    CommandContext context = CommandContext.builder()
        .sourceId("src-1")
        .httpRequest(AiatpIO.HttpRequest.newBuilder("ACTION", "/com.shellaia.verbatim.agent.dj.v2/process")
            .setHeader("X-Request-Id", "r-1")
            .build())
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
        .httpRequest(AiatpIO.HttpRequest.newBuilder("ACTION", "/com.shellaia.verbatim.agent.dj.v2/process")
            .setHeader("X-Request-Id", "r-2")
            .build())
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

  private static void invokeEmitLoopResult(AgentDJV2Component component,
                                           CommandContext context,
                                           AgentDecisionLoop.LoopResult result) throws Exception {
    Method method = AgentDJV2Component.class.getDeclaredMethod(
        "emitLoopResult", CommandContext.class, AgentDecisionLoop.LoopResult.class, String.class, String.class);
    method.setAccessible(true);
    method.invoke(component, context, result, "process", "corr-1");
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
