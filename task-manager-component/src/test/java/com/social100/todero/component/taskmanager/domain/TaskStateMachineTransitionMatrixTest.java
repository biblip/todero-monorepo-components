package com.social100.todero.component.taskmanager.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.AbstractMap.SimpleEntry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskStateMachineTransitionMatrixTest {
  private final TaskStateMachine machine = new TaskStateMachine();
  private final Instant now = Instant.parse("2026-03-01T00:00:00Z");

  @Test
  void allowsRepresentativeValidTransitions() {
    assertDoesNotThrow(() -> apply(task(TaskStatus.NEW), req(TaskTransitionType.EVALUATE)));
    assertDoesNotThrow(() -> apply(task(TaskStatus.READY), req(TaskTransitionType.CLAIM).withActor("agent-a").withLease(Duration.ofMinutes(5))));
    assertDoesNotThrow(() -> apply(task(TaskStatus.CLAIMED, "agent-a", now.plusSeconds(60)), req(TaskTransitionType.RENEW_CLAIM).withActor("agent-a").withLease(Duration.ofMinutes(3))));
    assertDoesNotThrow(() -> apply(task(TaskStatus.CLAIMED, "agent-a", now.minusSeconds(1)), req(TaskTransitionType.CLAIM_EXPIRED)));
    assertDoesNotThrow(() -> apply(task(TaskStatus.CLAIMED, "agent-a", now.plusSeconds(60)), req(TaskTransitionType.FORCE_RELEASE_CLAIM).withActor("admin")));
    assertDoesNotThrow(() -> apply(task(TaskStatus.READY), req(TaskTransitionType.START).withActor("agent-a")));
    assertDoesNotThrow(() -> apply(task(TaskStatus.IN_PROGRESS), req(TaskTransitionType.BLOCK)));
    assertDoesNotThrow(() -> apply(task(TaskStatus.BLOCKED), req(TaskTransitionType.RESUME)));
    assertDoesNotThrow(() -> apply(task(TaskStatus.IN_PROGRESS), req(TaskTransitionType.COMPLETE)));
    assertDoesNotThrow(() -> apply(task(TaskStatus.IN_PROGRESS), req(TaskTransitionType.FAIL).withError("boom")));
    assertDoesNotThrow(() -> apply(task(TaskStatus.READY), req(TaskTransitionType.CANCEL).withActor("agent-a")));
    assertDoesNotThrow(() -> apply(task(TaskStatus.READY), req(TaskTransitionType.SNOOZE).withScheduleAt(now.plusSeconds(120))));
  }

  @Test
  void rejectsInvalidTransitionsAcrossMatrix() {
    Map<TaskTransitionType, Set<TaskStatus>> allowed = Map.ofEntries(
        new SimpleEntry<>(TaskTransitionType.EVALUATE, Set.of(TaskStatus.NEW, TaskStatus.SNOOZED, TaskStatus.CLAIMED, TaskStatus.READY, TaskStatus.BLOCKED, TaskStatus.IN_PROGRESS)),
        new SimpleEntry<>(TaskTransitionType.CLAIM, Set.of(TaskStatus.READY)),
        new SimpleEntry<>(TaskTransitionType.RENEW_CLAIM, Set.of(TaskStatus.CLAIMED)),
        new SimpleEntry<>(TaskTransitionType.CLAIM_EXPIRED, Set.of(TaskStatus.CLAIMED)),
        new SimpleEntry<>(TaskTransitionType.FORCE_RELEASE_CLAIM, Set.of(TaskStatus.CLAIMED)),
        new SimpleEntry<>(TaskTransitionType.START, Set.of(TaskStatus.READY, TaskStatus.CLAIMED)),
        new SimpleEntry<>(TaskTransitionType.BLOCK, Set.of(TaskStatus.IN_PROGRESS)),
        new SimpleEntry<>(TaskTransitionType.RESUME, Set.of(TaskStatus.BLOCKED)),
        new SimpleEntry<>(TaskTransitionType.COMPLETE, Set.of(TaskStatus.IN_PROGRESS)),
        new SimpleEntry<>(TaskTransitionType.FAIL, Set.of(TaskStatus.IN_PROGRESS)),
        new SimpleEntry<>(TaskTransitionType.CANCEL, Set.of(TaskStatus.NEW, TaskStatus.READY, TaskStatus.CLAIMED, TaskStatus.IN_PROGRESS, TaskStatus.BLOCKED, TaskStatus.SNOOZED)),
        new SimpleEntry<>(TaskTransitionType.SNOOZE, Set.of(TaskStatus.NEW, TaskStatus.READY))
    );

    for (TaskTransitionType type : List.of(
        TaskTransitionType.CLAIM,
        TaskTransitionType.RENEW_CLAIM,
        TaskTransitionType.CLAIM_EXPIRED,
        TaskTransitionType.FORCE_RELEASE_CLAIM,
        TaskTransitionType.START,
        TaskTransitionType.BLOCK,
        TaskTransitionType.RESUME,
        TaskTransitionType.COMPLETE,
        TaskTransitionType.FAIL,
        TaskTransitionType.CANCEL,
        TaskTransitionType.SNOOZE
    )) {
      for (TaskStatus status : TaskStatus.values()) {
        if (status.isTerminal()) {
          continue;
        }
        if (allowed.get(type).contains(status)) {
          continue;
        }
        TaskTransitionRequest request = requestFor(type);
        TaskEntity current = status == TaskStatus.CLAIMED
            ? task(status, "agent-a", now.plusSeconds(60))
            : task(status);
        assertThrows(TaskStateMachineException.class, () -> apply(current, request),
            () -> "Expected invalid transition for " + type + " from " + status);
      }
    }
  }

  private TaskTransitionRequest requestFor(TaskTransitionType type) {
    return switch (type) {
      case CLAIM -> req(type).withActor("agent-a").withLease(Duration.ofMinutes(5));
      case RENEW_CLAIM -> req(type).withActor("agent-a").withLease(Duration.ofMinutes(5));
      case CLAIM_EXPIRED -> req(type);
      case FORCE_RELEASE_CLAIM -> req(type).withActor("admin");
      case START -> req(type).withActor("agent-a");
      case FAIL -> req(type).withError("boom");
      case SNOOZE -> req(type).withScheduleAt(now.plusSeconds(120));
      default -> req(type);
    };
  }

  private TaskTransitionRequest req(TaskTransitionType type) {
    return TaskTransitionRequest.at(type, now);
  }

  private TaskEntity apply(TaskEntity task, TaskTransitionRequest request) {
    return machine.transition(new TaskTransitionContext(task, request));
  }

  private TaskEntity task(TaskStatus status) {
    return task(status, null, null);
  }

  private TaskEntity task(TaskStatus status, String claimedBy, Instant claimExpiresAt) {
    return new TaskEntity(
        "task-matrix",
        "Matrix",
        "desc",
        List.of("agent-a"),
        now.minusSeconds(60),
        now.minusSeconds(60),
        "creator",
        status,
        1,
        List.of("test"),
        now.minusSeconds(5),
        now.minusSeconds(30),
        null,
        null,
        null,
        null,
        0,
        3,
        null,
        claimedBy,
        claimExpiresAt,
        1,
        null,
        null
    );
  }
}
