package com.shellaia.tutil.todo;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TodoTask(String id,
                       String ownerId,
                       String title,
                       String description,
                       TodoStatus status,
                       String outcome,
                       Instant dueAt,
                       int priority,
                       List<String> dependencyTaskIds,
                       String claimedBy,
                       Instant claimedAt,
                       Instant claimExpiresAt,
                       Map<String, String> metadata,
                       Instant createdAt,
                       Instant updatedAt) {

  public TodoTask {
    id = safeTrim(id);
    ownerId = safeTrim(ownerId);
    title = safeTrim(title);
    description = safeTrim(description);
    outcome = safeTrim(outcome);
    status = status == null ? TodoStatus.PLANNED : status;
    dependencyTaskIds = dependencyTaskIds == null ? List.of() : List.copyOf(dependencyTaskIds.stream().map(TodoTask::safeTrim).filter(v -> !v.isEmpty()).toList());
    claimedBy = safeTrim(claimedBy);
    if (claimedBy.isEmpty()) {
      claimedAt = null;
      claimExpiresAt = null;
    }
    metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    if (createdAt == null) {
      createdAt = Instant.now();
    }
    if (updatedAt == null) {
      updatedAt = createdAt;
    }
    if (id.isEmpty()) {
      throw new IllegalArgumentException("Task id is required.");
    }
    if (ownerId.isEmpty()) {
      throw new IllegalArgumentException("Task owner is required.");
    }
    if (title.isEmpty()) {
      throw new IllegalArgumentException("Task title is required.");
    }
  }

  TodoTask withStatus(TodoStatus nextStatus, String nextOutcome, Instant now) {
    return new TodoTask(id, ownerId, title, description, nextStatus, nextOutcome, dueAt, priority, dependencyTaskIds, claimedBy, claimedAt, claimExpiresAt, metadata, createdAt, now);
  }

  TodoTask withMetadata(Map<String, String> nextMetadata, Instant now) {
    return new TodoTask(id, ownerId, title, description, status, outcome, dueAt, priority, dependencyTaskIds, claimedBy, claimedAt, claimExpiresAt, nextMetadata, createdAt, now);
  }

  TodoTask withPlanning(String nextTitle,
                        String nextDescription,
                        Instant nextDueAt,
                        int nextPriority,
                        List<String> nextDependencyTaskIds,
                        Instant now) {
    return new TodoTask(
        id,
        ownerId,
        nextTitle,
        nextDescription,
        status,
        outcome,
        nextDueAt,
        nextPriority,
        nextDependencyTaskIds,
        claimedBy,
        claimedAt,
        claimExpiresAt,
        metadata,
        createdAt,
        now
    );
  }

  TodoTask claim(String agentId, Instant now, Instant expiresAt) {
    String nextClaimedBy = safeTrim(agentId);
    if (nextClaimedBy.isEmpty()) {
      throw new IllegalArgumentException("Agent id is required.");
    }
    return new TodoTask(
        id,
        ownerId,
        title,
        description,
        status,
        outcome,
        dueAt,
        priority,
        dependencyTaskIds,
        nextClaimedBy,
        now,
        expiresAt,
        metadata,
        createdAt,
        now
    );
  }

  TodoTask renewClaim(String agentId, Instant now, Instant expiresAt) {
    String nextClaimedBy = safeTrim(agentId);
    if (nextClaimedBy.isEmpty()) {
      throw new IllegalArgumentException("Agent id is required.");
    }
    return new TodoTask(
        id,
        ownerId,
        title,
        description,
        status,
        outcome,
        dueAt,
        priority,
        dependencyTaskIds,
        nextClaimedBy,
        claimedAt == null ? now : claimedAt,
        expiresAt,
        metadata,
        createdAt,
        now
    );
  }

  TodoTask releaseClaim(Instant now) {
    return new TodoTask(
        id,
        ownerId,
        title,
        description,
        status,
        outcome,
        dueAt,
        priority,
        dependencyTaskIds,
        "",
        null,
        null,
        metadata,
        createdAt,
        now
    );
  }

  private static String safeTrim(String value) {
    return value == null ? "" : value.trim();
  }
}
