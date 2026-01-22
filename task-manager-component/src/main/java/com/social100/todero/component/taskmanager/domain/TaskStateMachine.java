package com.social100.todero.component.taskmanager.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public class TaskStateMachine {

  public TaskEntity initialize(TaskEntity draft, Instant now) {
    if (draft == null) {
      throw new TaskStateMachineException("Task draft is required.");
    }
    if (now == null) {
      throw new TaskStateMachineException("now is required.");
    }
    validateWindow(draft);

    TaskStatus initial;
    if (isExpired(draft, now)) {
      initial = TaskStatus.EXPIRED;
    } else if (isDue(draft, now)) {
      initial = TaskStatus.READY;
    } else {
      initial = TaskStatus.NEW;
    }
    return rewrite(draft, initial, now, draft.claimedBy(), draft.claimExpiresAt(), draft.attemptsCount(), draft.activeAttemptId(), draft.scheduledFor());
  }

  public TaskEntity transition(TaskTransitionContext context) {
    TaskEntity current = context.task();
    TaskTransitionRequest request = context.request();
    Instant now = request.now();

    validateWindow(current);

    if (current.status().isTerminal()) {
      throw invalid(current.status(), request.type(), "Task is in terminal state.");
    }

    return switch (request.type()) {
      case EVALUATE -> evaluate(current, now);
      case CLAIM -> claim(current, request);
      case RENEW_CLAIM -> renewClaim(current, request);
      case CLAIM_EXPIRED -> claimExpired(current, now);
      case FORCE_RELEASE_CLAIM -> forceRelease(current, request, now);
      case START -> start(current, request, now);
      case BLOCK -> expect(current, TaskStatus.IN_PROGRESS, TaskStatus.BLOCKED, request.type(), now);
      case RESUME -> expect(current, TaskStatus.BLOCKED, TaskStatus.IN_PROGRESS, request.type(), now);
      case COMPLETE -> expect(current, TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED, request.type(), now, null);
      case FAIL -> fail(current, request, now);
      case CANCEL -> cancel(current, now);
      case SNOOZE -> snooze(current, request, now);
      case CREATE -> throw new TaskStateMachineException("CREATE transition is not valid here. Use initialize().");
    };
  }

  private TaskEntity evaluate(TaskEntity task, Instant now) {
    if (isExpired(task, now)) {
      return rewrite(task, TaskStatus.EXPIRED, now, null, null, task.attemptsCount(), null, task.scheduledFor());
    }
    return switch (task.status()) {
      case NEW, SNOOZED -> isDue(task, now)
          ? rewrite(task, TaskStatus.READY, now, null, null, task.attemptsCount(), task.activeAttemptId(), task.scheduledFor())
          : rewrite(task, task.status(), now, task.claimedBy(), task.claimExpiresAt(), task.attemptsCount(), task.activeAttemptId(), task.scheduledFor());
      case CLAIMED -> {
        if (task.claimExpiresAt() != null && !task.claimExpiresAt().isAfter(now)) {
          yield rewrite(task, TaskStatus.READY, now, null, null, task.attemptsCount(), task.activeAttemptId(), task.scheduledFor());
        }
        yield rewrite(task, task.status(), now, task.claimedBy(), task.claimExpiresAt(), task.attemptsCount(), task.activeAttemptId(), task.scheduledFor());
      }
      default -> rewrite(task, task.status(), now, task.claimedBy(), task.claimExpiresAt(), task.attemptsCount(), task.activeAttemptId(), task.scheduledFor());
    };
  }

  private TaskEntity claim(TaskEntity task, TaskTransitionRequest request) {
    if (task.status() != TaskStatus.READY) {
      throw invalid(task.status(), request.type(), "Only READY tasks can be claimed.");
    }
    String actor = safe(request.actor());
    if (actor.isEmpty()) {
      throw new TaskStateMachineException("claim requires actor.");
    }
    Duration lease = request.leaseDuration();
    if (lease == null || lease.isNegative() || lease.isZero()) {
      throw new TaskStateMachineException("claim requires positive lease duration.");
    }
    Instant leaseUntil = request.now().plus(lease);
    return rewrite(task, TaskStatus.CLAIMED, request.now(), actor, leaseUntil, task.attemptsCount(), task.activeAttemptId(), task.scheduledFor());
  }

  private TaskEntity renewClaim(TaskEntity task, TaskTransitionRequest request) {
    if (task.status() != TaskStatus.CLAIMED) {
      throw invalid(task.status(), request.type(), "Only CLAIMED tasks can renew claim.");
    }
    String actor = safe(request.actor());
    if (actor.isEmpty() || !actor.equals(task.claimedBy())) {
      throw new TaskStateMachineException("renew-claim actor must match claimed_by.");
    }
    Duration lease = request.leaseDuration();
    if (lease == null || lease.isNegative() || lease.isZero()) {
      throw new TaskStateMachineException("renew-claim requires positive lease duration.");
    }
    Instant leaseUntil = request.now().plus(lease);
    return rewrite(task, TaskStatus.CLAIMED, request.now(), actor, leaseUntil, task.attemptsCount(), task.activeAttemptId(), task.scheduledFor());
  }

  private TaskEntity claimExpired(TaskEntity task, Instant now) {
    if (task.status() != TaskStatus.CLAIMED) {
      throw invalid(task.status(), TaskTransitionType.CLAIM_EXPIRED, "Only CLAIMED tasks can expire claim.");
    }
    if (task.claimExpiresAt() == null || task.claimExpiresAt().isAfter(now)) {
      throw new TaskStateMachineException("claim has not expired.");
    }
    return rewrite(task, TaskStatus.READY, now, null, null, task.attemptsCount(), task.activeAttemptId(), task.scheduledFor());
  }

  private TaskEntity forceRelease(TaskEntity task, TaskTransitionRequest request, Instant now) {
    if (task.status() != TaskStatus.CLAIMED) {
      throw invalid(task.status(), request.type(), "Only CLAIMED tasks can be force-released.");
    }
    if (safe(request.actor()).isEmpty()) {
      throw new TaskStateMachineException("force-release requires actor.");
    }
    return rewrite(task, TaskStatus.READY, now, null, null, task.attemptsCount(), task.activeAttemptId(), task.scheduledFor());
  }

  private TaskEntity start(TaskEntity task, TaskTransitionRequest request, Instant now) {
    if (task.activeAttemptId() != null && !task.activeAttemptId().isBlank()) {
      throw new TaskStateMachineException("Task already has an active attempt.");
    }
    String attemptId = safe(request.attemptId());
    if (attemptId.isEmpty()) {
      attemptId = UUID.randomUUID().toString();
    }
    if (task.status() == TaskStatus.READY) {
      return rewrite(task, TaskStatus.IN_PROGRESS, now, task.claimedBy(), task.claimExpiresAt(), task.attemptsCount(), attemptId, task.scheduledFor());
    }
    if (task.status() == TaskStatus.CLAIMED) {
      String actor = safe(request.actor());
      if (actor.isEmpty() || !actor.equals(task.claimedBy())) {
        throw new TaskStateMachineException("start actor must match claimed_by for CLAIMED tasks.");
      }
      return rewrite(task, TaskStatus.IN_PROGRESS, now, task.claimedBy(), task.claimExpiresAt(), task.attemptsCount(), attemptId, task.scheduledFor());
    }
    throw invalid(task.status(), request.type(), "start requires READY or CLAIMED.");
  }

  private TaskEntity fail(TaskEntity task, TaskTransitionRequest request, Instant now) {
    if (task.status() != TaskStatus.IN_PROGRESS) {
      throw invalid(task.status(), request.type(), "fail requires IN_PROGRESS.");
    }
    int nextAttempts = task.attemptsCount() + 1;
    Integer maxAttempts = task.maxAttempts();
    boolean canRetry = maxAttempts != null && maxAttempts > 0 && nextAttempts < maxAttempts;
    TaskStatus next = canRetry ? TaskStatus.READY : TaskStatus.FAILED;
    return rewrite(task, next, now, null, null, nextAttempts, null, task.scheduledFor());
  }

  private TaskEntity cancel(TaskEntity task, Instant now) {
    return switch (task.status()) {
      case NEW, READY, CLAIMED, IN_PROGRESS, BLOCKED, SNOOZED ->
          rewrite(task, TaskStatus.CANCELED, now, null, null, task.attemptsCount(), null, task.scheduledFor());
      default -> throw invalid(task.status(), TaskTransitionType.CANCEL, "cancel not allowed for this state.");
    };
  }

  private TaskEntity snooze(TaskEntity task, TaskTransitionRequest request, Instant now) {
    if (task.status() != TaskStatus.NEW && task.status() != TaskStatus.READY) {
      throw invalid(task.status(), request.type(), "snooze requires NEW or READY.");
    }
    if (request.scheduleAt() == null || !request.scheduleAt().isAfter(now)) {
      throw new TaskStateMachineException("snooze requires scheduleAt in the future.");
    }
    return rewrite(task, TaskStatus.SNOOZED, now, null, null, task.attemptsCount(), task.activeAttemptId(), request.scheduleAt());
  }

  private TaskEntity expect(TaskEntity task,
                            TaskStatus expectedCurrent,
                            TaskStatus next,
                            TaskTransitionType type,
                            Instant now) {
    return expect(task, expectedCurrent, next, type, now, task.activeAttemptId());
  }

  private TaskEntity expect(TaskEntity task,
                            TaskStatus expectedCurrent,
                            TaskStatus next,
                            TaskTransitionType type,
                            Instant now,
                            String activeAttemptId) {
    if (task.status() != expectedCurrent) {
      throw invalid(task.status(), type, "Invalid state for transition.");
    }
    return rewrite(task, next, now, task.claimedBy(), task.claimExpiresAt(), task.attemptsCount(), activeAttemptId, task.scheduledFor());
  }

  private TaskEntity rewrite(TaskEntity task,
                             TaskStatus status,
                             Instant now,
                             String claimedBy,
                             Instant claimExpiresAt,
                             int attempts,
                             String activeAttemptId,
                             Instant scheduledFor) {
    return new TaskEntity(
        task.taskId(),
        task.title(),
        task.description(),
        task.assignedTo(),
        task.createdAt(),
        now,
        task.createdBy(),
        status,
        task.priority(),
        task.tags(),
        scheduledFor,
        task.notBefore(),
        task.deadline(),
        task.windowStart(),
        task.windowEnd(),
        task.recurrence(),
        attempts,
        task.maxAttempts(),
        activeAttemptId,
        claimedBy,
        claimExpiresAt,
        task.version() + 1,
        task.lastEmittedDueAt(),
        task.idempotencyKey()
    );
  }

  private boolean isDue(TaskEntity task, Instant now) {
    if (task.windowStart() != null && now.isBefore(task.windowStart())) {
      return false;
    }
    if (task.notBefore() != null && now.isBefore(task.notBefore())) {
      return false;
    }
    return task.scheduledFor() == null || !now.isBefore(task.scheduledFor());
  }

  private boolean isExpired(TaskEntity task, Instant now) {
    if (task.windowEnd() != null && now.isAfter(task.windowEnd())) {
      return true;
    }
    return task.deadline() != null && now.isAfter(task.deadline());
  }

  private void validateWindow(TaskEntity task) {
    if (task.windowStart() != null && task.windowEnd() != null && task.windowEnd().isBefore(task.windowStart())) {
      throw new TaskStateMachineException("window_end must be >= window_start.");
    }
  }

  private TaskStateMachineException invalid(TaskStatus current, TaskTransitionType requested, String reason) {
    return new TaskStateMachineException("Transition not allowed: " + requested + " from " + current + ". " + reason);
  }

  private String safe(String value) {
    return value == null ? "" : value.trim();
  }
}
