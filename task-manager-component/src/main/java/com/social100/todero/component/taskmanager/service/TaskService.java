package com.social100.todero.component.taskmanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.social100.todero.component.taskmanager.domain.TaskAttemptEntity;
import com.social100.todero.component.taskmanager.domain.TaskEntity;
import com.social100.todero.component.taskmanager.domain.TaskStateMachine;
import com.social100.todero.component.taskmanager.domain.TaskStateMachineException;
import com.social100.todero.component.taskmanager.domain.TaskStatus;
import com.social100.todero.component.taskmanager.domain.TaskTransitionContext;
import com.social100.todero.component.taskmanager.domain.TaskTransitionRequest;
import com.social100.todero.component.taskmanager.domain.TaskTransitionType;
import com.social100.todero.component.taskmanager.persistence.EventOutboxRepository;
import com.social100.todero.component.taskmanager.persistence.SqliteTaskRepository;
import com.social100.todero.component.taskmanager.persistence.TaskEvent;
import com.social100.todero.component.taskmanager.persistence.TaskEventType;
import com.social100.todero.component.taskmanager.persistence.TaskListFilter;
import com.social100.todero.component.taskmanager.persistence.TaskRepository;
import com.social100.todero.component.taskmanager.persistence.TaskRepositoryException;
import com.social100.todero.component.taskmanager.persistence.TaskTransitionOutboxWriter;
import com.social100.todero.component.taskmanager.persistence.TaskVersionConflictException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class TaskService {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final TaskRepository taskRepository;
  private final EventOutboxRepository outboxRepository;
  private final TaskTransitionOutboxWriter transitionWriter;
  private final TaskStateMachine stateMachine;
  private final Clock clock;

  private final AtomicLong attemptsOpened = new AtomicLong();
  private final AtomicLong attemptsCompleted = new AtomicLong();
  private final AtomicLong attemptsFailed = new AtomicLong();
  private final AtomicLong attemptsCanceled = new AtomicLong();

  public TaskService(TaskRepository taskRepository,
                     EventOutboxRepository outboxRepository,
                     TaskTransitionOutboxWriter transitionWriter,
                     TaskStateMachine stateMachine,
                     Clock clock) {
    this.taskRepository = taskRepository;
    this.outboxRepository = outboxRepository;
    this.transitionWriter = transitionWriter;
    this.stateMachine = stateMachine;
    this.clock = clock;
  }

  public TaskServiceResult<TaskEntity> createTask(CreateTaskRequest request) {
    try {
      Instant now = now();
      validateCreateRequest(request);

      TaskEntity draft = new TaskEntity(
          request.taskId() == null || request.taskId().isBlank() ? UUID.randomUUID().toString() : request.taskId().trim(),
          request.title().trim(),
          request.description(),
          List.copyOf(request.assignedTo()),
          now,
          now,
          request.createdBy(),
          TaskStatus.NEW,
          request.priority(),
          request.tags() == null ? List.of() : List.copyOf(request.tags()),
          request.scheduledFor(),
          request.notBefore(),
          request.deadline(),
          request.windowStart(),
          request.windowEnd(),
          request.recurrence(),
          0,
          request.maxAttempts(),
          null,
          null,
          null,
          0,
          null,
          request.idempotencyKey()
      );

      TaskEntity initialized = stateMachine.initialize(draft, now);
      TaskEntity created = taskRepository.create(initialized);
      enqueuePerAgent(created, TaskEventType.TASK_CREATED, now, payload(TaskEventType.TASK_CREATED, created, null, null));
      return TaskServiceResult.success(created, "Task created.");
    } catch (Exception e) {
      return failure(e);
    }
  }

  public TaskServiceResult<TaskEntity> getTask(String taskId) {
    try {
      if (isBlank(taskId)) {
        return TaskServiceResult.failure("invalid_arguments", "taskId is required.");
      }
      Optional<TaskEntity> task = taskRepository.getById(taskId.trim());
      if (task.isEmpty()) {
        return TaskServiceResult.failure("not_found", "Task not found.");
      }
      return TaskServiceResult.success(task.get(), "Task found.");
    } catch (Exception e) {
      return failure(e);
    }
  }

  public TaskServiceResult<List<TaskEntity>> listTasks(TaskListFilter filter) {
    try {
      TaskListFilter effective = filter == null ? TaskListFilter.defaults() : filter;
      List<TaskEntity> items = taskRepository.list(effective);
      return TaskServiceResult.success(items, "Tasks listed.");
    } catch (Exception e) {
      return failure(e);
    }
  }

  public TaskServiceResult<List<TaskAttemptEntity>> listAttempts(String taskId, int limit, int offset) {
    try {
      if (isBlank(taskId)) {
        return TaskServiceResult.failure("invalid_arguments", "taskId is required.");
      }
      if (taskRepository.getById(taskId.trim()).isEmpty()) {
        return TaskServiceResult.failure("not_found", "Task not found.");
      }
      return TaskServiceResult.success(taskRepository.listAttempts(taskId.trim(), limit, offset), "Task attempts listed.");
    } catch (Exception e) {
      return failure(e);
    }
  }

  public TaskServiceResult<TaskAttemptEntity> getAttempt(String taskId, int attemptNumber) {
    try {
      if (isBlank(taskId)) {
        return TaskServiceResult.failure("invalid_arguments", "taskId is required.");
      }
      if (attemptNumber <= 0) {
        return TaskServiceResult.failure("invalid_arguments", "attemptNumber must be > 0.");
      }
      if (taskRepository.getById(taskId.trim()).isEmpty()) {
        return TaskServiceResult.failure("not_found", "Task not found.");
      }
      Optional<TaskAttemptEntity> attempt = taskRepository.getAttempt(taskId.trim(), attemptNumber);
      if (attempt.isEmpty()) {
        return TaskServiceResult.failure("not_found", "Attempt not found.");
      }
      return TaskServiceResult.success(attempt.get(), "Task attempt found.");
    } catch (Exception e) {
      return failure(e);
    }
  }

  public TaskServiceResult<TaskEntity> updateTask(UpdateTaskRequest request) {
    try {
      if (request == null || isBlank(request.taskId())) {
        return TaskServiceResult.failure("invalid_arguments", "taskId is required.");
      }
      Optional<TaskEntity> currentOpt = taskRepository.getById(request.taskId().trim());
      if (currentOpt.isEmpty()) {
        return TaskServiceResult.failure("not_found", "Task not found.");
      }
      TaskEntity current = currentOpt.get();
      if (current.status().isTerminal()) {
        return TaskServiceResult.failure("invalid_transition", "Cannot update terminal task.");
      }
      if (request.title() != null && request.title().trim().isEmpty()) {
        return TaskServiceResult.failure("invalid_arguments", "title cannot be empty.");
      }
      if (request.assignedTo() != null && request.assignedTo().isEmpty()) {
        return TaskServiceResult.failure("invalid_arguments", "assignedTo must contain at least one agent.");
      }

      Instant now = now();
      TaskEntity patched = new TaskEntity(
          current.taskId(),
          request.title() == null ? current.title() : request.title().trim(),
          request.description() == null ? current.description() : request.description(),
          request.assignedTo() == null ? current.assignedTo() : List.copyOf(request.assignedTo()),
          current.createdAt(),
          now,
          request.createdBy() == null ? current.createdBy() : request.createdBy(),
          current.status(),
          request.priority() == null ? current.priority() : request.priority(),
          request.tags() == null ? current.tags() : List.copyOf(request.tags()),
          request.scheduledFor() == null ? current.scheduledFor() : request.scheduledFor(),
          request.notBefore() == null ? current.notBefore() : request.notBefore(),
          request.deadline() == null ? current.deadline() : request.deadline(),
          request.windowStart() == null ? current.windowStart() : request.windowStart(),
          request.windowEnd() == null ? current.windowEnd() : request.windowEnd(),
          request.recurrence() == null ? current.recurrence() : request.recurrence(),
          current.attemptsCount(),
          request.maxAttempts() == null ? current.maxAttempts() : request.maxAttempts(),
          current.activeAttemptId(),
          current.claimedBy(),
          current.claimExpiresAt(),
          current.version() + 1,
          current.lastEmittedDueAt(),
          request.idempotencyKey() == null ? current.idempotencyKey() : request.idempotencyKey()
      );

      long expectedVersion = request.expectedVersion() == null ? current.version() : request.expectedVersion();
      TaskEntity updated = taskRepository.update(patched, expectedVersion);
      enqueuePerAgent(updated, TaskEventType.TASK_UPDATED, now, payload(TaskEventType.TASK_UPDATED, updated, null, null));
      return TaskServiceResult.success(updated, "Task updated.");
    } catch (Exception e) {
      return failure(e);
    }
  }

  public TaskServiceResult<EvaluateDueSummary> evaluateDueTasks(int limit) {
    int safeLimit = limit <= 0 ? 100 : Math.min(limit, 1000);
    int dueTransitions = 0;
    int expiryTransitions = 0;
    int claimExpiryTransitions = 0;
    int eventsEnqueued = 0;
    int versionConflicts = 0;
    try {
      Instant now = now();

      List<TaskEntity> dueCandidates = taskRepository.findDueCandidates(now, safeLimit);
      for (TaskEntity current : dueCandidates) {
        try {
          TaskTransitionRequest request = TaskTransitionRequest.at(TaskTransitionType.EVALUATE, now);
          TaskEntity next = stateMachine.transition(new TaskTransitionContext(current, request));
          if (next.status() != current.status() && next.status() == TaskStatus.READY && !next.status().isTerminal()) {
            TaskTransitionOutboxWriter.PersistResult persisted = transitionWriter.persistTransition(
                next,
                current.version(),
                TaskEventType.TASK_DUE,
                payload(TaskEventType.TASK_DUE, next, null, null),
                now
            );
            dueTransitions++;
            eventsEnqueued += persisted.events().size();
          }
        } catch (TaskVersionConflictException e) {
          versionConflicts++;
        }
      }

      List<TaskEntity> expiryCandidates = taskRepository.findExpiryCandidates(now, safeLimit);
      for (TaskEntity current : expiryCandidates) {
        try {
          TaskTransitionRequest request = TaskTransitionRequest.at(TaskTransitionType.EVALUATE, now);
          TaskEntity next = stateMachine.transition(new TaskTransitionContext(current, request));
          if (next.status() == TaskStatus.EXPIRED && current.status() != TaskStatus.EXPIRED) {
            TaskTransitionOutboxWriter.PersistResult persisted = transitionWriter.persistTransition(
                next,
                current.version(),
                TaskEventType.TASK_EXPIRED,
                payload(TaskEventType.TASK_EXPIRED, next, null, null),
                now
            );
            expiryTransitions++;
            eventsEnqueued += persisted.events().size();
          }
        } catch (TaskVersionConflictException e) {
          versionConflicts++;
        }
      }

      List<TaskEntity> claimExpiryCandidates = taskRepository.findClaimExpiryCandidates(now, safeLimit);
      for (TaskEntity current : claimExpiryCandidates) {
        try {
          TaskTransitionRequest request = TaskTransitionRequest.at(TaskTransitionType.CLAIM_EXPIRED, now);
          TaskEntity next = stateMachine.transition(new TaskTransitionContext(current, request));
          TaskTransitionOutboxWriter.PersistResult persisted = transitionWriter.persistTransition(
              next,
              current.version(),
              TaskEventType.CLAIM_EXPIRED,
              payload(TaskEventType.CLAIM_EXPIRED, next, null, null),
              now
          );
          claimExpiryTransitions++;
          eventsEnqueued += persisted.events().size();
        } catch (TaskVersionConflictException e) {
          versionConflicts++;
        }
      }

      EvaluateDueSummary summary = new EvaluateDueSummary(
          dueTransitions,
          expiryTransitions,
          claimExpiryTransitions,
          eventsEnqueued,
          versionConflicts
      );
      return TaskServiceResult.success(summary, "Due-task evaluation completed.");
    } catch (Exception e) {
      return failure(e);
    }
  }

  public TaskServiceResult<TaskEntity> claimTask(String taskId, String agentId, Duration leaseDuration) {
    return transition(taskId, TaskTransitionRequest.at(TaskTransitionType.CLAIM, now())
            .withActor(agentId)
            .withLease(leaseDuration),
        TaskEventType.TASK_CLAIMED,
        "Task claimed.");
  }

  public TaskServiceResult<TaskEntity> renewClaim(String taskId, String agentId, Duration leaseDuration) {
    return transition(taskId, TaskTransitionRequest.at(TaskTransitionType.RENEW_CLAIM, now())
            .withActor(agentId)
            .withLease(leaseDuration),
        TaskEventType.TASK_UPDATED,
        "Task claim renewed.");
  }

  public TaskServiceResult<TaskEntity> startTask(String taskId, String agentId) {
    return transition(taskId, TaskTransitionRequest.at(TaskTransitionType.START, now())
            .withActor(agentId)
            .withAttemptId(UUID.randomUUID().toString()),
        TaskEventType.TASK_STARTED,
        "Task started.");
  }

  public TaskServiceResult<TaskEntity> completeTask(String taskId, String agentId, String note) {
    return transition(taskId, TaskTransitionRequest.at(TaskTransitionType.COMPLETE, now())
            .withActor(agentId)
            .withNote(note),
        TaskEventType.TASK_COMPLETED,
        "Task completed.");
  }

  public TaskServiceResult<TaskEntity> failTask(String taskId,
                                                String agentId,
                                                String errorCode,
                                                String errorMessage,
                                                String note) {
    try {
      TaskServiceResult<TaskEntity> result = transition(taskId,
          TaskTransitionRequest.at(TaskTransitionType.FAIL, now())
              .withActor(agentId)
              .withErrorCode(errorCode)
              .withError(errorMessage)
              .withNote(note),
          TaskEventType.TASK_FAILED,
          "Task failed.");
      if (!result.ok() || result.data() == null) {
        return result;
      }
      if (result.data().status() == TaskStatus.READY) {
        return TaskServiceResult.success(result.data(), "Task failed and moved back to READY for retry.");
      }
      return result;
    } catch (Exception e) {
      return failure(e);
    }
  }

  public TaskServiceResult<TaskEntity> cancelTask(String taskId, String actor, String note) {
    return transition(taskId,
        TaskTransitionRequest.at(TaskTransitionType.CANCEL, now()).withActor(actor).withNote(note),
        TaskEventType.TASK_CANCELED,
        "Task canceled.");
  }

  public TaskServiceResult<TaskEntity> snoozeTask(String taskId, String actor, Instant scheduleAt) {
    return transition(taskId, TaskTransitionRequest.at(TaskTransitionType.SNOOZE, now())
            .withActor(actor)
            .withScheduleAt(scheduleAt),
        TaskEventType.TASK_SNOOZED,
        "Task snoozed.");
  }

  public TaskServiceResult<Void> ackEvent(String agentId, String eventId) {
    try {
      if (isBlank(agentId) || isBlank(eventId)) {
        return TaskServiceResult.failure("invalid_arguments", "agentId and eventId are required.");
      }
      outboxRepository.markAcked(agentId.trim(), eventId.trim(), now());
      return TaskServiceResult.success(null, "Event acknowledged.");
    } catch (Exception e) {
      return failure(e);
    }
  }

  public Map<String, Object> metricsSnapshot() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("attemptsOpened", attemptsOpened.get());
    out.put("attemptsCompleted", attemptsCompleted.get());
    out.put("attemptsFailed", attemptsFailed.get());
    out.put("attemptsCanceled", attemptsCanceled.get());
    return out;
  }

  private TaskServiceResult<TaskEntity> transition(String taskId,
                                                   TaskTransitionRequest request,
                                                   TaskEventType eventType,
                                                   String successMessage) {
    try {
      if (isBlank(taskId)) {
        return TaskServiceResult.failure("invalid_arguments", "taskId is required.");
      }
      Optional<TaskEntity> currentOpt = taskRepository.getById(taskId.trim());
      if (currentOpt.isEmpty()) {
        return TaskServiceResult.failure("not_found", "Task not found.");
      }
      TaskEntity current = currentOpt.get();

      TaskTransitionRequest effectiveRequest = request;
      if (request.type() == TaskTransitionType.START && isBlank(request.attemptId())) {
        effectiveRequest = request.withAttemptId(UUID.randomUUID().toString());
      }
      final TaskTransitionRequest finalRequest = effectiveRequest;

      TaskEntity next = stateMachine.transition(new TaskTransitionContext(current, effectiveRequest));
      TaskEventType effectiveType = eventType;
      if (finalRequest.type() == TaskTransitionType.FAIL && next.status() == TaskStatus.READY) {
        effectiveType = TaskEventType.TASK_UPDATED;
      }
      final TaskEventType finalEventType = effectiveType;

      final TransitionAttemptMeta transitionAttemptMeta = deriveAttemptMeta(current, next, finalRequest);

      TaskTransitionOutboxWriter.PersistResult persisted = transitionWriter.persistTransition(
          next,
          current.version(),
          finalEventType,
          payload(finalEventType, next, finalRequest.actor(), transitionAttemptMeta),
          finalRequest.now(),
          (connection, updatedTask) -> {
            if (transitionAttemptMeta == null) {
              return;
            }
            SqliteTaskRepository sqliteRepo = asSqliteRepository();
            try {
              if (transitionAttemptMeta.open()) {
                sqliteRepo.openAttemptInConnection(
                    connection,
                    updatedTask.taskId(),
                    transitionAttemptMeta.attemptId(),
                    finalRequest.actor(),
                    finalRequest.now()
                );
                attemptsOpened.incrementAndGet();
              } else {
                sqliteRepo.closeActiveAttemptInConnection(
                    connection,
                    updatedTask.taskId(),
                    finalRequest.actor(),
                    finalRequest.now(),
                    transitionAttemptMeta.status(),
                    finalRequest.errorCode(),
                    finalRequest.errorMessage(),
                    finalRequest.note()
                );
                if ("COMPLETED".equalsIgnoreCase(transitionAttemptMeta.status())) {
                  attemptsCompleted.incrementAndGet();
                } else if ("FAILED".equalsIgnoreCase(transitionAttemptMeta.status())) {
                  attemptsFailed.incrementAndGet();
                } else if ("CANCELED".equalsIgnoreCase(transitionAttemptMeta.status())) {
                  attemptsCanceled.incrementAndGet();
                }
              }
            } catch (Exception e) {
              throw new TaskRepositoryException("Failed writing task attempt lifecycle.", e);
            }
          }
      );

      return TaskServiceResult.success(persisted.updatedTask(), successMessage);
    } catch (Exception e) {
      return failure(e);
    }
  }

  private TransitionAttemptMeta deriveAttemptMeta(TaskEntity current,
                                                  TaskEntity next,
                                                  TaskTransitionRequest request) {
    return switch (request.type()) {
      case START -> new TransitionAttemptMeta(true, request.attemptId(), "STARTED", current.attemptsCount() + 1);
      case COMPLETE -> new TransitionAttemptMeta(false, current.activeAttemptId(), "COMPLETED", current.attemptsCount() + 1);
      case FAIL -> new TransitionAttemptMeta(false, current.activeAttemptId(), "FAILED", current.attemptsCount() + 1);
      case CANCEL -> {
        if (current.status() == TaskStatus.IN_PROGRESS && current.activeAttemptId() != null && !current.activeAttemptId().isBlank()) {
          yield new TransitionAttemptMeta(false, current.activeAttemptId(), "CANCELED", current.attemptsCount() + 1);
        }
        yield null;
      }
      default -> null;
    };
  }

  private SqliteTaskRepository asSqliteRepository() {
    if (taskRepository instanceof SqliteTaskRepository sqlite) {
      return sqlite;
    }
    throw new TaskRepositoryException("Attempt lifecycle requires SqliteTaskRepository.");
  }

  private void enqueuePerAgent(TaskEntity task, TaskEventType eventType, Instant emittedAt, String payload) {
    for (String agentId : task.assignedTo()) {
      outboxRepository.enqueue(new TaskEvent(
          null,
          UUID.randomUUID().toString(),
          eventType,
          task.taskId(),
          agentId,
          task.version(),
          payload,
          emittedAt,
          0,
          null,
          null
      ));
    }
  }

  private String payload(TaskEventType eventType, TaskEntity task, String actor, TransitionAttemptMeta attemptMeta) {
    try {
      Map<String, Object> root = new LinkedHashMap<>();
      root.put("eventType", eventType.name());
      root.put("event_type", eventType.name());
      root.put("taskId", task.taskId());
      root.put("task_id", task.taskId());
      root.put("status", task.status().name());
      root.put("version", task.version());
      root.put("task_version", task.version());
      root.put("assignedTo", new ArrayList<>(task.assignedTo()));
      root.put("assigned_to", new ArrayList<>(task.assignedTo()));
      root.put("actor", actor == null ? "" : actor);
      root.put("at", now().toString());
      if (attemptMeta != null) {
        root.put("attempt_id", attemptMeta.attemptId());
        root.put("attempt_number", attemptMeta.attemptNumber());
        root.put("attempt_status", attemptMeta.status());
      }
      return JSON.writeValueAsString(root);
    } catch (Exception e) {
      throw new TaskRepositoryException("Failed to serialize event payload.", e);
    }
  }

  private <T> TaskServiceResult<T> failure(Exception e) {
    if (e instanceof TaskVersionConflictException) {
      return TaskServiceResult.failure("version_conflict", e.getMessage());
    }
    if (e instanceof TaskStateMachineException) {
      return TaskServiceResult.failure("invalid_transition", e.getMessage());
    }
    if (e instanceof IllegalArgumentException) {
      return TaskServiceResult.failure("invalid_arguments", e.getMessage());
    }
    if (e instanceof TaskRepositoryException) {
      return TaskServiceResult.failure("repository_error", e.getMessage());
    }
    return TaskServiceResult.failure("internal_error", e.getMessage() == null ? "Unexpected error." : e.getMessage());
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private Instant now() {
    return Instant.now(clock);
  }

  private void validateCreateRequest(CreateTaskRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request is required.");
    }
    if (isBlank(request.title())) {
      throw new IllegalArgumentException("title is required.");
    }
    if (request.assignedTo() == null || request.assignedTo().isEmpty()) {
      throw new IllegalArgumentException("assignedTo must contain at least one agent.");
    }
  }

  public record CreateTaskRequest(
      String taskId,
      String title,
      String description,
      List<String> assignedTo,
      String createdBy,
      Integer priority,
      List<String> tags,
      Instant scheduledFor,
      Instant notBefore,
      Instant deadline,
      Instant windowStart,
      Instant windowEnd,
      String recurrence,
      Integer maxAttempts,
      String idempotencyKey
  ) {
  }

  public record UpdateTaskRequest(
      String taskId,
      Long expectedVersion,
      String title,
      String description,
      List<String> assignedTo,
      String createdBy,
      Integer priority,
      List<String> tags,
      Instant scheduledFor,
      Instant notBefore,
      Instant deadline,
      Instant windowStart,
      Instant windowEnd,
      String recurrence,
      Integer maxAttempts,
      String idempotencyKey
  ) {
  }

  public record EvaluateDueSummary(
      int dueTransitions,
      int expiryTransitions,
      int claimExpiryTransitions,
      int eventsEnqueued,
      int versionConflicts
  ) {
  }

  private record TransitionAttemptMeta(
      boolean open,
      String attemptId,
      String status,
      int attemptNumber
  ) {
  }
}
