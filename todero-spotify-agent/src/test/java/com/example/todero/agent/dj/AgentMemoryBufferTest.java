package com.example.todero.agent.dj;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMemoryBufferTest {

  @Test
  void keepsOnlyMostRecentEntriesWithinCapacity() {
    AgentMemoryBuffer buffer = new AgentMemoryBuffer(3);

    buffer.add("event", "spotify", "one", 1000);
    buffer.add("event", "spotify", "two", 1001);
    buffer.add("event", "spotify", "three", 1002);
    buffer.add("event", "spotify", "four", 1003);

    assertEquals(3, buffer.size());
    String summary = buffer.summary(10, 1000);
    assertFalse(summary.contains("one"));
    assertTrue(summary.contains("two"));
    assertTrue(summary.contains("three"));
    assertTrue(summary.contains("four"));
  }

  @Test
  void summaryReturnsMostRecentEntriesInOrder() {
    AgentMemoryBuffer buffer = new AgentMemoryBuffer(10);

    buffer.add("goal", "process", "first", 2000);
    buffer.add("goal", "process", "second", 2001);
    buffer.add("goal", "process", "third", 2002);

    String summary = buffer.summary(2, 1000);
    assertFalse(summary.contains("first"));
    assertTrue(summary.contains("second"));
    assertTrue(summary.contains("third"));
    assertTrue(summary.indexOf("second") < summary.indexOf("third"));
  }

  @Test
  void summaryIsCharacterBounded() {
    AgentMemoryBuffer buffer = new AgentMemoryBuffer(2);

    buffer.add("result", "process", "x".repeat(100), 3000);
    String summary = buffer.summary(2, 64);

    assertTrue(summary.length() <= 64);
    assertTrue(summary.endsWith("..."));
  }
}
