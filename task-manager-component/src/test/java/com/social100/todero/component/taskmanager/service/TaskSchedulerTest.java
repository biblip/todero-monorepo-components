package com.social100.todero.component.taskmanager.service;

import com.social100.todero.component.taskmanager.domain.TaskEntity;
import com.social100.todero.component.taskmanager.domain.TaskStateMachine;
import com.social100.todero.component.taskmanager.domain.TaskStatus;
import com.social100.todero.component.taskmanager.persistence.SqliteEventOutboxRepository;
import com.social100.todero.component.taskmanager.persistence.SqliteTaskRepository;
import com.social100.todero.component.taskmanager.persistence.TaskTransitionOutboxWriter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskSchedulerTest {

  @Test
  void startRunsStartupCatchupAndDispatchesDueEvents() throws Exception {
    Instant now = Instant.parse("2026-02-01T00:00:00Z");
    Fixture fx = fixture(now);
    fx.taskRepo.create(new TaskEntity(
        "task-due",
        "Task Due",
        null,
        List.of("agent-a"),
        now.minusSeconds(100),
        now.minusSeconds(100),
        "creator",
        TaskStatus.NEW,
        null,
        List.of(),
        now.minusSeconds(1),
        null,
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

    AtomicInteger callbacks = new AtomicInteger();
    fx.dispatcher.subscribe("agent-a", e -> callbacks.incrementAndGet());
    fx.scheduler.start();
    Thread.sleep(120);
    fx.scheduler.stop();

    TaskEntity after = fx.taskRepo.getById("task-due").orElseThrow();
    assertEquals(TaskStatus.READY, after.status());
    assertTrue(callbacks.get() >= 1);
    assertTrue(fx.scheduler.metricsSnapshot().startupCatchupRuns() >= 1);
  }

  @Test
  void runCycleHandlesClaimExpiry() throws Exception {
    Instant now = Instant.parse("2026-02-01T00:00:00Z");
    Fixture fx = fixture(now);
    fx.taskRepo.create(new TaskEntity(
        "task-claim-expired",
        "Task Claim Expired",
        null,
        List.of("agent-a"),
        now.minusSeconds(100),
        now.minusSeconds(100),
        "creator",
        TaskStatus.CLAIMED,
        null,
        List.of(),
        now.minusSeconds(10),
        null,
        null,
        null,
        null,
        null,
        0,
        3,
        null,
        "agent-a",
        now.minusSeconds(1),
        0,
        null,
        null
    ));

    TaskScheduler.CycleResult cycle = fx.scheduler.runCycle();
    assertTrue(cycle.evaluation().ok());
    assertTrue(cycle.evaluation().data().claimExpiryTransitions() >= 1);
    assertEquals(TaskStatus.READY, fx.taskRepo.getById("task-claim-expired").orElseThrow().status());
  }

  private static Fixture fixture(Instant now) throws Exception {
    Path db = Files.createTempFile("task-scheduler", ".sqlite");
    SqliteTaskRepository taskRepo = new SqliteTaskRepository(db);
    SqliteEventOutboxRepository outbox = new SqliteEventOutboxRepository(taskRepo);
    TaskTransitionOutboxWriter writer = new TaskTransitionOutboxWriter(taskRepo, outbox);
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    TaskService service = new TaskService(taskRepo, outbox, writer, new TaskStateMachine(), clock);
    TaskEventDispatcher dispatcher = new TaskEventDispatcher(outbox, clock, 3);
    TaskScheduler scheduler = new TaskScheduler(service, dispatcher, 500, 100, 100);
    return new Fixture(taskRepo, dispatcher, scheduler);
  }

  private record Fixture(SqliteTaskRepository taskRepo,
                         TaskEventDispatcher dispatcher,
                         TaskScheduler scheduler) {
  }
}
