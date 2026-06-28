package com.shellaia.tutil.todo;

import java.time.Instant;

public record TodoEvent(String goalId,
                        String phaseId,
                        String taskId,
                        TodoEventType type,
                        Instant at,
                        String summary) {
}
