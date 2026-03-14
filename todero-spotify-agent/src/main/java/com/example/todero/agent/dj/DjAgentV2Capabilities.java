package com.example.todero.agent.dj;

import com.social100.todero.common.routing.AgentCapabilityManifest;
import com.social100.todero.common.routing.AgentCapabilityProvider;
import com.social100.todero.common.routing.AgentCommandSchema;

import java.util.List;
import java.util.Map;

public final class DjAgentV2Capabilities implements AgentCapabilityProvider {
  @Override
  public AgentCapabilityManifest manifest() {
    return AgentCapabilityManifest.builder()
        .contractVersion(2)
        .agentName("com.shellaia.verbatim.agent.dj.v2")
        .intents(List.of(
            "music.play", "music.pause", "music.stop", "music.volume",
            "music.playlist.list", "music.playlist.play", "music.playlist.add",
            "music.recommend", "music.events"
        ))
        .commands(List.of(
            command("play", List.of("<query|uri>")),
            command("pause", List.of()),
            command("stop", List.of()),
            command("status", List.of()),
            command("volume", List.of("<0-100>")),
            command("skip", List.of()),
            command("previous", List.of()),
            command("playlists", List.of()),
            command("playlist-play", List.of("--id")),
            command("playlist-add", List.of("--id", "--track")),
            command("playlist-add-current", List.of("--id")),
            command("suggest", List.of("<theme>")),
            command("recommend", List.of("<theme>")),
            command("events", List.of("ON|OFF")),
            command("capabilities", List.of()),
            command("react", List.of("<event_payload>"))
        ))
        .followUpPolicyHints(Map.of(
            "supports_latest", "true",
            "slot_track_uri", "last_track_uri",
            "slot_playlist_id", "last_playlist_id"
        ))
        .routingHints(Map.of(
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
