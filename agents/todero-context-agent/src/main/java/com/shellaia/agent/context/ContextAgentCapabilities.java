package com.shellaia.agent.context;

import com.social100.todero.common.routing.AgentCapabilityManifest;
import com.social100.todero.common.routing.AgentCapabilityProvider;
import com.social100.todero.common.routing.AgentCommandSchema;

import java.util.List;
import java.util.Map;

public final class ContextAgentCapabilities implements AgentCapabilityProvider {
  @Override
  public AgentCapabilityManifest manifest() {
    return AgentCapabilityManifest.builder()
        .contractVersion(2)
        .agentName("com.shellaia.agent.context")
        .intents(List.of(
            "conversation.context",
            "conversation.subject",
            "conversation.memory"
        ))
        .commands(List.of(
            command("html",
                List.of(),
                List.of(),
                List.of("html")),
            command("process",
                List.of("<message>"),
                List.of(),
                List.of(
                    "process I want to keep track of this subject.",
                    "process {\"subjectId\":\"subject-alpha\",\"message\":\"Open a branch for this.\"}"
                )),
            command("durables",
                List.of(),
                List.of("[limit|json|view|days]"),
                List.of(
                    "durables",
                    "durables 20",
                    "durables {\"limit\":20,\"format\":\"json\"}",
                    "durables {\"limit\":20,\"format\":\"json\",\"view\":\"finalized\",\"days\":30}"
                )),
            command("durable-set",
                List.of("<recordId|phrase>", "<status>"),
                List.of(),
                List.of(
                    "durable-set the Jira ticket done",
                    "durable-set durable-123 done",
                    "durable-set durable-123 canceled"
                ))
        ))
        .routingHints(Map.of(
            "domain", "conversation",
            "toolComponentName", "com.shellaia.agent.context",
            "routingKeywords", "context,subject,conversation,memory,branch,general mode,subject mode",
            "canHandleOpaqueRelay", "true",
            "confidenceBias", "high"
        ))
        .build();
  }

  private static AgentCommandSchema command(String name, List<String> required, List<String> optional, List<String> examples) {
    return AgentCommandSchema.builder()
        .name(name)
        .requiredArgs(required)
        .optionalArgs(optional == null ? List.of() : optional)
        .argTypes(Map.of())
        .defaults(Map.of())
        .examples(examples == null ? List.of() : examples)
        .build();
  }
}
