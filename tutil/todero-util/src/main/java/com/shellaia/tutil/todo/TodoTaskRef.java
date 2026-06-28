package com.shellaia.tutil.todo;

public record TodoTaskRef(String goalId,
                          String ownerId,
                          String goalTitle,
                          String phaseId,
                          String phaseTitle,
                          int phasePosition,
                          String taskId,
                          String taskTitle,
                          int taskPosition,
                          TodoStatus status,
                          Integer priority,
                          java.time.Instant dueAt,
                          java.util.List<String> dependencyTaskIds,
                          String claimedBy,
                          java.time.Instant claimedAt,
                          java.time.Instant claimExpiresAt) {
}
