package com.shellaia.tutil.todo;

import java.util.List;

public record TodoMutationResult(TodoGoal goal,
                                 List<TodoEvent> events,
                                 TodoSnapshot snapshot) {
}
