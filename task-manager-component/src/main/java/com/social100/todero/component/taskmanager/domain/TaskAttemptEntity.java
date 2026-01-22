package com.social100.todero.component.taskmanager.domain;

import java.time.Instant;
import java.util.Objects;

public record TaskAttemptEntity(
    String attemptId,
    String taskId,
    int attemptNumber,
    TaskAttemptStatus status,
    Instant startedAt,
    Instant endedAt,
    String startedBy,
    String endedBy,
    String errorCode,
    String errorMessage,
    String progressNote,
    String metaJson,
    Instant createdAt,
    Instant updatedAt
) {
  public TaskAttemptEntity {
    Objects.requireNonNull(attemptId, "attemptId is required");
    Objects.requireNonNull(taskId, "taskId is required");
    Objects.requireNonNull(status, "status is required");
    Objects.requireNonNull(startedAt, "startedAt is required");
    Objects.requireNonNull(createdAt, "createdAt is required");
    Objects.requireNonNull(updatedAt, "updatedAt is required");
    if (attemptNumber <= 0) {
      throw new IllegalArgumentException("attemptNumber must be > 0");
    }
  }
}
