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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskSchedulerFakeClockTest {

  @Test
  void fakeClockCoversDueExpiryAndClaimExpiry() throws Exception {
    Instant base = Instant.parse("2026-04-01T00:00:00Z");
    Fixture fx = fixture(base);

    fx.taskRepo.create(task("due", TaskStatus.NEW, base.plusSeconds(30), null, null, null));
    fx.taskRepo.create(task("exp", TaskStatus.READY, base.minusSeconds(10), base.plusSeconds(20), null, null));
    fx.taskRepo.create(task("claim-exp", TaskStatus.CLAIMED, base.minusSeconds(10), null, "agent-a", base.plusSeconds(20)));

    fx.clock.advanceSeconds(35);
    TaskScheduler.CycleResult cycle = fx.scheduler.runCycle();
    assertTrue(cycle.evaluation().ok());
    assertEquals(TaskStatus.READY, fx.taskRepo.getById("due").orElseThrow().status());
    assertEquals(TaskStatus.READY, fx.taskRepo.getById("claim-exp").orElseThrow().status());

    fx.clock.advanceSeconds(10);
    fx.scheduler.runCycle();
    assertEquals(TaskStatus.EXPIRED, fx.taskRepo.getById("exp").orElseThrow().status());
  }

  private static Fixture fixture(Instant now) throws Exception {
    Path db = Files.createTempFile("task-scheduler-fake-clock", ".sqlite");
    SqliteTaskRepository taskRepo = new SqliteTaskRepository(db);
    SqliteEventOutboxRepository outbox = new SqliteEventOutboxRepository(taskRepo);
    TaskTransitionOutboxWriter writer = new TaskTransitionOutboxWriter(taskRepo, outbox);
    MutableClock clock = new MutableClock(now);
    TaskService service = new TaskService(taskRepo, outbox, writer, new TaskStateMachine(), clock);
    TaskEventDispatcher dispatcher = new TaskEventDispatcher(outbox, clock, 3);
    TaskScheduler scheduler = new TaskScheduler(service, dispatcher, 500, 100, 100);
    return new Fixture(taskRepo, scheduler, clock);
  }

  private static TaskEntity task(String id,
                                 TaskStatus status,
                                 Instant scheduledFor,
                                 Instant deadline,
                                 String claimedBy,
                                 Instant claimExpiresAt) {
    Instant now = Instant.parse("2026-04-01T00:00:00Z");
    return new TaskEntity(
        id,
        "Task " + id,
        null,
        List.of("agent-a"),
        now.minusSeconds(100),
        now.minusSeconds(100),
        "creator",
        status,
        null,
        List.of(),
        scheduledFor,
        now.minusSeconds(60),
        deadline,
        null,
        null,
        null,
        0,
        3,
        null,
        claimedBy,
        claimExpiresAt,
        0,
        null,
        null
    );
  }

  private record Fixture(SqliteTaskRepository taskRepo, TaskScheduler scheduler, MutableClock clock) {
  }
}
