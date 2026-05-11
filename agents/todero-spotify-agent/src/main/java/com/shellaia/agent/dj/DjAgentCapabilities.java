package com.shellaia.agent.dj;

import com.social100.todero.common.routing.AgentCapabilityManifest;
import com.social100.todero.common.routing.AgentCapabilityProvider;
import com.social100.todero.common.routing.AgentCommandSchema;

import java.util.List;
import java.util.Map;

public final class DjAgentCapabilities implements AgentCapabilityProvider {
  @Override
  public AgentCapabilityManifest manifest() {
    return AgentCapabilityManifest.builder()
        .contractVersion(2)
        .agentName("com.shellaia.agent.dj")
        .intents(List.of(
            "music.play", "music.pause", "music.stop", "music.volume",
            "music.playlist.list", "music.playlist.play", "music.playlist.add",
            "music.recommend", "music.events"
        ))
        .commands(List.of(
            command("process", List.of("<goal>")),
            command("capabilities", List.of())
        ))
        .followUpPolicyHints(Map.of(
            "supports_latest", "true",
            "slot_track_uri", "last_track_uri",
            "slot_playlist_id", "last_playlist_id"
        ))
        .routingHints(Map.of(
            "toolComponentName", "com.shellaia.spotify",
            "routingKeywords", "music,spotify,dj,playback,playlist,songs,recommendations,audio",
            "canHandleOpaqueRelay", "true",
            "domain", "music",
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
