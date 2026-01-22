package com.social100.todero.component.taskmanager.service;

import com.social100.todero.component.taskmanager.domain.TaskEntity;
import com.social100.todero.component.taskmanager.domain.TaskAttemptEntity;
import com.social100.todero.component.taskmanager.domain.TaskAttemptStatus;
import com.social100.todero.component.taskmanager.domain.TaskStateMachine;
import com.social100.todero.component.taskmanager.domain.TaskStatus;
import com.social100.todero.component.taskmanager.persistence.SqliteEventOutboxRepository;
import com.social100.todero.component.taskmanager.persistence.SqliteTaskRepository;
import com.social100.todero.component.taskmanager.persistence.TaskEvent;
import com.social100.todero.component.taskmanager.persistence.TaskEventType;
import com.social100.todero.component.taskmanager.persistence.TaskListFilter;
import com.social100.todero.component.taskmanager.persistence.TaskTransitionOutboxWriter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskServiceTest {

  @Test
  void createGetListAndUpdateFlow() throws Exception {
    Fixture fx = fixture(Instant.parse("2026-02-01T00:00:00Z"));

    TaskServiceResult<TaskEntity> created = fx.service.createTask(new TaskService.CreateTaskRequest(
        "task-a",
        "Task A",
        "desc",
        List.of("agent-a"),
        "creator",
        1,
        List.of("tag-1"),
        null,
        null,
        null,
        null,
        null,
        null,
        3,
        null
    ));
    assertTrue(created.ok());
    assertEquals(TaskStatus.READY, created.data().status());

    TaskServiceResult<TaskEntity> fetched = fx.service.getTask("task-a");
    assertTrue(fetched.ok());
    assertEquals("Task A", fetched.data().title());

    TaskServiceResult<List<TaskEntity>> listed = fx.service.listTasks(new TaskListFilter(Set.of(TaskStatus.READY), null, 10, 0));
    assertTrue(listed.ok());
    assertEquals(1, listed.data().size());

    TaskServiceResult<TaskEntity> updated = fx.service.updateTask(new TaskService.UpdateTaskRequest(
        "task-a",
        created.data().version(),
        "Task A Updated",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null
    ));
    assertTrue(updated.ok());
    assertEquals("Task A Updated", updated.data().title());
  }

  @Test
  void claimStartCompleteAndAckFlow() throws Exception {
    Fixture fx = fixture(Instant.parse("2026-02-01T00:00:00Z"));
    fx.service.createTask(new TaskService.CreateTaskRequest(
        "task-b",
        "Task B",
        null,
        List.of("agent-a"),
        "creator",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        2,
        null
    ));

    TaskServiceResult<TaskEntity> claimed = fx.service.claimTask("task-b", "agent-a", Duration.ofMinutes(5));
    assertTrue(claimed.ok());
    assertEquals(TaskStatus.CLAIMED, claimed.data().status());

    TaskServiceResult<TaskEntity> started = fx.service.startTask("task-b", "agent-a");
    assertTrue(started.ok());
    assertEquals(TaskStatus.IN_PROGRESS, started.data().status());

    TaskServiceResult<TaskEntity> completed = fx.service.completeTask("task-b", "agent-a", null);
    assertTrue(completed.ok());
    assertEquals(TaskStatus.COMPLETED, completed.data().status());

    List<TaskEvent> pending = fx.outbox.listPending("agent-a", 100);
    assertFalse(pending.isEmpty());
    String eventId = pending.get(0).eventId();
    TaskServiceResult<Void> ack = fx.service.ackEvent("agent-a", eventId);
    assertTrue(ack.ok());
    assertTrue(fx.outbox.listPending("agent-a", 100).stream().noneMatch(e -> e.eventId().equals(eventId)));
  }

  @Test
  void evaluateDueAndClaimExpiryTransitions() throws Exception {
    Fixture fx = fixture(Instant.parse("2026-02-01T00:00:00Z"));
    Instant now = Instant.parse("2026-02-01T00:00:00Z");

    TaskEntity dueTask = new TaskEntity(
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
        2,
        null,
        null,
        null,
        0,
        null,
        null
    );
    fx.taskRepo.create(dueTask);

    TaskEntity claimExpired = new TaskEntity(
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
        2,
        null,
        "agent-a",
        now.minusSeconds(1),
        0,
        null,
        null
    );
    fx.taskRepo.create(claimExpired);

    TaskServiceResult<TaskService.EvaluateDueSummary> result = fx.service.evaluateDueTasks(100);
    assertTrue(result.ok());
    assertTrue(result.data().dueTransitions() >= 1);
    assertTrue(result.data().claimExpiryTransitions() >= 1);

    TaskEntity dueAfter = fx.taskRepo.getById("task-due").orElseThrow();
    TaskEntity claimAfter = fx.taskRepo.getById("task-claim-expired").orElseThrow();
    assertEquals(TaskStatus.READY, dueAfter.status());
    assertEquals(TaskStatus.READY, claimAfter.status());
  }

  @Test
  void updateWithWrongExpectedVersionReturnsStructuredConflict() throws Exception {
    Fixture fx = fixture(Instant.parse("2026-02-01T00:00:00Z"));
    TaskServiceResult<TaskEntity> created = fx.service.createTask(new TaskService.CreateTaskRequest(
        "task-c",
        "Task C",
        null,
        List.of("agent-a"),
        "creator",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        2,
        null
    ));
    assertTrue(created.ok());

    TaskServiceResult<TaskEntity> conflict = fx.service.updateTask(new TaskService.UpdateTaskRequest(
        "task-c",
        999L,
        "Task C Updated",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null
    ));
    assertFalse(conflict.ok());
    assertEquals("version_conflict", conflict.errorCode());
    assertNotNull(conflict.message());
  }

  @Test
  void attemptHistoryTracksFailThenCompleteAcrossRetries() throws Exception {
    Fixture fx = fixture(Instant.parse("2026-02-01T00:00:00Z"));
    TaskServiceResult<TaskEntity> created = fx.service.createTask(new TaskService.CreateTaskRequest(
        "task-attempts",
        "Task Attempts",
        null,
        List.of("agent-a"),
        "creator",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        3,
        null
    ));
    assertTrue(created.ok());

    TaskServiceResult<TaskEntity> start1 = fx.service.startTask("task-attempts", "agent-a");
    assertTrue(start1.ok());
    assertEquals(TaskStatus.IN_PROGRESS, start1.data().status());

    TaskServiceResult<TaskEntity> fail1 = fx.service.failTask("task-attempts", "agent-a", "tool_error", "temporary issue", "retrying");
    assertTrue(fail1.ok());
    assertEquals(TaskStatus.READY, fail1.data().status());

    TaskServiceResult<TaskEntity> start2 = fx.service.startTask("task-attempts", "agent-a");
    assertTrue(start2.ok());
    assertEquals(TaskStatus.IN_PROGRESS, start2.data().status());

    TaskServiceResult<TaskEntity> complete2 = fx.service.completeTask("task-attempts", "agent-a", "resolved");
    assertTrue(complete2.ok());
    assertEquals(TaskStatus.COMPLETED, complete2.data().status());

    TaskServiceResult<List<TaskAttemptEntity>> attempts = fx.service.listAttempts("task-attempts", 10, 0);
    assertTrue(attempts.ok());
    assertEquals(2, attempts.data().size());
    assertEquals(TaskAttemptStatus.COMPLETED, attempts.data().get(0).status());
    assertEquals(TaskAttemptStatus.FAILED, attempts.data().get(1).status());

    TaskServiceResult<TaskAttemptEntity> attemptOne = fx.service.getAttempt("task-attempts", 1);
    assertTrue(attemptOne.ok());
    assertEquals(TaskAttemptStatus.FAILED, attemptOne.data().status());
  }

  private static Fixture fixture(Instant now) throws Exception {
    Path db = Files.createTempFile("task-service", ".sqlite");
    SqliteTaskRepository taskRepo = new SqliteTaskRepository(db);
    SqliteEventOutboxRepository outbox = new SqliteEventOutboxRepository(taskRepo);
    TaskTransitionOutboxWriter writer = new TaskTransitionOutboxWriter(taskRepo, outbox);
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    TaskService service = new TaskService(taskRepo, outbox, writer, new TaskStateMachine(), clock);
    return new Fixture(service, taskRepo, outbox);
  }

  private record Fixture(TaskService service, SqliteTaskRepository taskRepo, SqliteEventOutboxRepository outbox) {
  }
}
