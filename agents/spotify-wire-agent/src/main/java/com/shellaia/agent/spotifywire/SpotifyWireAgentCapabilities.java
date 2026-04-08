package com.shellaia.agent.spotifywire;

import com.social100.todero.common.routing.AgentCapabilityManifest;
import com.social100.todero.common.routing.AgentCapabilityProvider;
import com.social100.todero.common.routing.AgentCommandSchema;

import java.util.List;
import java.util.Map;

/**
 * Minimal capability manifest so router agents can reason about this agent.
 */
public final class SpotifyWireAgentCapabilities implements AgentCapabilityProvider {
  @Override
  public AgentCapabilityManifest manifest() {
    return AgentCapabilityManifest.builder()
        .contractVersion(2)
        .agentName("com.shellaia.agent.spotify.wire")
        .intents(List.of(
            "music.play",
            "music.volume",
            "music.status",
            "auth.spotify"
        ))
        .commands(List.of(
            command("process", List.of("<text>")),
            command("play", List.of("<query|spotify:track:...>")),
            command("volume", List.of("<0..150>")),
            command("status", List.of("[all]")),
            command("auth-begin", List.of()),
            command("auth-complete", List.of()),
            command("capabilities", List.of())
        ))
        .routingHints(Map.of(
            "toolComponentName", "com.shellaia.spotify.wire",
            "routingKeywords", "spotify,music,play,volume,status,auth",
            "domain", "music",
            "confidenceBias", "low"
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

