package com.social100.todero.component.spotify;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEventForwardingGateTest {

  @Test
  void forwardsFirstEventAndDeduplicatesRepeatedStatus() {
    AgentEventForwardingGate gate = new AgentEventForwardingGate();

    assertTrue(gate.shouldForward("playing track A", 1_000, 2_500));
    assertFalse(gate.shouldForward("playing track A", 5_000, 2_500));
    assertTrue(gate.shouldForward("playing track B", 6_000, 2_500));
  }

  @Test
  void enforcesMinIntervalForChangedStatuses() {
    AgentEventForwardingGate gate = new AgentEventForwardingGate();

    assertTrue(gate.shouldForward("status-1", 1_000, 2_500));
    assertFalse(gate.shouldForward("status-2", 2_000, 2_500));
    assertTrue(gate.shouldForward("status-3", 3_600, 2_500));
  }

  @Test
  void resetClearsState() {
    AgentEventForwardingGate gate = new AgentEventForwardingGate();

    assertTrue(gate.shouldForward("playing", 1_000, 2_500));
    gate.reset();
    assertTrue(gate.shouldForward("playing", 1_100, 2_500));
  }
}
