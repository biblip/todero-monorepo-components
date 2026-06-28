package com.shellaia.tutil.todo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TodoGoalDraft(String ownerId,
                            String title,
                            String description,
                            List<TodoPhaseDraft> phases,
                            Map<String, String> metadata) {

  public TodoGoalDraft {
    ownerId = safeTrim(ownerId);
    title = safeTrim(title);
    description = safeTrim(description);
    phases = phases == null ? List.of() : List.copyOf(phases);
    metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    if (ownerId.isEmpty()) {
      throw new IllegalArgumentException("Goal owner is required.");
    }
    if (title.isEmpty()) {
      throw new IllegalArgumentException("Goal title is required.");
    }
  }

  private static String safeTrim(String value) {
    return value == null ? "" : value.trim();
  }
}
