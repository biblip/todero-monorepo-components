package com.social100.todero.component.taskmanager.persistence;

import com.social100.todero.component.taskmanager.domain.TaskEntity;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

public class TaskTransitionOutboxWriter {
  private final SqliteTaskRepository taskRepository;
  private final SqliteEventOutboxRepository outboxRepository;

  public TaskTransitionOutboxWriter(SqliteTaskRepository taskRepository,
                                    SqliteEventOutboxRepository outboxRepository) {
    this.taskRepository = taskRepository;
    this.outboxRepository = outboxRepository;
  }

  public record PersistResult(TaskEntity updatedTask, List<TaskEvent> events) {
  }

  public PersistResult persistTransition(TaskEntity nextTask,
                                         long expectedPreviousVersion,
                                         TaskEventType eventType,
                                         String payloadJson,
                                         Instant emittedAt) {
    return persistTransition(nextTask, expectedPreviousVersion, eventType, payloadJson, emittedAt, null);
  }

  public PersistResult persistTransition(TaskEntity nextTask,
                                         long expectedPreviousVersion,
                                         TaskEventType eventType,
                                         String payloadJson,
                                         Instant emittedAt,
                                         BiConsumer<Connection, TaskEntity> afterTaskUpdate) {
    try (Connection c = taskRepository.open()) {
      c.setAutoCommit(false);
      try {
        TaskEntity updated = taskRepository.updateInConnection(c, nextTask, expectedPreviousVersion);
        if (afterTaskUpdate != null) {
          afterTaskUpdate.accept(c, updated);
        }
        List<TaskEvent> emitted = new ArrayList<>();
        for (String agentId : updated.assignedTo()) {
          TaskEvent event = new TaskEvent(
              null,
              UUID.randomUUID().toString(),
              eventType,
              updated.taskId(),
              agentId,
              updated.version(),
              payloadJson,
              emittedAt,
              0,
              null,
              null
          );
          emitted.add(outboxRepository.enqueueInConnection(c, event));
        }
        c.commit();
        return new PersistResult(updated, emitted);
      } catch (Exception e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(true);
      }
    } catch (TaskRepositoryException e) {
      throw e;
    } catch (Exception e) {
      throw new TaskRepositoryException("Failed to persist task transition and outbox events.", e);
    }
  }
}
