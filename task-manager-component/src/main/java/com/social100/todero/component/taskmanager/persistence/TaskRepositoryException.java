package com.social100.todero.component.taskmanager.persistence;

public class TaskRepositoryException extends RuntimeException {
  public TaskRepositoryException(String message, Throwable cause) {
    super(message, cause);
  }

  public TaskRepositoryException(String message) {
    super(message);
  }
}
