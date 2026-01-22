package com.social100.todero.component.taskmanager.persistence;

import com.social100.todero.component.taskmanager.domain.TaskEntity;
import com.social100.todero.component.taskmanager.domain.TaskAttemptEntity;
import com.social100.todero.component.taskmanager.domain.TaskAttemptStatus;
import com.social100.todero.component.taskmanager.domain.TaskStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteTaskRepositoryIntegrationTest {

  @Test
  void createGetListAndUpdateWithVersionCheck() throws Exception {
    Path db = Files.createTempFile("task-manager", ".sqlite");
    SqliteTaskRepository repo = new SqliteTaskRepository(db);

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    TaskEntity created = repo.create(task("task-1", TaskStatus.NEW, now.plusSeconds(60), now.minusSeconds(20), null, null, null));

    TaskEntity loaded = repo.getById("task-1").orElseThrow();
    assertEquals(created.taskId(), loaded.taskId());
    assertEquals(TaskStatus.NEW, loaded.status());

    List<TaskEntity> filtered = repo.list(new TaskListFilter(Set.of(TaskStatus.NEW), "agent-a", 10, 0));
    assertEquals(1, filtered.size());

    TaskEntity updated = new TaskEntity(
        loaded.taskId(),
        loaded.title(),
        loaded.description(),
        loaded.assignedTo(),
        loaded.createdAt(),
        now.plusSeconds(1),
        loaded.createdBy(),
        TaskStatus.READY,
        loaded.priority(),
        loaded.tags(),
        loaded.scheduledFor(),
        loaded.notBefore(),
        loaded.deadline(),
        loaded.windowStart(),
        loaded.windowEnd(),
        loaded.recurrence(),
        loaded.attemptsCount(),
        loaded.maxAttempts(),
        loaded.activeAttemptId(),
        loaded.claimedBy(),
        loaded.claimExpiresAt(),
        loaded.version() + 1,
        loaded.lastEmittedDueAt(),
        loaded.idempotencyKey()
    );

    repo.update(updated, loaded.version());

    TaskEntity afterUpdate = repo.getById("task-1").orElseThrow();
    assertEquals(TaskStatus.READY, afterUpdate.status());

    assertThrows(TaskVersionConflictException.class, () -> repo.update(updated, loaded.version()));
  }

  @Test
  void dueExpiryAndClaimExpiryQueriesReturnExpectedRows() throws Exception {
    Path db = Files.createTempFile("task-manager", ".sqlite");
    SqliteTaskRepository repo = new SqliteTaskRepository(db);

    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    repo.create(task("due-1", TaskStatus.NEW, now.minusSeconds(10), now.minusSeconds(20), null, null, null));
    repo.create(task("future-1", TaskStatus.NEW, now.plusSeconds(300), now.minusSeconds(20), null, null, null));

    repo.create(task("exp-1", TaskStatus.READY, now.minusSeconds(10), now.minusSeconds(20), null, null, now.minusSeconds(1)));
    repo.create(task("active-1", TaskStatus.IN_PROGRESS, now.minusSeconds(10), now.minusSeconds(20), null, null, null));

    repo.create(task("claim-exp-1", TaskStatus.CLAIMED, now.minusSeconds(10), now.minusSeconds(20), "agent-a", now.minusSeconds(1), null));
    repo.create(task("claim-live-1", TaskStatus.CLAIMED, now.minusSeconds(10), now.minusSeconds(20), "agent-a", now.plusSeconds(300), null));

    List<TaskEntity> due = repo.findDueCandidates(now, 50);
    assertTrue(due.stream().anyMatch(t -> "due-1".equals(t.taskId())));
    assertFalse(due.stream().anyMatch(t -> "future-1".equals(t.taskId())));

    List<TaskEntity> exp = repo.findExpiryCandidates(now, 50);
    assertTrue(exp.stream().anyMatch(t -> "exp-1".equals(t.taskId())));
    assertFalse(exp.stream().anyMatch(t -> "active-1".equals(t.taskId())));

    List<TaskEntity> claimExp = repo.findClaimExpiryCandidates(now, 50);
    assertTrue(claimExp.stream().anyMatch(t -> "claim-exp-1".equals(t.taskId())));
    assertFalse(claimExp.stream().anyMatch(t -> "claim-live-1".equals(t.taskId())));
  }

  @Test
  void openAndCloseAttemptPersistsLifecycleRows() throws Exception {
    Path db = Files.createTempFile("task-manager", ".sqlite");
    SqliteTaskRepository repo = new SqliteTaskRepository(db);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    TaskEntity task = repo.create(task("attempt-task", TaskStatus.READY, now.minusSeconds(1), now.minusSeconds(10), null, null, null));
    TaskEntity inProgress = new TaskEntity(
        task.taskId(),
        task.title(),
        task.description(),
        task.assignedTo(),
        task.createdAt(),
        now.plusSeconds(1),
        task.createdBy(),
        TaskStatus.IN_PROGRESS,
        task.priority(),
        task.tags(),
        task.scheduledFor(),
        task.notBefore(),
        task.deadline(),
        task.windowStart(),
        task.windowEnd(),
        task.recurrence(),
        task.attemptsCount(),
        task.maxAttempts(),
        "attempt-1",
        task.claimedBy(),
        task.claimExpiresAt(),
        task.version() + 1,
        task.lastEmittedDueAt(),
        task.idempotencyKey()
    );
    repo.update(inProgress, task.version());

    TaskAttemptEntity opened = repo.openAttempt("attempt-task", "attempt-1", "agent-a", now.plusSeconds(1));
    assertEquals(TaskAttemptStatus.STARTED, opened.status());
    assertEquals(1, opened.attemptNumber());

    TaskAttemptEntity closed = repo.closeActiveAttempt(
        "attempt-task",
        "agent-a",
        now.plusSeconds(2),
        "FAILED",
        "tool_error",
        "cannot reach provider",
        "retry"
    );
    assertEquals(TaskAttemptStatus.FAILED, closed.status());

    List<TaskAttemptEntity> attempts = repo.listAttempts("attempt-task", 10, 0);
    assertEquals(1, attempts.size());
    assertEquals(TaskAttemptStatus.FAILED, attempts.get(0).status());
    assertEquals("tool_error", attempts.get(0).errorCode());

    TaskAttemptEntity byNumber = repo.getAttempt("attempt-task", 1).orElseThrow();
    assertEquals("attempt-1", byNumber.attemptId());
    assertEquals(TaskAttemptStatus.FAILED, byNumber.status());
  }

  private static TaskEntity task(String id,
                                 TaskStatus status,
                                 Instant scheduledFor,
                                 Instant notBefore,
                                 String claimedBy,
                                 Instant claimExpiresAt,
                                 Instant deadline) {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    return new TaskEntity(
        id,
        "Task " + id,
        "desc",
        List.of("agent-a"),
        now,
        now,
        "creator",
        status,
        1,
        List.of("t"),
        scheduledFor,
        notBefore,
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
}
