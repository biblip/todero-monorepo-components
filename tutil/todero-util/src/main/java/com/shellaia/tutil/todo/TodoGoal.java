package com.shellaia.tutil.todo;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TodoGoal(String id,
                       String ownerId,
                       String title,
                       String description,
                       TodoStatus status,
                       String outcome,
                       List<TodoPhase> phases,
                       Map<String, String> metadata,
                       Instant createdAt,
                       Instant updatedAt,
                       long version) {

  public TodoGoal {
    id = safeTrim(id);
    ownerId = safeTrim(ownerId);
    title = safeTrim(title);
    description = safeTrim(description);
    outcome = safeTrim(outcome);
    status = status == null ? TodoStatus.PLANNED : status;
    phases = phases == null ? List.of() : List.copyOf(phases);
    metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    if (createdAt == null) {
      createdAt = Instant.now();
    }
    if (updatedAt == null) {
      updatedAt = createdAt;
    }
    if (id.isEmpty()) {
      throw new IllegalArgumentException("Goal id is required.");
    }
    if (ownerId.isEmpty()) {
      throw new IllegalArgumentException("Goal owner is required.");
    }
    if (title.isEmpty()) {
      throw new IllegalArgumentException("Goal title is required.");
    }
  }

  public TodoProgress progress() {
    int phaseCount = phases.size();
    int completedPhases = 0;
    int taskCount = 0;
    int completedTasks = 0;
    int readyTasks = 0;
    int inProgressTasks = 0;
    int blockedTasks = 0;
    int failedTasks = 0;
    int canceledTasks = 0;
    for (TodoPhase phase : phases) {
      if (phase.status() == TodoStatus.COMPLETED) {
        completedPhases++;
      }
      taskCount += phase.tasks().size();
      for (TodoTask task : phase.tasks()) {
        switch (task.status()) {
          case COMPLETED -> completedTasks++;
          case READY -> readyTasks++;
          case IN_PROGRESS -> inProgressTasks++;
          case BLOCKED -> blockedTasks++;
          case FAILED -> failedTasks++;
          case CANCELED -> canceledTasks++;
          default -> {
          }
        }
      }
    }
    return new TodoProgress(
        phaseCount,
        completedPhases,
        taskCount,
        completedTasks,
        readyTasks,
        inProgressTasks,
        blockedTasks,
        failedTasks,
        canceledTasks
    );
  }

  TodoGoal withPhases(List<TodoPhase> nextPhases, TodoStatus nextStatus, String nextOutcome, Instant now) {
    return new TodoGoal(id, ownerId, title, description, nextStatus, nextOutcome, nextPhases, metadata, createdAt, now, version + 1);
  }

  TodoGoal withStatus(TodoStatus nextStatus, String nextOutcome, Instant now) {
    return new TodoGoal(id, ownerId, title, description, nextStatus, nextOutcome, phases, metadata, createdAt, now, version + 1);
  }

  private static String safeTrim(String value) {
    return value == null ? "" : value.trim();
  }
}
