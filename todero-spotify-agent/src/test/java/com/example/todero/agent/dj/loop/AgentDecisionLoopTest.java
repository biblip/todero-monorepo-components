package com.example.todero.agent.dj.loop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentDecisionLoopTest {

  @Test
  void plannerExceptionProducesFailureChannels() {
    AgentDecisionLoop loop = new AgentDecisionLoop();

    AgentDecisionLoop.LoopResult result = loop.run(
        new AgentDecisionLoop.LoopRequest("play enya", "process", "corr-1", 1, null),
        (prompt, step) -> {
          throw new IllegalStateException("No active system LLM available for DJV2 planning.");
        },
        action -> new AgentDecisionLoop.ToolCall("play", "enya", action),
        (call, sink) -> AgentDecisionLoop.ToolCallResult.success(call, "ok", null),
        null,
        null);

    assertEquals("planner_exception", result.stopReason().code);
    assertEquals("No active system LLM available for DJV2 planning.", result.channels().status());
    assertEquals("No active system LLM available for DJV2 planning.", result.channels().chat());
  }

  @Test
  void terminalToolErrorDoesNotEmitCompletedStatus() {
    AgentDecisionLoop loop = new AgentDecisionLoop();

    AgentDecisionLoop.LoopResult result = loop.run(
        new AgentDecisionLoop.LoopRequest("play enya", "process", "corr-2", 1, null),
        (prompt, step) -> new AgentDecisionLoop.PlannerResult("play enya", "Playing Enya.", ""),
        action -> new AgentDecisionLoop.ToolCall("play", "enya", action),
        (call, sink) -> {
          sink.onEvent(new AgentDecisionLoop.ToolEvent("error", "final", "Spotify authentication required.", "auth_required"));
          return AgentDecisionLoop.ToolCallResult.failure(call, "auth_required", "Spotify authentication required.", null);
        },
        null,
        null);

    assertEquals("tool_failed", result.stopReason().code);
    assertEquals("Spotify authentication required.", result.channels().status());
    assertEquals("Playing Enya.", result.channels().chat());
    assertFalse("completed".equals(result.channels().status()));
  }
}
