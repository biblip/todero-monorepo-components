package com.social100.todero.component.taskmanager.persistence;

import com.social100.todero.component.taskmanager.domain.TaskEntity;
import com.social100.todero.component.taskmanager.domain.TaskAttemptEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TaskRepository {
  TaskEntity create(TaskEntity task);

  Optional<TaskEntity> getById(String taskId);

  List<TaskEntity> list(TaskListFilter filter);

  TaskEntity update(TaskEntity task, long expectedVersion);

  List<TaskEntity> findDueCandidates(Instant now, int limit);

  List<TaskEntity> findExpiryCandidates(Instant now, int limit);

  List<TaskEntity> findClaimExpiryCandidates(Instant now, int limit);

  List<TaskAttemptEntity> listAttempts(String taskId, int limit, int offset);

  Optional<TaskAttemptEntity> getAttempt(String taskId, int attemptNumber);

  TaskAttemptEntity openAttempt(String taskId, String attemptId, String actor, Instant startedAt);

  TaskAttemptEntity closeActiveAttempt(String taskId,
                                       String actor,
                                       Instant endedAt,
                                       String status,
                                       String errorCode,
                                       String errorMessage,
                                       String note);
}
