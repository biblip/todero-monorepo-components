package com.social100.todero.component.taskmanager.service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class TaskScheduler implements AutoCloseable {
  private final TaskService taskService;
  private final TaskEventDispatcher dispatcher;
  private final long scanIntervalMs;
  private final int evaluateLimit;
  private final int dispatchLimit;
  private final ScheduledExecutorService executor;
  private final AtomicBoolean running = new AtomicBoolean(false);

  private final AtomicLong cycles = new AtomicLong();
  private final AtomicLong startupCatchupRuns = new AtomicLong();
  private final AtomicLong cycleFailures = new AtomicLong();

  public TaskScheduler(TaskService taskService,
                       TaskEventDispatcher dispatcher,
                       long scanIntervalMs,
                       int evaluateLimit,
                       int dispatchLimit) {
    this.taskService = taskService;
    this.dispatcher = dispatcher;
    this.scanIntervalMs = Math.max(100L, scanIntervalMs);
    this.evaluateLimit = evaluateLimit <= 0 ? 100 : evaluateLimit;
    this.dispatchLimit = dispatchLimit <= 0 ? 100 : dispatchLimit;
    this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "task-manager-scheduler");
      t.setDaemon(true);
      return t;
    });
  }

  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    startupCatchupRuns.incrementAndGet();
    runCycle();
    executor.scheduleAtFixedRate(this::safeRunCycle, scanIntervalMs, scanIntervalMs, TimeUnit.MILLISECONDS);
  }

  public void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    executor.shutdownNow();
  }

  public boolean isRunning() {
    return running.get();
  }

  public CycleResult runCycle() {
    TaskServiceResult<TaskService.EvaluateDueSummary> evaluation = taskService.evaluateDueTasks(evaluateLimit);
    TaskEventDispatcher.DispatchResult dispatch = dispatcher.dispatchPendingAll(dispatchLimit);
    cycles.incrementAndGet();
    return new CycleResult(evaluation, dispatch);
  }

  public SchedulerMetricsSnapshot metricsSnapshot() {
    return new SchedulerMetricsSnapshot(cycles.get(), startupCatchupRuns.get(), cycleFailures.get());
  }

  @Override
  public void close() {
    stop();
  }

  private void safeRunCycle() {
    try {
      runCycle();
    } catch (Exception e) {
      cycleFailures.incrementAndGet();
    }
  }

  public record CycleResult(
      TaskServiceResult<TaskService.EvaluateDueSummary> evaluation,
      TaskEventDispatcher.DispatchResult dispatch
  ) {
  }

  public record SchedulerMetricsSnapshot(
      long cycles,
      long startupCatchupRuns,
      long cycleFailures
  ) {
  }
}
