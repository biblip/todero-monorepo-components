package com.shellaia.component.taskmanager.domain;

public class TaskStateMachineException extends RuntimeException {
  public TaskStateMachineException(String message) {
    super(message);
  }
}
