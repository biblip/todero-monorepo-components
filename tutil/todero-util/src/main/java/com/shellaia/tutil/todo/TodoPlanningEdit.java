package com.shellaia.tutil.todo;

import java.time.Instant;
import java.util.List;

public record TodoPlanningEdit(TodoPlanningEditType type,
                               String phaseId,
                               String taskId,
                               Integer index,
                               Integer toIndex,
                               TodoPhaseDraft phaseDraft,
                               TodoTaskDraft taskDraft,
                               String title,
                               String description,
                               Instant dueAt,
                               Integer priority,
                               List<String> dependencyTaskIds) {

  public static TodoPlanningEdit insertPhase(int index, TodoPhaseDraft draft) {
    return new TodoPlanningEdit(TodoPlanningEditType.INSERT_PHASE, "", "", index, null, draft, null, null, null, null, null, null);
  }

  public static TodoPlanningEdit reorderPhase(int fromIndex, int toIndex) {
    return new TodoPlanningEdit(TodoPlanningEditType.REORDER_PHASE, "", "", fromIndex, toIndex, null, null, null, null, null, null, null);
  }

  public static TodoPlanningEdit deletePhase(String phaseId) {
    return new TodoPlanningEdit(TodoPlanningEditType.DELETE_PHASE, phaseId, "", null, null, null, null, null, null, null, null, null);
  }

  public static TodoPlanningEdit updatePhase(String phaseId, String title, String description) {
    return new TodoPlanningEdit(TodoPlanningEditType.UPDATE_PHASE, phaseId, "", null, null, null, null, title, description, null, null, null);
  }

  public static TodoPlanningEdit insertTask(String phaseId, int index, TodoTaskDraft draft) {
    return new TodoPlanningEdit(TodoPlanningEditType.INSERT_TASK, phaseId, "", index, null, null, draft, null, null, null, null, null);
  }

  public static TodoPlanningEdit reorderTask(String phaseId, int fromIndex, int toIndex) {
    return new TodoPlanningEdit(TodoPlanningEditType.REORDER_TASK, phaseId, "", fromIndex, toIndex, null, null, null, null, null, null, null);
  }

  public static TodoPlanningEdit deleteTask(String phaseId, String taskId) {
    return new TodoPlanningEdit(TodoPlanningEditType.DELETE_TASK, phaseId, taskId, null, null, null, null, null, null, null, null, null);
  }

  public static TodoPlanningEdit updateTask(String phaseId,
                                            String taskId,
                                            String title,
                                            String description,
                                            Instant dueAt,
                                            Integer priority,
                                            List<String> dependencyTaskIds) {
    return new TodoPlanningEdit(TodoPlanningEditType.UPDATE_TASK, phaseId, taskId, null, null, null, null, title, description, dueAt, priority, dependencyTaskIds);
  }
}
