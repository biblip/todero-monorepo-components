package com.social100.todero.component.taskmanager.persistence;

public class TaskVersionConflictException extends TaskRepositoryException {
  public TaskVersionConflictException(String taskId, long expectedVersion) {
    super("Version conflict for task " + taskId + " (expected version=" + expectedVersion + ").");
  }
}
