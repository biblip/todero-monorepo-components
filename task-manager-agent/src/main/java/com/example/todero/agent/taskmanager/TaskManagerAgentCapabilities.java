package com.example.todero.agent.taskmanager;

import com.social100.todero.common.routing.AgentCapabilityManifest;
import com.social100.todero.common.routing.AgentCapabilityProvider;
import com.social100.todero.common.routing.AgentCommandSchema;

import java.util.List;
import java.util.Map;

public final class TaskManagerAgentCapabilities implements AgentCapabilityProvider {
  @Override
  public AgentCapabilityManifest manifest() {
    return AgentCapabilityManifest.builder()
        .contractVersion(2)
        .agentName("com.shellaia.verbatim.agent.task.manager")
        .intents(List.of(
            "task.create", "task.get", "task.list", "task.update",
            "task.start", "task.complete", "task.fail", "task.cancel",
            "task.snooze", "task.claim", "task.subscribe", "task.metrics"
        ))
        .commands(List.of(
            command("create", List.of("--title", "--assigned"), List.of(
                "--description", "--priority", "--tags", "--scheduled-for", "--not-before",
                "--deadline", "--window-start", "--window-end", "--max-attempts"
            )),
            command("get", List.of("--task-id"), List.of()),
            command("list", List.of(), List.of("--status", "--assigned", "--limit", "--offset", "--sort")),
            command("update", List.of("--task-id"), List.of(
                "--title", "--description", "--assigned", "--priority", "--tags",
                "--scheduled-for", "--not-before", "--deadline", "--window-start", "--window-end",
                "--max-attempts", "--expected-version"
            )),
            command("start", List.of("--task-id", "--agent"), List.of()),
            command("complete", List.of("--task-id", "--agent"), List.of("--note")),
            command("fail", List.of("--task-id", "--agent"), List.of("--error-code", "--message", "--retry", "--note")),
            command("cancel", List.of("--task-id", "--actor"), List.of("--note")),
            command("snooze", List.of("--task-id", "--schedule-at"), List.of("--actor")),
            command("claim", List.of("--task-id", "--agent", "--lease-seconds"), List.of()),
            command("renew-claim", List.of("--task-id", "--agent", "--lease-seconds"), List.of()),
            command("attempt", List.of("--task-id", "--attempt-number"), List.of()),
            command("attempts", List.of("--task-id"), List.of("--limit", "--offset")),
            command("subscribe", List.of("--agent"), List.of("--limit", "--auto-ack", "--poll-ms")),
            command("unsubscribe", List.of("--agent"), List.of()),
            command("ack-event", List.of("--agent", "--event-id"), List.of()),
            command("capabilities", List.of(), List.of()),
            command("evaluate", List.of(), List.of()),
            command("health", List.of(), List.of()),
            command("metrics", List.of(), List.of())
        ))
        .followUpPolicyHints(Map.of(
            "supports_latest", "true",
            "slot_task_id", "last_task_id",
            "slot_agent", "last_task_agent"
        ))
        .routingHints(Map.of(
            "domain", "task",
            "confidenceBias", "high"
        ))
        .build();
  }

  private static AgentCommandSchema command(String name, List<String> required, List<String> optional) {
    return AgentCommandSchema.builder()
        .name(name)
        .requiredArgs(required)
        .optionalArgs(optional)
        .build();
  }
}
