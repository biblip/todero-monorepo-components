package com.shellaia.tutil.todo;

public record TodoProgress(int phaseCount,
                           int completedPhases,
                           int taskCount,
                           int completedTasks,
                           int readyTasks,
                           int inProgressTasks,
                           int blockedTasks,
                           int failedTasks,
                           int canceledTasks) {
}
