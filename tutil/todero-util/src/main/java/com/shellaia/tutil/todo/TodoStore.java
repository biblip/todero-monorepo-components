package com.shellaia.tutil.todo;

import java.util.List;
import java.util.Optional;

public interface TodoStore {
  TodoGoal create(TodoGoal goal);

  TodoGoal save(TodoGoal goal, long expectedVersion);

  Optional<TodoGoal> findById(String goalId);

  List<TodoGoal> listGoals();

  void delete(String goalId, long expectedVersion);
}
