package com.social100.todero.component.taskmanager.service;

import com.social100.todero.component.taskmanager.persistence.SqliteEventOutboxRepository;
import com.social100.todero.component.taskmanager.persistence.SqliteTaskRepository;
import com.social100.todero.component.taskmanager.persistence.TaskEvent;
import com.social100.todero.component.taskmanager.persistence.TaskEventType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskEventDispatcherTest {

  @Test
  void dispatchDeliversAndAcksWhenSubscriberExists() throws Exception {
    Instant now = Instant.parse("2026-02-01T00:00:00Z");
    Fixture fx = fixture(now);
    fx.outbox.enqueue(event("agent-a", "task-1", now));

    List<String> received = new ArrayList<>();
    fx.dispatcher.subscribe("agent-a", e -> received.add(e.eventId()));

    TaskEventDispatcher.DispatchResult result = fx.dispatcher.dispatchPendingAll(10);

    assertEquals(1, result.scanned());
    assertEquals(1, result.acked());
    assertTrue(received.size() == 1);
    assertTrue(fx.outbox.listPending("agent-a", 10).isEmpty());
    assertEquals(1, fx.dispatcher.metricsSnapshot().eventsAcked());
  }

  @Test
  void dispatchMarksAttemptWhenNoSubscriber() throws Exception {
    Instant now = Instant.parse("2026-02-01T00:00:00Z");
    Fixture fx = fixture(now);
    TaskEvent original = fx.outbox.enqueue(event("agent-missing", "task-2", now));

    TaskEventDispatcher.DispatchResult result = fx.dispatcher.dispatchPendingAll(10);

    assertEquals(1, result.noSubscriber());
    List<TaskEvent> pending = fx.outbox.listPending("agent-missing", 10);
    assertFalse(pending.isEmpty());
    assertEquals(original.eventId(), pending.get(0).eventId());
    assertTrue(pending.get(0).deliveryAttempts() >= 1);
    assertEquals(1, fx.dispatcher.metricsSnapshot().eventsWithoutSubscriber());
  }

  @Test
  void executeModeUsesTargetCommandAndPayloadIncludesDedupeFields() throws Exception {
    Instant now = Instant.parse("2026-02-01T00:00:00Z");
    Fixture fx = fixture(now);
    TaskEvent persisted = fx.outbox.enqueue(event("agent-a", "task-9", now));

    AtomicReference<String> executedAgent = new AtomicReference<>();
    AtomicReference<String> executedCommand = new AtomicReference<>();
    AtomicReference<String> executedPayload = new AtomicReference<>();
    fx.dispatcher.setTargetCommand("react");

    TaskEventDispatcher.DispatchResult result = fx.dispatcher.dispatchPendingAll(
        10,
        TaskEventDispatcher.DispatchMode.EXECUTE,
        (agentId, command, payload) -> {
          executedAgent.set(agentId);
          executedCommand.set(command);
          executedPayload.set(payload);
        }
    );

    assertEquals(1, result.acked());
    assertEquals("agent-a", executedAgent.get());
    assertEquals("react", executedCommand.get());
    assertTrue(executedPayload.get().contains("\"event_id\":\"" + persisted.eventId() + "\""));
    assertTrue(executedPayload.get().contains("\"seq\":"));
    assertTrue(executedPayload.get().contains("\"payload\""));
  }

  private static Fixture fixture(Instant now) throws Exception {
    Path db = Files.createTempFile("task-dispatcher", ".sqlite");
    SqliteTaskRepository taskRepo = new SqliteTaskRepository(db);
    SqliteEventOutboxRepository outbox = new SqliteEventOutboxRepository(taskRepo);
    TaskEventDispatcher dispatcher = new TaskEventDispatcher(outbox, Clock.fixed(now, ZoneOffset.UTC), 3);
    return new Fixture(outbox, dispatcher);
  }

  private static TaskEvent event(String agentId, String taskId, Instant at) {
    return new TaskEvent(
        null,
        UUID.randomUUID().toString(),
        TaskEventType.TASK_DUE,
        taskId,
        agentId,
        1,
        "{\"event\":\"due\"}",
        at,
        0,
        null,
        null
    );
  }

  private record Fixture(SqliteEventOutboxRepository outbox, TaskEventDispatcher dispatcher) {
  }
}
