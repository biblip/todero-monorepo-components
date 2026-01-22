package com.social100.todero.component.taskmanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.social100.todero.component.taskmanager.persistence.EventOutboxRepository;
import com.social100.todero.component.taskmanager.persistence.TaskEvent;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public class TaskEventDispatcher {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final EventOutboxRepository outboxRepository;
  private final Clock clock;
  private final int maxDeliveryAttempts;
  private final Map<String, CopyOnWriteArrayList<TaskEventSubscriber>> subscribers = new ConcurrentHashMap<>();
  private final TaskDispatchMetrics metrics = new TaskDispatchMetrics();
  private volatile String targetCommand = "react";

  public TaskEventDispatcher(EventOutboxRepository outboxRepository, Clock clock, int maxDeliveryAttempts) {
    this.outboxRepository = outboxRepository;
    this.clock = clock;
    this.maxDeliveryAttempts = Math.max(1, maxDeliveryAttempts);
  }

  public Runnable subscribe(String agentId, TaskEventSubscriber subscriber) {
    if (agentId == null || agentId.isBlank()) {
      throw new IllegalArgumentException("agentId is required.");
    }
    if (subscriber == null) {
      throw new IllegalArgumentException("subscriber is required.");
    }
    String normalized = agentId.trim();
    subscribers.computeIfAbsent(normalized, k -> new CopyOnWriteArrayList<>()).add(subscriber);
    return () -> unsubscribe(normalized, subscriber);
  }

  public void unsubscribe(String agentId, TaskEventSubscriber subscriber) {
    if (agentId == null || subscriber == null) {
      return;
    }
    CopyOnWriteArrayList<TaskEventSubscriber> list = subscribers.get(agentId.trim());
    if (list == null) {
      return;
    }
    list.remove(subscriber);
    if (list.isEmpty()) {
      subscribers.remove(agentId.trim(), list);
    }
  }

  public DispatchResult dispatchPendingAll(int limit) {
    return dispatchPendingAll(limit, DispatchMode.SUBSCRIBERS, null);
  }

  public DispatchResult dispatchPendingAll(int limit, DispatchMode mode, AgentCommandExecutor executor) {
    int safeLimit = sanitizeLimit(limit);
    List<TaskEvent> pending = outboxRepository.listPendingAll(safeLimit);
    int ackedNow = 0;
    int failedNow = 0;
    int noSubscriberNow = 0;
    int skippedRetriesNow = 0;

    for (TaskEvent event : pending) {
      metrics.eventsScanned.incrementAndGet();
      if (event.deliveryAttempts() >= maxDeliveryAttempts) {
        skippedRetriesNow++;
        metrics.eventsSkippedMaxRetries.incrementAndGet();
        continue;
      }

      boolean delivered = false;
      String payload = buildDispatchPayload(event);

      try {
        if (mode.includesSubscribers()) {
          CopyOnWriteArrayList<TaskEventSubscriber> listeners = subscribers.get(event.targetAgentId());
          if (listeners == null || listeners.isEmpty()) {
            noSubscriberNow++;
            metrics.eventsWithoutSubscriber.incrementAndGet();
          } else {
            for (TaskEventSubscriber listener : listeners) {
              listener.onEvent(new TaskEvent(
                  event.seq(),
                  event.eventId(),
                  event.eventType(),
                  event.taskId(),
                  event.targetAgentId(),
                  event.taskVersion(),
                  payload,
                  event.emittedAt(),
                  event.deliveryAttempts(),
                  event.lastDeliveryError(),
                  event.ackedAt()
              ));
            }
            delivered = true;
          }
        }

        if (mode.includesExecute()) {
          if (executor == null) {
            throw new IllegalArgumentException("execute dispatch mode requires executor.");
          }
          executor.execute(event.targetAgentId(), targetCommand, payload);
          delivered = true;
        }

        if (delivered) {
          outboxRepository.markAcked(event.targetAgentId(), event.eventId(), now());
          ackedNow++;
          metrics.eventsAcked.incrementAndGet();
        } else {
          outboxRepository.markDeliveryAttempt(event.eventId(), now(), "no_delivery_target");
        }
      } catch (Exception e) {
        String message = e.getMessage() == null ? "delivery_failed" : e.getMessage();
        outboxRepository.markDeliveryAttempt(event.eventId(), now(), message);
        failedNow++;
        metrics.eventsDeliveryFailed.incrementAndGet();
      }
    }

    return new DispatchResult(pending.size(), ackedNow, failedNow, noSubscriberNow, skippedRetriesNow);
  }

  public TaskDispatchMetricsSnapshot metricsSnapshot() {
    return new TaskDispatchMetricsSnapshot(
        metrics.eventsScanned.get(),
        metrics.eventsAcked.get(),
        metrics.eventsDeliveryFailed.get(),
        metrics.eventsWithoutSubscriber.get(),
        metrics.eventsSkippedMaxRetries.get()
    );
  }

  public String getTargetCommand() {
    return targetCommand;
  }

  public void setTargetCommand(String targetCommand) {
    if (targetCommand == null || targetCommand.isBlank()) {
      throw new IllegalArgumentException("targetCommand is required.");
    }
    this.targetCommand = targetCommand.trim();
  }

  public String buildDispatchPayload(TaskEvent event) {
    try {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("event_id", event.eventId());
      out.put("seq", event.seq());
      out.put("event_type", event.eventType().name());
      out.put("task_id", event.taskId());
      out.put("target_agent_id", event.targetAgentId());
      out.put("task_version", event.taskVersion());
      out.put("emitted_at", event.emittedAt().toString());

      JsonNode payloadNode;
      try {
        payloadNode = JSON.readTree(event.payloadJson());
      } catch (Exception ignored) {
        payloadNode = JSON.getNodeFactory().textNode(event.payloadJson());
      }
      out.put("payload", payloadNode);
      if (payloadNode.isObject()) {
        payloadNode.fields().forEachRemaining(entry -> {
          if (!out.containsKey(entry.getKey())) {
            out.put(entry.getKey(), entry.getValue());
          }
        });
      }
      return JSON.writeValueAsString(out);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to build dispatch payload.", e);
    }
  }

  private Instant now() {
    return Instant.now(clock);
  }

  private static int sanitizeLimit(int limit) {
    if (limit <= 0) {
      return 100;
    }
    return Math.min(limit, 1000);
  }

  public record DispatchResult(
      int scanned,
      int acked,
      int failed,
      int noSubscriber,
      int skippedMaxRetries
  ) {
  }

  public record TaskDispatchMetricsSnapshot(
      long eventsScanned,
      long eventsAcked,
      long eventsDeliveryFailed,
      long eventsWithoutSubscriber,
      long eventsSkippedMaxRetries
  ) {
  }

  @FunctionalInterface
  public interface AgentCommandExecutor {
    void execute(String agentId, String command, String payload) throws Exception;
  }

  public enum DispatchMode {
    SUBSCRIBERS,
    EXECUTE,
    BOTH;

    boolean includesSubscribers() {
      return this == SUBSCRIBERS || this == BOTH;
    }

    boolean includesExecute() {
      return this == EXECUTE || this == BOTH;
    }
  }

  private static final class TaskDispatchMetrics {
    private final AtomicLong eventsScanned = new AtomicLong();
    private final AtomicLong eventsAcked = new AtomicLong();
    private final AtomicLong eventsDeliveryFailed = new AtomicLong();
    private final AtomicLong eventsWithoutSubscriber = new AtomicLong();
    private final AtomicLong eventsSkippedMaxRetries = new AtomicLong();
  }
}
