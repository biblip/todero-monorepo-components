package com.shellaia.tutil.todo;

import java.util.LinkedHashSet;
import java.util.Set;

public record TodoTaskQuery(String goalId,
                            String ownerId,
                            Set<TodoStatus> statuses,
                            String text,
                            boolean includeCompleted,
                            boolean includeCanceled) {

  public TodoTaskQuery {
    goalId = goalId == null ? "" : goalId.trim();
    ownerId = ownerId == null ? "" : ownerId.trim();
    statuses = statuses == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(statuses));
    text = text == null ? "" : text.trim();
  }
}
