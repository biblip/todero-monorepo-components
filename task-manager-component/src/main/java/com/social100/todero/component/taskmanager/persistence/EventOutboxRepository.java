package com.social100.todero.component.taskmanager.persistence;

import java.time.Instant;
import java.util.List;

public interface EventOutboxRepository {
  TaskEvent enqueue(TaskEvent event);

  List<TaskEvent> listPending(String agentId, int limit);

  List<TaskEvent> listPendingAll(int limit);

  void markAcked(String agentId, String eventId, Instant ackedAt);

  void markDeliveryAttempt(String eventId, Instant attemptAt, String errorMessage);
}
