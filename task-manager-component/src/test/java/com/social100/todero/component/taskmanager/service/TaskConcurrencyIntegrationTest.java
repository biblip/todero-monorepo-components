package com.social100.todero.component.taskmanager.service;

import com.social100.todero.component.taskmanager.domain.TaskEntity;
import com.social100.todero.component.taskmanager.domain.TaskStateMachine;
import com.social100.todero.component.taskmanager.domain.TaskStatus;
import com.social100.todero.component.taskmanager.domain.TaskTransitionContext;
import com.social100.todero.component.taskmanager.domain.TaskTransitionRequest;
import com.social100.todero.component.taskmanager.domain.TaskTransitionType;
import com.social100.todero.component.taskmanager.persistence.SqliteEventOutboxRepository;
import com.social100.todero.component.taskmanager.persistence.SqliteTaskRepository;
import com.social100.todero.component.taskmanager.persistence.TaskEventType;
import com.social100.todero.component.taskmanager.persistence.TaskTransitionOutboxWriter;
import com.social100.todero.component.taskmanager.persistence.TaskVersionConflictException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskConcurrencyIntegrationTest {

  @Test
  void concurrentStartOnlyOneTransitionSucceeds() throws Exception {
    Instant now = Instant.parse("2026-07-01T00:00:00Z");
    Path db = Files.createTempFile("task-concurrency", ".sqlite");
    SqliteTaskRepository taskRepo = new SqliteTaskRepository(db);
    SqliteEventOutboxRepository outbox = new SqliteEventOutboxRepository(taskRepo);
    TaskTransitionOutboxWriter writer = new TaskTransitionOutboxWriter(taskRepo, outbox);
    TaskStateMachine machine = new TaskStateMachine();

    taskRepo.create(new TaskEntity(
        "task-concurrent-start",
        "Task Concurrent",
        null,
        List.of("agent-a"),
        now.minusSeconds(60),
        now.minusSeconds(60),
        "creator",
        TaskStatus.READY,
        null,
        List.of(),
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
    ));

    TaskEntity snapshot = taskRepo.getById("task-concurrent-start").orElseThrow();
    TaskEntity next = machine.transition(new TaskTransitionContext(
        snapshot,
        TaskTransitionRequest.at(TaskTransitionType.START, now).withActor("agent-a")
    ));

    Callable<Boolean> starter = () -> {
      writer.persistTransition(next, snapshot.version(), TaskEventType.TASK_STARTED, "{\"event\":\"started\"}", now);
      return true;
    };

    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<Boolean> first = executor.submit(starter);
    Future<Boolean> second = executor.submit(starter);
    executor.shutdown();

    int success = 0;
    int conflicts = 0;
    for (Future<Boolean> future : List.of(first, second)) {
      try {
        if (future.get()) {
          success++;
        }
      } catch (ExecutionException e) {
        if (e.getCause() instanceof TaskVersionConflictException) {
          conflicts++;
        } else {
          throw e;
        }
      }
    }

    assertEquals(1, success);
    assertEquals(1, conflicts);
    assertEquals(TaskStatus.IN_PROGRESS, taskRepo.getById("task-concurrent-start").orElseThrow().status());
    assertTrue(outbox.listPending("agent-a", 10).size() >= 1);
  }
}
