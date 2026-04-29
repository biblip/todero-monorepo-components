package com.shellaia.agent.term;

import com.social100.todero.common.routing.AgentCapabilityManifest;
import com.social100.todero.common.routing.AgentCapabilityProvider;
import com.social100.todero.common.routing.AgentCommandSchema;

import java.util.List;
import java.util.Map;

public final class TermAgentCapabilities implements AgentCapabilityProvider {
  @Override
  public AgentCapabilityManifest manifest() {
    return AgentCapabilityManifest.builder()
        .contractVersion(2)
        .agentName("com.shellaia.agent.term")
        .intents(List.of(
            "terminal.open",
            "terminal.session.list",
            "terminal.write",
            "terminal.ctrlc",
            "terminal.screen",
            "terminal.close"
        ))
        .commands(List.of(
            command("html", List.of()),
            command("sessions", List.of()),
            command("open", List.of("<name>")),
            command("write", List.of("<idOrName>", "<dataB64>")),
            command("ctrlc", List.of("<idOrName>")),
            command("events", List.of("ON|OFF", "<idOrName>")),
            command("screen_text", List.of("<idOrName>")),
            command("screen_diff", List.of("<idOrName>", "<sinceFrameId>")),
            command("screen_scrollback", List.of("<idOrName>")),
            command("resize", List.of("<idOrName>", "<cols>", "<rows>")),
            command("close", List.of("<idOrName>")),
            command("kill", List.of("<idOrName>")),
            command("process", List.of("<prompt>")),
            command("capabilities", List.of())
        ))
        .routingHints(Map.of(
            "toolComponentName", "com.shellaia.term",
            "routingKeywords", "terminal,shell,pty,zsh,bash,screen,ctrl-c,conpty",
            "canHandleOpaqueRelay", "true",
            "domain", "terminal",
            "confidenceBias", "high"
        ))
        .build();
  }

  private static AgentCommandSchema command(String name, List<String> required) {
    return AgentCommandSchema.builder()
        .name(name)
        .requiredArgs(required)
        .optionalArgs(List.of())
        .build();
  }
}
