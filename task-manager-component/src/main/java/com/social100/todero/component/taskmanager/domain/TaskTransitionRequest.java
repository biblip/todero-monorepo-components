package com.social100.todero.component.taskmanager.domain;

import java.time.Duration;
import java.time.Instant;

public record TaskTransitionRequest(
    TaskTransitionType type,
    String actor,
    Instant now,
    Instant scheduleAt,
    Duration leaseDuration,
    String errorMessage,
    String errorCode,
    String note,
    String attemptId
) {
  public TaskTransitionRequest {
    if (type == null) {
      throw new IllegalArgumentException("type is required");
    }
    if (now == null) {
      throw new IllegalArgumentException("now is required");
    }
  }

  public static TaskTransitionRequest at(TaskTransitionType type, Instant now) {
    return new TaskTransitionRequest(type, null, now, null, null, null, null, null, null);
  }

  public TaskTransitionRequest withActor(String value) {
    return new TaskTransitionRequest(type, value, now, scheduleAt, leaseDuration, errorMessage, errorCode, note, attemptId);
  }

  public TaskTransitionRequest withScheduleAt(Instant value) {
    return new TaskTransitionRequest(type, actor, now, value, leaseDuration, errorMessage, errorCode, note, attemptId);
  }

  public TaskTransitionRequest withLease(Duration value) {
    return new TaskTransitionRequest(type, actor, now, scheduleAt, value, errorMessage, errorCode, note, attemptId);
  }

  public TaskTransitionRequest withError(String value) {
    return new TaskTransitionRequest(type, actor, now, scheduleAt, leaseDuration, value, errorCode, note, attemptId);
  }

  public TaskTransitionRequest withErrorCode(String value) {
    return new TaskTransitionRequest(type, actor, now, scheduleAt, leaseDuration, errorMessage, value, note, attemptId);
  }

  public TaskTransitionRequest withNote(String value) {
    return new TaskTransitionRequest(type, actor, now, scheduleAt, leaseDuration, errorMessage, errorCode, value, attemptId);
  }

  public TaskTransitionRequest withAttemptId(String value) {
    return new TaskTransitionRequest(type, actor, now, scheduleAt, leaseDuration, errorMessage, errorCode, note, value);
  }
}
