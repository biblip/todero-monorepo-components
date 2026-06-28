package com.shellaia.tutil.todo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryTodoStore implements TodoStore {
  private final Map<String, TodoGoal> goals = new LinkedHashMap<>();

  @Override
  public synchronized TodoGoal create(TodoGoal goal) {
    if (goals.containsKey(goal.id())) {
      throw new TodoConflictException("Goal already exists: " + goal.id());
    }
    goals.put(goal.id(), goal);
    return goal;
  }

  @Override
  public synchronized TodoGoal save(TodoGoal goal, long expectedVersion) {
    TodoGoal current = goals.get(goal.id());
    if (current == null) {
      throw new TodoNotFoundException("Goal not found: " + goal.id());
    }
    if (current.version() != expectedVersion) {
      throw new TodoConflictException("Version mismatch for goal " + goal.id() + ": expected " + expectedVersion + ", actual " + current.version());
    }
    goals.put(goal.id(), goal);
    return goal;
  }

  @Override
  public synchronized Optional<TodoGoal> findById(String goalId) {
    return Optional.ofNullable(goals.get(goalId));
  }

  @Override
  public synchronized List<TodoGoal> listGoals() {
    return new ArrayList<>(goals.values());
  }

  @Override
  public synchronized void delete(String goalId, long expectedVersion) {
    TodoGoal current = goals.get(goalId);
    if (current == null) {
      throw new TodoNotFoundException("Goal not found: " + goalId);
    }
    if (current.version() != expectedVersion) {
      throw new TodoConflictException("Version mismatch for goal " + goalId + ": expected " + expectedVersion + ", actual " + current.version());
    }
    goals.remove(goalId);
  }
}
