package com.social100.todero.component.taskmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.storage.Storage;
import com.social100.todero.component.taskmanager.domain.TaskEntity;
import com.social100.todero.component.taskmanager.domain.TaskAttemptEntity;
import com.social100.todero.component.taskmanager.domain.TaskStateMachine;
import com.social100.todero.component.taskmanager.domain.TaskStatus;
import com.social100.todero.component.taskmanager.persistence.SqliteEventOutboxRepository;
import com.social100.todero.component.taskmanager.persistence.SqliteTaskRepository;
import com.social100.todero.component.taskmanager.persistence.TaskListFilter;
import com.social100.todero.component.taskmanager.persistence.TaskTransitionOutboxWriter;
import com.social100.todero.component.taskmanager.service.TaskEventDispatcher;
import com.social100.todero.component.taskmanager.service.TaskScheduler;
import com.social100.todero.component.taskmanager.service.TaskService;
import com.social100.todero.component.taskmanager.service.TaskServiceResult;
import com.social100.todero.processor.EventDefinition;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@AIAController(name = "com.shellaia.verbatim.component.task.manager",
    type = ServerType.AIA,
    visible = true,
    description = "Task manager component for agent task tracking and scheduling",
    events = TaskManagerComponent.TaskManagerEvent.class)
public class TaskManagerComponent {
  private static final String MAIN_GROUP = "Main";
  private static final String LOG_PREFIX = "[TASK-MANAGER]";
  private static final ObjectMapper JSON = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private static final String EVENT_TASK = TaskManagerEvent.TASK_EVENT.name();
  private static final int DEFAULT_SCAN_INTERVAL_MS = 1000;
  private static final int DEFAULT_EVALUATE_LIMIT = 200;
  private static final int DEFAULT_DISPATCH_LIMIT = 200;
  private static final int DEFAULT_MAX_DELIVERY_ATTEMPTS = 10;

  private final TaskService taskService;
  private final TaskEventDispatcher dispatcher;
  private final TaskScheduler scheduler;
  private final ConcurrentMap<String, ConcurrentMap<String, Runnable>> subscriptionHandlesByAgent = new ConcurrentHashMap<>();
  private final Path dbPath;

  public TaskManagerComponent(Storage storage) {
    try {
      this.dbPath = resolveDbPath();
      SqliteTaskRepository taskRepo = new SqliteTaskRepository(dbPath);
      SqliteEventOutboxRepository outboxRepo = new SqliteEventOutboxRepository(taskRepo);
      TaskTransitionOutboxWriter transitionWriter = new TaskTransitionOutboxWriter(taskRepo, outboxRepo);
      this.taskService = new TaskService(taskRepo, outboxRepo, transitionWriter, new TaskStateMachine(), Clock.systemUTC());
      int maxDeliveryAttempts = readIntConfig(
          "todero.taskmanager.dispatch.max-delivery-attempts",
          "TODERO_TASKMANAGER_DISPATCH_MAX_DELIVERY_ATTEMPTS",
          DEFAULT_MAX_DELIVERY_ATTEMPTS,
          1,
          1000
      );
      this.dispatcher = new TaskEventDispatcher(outboxRepo, Clock.systemUTC(), maxDeliveryAttempts);
      String targetCommand = firstNotBlank(
          System.getProperty("todero.taskmanager.dispatch.target-command"),
          System.getenv("TODERO_TASKMANAGER_DISPATCH_TARGET_COMMAND"),
          "react"
      );
      this.dispatcher.setTargetCommand(targetCommand);
      int scanIntervalMs = readIntConfig(
          "todero.taskmanager.scheduler.scan-interval-ms",
          "TODERO_TASKMANAGER_SCHEDULER_SCAN_INTERVAL_MS",
          DEFAULT_SCAN_INTERVAL_MS,
          100,
          60000
      );
      int evaluateLimit = readIntConfig(
          "todero.taskmanager.scheduler.evaluate-limit",
          "TODERO_TASKMANAGER_SCHEDULER_EVALUATE_LIMIT",
          DEFAULT_EVALUATE_LIMIT,
          1,
          1000
      );
      int dispatchLimit = readIntConfig(
          "todero.taskmanager.scheduler.dispatch-limit",
          "TODERO_TASKMANAGER_SCHEDULER_DISPATCH_LIMIT",
          DEFAULT_DISPATCH_LIMIT,
          1,
          1000
      );
      this.scheduler = new TaskScheduler(taskService, dispatcher, scanIntervalMs, evaluateLimit, dispatchLimit);
      this.scheduler.start();
    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize task-manager-component.", e);
    }
  }

