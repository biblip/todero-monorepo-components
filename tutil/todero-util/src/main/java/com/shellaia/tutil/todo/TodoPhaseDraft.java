package com.shellaia.tutil.todo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TodoPhaseDraft(String title,
                             String description,
                             List<TodoTaskDraft> tasks,
                             Map<String, String> metadata) {

  public TodoPhaseDraft {
    title = safeTrim(title);
    description = safeTrim(description);
    tasks = tasks == null ? List.of() : List.copyOf(tasks);
    metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    if (title.isEmpty()) {
      throw new IllegalArgumentException("Phase title is required.");
    }
  }

  private static String safeTrim(String value) {
    return value == null ? "" : value.trim();
  }
}
