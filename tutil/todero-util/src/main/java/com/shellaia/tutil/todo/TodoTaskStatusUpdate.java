package com.shellaia.tutil.todo;

public record TodoTaskStatusUpdate(String phaseId,
                                   String taskId,
                                   TodoStatus status,
                                   String outcome) {

  public TodoTaskStatusUpdate {
    phaseId = phaseId == null ? "" : phaseId.trim();
    taskId = taskId == null ? "" : taskId.trim();
    outcome = outcome == null ? "" : outcome.trim();
    if (phaseId.isEmpty()) {
      throw new IllegalArgumentException("Phase id is required.");
    }
    if (taskId.isEmpty()) {
      throw new IllegalArgumentException("Task id is required.");
    }
    if (status == null) {
      throw new IllegalArgumentException("Status is required.");
    }
  }
}
