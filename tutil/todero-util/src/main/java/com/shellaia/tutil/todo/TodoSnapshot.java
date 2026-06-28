package com.shellaia.tutil.todo;

import java.time.Instant;

public record TodoSnapshot(String goalId,
                           String ownerId,
                           TodoStatus status,
                           long version,
                           TodoProgress progress,
                           TodoTaskRef nextTask,
                           Instant updatedAt) {
}
