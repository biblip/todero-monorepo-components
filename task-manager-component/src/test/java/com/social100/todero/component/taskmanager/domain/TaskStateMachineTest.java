package com.social100.todero.component.taskmanager.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskStateMachineTest {
  private final TaskStateMachine machine = new TaskStateMachine();

  @Test
  void initializeReadyWhenImmediatelyDue() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    TaskEntity task = draft(now.minusSeconds(5), now.minusSeconds(5), null, null, 0, 3, TaskStatus.NEW);

    TaskEntity initialized = machine.initialize(task, now);

    assertEquals(TaskStatus.READY, initialized.status());
    assertEquals(1, initialized.version());
  }

  @Test
  void initializeNewWhenScheduledInFuture() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    TaskEntity task = draft(now.plusSeconds(120), now.minusSeconds(1), null, null, 0, 3, TaskStatus.NEW);

    TaskEntity initialized = machine.initialize(task, now);

    assertEquals(TaskStatus.NEW, initialized.status());
  }

  @Test
  void evaluateTransitionsNewToReadyWhenDue() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    TaskEntity current = task(now, TaskStatus.NEW, null, null, 0, 3, now.minusSeconds(1));

    TaskEntity next = machine.transition(new TaskTransitionContext(current,
        TaskTransitionRequest.at(TaskTransitionType.EVALUATE, now)));

    assertEquals(TaskStatus.READY, next.status());
  }

  @Test
  void claimTransitionsReadyToClaimed() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    TaskEntity current = task(now, TaskStatus.READY, null, null, 0, 3, now.minusSeconds(1));

    TaskEntity next = machine.transition(new TaskTransitionContext(current,
        TaskTransitionRequest.at(TaskTransitionType.CLAIM, now)
            .withActor("agent-a")
            .withLease(Duration.ofMinutes(5))));

    assertEquals(TaskStatus.CLAIMED, next.status());
    assertEquals("agent-a", next.claimedBy());
  }

  @Test
  void renewClaimRequiresSameActor() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    TaskEntity claimed = task(now, TaskStatus.CLAIMED, "agent-a", now.plusSeconds(10), 0, 3, now.minusSeconds(1));

    assertThrows(TaskStateMachineException.class, () -> machine.transition(new TaskTransitionContext(claimed,
        TaskTransitionRequest.at(TaskTransitionType.RENEW_CLAIM, now)
            .withActor("agent-b")
            .withLease(Duration.ofMinutes(2)))));
  }

  @Test
  void claimExpiredMovesClaimedToReady() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    TaskEntity claimed = task(now, TaskStatus.CLAIMED, "agent-a", now.minusSeconds(1), 0, 3, now.minusSeconds(1));

    TaskEntity next = machine.transition(new TaskTransitionContext(claimed,
        TaskTransitionRequest.at(TaskTransitionType.CLAIM_EXPIRED, now)));

    assertEquals(TaskStatus.READY, next.status());
    assertEquals(null, next.claimedBy());
  }

  @Test
  void startClaimedRequiresMatchingActor() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    TaskEntity claimed = task(now, TaskStatus.CLAIMED, "agent-a", now.plusSeconds(120), 0, 3, now.minusSeconds(1));

    assertThrows(TaskStateMachineException.class, () -> machine.transition(new TaskTransitionContext(claimed,
        TaskTransitionRequest.at(TaskTransitionType.START, now).withActor("agent-b"))));

    TaskEntity started = machine.transition(new TaskTransitionContext(claimed,
        TaskTransitionRequest.at(TaskTransitionType.START, now).withActor("agent-a")));
    assertEquals(TaskStatus.IN_PROGRESS, started.status());
  }

  @Test
  void inProgressLifecycleBlockResumeComplete() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    TaskEntity inProgress = task(now, TaskStatus.IN_PROGRESS, null, null, 0, 3, now.minusSeconds(1));

    TaskEntity blocked = machine.transition(new TaskTransitionContext(inProgress,
        TaskTransitionRequest.at(TaskTransitionType.BLOCK, now.plusSeconds(1))));
    assertEquals(TaskStatus.BLOCKED, blocked.status());

    TaskEntity resumed = machine.transition(new TaskTransitionContext(blocked,
        TaskTransitionRequest.at(TaskTransitionType.RESUME, now.plusSeconds(2))));
    assertEquals(TaskStatus.IN_PROGRESS, resumed.status());

    TaskEntity completed = machine.transition(new TaskTransitionContext(resumed,
        TaskTransitionRequest.at(TaskTransitionType.COMPLETE, now.plusSeconds(3))));
    assertEquals(TaskStatus.COMPLETED, completed.status());
  }

  @Test
  void failUsesRetryRule() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    TaskEntity inProgressWithRetries = task(now, TaskStatus.IN_PROGRESS, null, null, 0, 3, now.minusSeconds(1));
    TaskEntity retryReady = machine.transition(new TaskTransitionContext(inProgressWithRetries,
        TaskTransitionRequest.at(TaskTransitionType.FAIL, now).withError("boom")));
    assertEquals(TaskStatus.READY, retryReady.status());
    assertEquals(1, retryReady.attemptsCount());

    TaskEntity finalAttempt = task(now, TaskStatus.IN_PROGRESS, null, null, 2, 3, now.minusSeconds(1));
    TaskEntity failed = machine.transition(new TaskTransitionContext(finalAttempt,
        TaskTransitionRequest.at(TaskTransitionType.FAIL, now).withError("boom-again")));
    assertEquals(TaskStatus.FAILED, failed.status());
    assertEquals(3, failed.attemptsCount());
  }

  @Test
  void snoozeRequiresFutureSchedule() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    TaskEntity ready = task(now, TaskStatus.READY, null, null, 0, 3, now.minusSeconds(1));

    assertThrows(TaskStateMachineException.class, () -> machine.transition(new TaskTransitionContext(ready,
        TaskTransitionRequest.at(TaskTransitionType.SNOOZE, now).withScheduleAt(now.minusSeconds(10)))));

    TaskEntity snoozed = machine.transition(new TaskTransitionContext(ready,
        TaskTransitionRequest.at(TaskTransitionType.SNOOZE, now).withScheduleAt(now.plusSeconds(120))));
    assertEquals(TaskStatus.SNOOZED, snoozed.status());
    assertEquals(now.plusSeconds(120), snoozed.scheduledFor());
  }

  @Test
  void cancelAllowedFromNonTerminalStates() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    TaskEntity blocked = task(now, TaskStatus.BLOCKED, null, null, 0, 3, now.minusSeconds(1));

    TaskEntity canceled = machine.transition(new TaskTransitionContext(blocked,
        TaskTransitionRequest.at(TaskTransitionType.CANCEL, now)));
    assertEquals(TaskStatus.CANCELED, canceled.status());
  }

  @Test
  void terminalStateRejectsFurtherTransitions() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    TaskEntity completed = task(now, TaskStatus.COMPLETED, null, null, 0, 3, now.minusSeconds(1));

    assertThrows(TaskStateMachineException.class, () -> machine.transition(new TaskTransitionContext(completed,
        TaskTransitionRequest.at(TaskTransitionType.EVALUATE, now.plusSeconds(1)))));
  }

  private static TaskEntity draft(Instant scheduledFor,
                                  Instant notBefore,
                                  Instant windowStart,
                                  Instant windowEnd,
                                  int attempts,
                                  Integer maxAttempts,
                                  TaskStatus status) {
    Instant base = Instant.parse("2026-01-01T00:00:00Z");
    return new TaskEntity(
        "task-1",
        "Task one",
        "desc",
        List.of("agent-a"),
        base,
        base,
        "creator",
        status,
        5,
        List.of("alpha"),
        scheduledFor,
        notBefore,
        null,
        windowStart,
        windowEnd,
        null,
        attempts,
        maxAttempts,
        null,
        null,
        null,
        0,
        null,
        null
    );
  }

  private static TaskEntity task(Instant now,
                                 TaskStatus status,
                                 String claimedBy,
                                 Instant claimExpiresAt,
                                 int attempts,
                                 Integer maxAttempts,
                                 Instant scheduledFor) {
    return new TaskEntity(
        "task-1",
        "Task one",
        "desc",
        List.of("agent-a"),
        now.minusSeconds(10),
        now.minusSeconds(10),
        "creator",
        status,
        5,
        List.of("alpha"),
        scheduledFor,
        now.minusSeconds(20),
        null,
        null,
        null,
        null,
        attempts,
        maxAttempts,
        null,
        claimedBy,
        claimExpiresAt,
        1,
        null,
        null
    );
  }
}
