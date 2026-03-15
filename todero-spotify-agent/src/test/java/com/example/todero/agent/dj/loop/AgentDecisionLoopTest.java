package com.example.todero.agent.dj.loop;

import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.command.CommandContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDecisionLoopTest {

  @Test
  void plannerExceptionProducesFailureChannels() throws Exception {
    AgentDecisionLoop loop = new AgentDecisionLoop();
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<AgentDecisionLoop.LoopResult> resultRef = new AtomicReference<>();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      loop.start(
          request(null),
          (prompt, step) -> {
            throw new IllegalStateException("No active system LLM available for DJV2 planning.");
          },
          action -> new AgentDecisionLoop.ToolCall("play", "enya", action),
          (call, handle) -> handle.complete("ok"),
          null,
          null,
          result -> {
            resultRef.set(result);
            done.countDown();
          },
          Runnable::run,
          scheduler
      );

      assertTrue(done.await(2, TimeUnit.SECONDS));
      AgentDecisionLoop.LoopResult result = resultRef.get();
      assertEquals("planner_exception", result.stopReason().code);
      assertEquals("No active system LLM available for DJV2 planning.", result.channels().status());
      assertEquals("No active system LLM available for DJV2 planning.", result.channels().chat());
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void terminalToolErrorDoesNotEmitCompletedStatus() throws Exception {
    AgentDecisionLoop loop = new AgentDecisionLoop();
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<AgentDecisionLoop.LoopResult> resultRef = new AtomicReference<>();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      loop.start(
          request(null),
          (prompt, step) -> new AgentDecisionLoop.PlannerResult("play enya", "Playing Enya.", ""),
          action -> new AgentDecisionLoop.ToolCall("play", "enya", action),
          (call, handle) -> {
            handle.onEvent(new AgentDecisionLoop.ToolEvent("error", "error", true, "Spotify authentication required.", "auth_required"));
            handle.fail("auth_required", "Spotify authentication required.");
          },
          null,
          null,
          result -> {
            resultRef.set(result);
            done.countDown();
          },
          Runnable::run,
          scheduler
      );

      assertTrue(done.await(2, TimeUnit.SECONDS));
      AgentDecisionLoop.LoopResult result = resultRef.get();
      assertEquals("tool_failed", result.stopReason().code);
      assertEquals("Spotify authentication required.", result.channels().status());
      assertEquals("Playing Enya.", result.channels().chat());
      assertFalse("completed".equals(result.channels().status()));
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void eventForwarderTracksTerminalDeliveryByChannel() {
    CommandContext context = CommandContext.builder()
        .sourceId("src-1")
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/agent/process", AiatpIO.Body.none()))
        .build();
    AgentDecisionLoop.EventForwarder forwarder = new AgentDecisionLoop.EventForwarder(context);

    forwarder.onEvent(new AgentDecisionLoop.ToolEvent("status", "progress", false, "Processing", ""));
    forwarder.onEvent(new AgentDecisionLoop.ToolEvent("chat", "final", true, "Playing now", ""));
    forwarder.onEvent(new AgentDecisionLoop.ToolEvent("html", "final", true, "<html>x</html>", ""));

    AgentDecisionLoop.DeliverySummary summary = forwarder.summary();

    assertFalse(summary.terminalStatusForwarded());
    assertTrue(summary.terminalChatForwarded());
    assertTrue(summary.terminalHtmlForwarded());
    assertTrue(summary.hasTerminalForwardedContent());
  }

  private static AgentDecisionLoop.LoopRequest request(AgentDecisionLoop.EventForwarder forwarder) {
    return new AgentDecisionLoop.LoopRequest("play enya", "process", "corr-1", 1, 5_000L, 1_000L, forwarder);
  }
}
