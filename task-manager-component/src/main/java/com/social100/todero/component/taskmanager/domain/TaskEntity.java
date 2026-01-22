package com.social100.todero.component.taskmanager.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record TaskEntity(
    String taskId,
    String title,
    String description,
    List<String> assignedTo,
    Instant createdAt,
    Instant updatedAt,
    String createdBy,
    TaskStatus status,
    Integer priority,
    List<String> tags,
    Instant scheduledFor,
    Instant notBefore,
    Instant deadline,
    Instant windowStart,
    Instant windowEnd,
    String recurrence,
    int attemptsCount,
    Integer maxAttempts,
    String activeAttemptId,
    String claimedBy,
    Instant claimExpiresAt,
    long version,
    Instant lastEmittedDueAt,
    String idempotencyKey
) {
  public TaskEntity {
    Objects.requireNonNull(taskId, "taskId is required");
    Objects.requireNonNull(title, "title is required");
    Objects.requireNonNull(assignedTo, "assignedTo is required");
    Objects.requireNonNull(createdAt, "createdAt is required");
    Objects.requireNonNull(updatedAt, "updatedAt is required");
    Objects.requireNonNull(status, "status is required");

    if (taskId.trim().isEmpty()) {
      throw new IllegalArgumentException("taskId is required");
    }
    if (title.trim().isEmpty()) {
      throw new IllegalArgumentException("title is required");
    }
    if (assignedTo.isEmpty()) {
      throw new IllegalArgumentException("assignedTo must contain at least one agent");
    }
  }

  public TaskEntity withState(TaskStatus newStatus, Instant now) {
    return new TaskEntity(
        taskId,
        title,
        description,
        assignedTo,
        createdAt,
        now,
        createdBy,
        newStatus,
        priority,
        tags,
        scheduledFor,
        notBefore,
        deadline,
        windowStart,
        windowEnd,
        recurrence,
        attemptsCount,
        maxAttempts,
        activeAttemptId,
        claimedBy,
        claimExpiresAt,
        version + 1,
        lastEmittedDueAt,
        idempotencyKey
    );
  }
}
