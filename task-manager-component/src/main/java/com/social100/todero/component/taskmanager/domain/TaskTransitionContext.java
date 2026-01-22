package com.social100.todero.component.taskmanager.domain;

public record TaskTransitionContext(
    TaskEntity task,
    TaskTransitionRequest request
) {
  public TaskTransitionContext {
    if (task == null) {
      throw new IllegalArgumentException("task is required");
    }
    if (request == null) {
      throw new IllegalArgumentException("request is required");
    }
  }
}
