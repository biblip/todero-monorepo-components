package com.social100.todero.component.taskmanager.service;

import com.social100.todero.component.taskmanager.persistence.SqliteEventOutboxRepository;
import com.social100.todero.component.taskmanager.persistence.SqliteTaskRepository;
import com.social100.todero.component.taskmanager.persistence.TaskEvent;
import com.social100.todero.component.taskmanager.persistence.TaskEventType;
import com.social100.todero.component.taskmanager.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskOutboxReliabilityTest {

  @Test
  void atLeastOnceRetryThenAck() throws Exception {
    Instant now = Instant.parse("2026-05-01T00:00:00Z");
    Path db = Files.createTempFile("task-outbox-reliability", ".sqlite");
    SqliteTaskRepository taskRepo = new SqliteTaskRepository(db);
    SqliteEventOutboxRepository outbox = new SqliteEventOutboxRepository(taskRepo);
    TaskEventDispatcher dispatcher = new TaskEventDispatcher(outbox, new MutableClock(now), 5);

    TaskEvent event = outbox.enqueue(new TaskEvent(
        null,
        UUID.randomUUID().toString(),
        TaskEventType.TASK_DUE,
        "task-r1",
        "agent-a",
        1,
        "{\"event\":\"due\"}",
        now,
        0,
        null,
        null
    ));

    AtomicInteger attempts = new AtomicInteger();
    dispatcher.subscribe("agent-a", e -> {
      int n = attempts.incrementAndGet();
      if (n == 1) {
        throw new RuntimeException("simulated first delivery failure");
      }
    });

    TaskEventDispatcher.DispatchResult first = dispatcher.dispatchPendingAll(10);
    assertEquals(1, first.failed());
    assertEquals(0, first.acked());
    assertEquals(1, outbox.listPending("agent-a", 10).get(0).deliveryAttempts());

    TaskEventDispatcher.DispatchResult second = dispatcher.dispatchPendingAll(10);
    assertEquals(1, second.acked());
    assertTrue(outbox.listPending("agent-a", 10).isEmpty());

    assertTrue(attempts.get() >= 2);
    assertEquals(2, dispatcher.metricsSnapshot().eventsScanned());
    assertEquals(1, dispatcher.metricsSnapshot().eventsDeliveryFailed());
    assertEquals(1, dispatcher.metricsSnapshot().eventsAcked());
  }
}