  private Path resolveDbPath() {
    List<Path> candidates = List.of(
        Paths.get(System.getProperty("user.home"), ".todero", "data", "state", "task-manager-component"),
        Paths.get(System.getProperty("java.io.tmpdir"), "todero-task-manager-component")
    );
    Exception last = null;
    for (Path dir : candidates) {
      try {
        Files.createDirectories(dir);
        return dir.resolve("tasks.sqlite");
      } catch (Exception e) {
        last = e;
      }
    }
    throw new IllegalStateException("No writable DB directory for task-manager-component.", last);
  }

  @Action(group = MAIN_GROUP,
      command = "health",
      description = "Health check for task manager component. Usage: health [--format json|text]")
  public Boolean health(CommandContext context) {
    ParsedInput input = parseInput(context);
    String format = parseFormat(input.options());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("component", "task-manager-component");
    data.put("schedulerRunning", scheduler.isRunning());
    data.put("dbPath", dbPath.toString());
    data.put("timestamp", Instant.now().toString());
    respond(context, "health", format, TaskServiceResult.success(data, "Component healthy."));
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "metrics",
      description = "Show scheduler and dispatch metrics. Usage: metrics [--format json|text]")
  public Boolean metrics(CommandContext context) {
    ParsedInput input = parseInput(context);
    String format = parseFormat(input.options());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("scheduler", scheduler.metricsSnapshot());
    data.put("dispatcher", dispatcher.metricsSnapshot());
    data.put("service", taskService.metricsSnapshot());
    data.put("activeSubscriptions", countSubscriptions());
    respond(context, "metrics", format, TaskServiceResult.success(data, "Metrics snapshot."));
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "create",
      description = "Create task. Usage: create --title <text> --assigned <agent[,agent...]> [--task-id <id>] [--description <text>] [--created-by <id>] [--priority N] [--tags t1,t2] [--scheduled-for <iso>] [--not-before <iso>] [--deadline <iso>] [--window-start <iso>] [--window-end <iso>] [--recurrence <text>] [--max-attempts N] [--idempotency-key <k>] [--format json|text]")
  public Boolean create(CommandContext context) {
    ParsedInput input = parseInput(context);
    String format = parseFormat(input.options());
    ensureNoUnknownOptions(input.options(), Set.of(
        "format", "task-id", "title", "description", "assigned", "created-by", "priority",
        "tags", "scheduled-for", "not-before", "deadline", "window-start", "window-end",
        "recurrence", "max-attempts", "idempotency-key"));

    try {
      String title = requireOption(input.options(), "title");
      List<String> assignedTo = parseCsvOption(input.options(), "assigned");
      TaskService.CreateTaskRequest request = new TaskService.CreateTaskRequest(
          optional(input.options(), "task-id"),
          title,
          optional(input.options(), "description"),
          assignedTo,
          optional(input.options(), "created-by"),
          parseIntNullable(optional(input.options(), "priority"), "priority"),
          parseCsvOptionNullable(input.options(), "tags"),
          parseInstantNullable(optional(input.options(), "scheduled-for"), "scheduled-for"),
          parseInstantNullable(optional(input.options(), "not-before"), "not-before"),
          parseInstantNullable(optional(input.options(), "deadline"), "deadline"),
          parseInstantNullable(optional(input.options(), "window-start"), "window-start"),
          parseInstantNullable(optional(input.options(), "window-end"), "window-end"),
          optional(input.options(), "recurrence"),
          parseIntNullable(optional(input.options(), "max-attempts"), "max-attempts"),
          optional(input.options(), "idempotency-key")
      );
      respond(context, "create", format, taskService.createTask(request));
    } catch (IllegalArgumentException e) {
      respond(context, "create", format, TaskServiceResult.failure("invalid_arguments", e.getMessage()));
    }
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "get",
      description = "Get task by id. Usage: get --task-id <id> [--format json|text]")
  public Boolean get(CommandContext context) {
    ParsedInput input = parseInput(context);
    String format = parseFormat(input.options());
    ensureNoUnknownOptions(input.options(), Set.of("format", "task-id"));
    try {
      respond(context, "get", format, taskService.getTask(requireOption(input.options(), "task-id")));
    } catch (IllegalArgumentException e) {
      respond(context, "get", format, TaskServiceResult.failure("invalid_arguments", e.getMessage()));
    }
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "list",
      description = "List tasks. Usage: list [--status NEW,READY,...] [--assigned <agent>] [--limit N] [--offset N] [--format json|text]")
  public Boolean list(CommandContext context) {
    ParsedInput input = parseInput(context);
    String format = parseFormat(input.options());
    ensureNoUnknownOptions(input.options(), Set.of("format", "status", "assigned", "limit", "offset"));
    try {
      Set<TaskStatus> statuses = parseStatuses(optional(input.options(), "status"));
      String assigned = optional(input.options(), "assigned");
      int limit = parseIntOrDefault(optional(input.options(), "limit"), 100, "limit");
      int offset = parseIntOrDefault(optional(input.options(), "offset"), 0, "offset");
      TaskListFilter filter = new TaskListFilter(statuses, assigned, limit, offset);
      respond(context, "list", format, taskService.listTasks(filter));
    } catch (IllegalArgumentException e) {
      respond(context, "list", format, TaskServiceResult.failure("invalid_arguments", e.getMessage()));
    }
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "attempts",
      description = "List task attempts. Usage: attempts --task-id <id> [--limit N] [--offset N] [--format json|text]")
  public Boolean attempts(CommandContext context) {
    ParsedInput input = parseInput(context);
    String format = parseFormat(input.options());
    ensureNoUnknownOptions(input.options(), Set.of("format", "task-id", "limit", "offset"));
    try {
      String taskId = requireOption(input.options(), "task-id");
      int limit = parseIntOrDefault(optional(input.options(), "limit"), 20, "limit");
      int offset = parseIntOrDefault(optional(input.options(), "offset"), 0, "offset");
      respond(context, "attempts", format, taskService.listAttempts(taskId, limit, offset));
    } catch (IllegalArgumentException e) {
      respond(context, "attempts", format, TaskServiceResult.failure("invalid_arguments", e.getMessage()));
    }
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "attempt",
      description = "Get one task attempt. Usage: attempt --task-id <id> --attempt-number <n> [--format json|text]")
  public Boolean attempt(CommandContext context) {
    ParsedInput input = parseInput(context);
    String format = parseFormat(input.options());
    ensureNoUnknownOptions(input.options(), Set.of("format", "task-id", "attempt-number"));
    try {
      String taskId = requireOption(input.options(), "task-id");
      Integer attemptNumber = parseIntNullable(requireOption(input.options(), "attempt-number"), "attempt-number");
      if (attemptNumber == null || attemptNumber <= 0) {
        throw new IllegalArgumentException("attempt-number must be > 0.");
      }
      respond(context, "attempt", format, taskService.getAttempt(taskId, attemptNumber));
    } catch (IllegalArgumentException e) {
      respond(context, "attempt", format, TaskServiceResult.failure("invalid_arguments", e.getMessage()));
    }
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "update",
      description = "Update task metadata. Usage: update --task-id <id> [--expected-version N] [--title <text>] [--description <text>] [--assigned <agent[,agent...]>] [--priority N] [--tags t1,t2] [--scheduled-for <iso>] [--not-before <iso>] [--deadline <iso>] [--window-start <iso>] [--window-end <iso>] [--recurrence <text>] [--max-attempts N] [--idempotency-key <k>] [--format json|text]")
  public Boolean update(CommandContext context) {
    ParsedInput input = parseInput(context);
    String format = parseFormat(input.options());
    ensureNoUnknownOptions(input.options(), Set.of(
        "format", "task-id", "expected-version", "title", "description", "assigned",
        "created-by", "priority", "tags", "scheduled-for", "not-before", "deadline",
        "window-start", "window-end", "recurrence", "max-attempts", "idempotency-key"));
    try {
      TaskService.UpdateTaskRequest request = new TaskService.UpdateTaskRequest(
          requireOption(input.options(), "task-id"),
          parseLongNullable(optional(input.options(), "expected-version"), "expected-version"),
          optional(input.options(), "title"),
          optional(input.options(), "description"),
          input.options().containsKey("assigned") ? parseCsvOption(input.options(), "assigned") : null,
          optional(input.options(), "created-by"),
          parseIntNullable(optional(input.options(), "priority"), "priority"),
          parseCsvOptionNullable(input.options(), "tags"),
          parseInstantNullable(optional(input.options(), "scheduled-for"), "scheduled-for"),
          parseInstantNullable(optional(input.options(), "not-before"), "not-before"),
          parseInstantNullable(optional(input.options(), "deadline"), "deadline"),
          parseInstantNullable(optional(input.options(), "window-start"), "window-start"),
          parseInstantNullable(optional(input.options(), "window-end"), "window-end"),
          optional(input.options(), "recurrence"),
          parseIntNullable(optional(input.options(), "max-attempts"), "max-attempts"),
          optional(input.options(), "idempotency-key")
      );
      respond(context, "update", format, taskService.updateTask(request));
    } catch (IllegalArgumentException e) {
      respond(context, "update", format, TaskServiceResult.failure("invalid_arguments", e.getMessage()));
    }
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "evaluate",
      description = "Evaluate due/expiry/claim-expiry transitions now. Usage: evaluate [--limit N] [--format json|text]")
  public Boolean evaluate(CommandContext context) {
    ParsedInput input = parseInput(context);
    String format = parseFormat(input.options());
    ensureNoUnknownOptions(input.options(), Set.of("format", "limit"));
    try {
      int limit = parseIntOrDefault(optional(input.options(), "limit"), 100, "limit");
      respond(context, "evaluate", format, taskService.evaluateDueTasks(limit));
    } catch (IllegalArgumentException e) {
      respond(context, "evaluate", format, TaskServiceResult.failure("invalid_arguments", e.getMessage()));
    }
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "claim",
      description = "Claim task lease. Usage: claim --task-id <id> --agent <id> --lease-seconds <n> [--format json|text]")
  public Boolean claim(CommandContext context) {
    ParsedInput input = parseInput(context);
    String format = parseFormat(input.options());
    ensureNoUnknownOptions(input.options(), Set.of("format", "task-id", "agent", "lease-seconds"));
    try {
      String taskId = requireOption(input.options(), "task-id");
      String agent = requireOption(input.options(), "agent");
      int leaseSeconds = parseIntOrDefault(requireOption(input.options(), "lease-seconds"), 60, "lease-seconds");
      respond(context, "claim", format, taskService.claimTask(taskId, agent, Duration.ofSeconds(leaseSeconds)));
    } catch (IllegalArgumentException e) {
      respond(context, "claim", format, TaskServiceResult.failure("invalid_arguments", e.getMessage()));
    }
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "renew-claim",
      description = "Renew task lease. Usage: renew-claim --task-id <id> --agent <id> --lease-seconds <n> [--format json|text]")
  public Boolean renewClaim(CommandContext context) {
    ParsedInput input = parseInput(context);
    String format = parseFormat(input.options());
    ensureNoUnknownOptions(input.options(), Set.of("format", "task-id", "agent", "lease-seconds"));
    try {
      String taskId = requireOption(input.options(), "task-id");
      String agent = requireOption(input.options(), "agent");
      int leaseSeconds = parseIntOrDefault(requireOption(input.options(), "lease-seconds"), 60, "lease-seconds");
      respond(context, "renew-claim", format, taskService.renewClaim(taskId, agent, Duration.ofSeconds(leaseSeconds)));
    } catch (IllegalArgumentException e) {
      respond(context, "renew-claim", format, TaskServiceResult.failure("invalid_arguments", e.getMessage()));
    }
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "start",
      description = "Start task. Usage: start --task-id <id> --agent <id> [--format json|text]")
  public Boolean start(CommandContext context) {
    ParsedInput input = parseInput(context);
    String format = parseFormat(input.options());
    ensureNoUnknownOptions(input.options(), Set.of("format", "task-id", "agent"));
    try {
      respond(context, "start", format, taskService.startTask(
          requireOption(input.options(), "task-id"),
          requireOption(input.options(), "agent")));
    } catch (IllegalArgumentException e) {
      respond(context, "start", format, TaskServiceResult.failure("invalid_arguments", e.getMessage()));
    }
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "complete",
      description = "Complete task. Usage: complete --task-id <id> --agent <id> [--note <text>] [--format json|text]")
  public Boolean complete(CommandContext context) {
    ParsedInput input = parseInput(context);
    String format = parseFormat(input.options());
    ensureNoUnknownOptions(input.options(), Set.of("format", "task-id", "agent", "note"));
    try {
      respond(context, "complete", format, taskService.completeTask(
          requireOption(input.options(), "task-id"),
          requireOption(input.options(), "agent"),
          optional(input.options(), "note")));
    } catch (IllegalArgumentException e) {
      respond(context, "complete", format, TaskServiceResult.failure("invalid_arguments", e.getMessage()));
    }
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "fail",
      description = "Fail task. Usage: fail --task-id <id> --agent <id> [--error-code <code>] [--error <message>] [--note <text>] [--format json|text]")
  public Boolean fail(CommandContext context) {
    ParsedInput input = parseInput(context);
    String format = parseFormat(input.options());
    ensureNoUnknownOptions(input.options(), Set.of("format", "task-id", "agent", "error-code", "error", "note"));
    try {
      respond(context, "fail", format, taskService.failTask(
          requireOption(input.options(), "task-id"),
          requireOption(input.options(), "agent"),
          optional(input.options(), "error-code"),
          optional(input.options(), "error"),
          optional(input.options(), "note")));
    } catch (IllegalArgumentException e) {
      respond(context, "fail", format, TaskServiceResult.failure("invalid_arguments", e.getMessage()));
    }
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "cancel",
      description = "Cancel task. Usage: cancel --task-id <id> --actor <id> [--note <text>] [--format json|text]")
  public Boolean cancel(CommandContext context) {
    ParsedInput input = parseInput(context);
    String format = parseFormat(input.options());
    ensureNoUnknownOptions(input.options(), Set.of("format", "task-id", "actor", "note"));
    try {
      respond(context, "cancel", format, taskService.cancelTask(
          requireOption(input.options(), "task-id"),
          requireOption(input.options(), "actor"),
          optional(input.options(), "note")));
    } catch (IllegalArgumentException e) {
      respond(context, "cancel", format, TaskServiceResult.failure("invalid_arguments", e.getMessage()));
    }
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "snooze",
      description = "Snooze task to future time. Usage: snooze --task-id <id> --actor <id> --schedule-at <iso> [--format json|text]")
  public Boolean snooze(CommandContext context) {
    ParsedInput input = parseInput(context);
    String format = parseFormat(input.options());
    ensureNoUnknownOptions(input.options(), Set.of("format", "task-id", "actor", "schedule-at"));
    try {
      respond(context, "snooze", format, taskService.snoozeTask(
          requireOption(input.options(), "task-id"),
          requireOption(input.options(), "actor"),
          parseInstantNullable(requireOption(input.options(), "schedule-at"), "schedule-at")));
    } catch (IllegalArgumentException e) {
      respond(context, "snooze", format, TaskServiceResult.failure("invalid_arguments", e.getMessage()));
    }
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "ack-event",
      description = "Acknowledge delivered event. Usage: ack-event --agent <id> --event-id <id> [--format json|text]")
  public Boolean ackEvent(CommandContext context) {
    ParsedInput input = parseInput(context);
    String format = parseFormat(input.options());
    ensureNoUnknownOptions(input.options(), Set.of("format", "agent", "event-id"));
    try {
      respond(context, "ack-event", format, taskService.ackEvent(
          requireOption(input.options(), "agent"),
          requireOption(input.options(), "event-id")));
    } catch (IllegalArgumentException e) {
      respond(context, "ack-event", format, TaskServiceResult.failure("invalid_arguments", e.getMessage()));
    }
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "subscribe",
      description = "Register in-process subscriber for agent events. Usage: subscribe --agent <id> [--subscription-id <id>] [--format json|text]")
  public Boolean subscribe(CommandContext context) {
    ParsedInput input = parseInput(context);
    String format = parseFormat(input.options());
    ensureNoUnknownOptions(input.options(), Set.of("format", "agent", "subscription-id"));
    try {
      String agentId = requireOption(input.options(), "agent");
      String subscriptionId = optional(input.options(), "subscription-id");
      if (subscriptionId == null || subscriptionId.isBlank()) {
        subscriptionId = UUID.randomUUID().toString();
      }
      String finalSubscriptionId = subscriptionId;
      Runnable unsubscribe = dispatcher.subscribe(agentId, event -> context.emitCustom(EVENT_TASK, EVENT_TASK, "application/json; charset=utf-8", event.payloadJson().getBytes(StandardCharsets.UTF_8), "progress"));
      subscriptionHandlesByAgent.computeIfAbsent(agentId, ignored -> new ConcurrentHashMap<>())
          .put(finalSubscriptionId, unsubscribe);

      Map<String, Object> data = Map.of(
          "agentId", agentId,
          "subscriptionId", subscriptionId,
          "activeSubscriptions", countSubscriptions());
      respond(context, "subscribe", format, TaskServiceResult.success(data, "Subscribed."));
    } catch (IllegalArgumentException e) {
      respond(context, "subscribe", format, TaskServiceResult.failure("invalid_arguments", e.getMessage()));
    }
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "unsubscribe",
      description = "Remove in-process subscriber. Usage: unsubscribe --agent <id> [--subscription-id <id> | --all true] [--format json|text]")
  public Boolean unsubscribe(CommandContext context) {
    ParsedInput input = parseInput(context);
    String format = parseFormat(input.options());
    ensureNoUnknownOptions(input.options(), Set.of("format", "agent", "subscription-id", "all"));
    try {
      String agentId = requireOption(input.options(), "agent");
      boolean removeAll = Boolean.parseBoolean(optional(input.options(), "all"));
      String subscriptionId = optional(input.options(), "subscription-id");

      ConcurrentMap<String, Runnable> byId = subscriptionHandlesByAgent.get(agentId);
      if (byId == null || byId.isEmpty()) {
        respond(context, "unsubscribe", format, TaskServiceResult.failure("not_found", "No subscriptions found for agent."));
        return true;
      }
      int removed = 0;
      if (removeAll) {
        for (Runnable runnable : byId.values()) {
          runnable.run();
          removed++;
        }
        byId.clear();
      } else {
        if (subscriptionId == null || subscriptionId.isBlank()) {
          respond(context, "unsubscribe", format, TaskServiceResult.failure("invalid_arguments", "subscription-id is required unless --all true."));
          return true;
        }
        Runnable unsubscribe = byId.remove(subscriptionId);
        if (unsubscribe != null) {
          unsubscribe.run();
          removed = 1;
        }
      }
      if (byId.isEmpty()) {
        subscriptionHandlesByAgent.remove(agentId);
      }
      Map<String, Object> data = Map.of(
          "agentId", agentId,
          "removed", removed,
          "activeSubscriptions", countSubscriptions());
      respond(context, "unsubscribe", format, TaskServiceResult.success(data, "Unsubscribed."));
    } catch (IllegalArgumentException e) {
      respond(context, "unsubscribe", format, TaskServiceResult.failure("invalid_arguments", e.getMessage()));
    }
    return true;
  }

