package com.social100.todero.component.taskmanager.service;

import com.social100.todero.component.taskmanager.domain.TaskEntity;
import com.social100.todero.component.taskmanager.domain.TaskStateMachine;
import com.social100.todero.component.taskmanager.domain.TaskStatus;
import com.social100.todero.component.taskmanager.persistence.SqliteEventOutboxRepository;
import com.social100.todero.component.taskmanager.persistence.SqliteTaskRepository;
import com.social100.todero.component.taskmanager.persistence.TaskTransitionOutboxWriter;
import com.social100.todero.component.taskmanager.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskManagerRestartIntegrationTest {

  @Test
  void restartEmitsDueEventsForPersistedFutureTask() throws Exception {
    Path db = Files.createTempFile("task-restart", ".sqlite");
    Instant t0 = Instant.parse("2026-06-01T00:00:00Z");
    MutableClock clock1 = new MutableClock(t0);

    SqliteTaskRepository repo1 = new SqliteTaskRepository(db);
    SqliteEventOutboxRepository outbox1 = new SqliteEventOutboxRepository(repo1);
    TaskTransitionOutboxWriter writer1 = new TaskTransitionOutboxWriter(repo1, outbox1);
    TaskService service1 = new TaskService(repo1, outbox1, writer1, new TaskStateMachine(), clock1);
    TaskEventDispatcher dispatcher1 = new TaskEventDispatcher(outbox1, clock1, 5);
    TaskScheduler scheduler1 = new TaskScheduler(service1, dispatcher1, 1000, 100, 100);

    repo1.create(new TaskEntity(
        "restart-due-task",
        "Restart Task",
        null,
        List.of("agent-a"),
        t0,
        t0,
        "creator",
        TaskStatus.NEW,
        null,
        List.of(),
        t0.plusSeconds(60),
        t0,
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
    ));

    scheduler1.start();
    Thread.sleep(80);
    scheduler1.stop();
    assertEquals(TaskStatus.NEW, repo1.getById("restart-due-task").orElseThrow().status());

    MutableClock clock2 = new MutableClock(t0.plusSeconds(120));
    SqliteTaskRepository repo2 = new SqliteTaskRepository(db);
    SqliteEventOutboxRepository outbox2 = new SqliteEventOutboxRepository(repo2);
    TaskTransitionOutboxWriter writer2 = new TaskTransitionOutboxWriter(repo2, outbox2);
    TaskService service2 = new TaskService(repo2, outbox2, writer2, new TaskStateMachine(), clock2);
    TaskEventDispatcher dispatcher2 = new TaskEventDispatcher(outbox2, clock2, 5);
    TaskScheduler scheduler2 = new TaskScheduler(service2, dispatcher2, 1000, 100, 100);

    AtomicInteger delivered = new AtomicInteger();
    dispatcher2.subscribe("agent-a", e -> delivered.incrementAndGet());

    scheduler2.start();
    Thread.sleep(120);
    scheduler2.stop();

    assertEquals(TaskStatus.READY, repo2.getById("restart-due-task").orElseThrow().status());
    assertTrue(delivered.get() >= 1);
  }
}
