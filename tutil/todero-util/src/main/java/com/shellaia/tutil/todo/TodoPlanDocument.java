package com.shellaia.tutil.todo;

import java.time.Instant;
import java.util.List;

public record TodoPlanDocument(String schemaVersion,
                               Instant exportedAt,
                               String source,
                               List<TodoGoal> goals) {

  public TodoPlanDocument {
    schemaVersion = safeTrim(schemaVersion);
    source = safeTrim(source);
    goals = goals == null ? List.of() : List.copyOf(goals);
    if (schemaVersion.isEmpty()) {
      throw new IllegalArgumentException("Schema version is required.");
    }
    if (exportedAt == null) {
      exportedAt = Instant.now();
    }
    if (goals.isEmpty()) {
      throw new IllegalArgumentException("At least one goal is required.");
    }
  }

  private static String safeTrim(String value) {
    return value == null ? "" : value.trim();
  }
}
