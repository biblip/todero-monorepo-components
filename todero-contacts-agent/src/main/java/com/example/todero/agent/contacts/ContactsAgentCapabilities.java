package com.example.todero.agent.contacts;

import com.social100.todero.common.routing.AgentCapabilityManifest;
import com.social100.todero.common.routing.AgentCapabilityProvider;
import com.social100.todero.common.routing.AgentCommandSchema;

import java.util.List;
import java.util.Map;

public final class ContactsAgentCapabilities implements AgentCapabilityProvider {
  @Override
  public AgentCapabilityManifest manifest() {
    return AgentCapabilityManifest.builder()
        .contractVersion(2)
        .agentName("com.shellaia.verbatim.agent.contacts")
        .intents(List.of(
            "contacts.add",
            "contacts.list",
            "contacts.find",
            "contacts.group",
            "contacts.remove"
        ))
        .commands(List.of(
            AgentCommandSchema.builder().name("process").requiredArgs(List.of("<prompt>")).optionalArgs(List.of()).build(),
            AgentCommandSchema.builder().name("capabilities").requiredArgs(List.of()).optionalArgs(List.of()).build()
        ))
        .followUpPolicyHints(Map.of(
            "component", "com.shellaia.verbatim.component.contacts",
            "component_commands", "add,list,find,group,remove"
        ))
        .routingHints(Map.of(
            "domain", "contacts",
            "confidenceBias", "medium"
        ))
        .build();
  }
}
