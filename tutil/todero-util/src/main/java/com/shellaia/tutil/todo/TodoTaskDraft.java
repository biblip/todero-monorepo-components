package com.shellaia.tutil.todo;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TodoTaskDraft(String title,
                            String description,
                            Instant dueAt,
                            Integer priority,
                            List<String> dependencyTaskIds,
                            Map<String, String> metadata) {

  public TodoTaskDraft {
    title = safeTrim(title);
    description = safeTrim(description);
    priority = priority == null ? 0 : priority;
    dependencyTaskIds = dependencyTaskIds == null ? List.of() : List.copyOf(dependencyTaskIds.stream().map(TodoTaskDraft::safeTrim).filter(v -> !v.isEmpty()).toList());
    metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    if (title.isEmpty()) {
      throw new IllegalArgumentException("Task title is required.");
    }
  }

  private static String safeTrim(String value) {
    return value == null ? "" : value.trim();
  }
}
