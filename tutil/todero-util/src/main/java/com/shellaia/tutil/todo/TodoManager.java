package com.shellaia.tutil.todo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class TodoManager {
  public static final Duration DEFAULT_CLAIM_LEASE = Duration.ofMinutes(10);

  private final TodoStore store;
  private final Clock clock;

  public TodoManager(TodoStore store) {
    this(store, Clock.systemUTC());
  }

  public TodoManager(TodoStore store, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public TodoGoal createGoal(TodoGoalDraft draft) {
    Instant now = now();
    List<TodoPhase> phases = new ArrayList<>();
    for (TodoPhaseDraft phaseDraft : draft.phases()) {
      phases.add(toPhase(draft.ownerId(), phaseDraft, now));
    }
    TodoGoal goal = new TodoGoal(
        newId(),
        draft.ownerId(),
        draft.title(),
        draft.description(),
        phases.isEmpty() ? TodoStatus.PLANNED : deriveStatusFromPhases(phases, TodoStatus.READY),
        "",
        phases,
        draft.metadata(),
        now,
        now,
        0L
    );
    return store.create(goal);
  }

  public TodoSnapshot snapshot(String goalId) {
    return buildSnapshot(requireGoal(goalId));
  }

  public Optional<TodoGoal> findGoal(String goalId) {
    return store.findById(goalId);
  }

  public List<TodoGoal> listGoals() {
    return store.listGoals();
  }

  public List<TodoGoal> queryGoals(TodoGoalQuery query) {
    TodoGoalQuery safeQuery = query == null ? new TodoGoalQuery("", Set.of(), "", false) : query;
    return store.listGoals().stream()
        .filter(goal -> matchesGoalQuery(goal, safeQuery))
        .sorted(Comparator.comparing(TodoGoal::updatedAt).reversed())
        .toList();
  }

  public List<TodoGoal> listOpenGoals() {
    return queryGoals(new TodoGoalQuery("", Set.of(), "", true));
  }

  public Optional<TodoPhase> findPhase(String goalId, String phaseId) {
    return findGoal(goalId)
        .flatMap(goal -> goal.phases().stream().filter(phase -> phase.id().equals(phaseId)).findFirst());
  }

  public Optional<TodoTask> findTask(String goalId, String phaseId, String taskId) {
    return findPhase(goalId, phaseId)
        .flatMap(phase -> phase.tasks().stream().filter(task -> task.id().equals(taskId)).findFirst());
  }

  public List<TodoTaskRef> queryTasks(TodoTaskQuery query) {
    TodoTaskQuery safeQuery = query == null ? new TodoTaskQuery("", "", Set.of(), "", true, true) : query;
    List<TodoTaskRef> matches = new ArrayList<>();
    for (TodoGoal goal : store.listGoals()) {
      if (!safeQuery.goalId().isEmpty() && !goal.id().equals(safeQuery.goalId())) {
        continue;
      }
      if (!safeQuery.ownerId().isEmpty() && !goal.ownerId().equals(safeQuery.ownerId())) {
        continue;
      }
      for (int phaseIndex = 0; phaseIndex < goal.phases().size(); phaseIndex++) {
        TodoPhase phase = goal.phases().get(phaseIndex);
        for (int taskIndex = 0; taskIndex < phase.tasks().size(); taskIndex++) {
          TodoTask task = phase.tasks().get(taskIndex);
          if (matchesTaskQuery(goal, phase, task, safeQuery)) {
            matches.add(toTaskRef(goal, phase, phaseIndex, task, taskIndex));
          }
        }
      }
    }
    return matches;
  }

  public Optional<TodoTaskRef> nextReadyTask(String goalId) {
    return nextReadyTask(goalId, "");
  }

  public Optional<TodoTaskRef> nextReadyTask(String goalId, String agentId) {
    TodoGoal goal = requireGoal(goalId);
    Map<String, TodoTask> taskIndex = indexTasks(goal);
    List<TodoTaskRef> candidates = new ArrayList<>();
    Instant now = now();
    String claimant = safeTrim(agentId);
    for (int phaseIndex = 0; phaseIndex < goal.phases().size(); phaseIndex++) {
      TodoPhase phase = goal.phases().get(phaseIndex);
      for (int taskIndexNumber = 0; taskIndexNumber < phase.tasks().size(); taskIndexNumber++) {
        TodoTask task = phase.tasks().get(taskIndexNumber);
        if ((task.status() == TodoStatus.READY || task.status() == TodoStatus.IN_PROGRESS)
            && dependenciesSatisfied(task, taskIndex)
            && claimCompatible(task, claimant, now)) {
          candidates.add(toTaskRef(goal, phase, phaseIndex, task, taskIndexNumber));
        }
      }
    }
    return candidates.stream()
        .sorted(Comparator
            .comparingInt((TodoTaskRef ref) -> ref.status() == TodoStatus.IN_PROGRESS ? 0 : 1)
            .thenComparing((TodoTaskRef ref) -> ref.priority() == null ? 0 : ref.priority(), Comparator.reverseOrder())
            .thenComparing(TodoTaskRef::dueAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingInt(TodoTaskRef::phasePosition)
            .thenComparingInt(TodoTaskRef::taskPosition))
        .findFirst();
  }

  public List<TodoGoal> updateTaskStatuses(String goalId, List<TodoTaskStatusUpdate> updates) {
    TodoGoal goal = requireGoal(goalId);
    if (updates == null || updates.isEmpty()) {
      return List.of(goal);
    }
    Instant now = now();
    Map<String, TodoTaskStatusUpdate> updateMap = new HashMap<>();
    for (TodoTaskStatusUpdate update : updates) {
      String key = update.phaseId() + "::" + update.taskId();
      if (updateMap.put(key, update) != null) {
        throw new IllegalArgumentException("Duplicate task status update for " + key);
      }
    }
    List<TodoPhase> phases = new ArrayList<>();
    int foundUpdates = 0;
    for (TodoPhase phase : goal.phases()) {
      List<TodoTask> tasks = new ArrayList<>();
      boolean phaseChanged = false;
      for (TodoTask task : phase.tasks()) {
        TodoTaskStatusUpdate update = updateMap.get(phase.id() + "::" + task.id());
        if (update != null) {
          tasks.add(task.withStatus(update.status(), update.outcome(), now));
          foundUpdates++;
          phaseChanged = true;
        } else {
          tasks.add(task);
        }
      }
      phases.add(phaseChanged
          ? phase.withTasks(tasks, deriveStatusFromTasks(tasks, phase.status()), phaseOutcomeFor(tasks, phase.outcome()), now)
          : phase);
    }
    if (foundUpdates != updateMap.size()) {
      throw new TodoNotFoundException("One or more task updates reference missing phase/task ids.");
    }
    TodoGoal updated = persist(goal, recalculateGoal(goal, phases, now));
    return List.of(updated);
  }

  public TodoMutationResult updateTaskStatusesDetailed(String goalId, List<TodoTaskStatusUpdate> updates) {
    TodoGoal before = requireGoal(goalId);
    TodoGoal after = updateTaskStatuses(goalId, updates).get(0);
    return mutationResult(before, after, List.of(new TodoEvent(
        after.id(),
        "",
        "",
        TodoEventType.TASK_STATUS_BATCH_UPDATED,
        after.updatedAt(),
        "Applied " + updates.size() + " task status update(s)."
    )));
  }

  public TodoGoal updateGoalStatus(String goalId, TodoStatus status, String outcome) {
    TodoGoal goal = requireGoal(goalId);
    return persist(goal, goal.withStatus(status, safeTrim(outcome), now()));
  }

  public TodoGoal updateGoalDetails(String goalId, String title, String description) {
    TodoGoal goal = requireGoal(goalId);
    TodoGoal updated = new TodoGoal(
        goal.id(),
        goal.ownerId(),
        safeTitle(title, goal.title(), "Goal title"),
        descriptionOrFallback(description, goal.description()),
        goal.status(),
        goal.outcome(),
        goal.phases(),
        goal.metadata(),
        goal.createdAt(),
        now(),
        goal.version() + 1
    );
    return persist(goal, updated);
  }

  public TodoGoal updatePhaseStatus(String goalId, String phaseId, TodoStatus status, String outcome) {
    TodoGoal goal = requireGoal(goalId);
    Instant now = now();
    List<TodoPhase> phases = new ArrayList<>();
    boolean found = false;
    for (TodoPhase phase : goal.phases()) {
      if (phase.id().equals(phaseId)) {
        phases.add(phase.withStatus(status, safeTrim(outcome), now));
        found = true;
      } else {
        phases.add(phase);
      }
    }
    if (!found) {
      throw new TodoNotFoundException("Phase not found: " + phaseId);
    }
    return persist(goal, recalculateGoal(goal, phases, now));
  }

  public TodoGoal updatePhaseDetails(String goalId, String phaseId, String title, String description) {
    TodoGoal goal = requireGoal(goalId);
    Instant now = now();
    List<TodoPhase> phases = new ArrayList<>();
    boolean found = false;
    for (TodoPhase phase : goal.phases()) {
      if (phase.id().equals(phaseId)) {
        phases.add(new TodoPhase(
            phase.id(),
            phase.ownerId(),
            safeTitle(title, phase.title(), "Phase title"),
            descriptionOrFallback(description, phase.description()),
            phase.status(),
            phase.outcome(),
            phase.tasks(),
            phase.metadata(),
            phase.createdAt(),
            now
        ));
        found = true;
      } else {
        phases.add(phase);
      }
    }
    if (!found) {
      throw new TodoNotFoundException("Phase not found: " + phaseId);
    }
    return persist(goal, recalculateGoal(goal, phases, now));
  }

  public TodoGoal insertPhase(String goalId, TodoPhaseDraft draft, int index) {
    TodoGoal goal = requireGoal(goalId);
    Instant now = now();
    List<TodoPhase> phases = new ArrayList<>(goal.phases());
    phases.add(normalizeIndex(index, phases.size()), toPhase(goal.ownerId(), draft, now));
    return persist(goal, recalculateGoal(goal, phases, now));
  }

  public TodoGoal reorderPhases(String goalId, int fromIndex, int toIndex) {
    TodoGoal goal = requireGoal(goalId);
    List<TodoPhase> phases = new ArrayList<>(goal.phases());
    moveItem(phases, fromIndex, toIndex, "Phase");
    return persist(goal, recalculateGoal(goal, phases, now()));
  }

  public TodoGoal deletePhase(String goalId, String phaseId) {
    TodoGoal goal = requireGoal(goalId);
    List<TodoPhase> phases = new ArrayList<>();
    boolean found = false;
    for (TodoPhase phase : goal.phases()) {
      if (phase.id().equals(phaseId)) {
        found = true;
      } else {
        phases.add(phase);
      }
    }
    if (!found) {
      throw new TodoNotFoundException("Phase not found: " + phaseId);
    }
    return persist(goal, recalculateGoal(goal, phases, now()));
  }

  public TodoGoal updateTaskStatus(String goalId, String phaseId, String taskId, TodoStatus status, String outcome) {
    return updateTaskStatuses(goalId, List.of(new TodoTaskStatusUpdate(phaseId, taskId, status, outcome))).get(0);
  }

  public TodoGoal updateTaskDetails(String goalId,
                                    String phaseId,
                                    String taskId,
                                    String title,
                                    String description,
                                    Instant dueAt,
                                    Integer priority,
                                    List<String> dependencyTaskIds) {
    TodoGoal goal = requireGoal(goalId);
    Instant now = now();
    List<TodoPhase> phases = new ArrayList<>();
    boolean foundPhase = false;
    boolean foundTask = false;
    for (TodoPhase phase : goal.phases()) {
      if (!phase.id().equals(phaseId)) {
        phases.add(phase);
        continue;
      }
      foundPhase = true;
      List<TodoTask> tasks = new ArrayList<>();
      for (TodoTask task : phase.tasks()) {
        if (task.id().equals(taskId)) {
          tasks.add(task.withPlanning(
              safeTitle(title, task.title(), "Task title"),
              descriptionOrFallback(description, task.description()),
              dueAt == null ? task.dueAt() : dueAt,
              priority == null ? task.priority() : priority,
              dependencyTaskIds == null ? task.dependencyTaskIds() : dependencyTaskIds,
              now
          ));
          foundTask = true;
        } else {
          tasks.add(task);
        }
      }
      assertTaskGraphValid(goal, phaseId, tasks);
      phases.add(phase.withTasks(tasks, deriveStatusFromTasks(tasks, phase.status()), phaseOutcomeFor(tasks, phase.outcome()), now));
    }
    if (!foundPhase) {
      throw new TodoNotFoundException("Phase not found: " + phaseId);
    }
    if (!foundTask) {
      throw new TodoNotFoundException("Task not found: " + taskId);
    }
    return persist(goal, recalculateGoal(goal, phases, now));
  }

  public TodoGoal insertTask(String goalId, String phaseId, TodoTaskDraft draft, int index) {
    TodoGoal goal = requireGoal(goalId);
    Instant now = now();
    List<TodoPhase> phases = new ArrayList<>();
    boolean found = false;
    for (TodoPhase phase : goal.phases()) {
      if (!phase.id().equals(phaseId)) {
        phases.add(phase);
        continue;
      }
      found = true;
      List<TodoTask> tasks = new ArrayList<>(phase.tasks());
      tasks.add(normalizeIndex(index, tasks.size()), toTask(goal.ownerId(), draft, now));
      assertTaskGraphValid(goal, phaseId, tasks);
      phases.add(phase.withTasks(tasks, deriveStatusFromTasks(tasks, phase.status()), phaseOutcomeFor(tasks, phase.outcome()), now));
    }
    if (!found) {
      throw new TodoNotFoundException("Phase not found: " + phaseId);
    }
    return persist(goal, recalculateGoal(goal, phases, now));
  }

  public TodoGoal reorderTasks(String goalId, String phaseId, int fromIndex, int toIndex) {
    TodoGoal goal = requireGoal(goalId);
    Instant now = now();
    List<TodoPhase> phases = new ArrayList<>();
    boolean found = false;
    for (TodoPhase phase : goal.phases()) {
      if (!phase.id().equals(phaseId)) {
        phases.add(phase);
        continue;
      }
      found = true;
      List<TodoTask> tasks = new ArrayList<>(phase.tasks());
      moveItem(tasks, fromIndex, toIndex, "Task");
      phases.add(phase.withTasks(tasks, deriveStatusFromTasks(tasks, phase.status()), phaseOutcomeFor(tasks, phase.outcome()), now));
    }
    if (!found) {
      throw new TodoNotFoundException("Phase not found: " + phaseId);
    }
    return persist(goal, recalculateGoal(goal, phases, now));
  }

  public TodoGoal deleteTask(String goalId, String phaseId, String taskId) {
    TodoGoal goal = requireGoal(goalId);
    Instant now = now();
    List<TodoPhase> phases = new ArrayList<>();
    boolean foundPhase = false;
    boolean foundTask = false;
    for (TodoPhase phase : goal.phases()) {
      if (!phase.id().equals(phaseId)) {
        phases.add(phase);
        continue;
      }
      foundPhase = true;
      List<TodoTask> tasks = new ArrayList<>();
      for (TodoTask task : phase.tasks()) {
        if (task.id().equals(taskId)) {
          foundTask = true;
        } else {
          tasks.add(task);
        }
      }
      assertNoDanglingDependencies(goal, phaseId, taskId, tasks);
      phases.add(phase.withTasks(tasks, deriveStatusFromTasks(tasks, phase.status()), phaseOutcomeFor(tasks, phase.outcome()), now));
    }
    if (!foundPhase) {
      throw new TodoNotFoundException("Phase not found: " + phaseId);
    }
    if (!foundTask) {
      throw new TodoNotFoundException("Task not found: " + taskId);
    }
    return persist(goal, recalculateGoal(goal, phases, now));
  }

  public TodoMutationResult applyPlanningEdits(String goalId, List<TodoPlanningEdit> edits) {
    TodoGoal goal = requireGoal(goalId);
    if (edits == null || edits.isEmpty()) {
      return mutationResult(goal, goal, List.of());
    }
    Instant now = now();
    List<TodoPhase> phases = new ArrayList<>(goal.phases());
    List<TodoEvent> events = new ArrayList<>();
    for (TodoPlanningEdit edit : edits) {
      switch (edit.type()) {
        case INSERT_PHASE -> {
          phases.add(normalizeIndex(requireIndex(edit.index(), "Phase insert index"), phases.size()), toPhase(goal.ownerId(), requirePhaseDraft(edit), now));
          events.add(new TodoEvent(goal.id(), "", "", TodoEventType.PHASE_INSERTED, now, "Inserted phase at index " + edit.index() + "."));
        }
        case REORDER_PHASE -> {
          moveItem(phases, requireIndex(edit.index(), "Phase source index"), requireIndex(edit.toIndex(), "Phase target index"), "Phase");
          events.add(new TodoEvent(goal.id(), "", "", TodoEventType.PHASE_REORDERED, now, "Reordered phase."));
        }
        case DELETE_PHASE -> {
          String phaseId = requireId(edit.phaseId(), "Phase id");
          phases.remove(findPhaseIndex(phases, phaseId));
          events.add(new TodoEvent(goal.id(), phaseId, "", TodoEventType.PHASE_DELETED, now, "Deleted phase " + phaseId + "."));
        }
        case UPDATE_PHASE -> {
          String phaseId = requireId(edit.phaseId(), "Phase id");
          int index = findPhaseIndex(phases, phaseId);
          TodoPhase phase = phases.get(index);
          phases.set(index, new TodoPhase(
              phase.id(),
              phase.ownerId(),
              safeTitle(edit.title(), phase.title(), "Phase title"),
              descriptionOrFallback(edit.description(), phase.description()),
              phase.status(),
              phase.outcome(),
              phase.tasks(),
              phase.metadata(),
              phase.createdAt(),
              now
          ));
          events.add(new TodoEvent(goal.id(), phaseId, "", TodoEventType.PHASE_UPDATED, now, "Updated phase " + phaseId + "."));
        }
        case INSERT_TASK -> {
          String phaseId = requireId(edit.phaseId(), "Phase id");
          int phaseIndex = findPhaseIndex(phases, phaseId);
          TodoPhase phase = phases.get(phaseIndex);
          List<TodoTask> tasks = new ArrayList<>(phase.tasks());
          tasks.add(normalizeIndex(requireIndex(edit.index(), "Task insert index"), tasks.size()), toTask(goal.ownerId(), requireTaskDraft(edit), now));
          TodoGoal workingGoal = goal.withPhases(phases, goal.status(), goal.outcome(), now);
          assertTaskGraphValid(workingGoal, phaseId, tasks);
          phases.set(phaseIndex, phase.withTasks(tasks, deriveStatusFromTasks(tasks, phase.status()), phaseOutcomeFor(tasks, phase.outcome()), now));
          events.add(new TodoEvent(goal.id(), phaseId, "", TodoEventType.TASK_INSERTED, now, "Inserted task into phase " + phaseId + "."));
        }
        case REORDER_TASK -> {
          String phaseId = requireId(edit.phaseId(), "Phase id");
          int phaseIndex = findPhaseIndex(phases, phaseId);
          TodoPhase phase = phases.get(phaseIndex);
          List<TodoTask> tasks = new ArrayList<>(phase.tasks());
          moveItem(tasks, requireIndex(edit.index(), "Task source index"), requireIndex(edit.toIndex(), "Task target index"), "Task");
          phases.set(phaseIndex, phase.withTasks(tasks, deriveStatusFromTasks(tasks, phase.status()), phaseOutcomeFor(tasks, phase.outcome()), now));
          events.add(new TodoEvent(goal.id(), phaseId, "", TodoEventType.TASK_REORDERED, now, "Reordered tasks in phase " + phaseId + "."));
        }
        case DELETE_TASK -> {
          String phaseId = requireId(edit.phaseId(), "Phase id");
          String taskId = requireId(edit.taskId(), "Task id");
          int phaseIndex = findPhaseIndex(phases, phaseId);
          TodoPhase phase = phases.get(phaseIndex);
          List<TodoTask> tasks = new ArrayList<>();
          for (TodoTask task : phase.tasks()) {
            if (!task.id().equals(taskId)) {
              tasks.add(task);
            }
          }
          TodoGoal workingGoal = goal.withPhases(phases, goal.status(), goal.outcome(), now);
          assertNoDanglingDependencies(workingGoal, phaseId, taskId, tasks);
          phases.set(phaseIndex, phase.withTasks(tasks, deriveStatusFromTasks(tasks, phase.status()), phaseOutcomeFor(tasks, phase.outcome()), now));
          events.add(new TodoEvent(goal.id(), phaseId, taskId, TodoEventType.TASK_DELETED, now, "Deleted task " + taskId + "."));
        }
        case UPDATE_TASK -> {
          String phaseId = requireId(edit.phaseId(), "Phase id");
          String taskId = requireId(edit.taskId(), "Task id");
          int phaseIndex = findPhaseIndex(phases, phaseId);
          TodoPhase phase = phases.get(phaseIndex);
          List<TodoTask> tasks = new ArrayList<>();
          for (TodoTask task : phase.tasks()) {
            if (task.id().equals(taskId)) {
              tasks.add(task.withPlanning(
                  safeTitle(edit.title(), task.title(), "Task title"),
                  descriptionOrFallback(edit.description(), task.description()),
                  edit.dueAt() == null ? task.dueAt() : edit.dueAt(),
                  edit.priority() == null ? task.priority() : edit.priority(),
                  edit.dependencyTaskIds() == null ? task.dependencyTaskIds() : edit.dependencyTaskIds(),
                  now
              ));
            } else {
              tasks.add(task);
            }
          }
          TodoGoal workingGoal = goal.withPhases(phases, goal.status(), goal.outcome(), now);
          assertTaskGraphValid(workingGoal, phaseId, tasks);
          phases.set(phaseIndex, phase.withTasks(tasks, deriveStatusFromTasks(tasks, phase.status()), phaseOutcomeFor(tasks, phase.outcome()), now));
          events.add(new TodoEvent(goal.id(), phaseId, taskId, TodoEventType.TASK_UPDATED, now, "Updated task " + taskId + "."));
        }
      }
    }
    TodoGoal updated = persist(goal, recalculateGoal(goal, phases, now));
    return mutationResult(goal, updated, events);
  }

  public TodoGoal claimTask(String goalId, String phaseId, String taskId, String agentId) {
    return claimTask(goalId, phaseId, taskId, agentId, DEFAULT_CLAIM_LEASE);
  }

  public TodoGoal claimTask(String goalId, String phaseId, String taskId, String agentId, Duration leaseDuration) {
    TodoGoal goal = requireGoal(goalId);
    Instant now = now();
    Instant expiresAt = claimExpiry(now, leaseDuration);
    String claimant = safeTrim(agentId);
    if (claimant.isEmpty()) {
      throw new IllegalArgumentException("Agent id is required.");
    }
    List<TodoPhase> phases = new ArrayList<>();
    boolean foundPhase = false;
    boolean foundTask = false;
    for (TodoPhase phase : goal.phases()) {
      if (!phase.id().equals(phaseId)) {
        phases.add(phase);
        continue;
      }
      foundPhase = true;
      List<TodoTask> tasks = new ArrayList<>();
      for (TodoTask task : phase.tasks()) {
        if (task.id().equals(taskId)) {
          if (!claimCompatible(task, claimant, now)) {
            throw new TodoConflictException("Task already claimed by another agent: " + taskId);
          }
          tasks.add(task.claim(claimant, now, expiresAt));
          foundTask = true;
        } else {
          tasks.add(task);
        }
      }
      phases.add(phase.withTasks(tasks, deriveStatusFromTasks(tasks, phase.status()), phaseOutcomeFor(tasks, phase.outcome()), now));
    }
    if (!foundPhase) {
      throw new TodoNotFoundException("Phase not found: " + phaseId);
    }
    if (!foundTask) {
      throw new TodoNotFoundException("Task not found: " + taskId);
    }
    return persist(goal, recalculateGoal(goal, phases, now));
  }

  public TodoGoal renewTaskClaim(String goalId, String phaseId, String taskId, String agentId, Duration leaseDuration) {
    TodoGoal goal = requireGoal(goalId);
    Instant now = now();
    Instant expiresAt = claimExpiry(now, leaseDuration);
    String claimant = safeTrim(agentId);
    List<TodoPhase> phases = new ArrayList<>();
    boolean foundPhase = false;
    boolean foundTask = false;
    for (TodoPhase phase : goal.phases()) {
      if (!phase.id().equals(phaseId)) {
        phases.add(phase);
        continue;
      }
      foundPhase = true;
      List<TodoTask> tasks = new ArrayList<>();
      for (TodoTask task : phase.tasks()) {
        if (task.id().equals(taskId)) {
          if (!task.claimedBy().equals(claimant) || !claimActive(task, now)) {
            throw new TodoConflictException("Task claim is not active for agent " + claimant + ": " + taskId);
          }
          tasks.add(task.renewClaim(claimant, now, expiresAt));
          foundTask = true;
        } else {
          tasks.add(task);
        }
      }
      phases.add(phase.withTasks(tasks, deriveStatusFromTasks(tasks, phase.status()), phaseOutcomeFor(tasks, phase.outcome()), now));
    }
    if (!foundPhase) {
      throw new TodoNotFoundException("Phase not found: " + phaseId);
    }
    if (!foundTask) {
      throw new TodoNotFoundException("Task not found: " + taskId);
    }
    return persist(goal, recalculateGoal(goal, phases, now));
  }

  public TodoGoal releaseTaskClaim(String goalId, String phaseId, String taskId, String agentId) {
    TodoGoal goal = requireGoal(goalId);
    Instant now = now();
    String claimant = safeTrim(agentId);
    List<TodoPhase> phases = new ArrayList<>();
    boolean foundPhase = false;
    boolean foundTask = false;
    for (TodoPhase phase : goal.phases()) {
      if (!phase.id().equals(phaseId)) {
        phases.add(phase);
        continue;
      }
      foundPhase = true;
      List<TodoTask> tasks = new ArrayList<>();
      for (TodoTask task : phase.tasks()) {
        if (task.id().equals(taskId)) {
          if (!task.claimedBy().isEmpty() && !task.claimedBy().equals(claimant) && claimActive(task, now)) {
            throw new TodoConflictException("Task claim owned by another agent: " + taskId);
          }
          tasks.add(task.releaseClaim(now));
          foundTask = true;
        } else {
          tasks.add(task);
        }
      }
      phases.add(phase.withTasks(tasks, deriveStatusFromTasks(tasks, phase.status()), phaseOutcomeFor(tasks, phase.outcome()), now));
    }
    if (!foundPhase) {
      throw new TodoNotFoundException("Phase not found: " + phaseId);
    }
    if (!foundTask) {
      throw new TodoNotFoundException("Task not found: " + taskId);
    }
    return persist(goal, recalculateGoal(goal, phases, now));
  }

  public Optional<TodoTaskRef> claimNextReadyTask(String goalId, String agentId) {
    return claimNextReadyTask(goalId, agentId, DEFAULT_CLAIM_LEASE);
  }

  public Optional<TodoTaskRef> claimNextReadyTask(String goalId, String agentId, Duration leaseDuration) {
    Optional<TodoTaskRef> candidate = nextReadyTask(goalId, agentId);
    if (candidate.isEmpty()) {
      return Optional.empty();
    }
    TodoTaskRef ref = candidate.orElseThrow();
    TodoGoal updated = claimTask(goalId, ref.phaseId(), ref.taskId(), agentId, leaseDuration);
    return findTask(updated.id(), ref.phaseId(), ref.taskId())
        .map(task -> toTaskRef(
            updated,
            updated.phases().stream().filter(phase -> phase.id().equals(ref.phaseId())).findFirst().orElseThrow(),
            ref.phasePosition(),
            task,
            ref.taskPosition()
        ));
  }

  public void deleteGoal(String goalId) {
    TodoGoal goal = requireGoal(goalId);
    store.delete(goal.id(), goal.version());
  }

  private TodoGoal persist(TodoGoal current, TodoGoal next) {
    return store.save(next, current.version());
  }

  private TodoMutationResult mutationResult(TodoGoal before, TodoGoal after, List<TodoEvent> events) {
    return new TodoMutationResult(after, List.copyOf(events), buildSnapshot(after));
  }

  private TodoSnapshot buildSnapshot(TodoGoal goal) {
    return new TodoSnapshot(
        goal.id(),
        goal.ownerId(),
        goal.status(),
        goal.version(),
        goal.progress(),
        nextReadyTask(goal.id(), goal.ownerId()).orElse(null),
        goal.updatedAt()
    );
  }

  private TodoGoal requireGoal(String goalId) {
    return store.findById(goalId)
        .orElseThrow(() -> new TodoNotFoundException("Goal not found: " + goalId));
  }

  private TodoGoal recalculateGoal(TodoGoal goal, List<TodoPhase> phases, Instant now) {
    TodoStatus goalStatus = deriveStatusFromPhases(phases, goal.status());
    String goalOutcome = isTerminal(goalStatus) ? goal.outcome() : "";
    return new TodoGoal(
        goal.id(),
        goal.ownerId(),
        goal.title(),
        goal.description(),
        goalStatus,
        goalOutcome,
        phases,
        goal.metadata(),
        goal.createdAt(),
        now,
        goal.version() + 1
    );
  }

  private TodoPhase toPhase(String ownerId, TodoPhaseDraft draft, Instant now) {
    List<TodoTask> tasks = new ArrayList<>();
    for (TodoTaskDraft taskDraft : draft.tasks()) {
      tasks.add(toTask(ownerId, taskDraft, now));
    }
    TodoStatus phaseStatus = deriveStatusFromTasks(tasks, TodoStatus.PLANNED);
    return new TodoPhase(
        newId(),
        ownerId,
        draft.title(),
        draft.description(),
        phaseStatus,
        "",
        tasks,
        draft.metadata(),
        now,
        now
    );
  }

  private TodoTask toTask(String ownerId, TodoTaskDraft draft, Instant now) {
    return new TodoTask(
        newId(),
        ownerId,
        draft.title(),
        draft.description(),
        TodoStatus.READY,
        "",
        draft.dueAt(),
        draft.priority() == null ? 0 : draft.priority(),
        draft.dependencyTaskIds(),
        "",
        null,
        null,
        draft.metadata(),
        now,
        now
    );
  }

  private TodoStatus deriveStatusFromTasks(List<TodoTask> tasks, TodoStatus fallback) {
    if (tasks.isEmpty()) {
      return fallback;
    }
    boolean allCompleted = true;
    boolean hasFailed = false;
    boolean hasInProgress = false;
    boolean hasBlocked = false;
    boolean hasReady = false;
    boolean hasPlanned = false;
    boolean hasCanceled = false;
    for (TodoTask task : tasks) {
      TodoStatus status = task.status();
      if (status != TodoStatus.COMPLETED && status != TodoStatus.CANCELED) {
        allCompleted = false;
      }
      hasFailed |= status == TodoStatus.FAILED;
      hasInProgress |= status == TodoStatus.IN_PROGRESS;
      hasBlocked |= status == TodoStatus.BLOCKED;
      hasReady |= status == TodoStatus.READY;
      hasPlanned |= status == TodoStatus.PLANNED;
      hasCanceled |= status == TodoStatus.CANCELED;
    }
    if (allCompleted) {
      return TodoStatus.COMPLETED;
    }
    if (hasFailed) {
      return TodoStatus.FAILED;
    }
    if (hasInProgress) {
      return TodoStatus.IN_PROGRESS;
    }
    if (hasBlocked) {
      return TodoStatus.BLOCKED;
    }
    if (hasReady || hasPlanned) {
      return TodoStatus.READY;
    }
    if (hasCanceled) {
      return TodoStatus.CANCELED;
    }
    return TodoStatus.PLANNED;
  }

  private TodoStatus deriveStatusFromPhases(List<TodoPhase> phases, TodoStatus fallback) {
    if (phases.isEmpty()) {
      return fallback;
    }
    boolean allCompleted = true;
    boolean hasFailed = false;
    boolean hasInProgress = false;
    boolean hasBlocked = false;
    boolean hasReady = false;
    boolean hasPlanned = false;
    boolean hasCanceled = false;
    for (TodoPhase phase : phases) {
      TodoStatus status = phase.status();
      if (status != TodoStatus.COMPLETED && status != TodoStatus.CANCELED) {
        allCompleted = false;
      }
      hasFailed |= status == TodoStatus.FAILED;
      hasInProgress |= status == TodoStatus.IN_PROGRESS;
      hasBlocked |= status == TodoStatus.BLOCKED;
      hasReady |= status == TodoStatus.READY;
      hasPlanned |= status == TodoStatus.PLANNED;
      hasCanceled |= status == TodoStatus.CANCELED;
    }
    if (allCompleted) {
      return TodoStatus.COMPLETED;
    }
    if (hasFailed) {
      return TodoStatus.FAILED;
    }
    if (hasInProgress) {
      return TodoStatus.IN_PROGRESS;
    }
    if (hasBlocked) {
      return TodoStatus.BLOCKED;
    }
    if (hasReady || hasPlanned) {
      return TodoStatus.READY;
    }
    if (hasCanceled) {
      return TodoStatus.CANCELED;
    }
    return TodoStatus.PLANNED;
  }

  private boolean matchesGoalQuery(TodoGoal goal, TodoGoalQuery query) {
    if (!query.ownerId().isEmpty() && !goal.ownerId().equals(query.ownerId())) {
      return false;
    }
    if (query.openOnly() && isTerminal(goal.status())) {
      return false;
    }
    if (!query.statuses().isEmpty() && !query.statuses().contains(goal.status())) {
      return false;
    }
    if (!query.text().isEmpty()) {
      String needle = query.text().toLowerCase();
      return goal.title().toLowerCase().contains(needle) || goal.description().toLowerCase().contains(needle);
    }
    return true;
  }

  private boolean matchesTaskQuery(TodoGoal goal, TodoPhase phase, TodoTask task, TodoTaskQuery query) {
    if (!query.includeCompleted() && task.status() == TodoStatus.COMPLETED) {
      return false;
    }
    if (!query.includeCanceled() && task.status() == TodoStatus.CANCELED) {
      return false;
    }
    if (!query.statuses().isEmpty() && !query.statuses().contains(task.status())) {
      return false;
    }
    if (!query.text().isEmpty()) {
      String needle = query.text().toLowerCase();
      return task.title().toLowerCase().contains(needle)
          || task.description().toLowerCase().contains(needle)
          || phase.title().toLowerCase().contains(needle)
          || goal.title().toLowerCase().contains(needle);
    }
    return true;
  }

  private TodoTaskRef toTaskRef(TodoGoal goal, TodoPhase phase, int phaseIndex, TodoTask task, int taskIndex) {
    return new TodoTaskRef(
        goal.id(),
        goal.ownerId(),
        goal.title(),
        phase.id(),
        phase.title(),
        phaseIndex,
        task.id(),
        task.title(),
        taskIndex,
        task.status(),
        task.priority(),
        task.dueAt(),
        task.dependencyTaskIds(),
        task.claimedBy(),
        task.claimedAt(),
        task.claimExpiresAt()
    );
  }

  private Map<String, TodoTask> indexTasks(TodoGoal goal) {
    Map<String, TodoTask> indexed = new HashMap<>();
    for (TodoPhase phase : goal.phases()) {
      for (TodoTask task : phase.tasks()) {
        indexed.put(task.id(), task);
      }
    }
    return indexed;
  }

  private boolean dependenciesSatisfied(TodoTask task, Map<String, TodoTask> taskIndex) {
    for (String dependencyId : task.dependencyTaskIds()) {
      TodoTask dependency = taskIndex.get(dependencyId);
      if (dependency == null || dependency.status() != TodoStatus.COMPLETED) {
        return false;
      }
    }
    return true;
  }

  private void assertTaskGraphValid(TodoGoal goal, String targetPhaseId, List<TodoTask> targetPhaseTasks) {
    Map<String, TodoTask> graph = new HashMap<>();
    for (TodoPhase phase : goal.phases()) {
      List<TodoTask> tasks = phase.id().equals(targetPhaseId) ? targetPhaseTasks : phase.tasks();
      for (TodoTask task : tasks) {
        graph.put(task.id(), task);
      }
    }
    for (TodoTask task : graph.values()) {
      for (String dependencyId : task.dependencyTaskIds()) {
        if (!graph.containsKey(dependencyId)) {
          throw new IllegalArgumentException("Unknown dependency task id: " + dependencyId);
        }
      }
    }
    detectCycles(graph);
  }

  private void assertNoDanglingDependencies(TodoGoal goal, String targetPhaseId, String deletedTaskId, List<TodoTask> targetPhaseTasks) {
    for (TodoPhase phase : goal.phases()) {
      List<TodoTask> tasks = phase.id().equals(targetPhaseId) ? targetPhaseTasks : phase.tasks();
      for (TodoTask task : tasks) {
        if (task.dependencyTaskIds().contains(deletedTaskId)) {
          throw new IllegalArgumentException("Cannot delete task; it is a dependency of task " + task.id());
        }
      }
    }
  }

  private void detectCycles(Map<String, TodoTask> graph) {
    Set<String> visiting = new HashSet<>();
    Set<String> visited = new HashSet<>();
    for (String taskId : graph.keySet()) {
      visit(taskId, graph, visiting, visited);
    }
  }

  private void visit(String taskId, Map<String, TodoTask> graph, Set<String> visiting, Set<String> visited) {
    if (visited.contains(taskId)) {
      return;
    }
    if (!visiting.add(taskId)) {
      throw new IllegalArgumentException("Dependency cycle detected at task " + taskId);
    }
    for (String dependencyId : graph.get(taskId).dependencyTaskIds()) {
      visit(dependencyId, graph, visiting, visited);
    }
    visiting.remove(taskId);
    visited.add(taskId);
  }

  private boolean claimCompatible(TodoTask task, String claimant, Instant now) {
    if (task.claimedBy().isEmpty() || !claimActive(task, now)) {
      return true;
    }
    return !claimant.isEmpty() && task.claimedBy().equals(claimant);
  }

  private boolean claimActive(TodoTask task, Instant now) {
    return !task.claimedBy().isEmpty()
        && task.claimExpiresAt() != null
        && task.claimExpiresAt().isAfter(now);
  }

  private Instant claimExpiry(Instant now, Duration leaseDuration) {
    if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("Lease duration must be positive.");
    }
    return now.plus(leaseDuration);
  }

  private String phaseOutcomeFor(List<TodoTask> tasks, String existingOutcome) {
    return deriveStatusFromTasks(tasks, TodoStatus.PLANNED) == TodoStatus.COMPLETED ? existingOutcome : "";
  }

  private static boolean isTerminal(TodoStatus status) {
    return status == TodoStatus.COMPLETED || status == TodoStatus.FAILED || status == TodoStatus.CANCELED;
  }

  private static int normalizeIndex(int requestedIndex, int size) {
    if (requestedIndex < 0 || requestedIndex > size) {
      throw new IllegalArgumentException("Index out of bounds: " + requestedIndex);
    }
    return requestedIndex;
  }

  private static <T> void moveItem(List<T> items, int fromIndex, int toIndex, String type) {
    if (fromIndex < 0 || fromIndex >= items.size()) {
      throw new IllegalArgumentException(type + " source index out of bounds: " + fromIndex);
    }
    if (toIndex < 0 || toIndex >= items.size()) {
      throw new IllegalArgumentException(type + " target index out of bounds: " + toIndex);
    }
    if (fromIndex == toIndex) {
      return;
    }
    T item = items.remove(fromIndex);
    items.add(toIndex, item);
  }

  private static int requireIndex(Integer index, String label) {
    if (index == null) {
      throw new IllegalArgumentException(label + " is required.");
    }
    return index;
  }

  private static String requireId(String value, String label) {
    String trimmed = safeTrim(value);
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(label + " is required.");
    }
    return trimmed;
  }

  private static TodoPhaseDraft requirePhaseDraft(TodoPlanningEdit edit) {
    if (edit.phaseDraft() == null) {
      throw new IllegalArgumentException("Phase draft is required.");
    }
    return edit.phaseDraft();
  }

  private static TodoTaskDraft requireTaskDraft(TodoPlanningEdit edit) {
    if (edit.taskDraft() == null) {
      throw new IllegalArgumentException("Task draft is required.");
    }
    return edit.taskDraft();
  }

  private static int findPhaseIndex(List<TodoPhase> phases, String phaseId) {
    for (int i = 0; i < phases.size(); i++) {
      if (phases.get(i).id().equals(phaseId)) {
        return i;
      }
    }
    throw new TodoNotFoundException("Phase not found: " + phaseId);
  }

  private Instant now() {
    return Instant.now(clock);
  }

  private static String safeTitle(String candidate, String fallback, String label) {
    String title = candidate == null ? fallback : candidate.trim();
    if (title.isEmpty()) {
      throw new IllegalArgumentException(label + " is required.");
    }
    return title;
  }

  private static String descriptionOrFallback(String candidate, String fallback) {
    return candidate == null ? fallback : candidate.trim();
  }

  private static String safeTrim(String value) {
    return value == null ? "" : value.trim();
  }

  private static String newId() {
    return UUID.randomUUID().toString();
  }
}
