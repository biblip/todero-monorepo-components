package com.shellaia.tutil.todo;

import java.util.LinkedHashSet;
import java.util.Set;

public record TodoGoalQuery(String ownerId,
                            Set<TodoStatus> statuses,
                            String text,
                            boolean openOnly) {

  public TodoGoalQuery {
    ownerId = ownerId == null ? "" : ownerId.trim();
    statuses = statuses == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(statuses));
    text = text == null ? "" : text.trim();
  }
}
