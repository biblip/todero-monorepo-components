package com.social100.todero.component.taskmanager.persistence;

import java.time.Instant;
import java.util.Objects;

public record TaskEvent(
    Long seq,
    String eventId,
    TaskEventType eventType,
    String taskId,
    String targetAgentId,
    long taskVersion,
    String payloadJson,
    Instant emittedAt,
    int deliveryAttempts,
    String lastDeliveryError,
    Instant ackedAt
) {
  public TaskEvent {
    Objects.requireNonNull(eventId, "eventId is required");
    Objects.requireNonNull(eventType, "eventType is required");
    Objects.requireNonNull(taskId, "taskId is required");
    Objects.requireNonNull(targetAgentId, "targetAgentId is required");
    Objects.requireNonNull(payloadJson, "payloadJson is required");
    Objects.requireNonNull(emittedAt, "emittedAt is required");
  }

  public TaskEvent withSeq(long value) {
    return new TaskEvent(
        value,
        eventId,
        eventType,
        taskId,
        targetAgentId,
        taskVersion,
        payloadJson,
        emittedAt,
        deliveryAttempts,
        lastDeliveryError,
        ackedAt
    );
  }
}
