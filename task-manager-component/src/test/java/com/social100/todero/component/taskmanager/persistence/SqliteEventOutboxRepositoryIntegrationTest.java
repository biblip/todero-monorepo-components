package com.social100.todero.component.taskmanager.persistence;

import com.social100.todero.component.taskmanager.domain.TaskEntity;
import com.social100.todero.component.taskmanager.domain.TaskStateMachine;
import com.social100.todero.component.taskmanager.domain.TaskStatus;
import com.social100.todero.component.taskmanager.domain.TaskTransitionContext;
import com.social100.todero.component.taskmanager.domain.TaskTransitionRequest;
import com.social100.todero.component.taskmanager.domain.TaskTransitionType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteEventOutboxRepositoryIntegrationTest {

  @Test
  void enqueueListAckAndDeliveryAttemptFlow() throws Exception {
    Path db = Files.createTempFile("task-manager", ".sqlite");
    SqliteTaskRepository taskRepo = new SqliteTaskRepository(db);
    SqliteEventOutboxRepository outbox = new SqliteEventOutboxRepository(taskRepo);

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    TaskEvent event = new TaskEvent(
        null,
        "ev-1",
        TaskEventType.TASK_DUE,
        "task-1",
        "agent-a",
        2,
        "{\"k\":\"v\"}",
        now,
        0,
        null,
        null
    );

    TaskEvent persisted = outbox.enqueue(event);
    assertTrue(persisted.seq() != null && persisted.seq() > 0);

    List<TaskEvent> pending = outbox.listPending("agent-a", 10);
    assertEquals(1, pending.size());

    outbox.markDeliveryAttempt("ev-1", now.plusSeconds(1), "first attempt failed");
    TaskEvent afterAttempt = outbox.listPending("agent-a", 10).get(0);
    assertEquals(1, afterAttempt.deliveryAttempts());
    assertEquals("first attempt failed", afterAttempt.lastDeliveryError());

    outbox.markAcked("agent-a", "ev-1", now.plusSeconds(2));
    assertTrue(outbox.listPending("agent-a", 10).isEmpty());
  }

  @Test
  void persistTransitionWritesOutboxEventsForAssignedAgentsAtomically() throws Exception {
    Path db = Files.createTempFile("task-manager", ".sqlite");
    SqliteTaskRepository taskRepo = new SqliteTaskRepository(db);
    SqliteEventOutboxRepository outbox = new SqliteEventOutboxRepository(taskRepo);
    TaskTransitionOutboxWriter writer = new TaskTransitionOutboxWriter(taskRepo, outbox);
    TaskStateMachine machine = new TaskStateMachine();

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    TaskEntity created = taskRepo.create(task("task-2", TaskStatus.READY, now));

    TaskEntity transitioned = machine.transition(new TaskTransitionContext(
        created,
        TaskTransitionRequest.at(TaskTransitionType.CLAIM, now)
            .withActor("agent-a")
            .withLease(java.time.Duration.ofMinutes(5))
    ));

    TaskTransitionOutboxWriter.PersistResult result = writer.persistTransition(
        transitioned,
        created.version(),
        TaskEventType.TASK_CLAIMED,
        "{\"event\":\"claimed\"}",
        now.plusSeconds(1)
    );

    assertEquals(TaskStatus.CLAIMED, result.updatedTask().status());
    assertEquals(2, result.events().size());

    List<TaskEvent> allPending = outbox.listPendingAll(10);
    assertEquals(2, allPending.size());
    assertTrue(allPending.stream().allMatch(e -> e.eventType() == TaskEventType.TASK_CLAIMED));
  }

  @Test
  void versionConflictRollsBackOutboxWrites() throws Exception {
    Path db = Files.createTempFile("task-manager", ".sqlite");
    SqliteTaskRepository taskRepo = new SqliteTaskRepository(db);
    SqliteEventOutboxRepository outbox = new SqliteEventOutboxRepository(taskRepo);
    TaskTransitionOutboxWriter writer = new TaskTransitionOutboxWriter(taskRepo, outbox);
    TaskStateMachine machine = new TaskStateMachine();

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    TaskEntity created = taskRepo.create(task("task-3", TaskStatus.READY, now));

    TaskEntity transitioned = machine.transition(new TaskTransitionContext(
        created,
        TaskTransitionRequest.at(TaskTransitionType.START, now).withActor("agent-a")
    ));

    assertThrows(TaskVersionConflictException.class, () -> writer.persistTransition(
        transitioned,
        999L,
        TaskEventType.TASK_STARTED,
        "{\"event\":\"started\"}",
        now.plusSeconds(1)
    ));

    assertFalse(taskRepo.getById("task-3").orElseThrow().status() == TaskStatus.IN_PROGRESS);
    assertTrue(outbox.listPendingAll(50).isEmpty());
  }

  private static TaskEntity task(String id, TaskStatus status, Instant now) {
    return new TaskEntity(
        id,
        "Task " + id,
        "desc",
        List.of("agent-a", "agent-b"),
        now,
        now,
        "creator",
        status,
        1,
        List.of("t"),
        now.minusSeconds(1),
        now.minusSeconds(20),
        null,
        null,
        null,
        null,
        0,
        3,
        null,
        null,
        null,
        0,
        null,
        null
    );
  }
}