  private void respond(CommandContext context, String command, String format, TaskServiceResult<?> result) {
    try {
      int status = httpStatus(result);
      logActionResult(command, format, result, status);
      if ("text".equals(format)) {
        context.completeText(status, renderText(command, result));
        return;
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("ok", result.ok());
      payload.put("errorCode", result.errorCode());
      payload.put("message", result.message());
      payload.put("data", normalizeData(result.data()));
      payload.put("meta", Map.of(
          "command", command,
          "format", format,
          "timestamp", Instant.now().toString()
      ));
      context.completeJson(status, JSON.writeValueAsString(payload));
    } catch (Exception e) {
      logActionException(command, e);
      context.completeJson(500, "{\"ok\":false,\"errorCode\":\"internal_error\",\"message\":\"Failed to encode response.\"}");
    }
  }

  private int httpStatus(TaskServiceResult<?> result) {
    if (result.ok()) {
      return 200;
    }
    if ("not_found".equals(result.errorCode())) {
      return 404;
    }
    if ("version_conflict".equals(result.errorCode())) {
      return 409;
    }
    if ("invalid_arguments".equals(result.errorCode()) || "invalid_transition".equals(result.errorCode())) {
      return 400;
    }
    return 500;
  }

  private String renderText(String command, TaskServiceResult<?> result) {
    if (result.ok()) {
      return "ok [" + command + "]: " + safe(result.message());
    }
    return "error [" + safe(result.errorCode()) + "]: " + safe(result.message());
  }

  private ParsedInput parseInput(CommandContext context) {
    String command = extractCommand(context);
    String raw = "";
    if (context.getAiatpRequest() != null && context.getAiatpRequest().getBody() != null) {
      raw = AiatpIO.bodyToString(context.getAiatpRequest().getBody(), StandardCharsets.UTF_8);
    }
    try {
      ParsedInput parsed = parseInputRaw(raw);
      logActionReceived(command, parsed);
      return parsed;
    } catch (RuntimeException e) {
      System.out.println(LOG_PREFIX + " action=" + safe(command)
          + " parse-error=" + safe(e.getMessage())
          + " raw=" + safe(raw));
      throw e;
    }
  }

  private String extractCommand(CommandContext context) {
    if (context == null || context.getAiatpRequest() == null) {
      return "unknown";
    }
    String target = context.getAiatpRequest().getTarget();
    if (target == null || target.isBlank()) {
      return "unknown";
    }
    String clean = target;
    int query = clean.indexOf('?');
    if (query >= 0) {
      clean = clean.substring(0, query);
    }
    int slash = clean.lastIndexOf('/');
    if (slash < 0 || slash == clean.length() - 1) {
      return clean;
    }
    return clean.substring(slash + 1);
  }

  private void logActionReceived(String command, ParsedInput parsed) {
    System.out.println(LOG_PREFIX + " action=" + safe(command)
        + " received raw=" + safe(parsed.raw())
        + " positional=" + parsed.positional()
        + " options=" + parsed.options());
  }

  private void logActionResult(String command, String format, TaskServiceResult<?> result, int status) {
    System.out.println(LOG_PREFIX + " action=" + safe(command)
        + " result status=" + status
        + " ok=" + result.ok()
        + " errorCode=" + safe(result.errorCode())
        + " message=" + safe(result.message())
        + " format=" + safe(format));
  }

  private void logActionException(String command, Exception e) {
    System.out.println(LOG_PREFIX + " action=" + safe(command) + " exception=" + safe(e.getMessage()));
  }

  ParsedInput parseInputRaw(String raw) {
    List<String> tokens = tokenize(raw == null ? "" : raw.trim());
    Map<String, String> options = new LinkedHashMap<>();
    List<String> positional = new ArrayList<>();
    for (int i = 0; i < tokens.size(); i++) {
      String token = tokens.get(i);
      if (!token.startsWith("--")) {
        positional.add(token);
        continue;
      }
      String option = token.substring(2);
      String value;
      int eq = option.indexOf('=');
      if (eq >= 0) {
        value = option.substring(eq + 1);
        option = option.substring(0, eq);
      } else if (i + 1 < tokens.size() && !tokens.get(i + 1).startsWith("--")) {
        value = tokens.get(++i);
      } else {
        value = "true";
      }
      if (option.isBlank()) {
        throw new IllegalArgumentException("Invalid option syntax.");
      }
      options.put(option.toLowerCase(Locale.ROOT), value);
    }
    return new ParsedInput(raw, positional, options);
  }

  private List<String> tokenize(String raw) {
    List<String> tokens = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inSingle = false;
    boolean inDouble = false;
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c == '\'' && !inDouble) {
        inSingle = !inSingle;
        continue;
      }
      if (c == '"' && !inSingle) {
        inDouble = !inDouble;
        continue;
      }
      if (Character.isWhitespace(c) && !inSingle && !inDouble) {
        if (current.length() > 0) {
          tokens.add(current.toString());
          current.setLength(0);
        }
        continue;
      }
      current.append(c);
    }
    if (inSingle || inDouble) {
      throw new IllegalArgumentException("Unclosed quoted argument.");
    }
    if (current.length() > 0) {
      tokens.add(current.toString());
    }
    return tokens;
  }

  private String parseFormat(Map<String, String> options) {
    String format = options.getOrDefault("format", "json").trim().toLowerCase(Locale.ROOT);
    if (!"json".equals(format) && !"text".equals(format)) {
      throw new IllegalArgumentException("format must be json or text.");
    }
    return format;
  }

  private void ensureNoUnknownOptions(Map<String, String> options, Set<String> allowed) {
    for (String key : options.keySet()) {
      if (!allowed.contains(key)) {
        throw new IllegalArgumentException("Unknown option --" + key + ".");
      }
    }
  }

  private String requireOption(Map<String, String> options, String key) {
    String value = optional(options, key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(key + " is required.");
    }
    return value.trim();
  }

  private String optional(Map<String, String> options, String key) {
    return options.get(key);
  }

  private Integer parseIntNullable(String raw, String field) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(field + " must be an integer.");
    }
  }

  private Long parseLongNullable(String raw, String field) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(raw.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(field + " must be a long.");
    }
  }

  private int parseIntOrDefault(String raw, int defaultValue, String field) {
    if (raw == null || raw.isBlank()) {
      return defaultValue;
    }
    Integer parsed = parseIntNullable(raw, field);
    if (parsed == null) {
      return defaultValue;
    }
    return parsed;
  }

  private Instant parseInstantNullable(String raw, String field) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(raw.trim());
    } catch (Exception e) {
      throw new IllegalArgumentException(field + " must be ISO-8601 instant.");
    }
  }

  private List<String> parseCsvOption(Map<String, String> options, String key) {
    String value = requireOption(options, key);
    String[] parts = value.split(",");
    List<String> items = new ArrayList<>();
    for (String part : parts) {
      String item = part.trim();
      if (!item.isBlank()) {
        items.add(item);
      }
    }
    if (items.isEmpty()) {
      throw new IllegalArgumentException(key + " must include at least one value.");
    }
    return items;
  }

  private List<String> parseCsvOptionNullable(Map<String, String> options, String key) {
    if (!options.containsKey(key)) {
      return null;
    }
    return parseCsvOption(options, key);
  }

  private Set<TaskStatus> parseStatuses(String raw) {
    if (raw == null || raw.isBlank()) {
      return Set.of();
    }
    Set<TaskStatus> out = new LinkedHashSet<>();
    for (String token : Arrays.asList(raw.split(","))) {
      String value = token.trim();
      if (value.isEmpty()) {
        continue;
      }
      try {
        out.add(TaskStatus.valueOf(value.toUpperCase(Locale.ROOT)));
      } catch (Exception e) {
        throw new IllegalArgumentException("Unknown status: " + value + ".");
      }
    }
    return out;
  }

  private int countSubscriptions() {
    return subscriptionHandlesByAgent.values().stream().mapToInt(Map::size).sum();
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  private Object normalizeData(Object data) {
    if (data == null) {
      return null;
    }
    if (data instanceof TaskEntity task) {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("taskId", task.taskId());
      out.put("title", task.title());
      out.put("description", task.description());
      out.put("assignedTo", task.assignedTo());
      out.put("createdAt", task.createdAt() == null ? null : task.createdAt().toString());
      out.put("updatedAt", task.updatedAt() == null ? null : task.updatedAt().toString());
      out.put("createdBy", task.createdBy());
      out.put("status", task.status() == null ? null : task.status().name());
      out.put("priority", task.priority());
      out.put("tags", task.tags());
      out.put("scheduledFor", task.scheduledFor() == null ? null : task.scheduledFor().toString());
      out.put("notBefore", task.notBefore() == null ? null : task.notBefore().toString());
      out.put("deadline", task.deadline() == null ? null : task.deadline().toString());
      out.put("windowStart", task.windowStart() == null ? null : task.windowStart().toString());
      out.put("windowEnd", task.windowEnd() == null ? null : task.windowEnd().toString());
      out.put("recurrence", task.recurrence());
      out.put("attemptsCount", task.attemptsCount());
      out.put("maxAttempts", task.maxAttempts());
      out.put("activeAttemptId", task.activeAttemptId());
      out.put("claimedBy", task.claimedBy());
      out.put("claimExpiresAt", task.claimExpiresAt() == null ? null : task.claimExpiresAt().toString());
      out.put("version", task.version());
      out.put("lastEmittedDueAt", task.lastEmittedDueAt() == null ? null : task.lastEmittedDueAt().toString());
      out.put("idempotencyKey", task.idempotencyKey());
      return out;
    }
    if (data instanceof TaskAttemptEntity attempt) {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("attemptId", attempt.attemptId());
      out.put("taskId", attempt.taskId());
      out.put("attemptNumber", attempt.attemptNumber());
      out.put("status", attempt.status().name());
      out.put("startedAt", attempt.startedAt() == null ? null : attempt.startedAt().toString());
      out.put("endedAt", attempt.endedAt() == null ? null : attempt.endedAt().toString());
      out.put("startedBy", attempt.startedBy());
      out.put("endedBy", attempt.endedBy());
      out.put("errorCode", attempt.errorCode());
      out.put("errorMessage", attempt.errorMessage());
      out.put("progressNote", attempt.progressNote());
      out.put("metaJson", attempt.metaJson());
      out.put("createdAt", attempt.createdAt() == null ? null : attempt.createdAt().toString());
      out.put("updatedAt", attempt.updatedAt() == null ? null : attempt.updatedAt().toString());
      return out;
    }
    if (data instanceof List<?> list) {
      List<Object> out = new ArrayList<>(list.size());
      for (Object item : list) {
        out.add(normalizeData(item));
      }
      return out;
    }
    try {
      return JSON.readTree(JSON.writeValueAsString(data));
    } catch (Exception ignored) {
      return String.valueOf(data);
    }
  }

  private static int readIntConfig(String propertyName,
                                   String envName,
                                   int defaultValue,
                                   int minValue,
                                   int maxValue) {
    String raw = firstNotBlank(System.getProperty(propertyName), System.getenv(envName), null);
    if (raw == null || raw.isBlank()) {
      return defaultValue;
    }
    try {
      int parsed = Integer.parseInt(raw.trim());
      if (parsed < minValue) {
        return minValue;
      }
      if (parsed > maxValue) {
        return maxValue;
      }
      return parsed;
    } catch (NumberFormatException ignored) {
      return defaultValue;
    }
  }

  private static String firstNotBlank(String a, String b, String c) {
    if (a != null && !a.isBlank()) return a;
    if (b != null && !b.isBlank()) return b;
    return c;
  }

  public enum TaskManagerEvent implements EventDefinition {
    TASK_EVENT("Event payload JSON for a delivered task outbox event");

    private final String description;

    TaskManagerEvent(String description) {
      this.description = description;
    }

    @Override
    public String getDescription() {
      return description;
    }
  }

  record ParsedInput(
      String raw,
      List<String> positional,
      Map<String, String> options
  ) {
  }
}
