package com.shellaia.agent.router;

import com.social100.todero.common.routing.AgentCapabilityManifest;
import com.social100.todero.common.routing.AgentCapabilityProvider;
import com.social100.todero.common.routing.AgentCommandSchema;

import java.util.List;
import java.util.Map;

public final class RouterAgentCapabilities implements AgentCapabilityProvider {
  @Override
  public AgentCapabilityManifest manifest() {
    return AgentCapabilityManifest.builder()
        .contractVersion(2)
        .agentName("com.shellaia.agent.router")
        .intents(List.of("route.process"))
        .commands(List.of(
        AgentCommandSchema.builder()
            .name("process")
            .requiredArgs(List.of("<prompt>"))
            .optionalArgs(List.of())
            .build()
        ))
        .followUpPolicyHints(Map.of(
            "sticky_enabled", "true",
            "fallback", "heuristic_then_llm"
        ))
        .routingHints(Map.of(
            "skillSummary", "Routes user requests to the best available specialized internal agent based on published skills and preserves delegated responses.",
            "oneLineSkillSummary", "Routes requests to the best available internal agent.",
            "domain", "routing",
            "discoversHiddenAgents", "true"
        ))
        .build();
  }
}
