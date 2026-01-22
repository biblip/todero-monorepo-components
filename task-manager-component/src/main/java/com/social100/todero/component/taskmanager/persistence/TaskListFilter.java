package com.social100.todero.component.taskmanager.persistence;

import com.social100.todero.component.taskmanager.domain.TaskStatus;

import java.util.Set;

public record TaskListFilter(
    Set<TaskStatus> statuses,
    String assignedTo,
    int limit,
    int offset
) {
  public TaskListFilter {
    if (limit < 1 || limit > 1000) {
      throw new IllegalArgumentException("limit must be in range 1..1000");
    }
    if (offset < 0) {
      throw new IllegalArgumentException("offset must be >= 0");
    }
  }

  public static TaskListFilter defaults() {
    return new TaskListFilter(Set.of(), null, 100, 0);
  }
}
