package com.shellaia.component.taskmanager;

import com.social100.todero.common.routing.ToolCapabilityManifest;
import com.social100.todero.common.routing.ToolCapabilityProvider;
import com.social100.todero.common.routing.ToolCommandSchema;

import java.util.List;

public final class TaskManagerToolCapabilities implements ToolCapabilityProvider {
  @Override
  public ToolCapabilityManifest manifest() {
    return ToolCapabilityManifest.builder()
        .contractVersion(1)
        .componentName("com.shellaia.taskmanager")
        .toolSummary("Task lifecycle, scheduling, attempts, claims, event acknowledgement, and subscription tool.")
        .commands(List.of(
            cmd("health", "Inspect task manager health."),
            cmd("metrics", "Inspect task manager metrics."),
            cmd("create", "Create a task.", List.of("--title", "--assigned")),
            cmd("get", "Get a task by id.", List.of("--task-id")),
            cmd("list", "List tasks."),
            cmd("attempts", "List task attempts.", List.of("--task-id")),
            cmd("attempt", "Inspect a single attempt.", List.of("--task-id", "--attempt-number")),
            cmd("update", "Update a task.", List.of("--task-id")),
            cmd("evaluate", "Evaluate the scheduler queue."),
            cmd("claim", "Claim a task.", List.of("--task-id", "--agent", "--lease-seconds")),
            cmd("renew-claim", "Renew a claim.", List.of("--task-id", "--agent", "--lease-seconds")),
            cmd("start", "Start a task.", List.of("--task-id", "--agent")),
            cmd("complete", "Complete a task.", List.of("--task-id", "--agent")),
            cmd("fail", "Fail a task.", List.of("--task-id", "--agent")),
            cmd("cancel", "Cancel a task.", List.of("--task-id", "--actor")),
            cmd("snooze", "Snooze a task.", List.of("--task-id", "--schedule-at")),
            cmd("ack-event", "Acknowledge a task event.", List.of("--agent", "--event-id")),
            cmd("subscribe", "Subscribe an agent to task events.", List.of("--agent")),
            cmd("unsubscribe", "Unsubscribe an agent from task events.", List.of("--agent"))
        ))
        .build();
  }

  private static ToolCommandSchema cmd(String name, String description) {
    return cmd(name, description, List.of());
  }

  private static ToolCommandSchema cmd(String name, String description, List<String> requiredArgs) {
    return ToolCommandSchema.builder()
        .name(name)
        .description(description)
        .requiredArgs(requiredArgs)
        .optionalArgs(List.of())
        .examples(List.of())
        .build();
  }
}
