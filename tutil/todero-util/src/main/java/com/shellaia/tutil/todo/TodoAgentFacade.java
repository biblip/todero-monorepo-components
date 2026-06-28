package com.shellaia.tutil.todo;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class TodoAgentFacade {
  private final TodoManager manager;
  private final TodoJsonCodec codec;

  public TodoAgentFacade(TodoManager manager) {
    this(manager, new TodoJsonCodec());
  }

  public TodoAgentFacade(TodoManager manager, TodoJsonCodec codec) {
    this.manager = Objects.requireNonNull(manager, "manager");
    this.codec = Objects.requireNonNull(codec, "codec");
  }

  public TodoGoal plan(TodoGoalDraft draft) {
    return manager.createGoal(draft);
  }

  public TodoSnapshot snapshot(String goalId) {
    return manager.snapshot(goalId);
  }

  public TodoPlanDocument exportGoalPlan(String goalId, String source) {
    TodoGoal goal = manager.findGoal(goalId).orElseThrow(() -> new TodoNotFoundException("Goal not found: " + goalId));
    return codec.documentOf(goal, source);
  }

  public TodoPlanDocument exportOpenGoalPlans(String ownerId, String source) {
    return codec.documentOf(openGoals(ownerId), source);
  }

  public String exportGoalPlanJson(String goalId, String source) {
    return codec.exportDocument(exportGoalPlan(goalId, source));
  }

  public String exportOpenGoalPlansJson(String ownerId, String source) {
    return codec.exportDocument(exportOpenGoalPlans(ownerId, source));
  }

  public TodoPlanDocument importPlanDocument(String json) {
    return codec.importDocument(json);
  }

  public TodoGoal importSingleGoal(String json) {
    return codec.importSingleGoal(json);
  }

  public String todoPlanJsonSchema() {
    return codec.loadJsonSchema();
  }

  public List<TodoGoal> openGoals(String ownerId) {
    return manager.queryGoals(new TodoGoalQuery(ownerId, java.util.Set.of(), "", true));
  }

  public Optional<TodoTaskRef> nextTask(String ownerId) {
    return openGoals(ownerId).stream()
        .map(TodoGoal::id)
        .map(goalId -> manager.nextReadyTask(goalId, ownerId))
        .flatMap(Optional::stream)
        .findFirst();
  }

  public Optional<TodoGoal> startNextTask(String ownerId, String note) {
    return claimNextTask(ownerId).map(task -> manager.updateTaskStatus(task.goalId(), task.phaseId(), task.taskId(), TodoStatus.IN_PROGRESS, note));
  }

  public Optional<TodoTaskRef> claimNextTask(String ownerId) {
    return openGoals(ownerId).stream()
        .map(TodoGoal::id)
        .map(goalId -> manager.claimNextReadyTask(goalId, ownerId))
        .flatMap(Optional::stream)
        .findFirst();
  }

  public Optional<TodoTaskRef> claimNextTask(String ownerId, Duration leaseDuration) {
    return openGoals(ownerId).stream()
        .map(TodoGoal::id)
        .map(goalId -> manager.claimNextReadyTask(goalId, ownerId, leaseDuration))
        .flatMap(Optional::stream)
        .findFirst();
  }

  public TodoGoal completeTask(String goalId, String phaseId, String taskId, String outcome) {
    return manager.updateTaskStatus(goalId, phaseId, taskId, TodoStatus.COMPLETED, outcome);
  }

  public TodoGoal blockTask(String goalId, String phaseId, String taskId, String outcome) {
    return manager.updateTaskStatus(goalId, phaseId, taskId, TodoStatus.BLOCKED, outcome);
  }

  public TodoGoal failTask(String goalId, String phaseId, String taskId, String outcome) {
    return manager.updateTaskStatus(goalId, phaseId, taskId, TodoStatus.FAILED, outcome);
  }

  public TodoGoal releaseTask(String goalId, String phaseId, String taskId, String ownerId) {
    return manager.releaseTaskClaim(goalId, phaseId, taskId, ownerId);
  }

  public TodoGoal renewTaskLease(String goalId, String phaseId, String taskId, String ownerId, Duration leaseDuration) {
    return manager.renewTaskClaim(goalId, phaseId, taskId, ownerId, leaseDuration);
  }

  public List<TodoGoal> applyTaskStatuses(String goalId, List<TodoTaskStatusUpdate> updates) {
    return manager.updateTaskStatuses(goalId, updates);
  }

  public TodoMutationResult applyTaskStatusesDetailed(String goalId, List<TodoTaskStatusUpdate> updates) {
    return manager.updateTaskStatusesDetailed(goalId, updates);
  }

  public TodoMutationResult applyPlanningEdits(String goalId, List<TodoPlanningEdit> edits) {
    return manager.applyPlanningEdits(goalId, edits);
  }

  public TodoGoal insertFollowupTask(String goalId, String phaseId, String anchorTaskId, TodoTaskDraft draft) {
    TodoPhase phase = manager.findPhase(goalId, phaseId)
        .orElseThrow(() -> new TodoNotFoundException("Phase not found: " + phaseId));
    int anchorIndex = -1;
    for (int i = 0; i < phase.tasks().size(); i++) {
      if (phase.tasks().get(i).id().equals(anchorTaskId)) {
        anchorIndex = i;
        break;
      }
    }
    if (anchorIndex < 0) {
      throw new TodoNotFoundException("Task not found: " + anchorTaskId);
    }
    return manager.insertTask(goalId, phaseId, draft, anchorIndex + 1);
  }
}
