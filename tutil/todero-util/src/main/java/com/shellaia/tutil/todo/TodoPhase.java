package com.shellaia.tutil.todo;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TodoPhase(String id,
                        String ownerId,
                        String title,
                        String description,
                        TodoStatus status,
                        String outcome,
                        List<TodoTask> tasks,
                        Map<String, String> metadata,
                        Instant createdAt,
                        Instant updatedAt) {

  public TodoPhase {
    id = safeTrim(id);
    ownerId = safeTrim(ownerId);
    title = safeTrim(title);
    description = safeTrim(description);
    outcome = safeTrim(outcome);
    status = status == null ? TodoStatus.PLANNED : status;
    tasks = tasks == null ? List.of() : List.copyOf(tasks);
    metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    if (createdAt == null) {
      createdAt = Instant.now();
    }
    if (updatedAt == null) {
      updatedAt = createdAt;
    }
    if (id.isEmpty()) {
      throw new IllegalArgumentException("Phase id is required.");
    }
    if (ownerId.isEmpty()) {
      throw new IllegalArgumentException("Phase owner is required.");
    }
    if (title.isEmpty()) {
      throw new IllegalArgumentException("Phase title is required.");
    }
  }

  TodoPhase withTasks(List<TodoTask> nextTasks, TodoStatus nextStatus, String nextOutcome, Instant now) {
    return new TodoPhase(id, ownerId, title, description, nextStatus, nextOutcome, nextTasks, metadata, createdAt, now);
  }

  TodoPhase withStatus(TodoStatus nextStatus, String nextOutcome, Instant now) {
    return new TodoPhase(id, ownerId, title, description, nextStatus, nextOutcome, tasks, metadata, createdAt, now);
  }

  private static String safeTrim(String value) {
    return value == null ? "" : value.trim();
  }
}
