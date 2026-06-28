package com.shellaia.agent.spotify;

import com.social100.todero.common.routing.AgentCapabilityManifest;
import com.social100.todero.common.routing.AgentCapabilityProvider;
import com.social100.todero.common.routing.AgentCommandSchema;

import java.util.List;
import java.util.Map;

public final class SpotifyAgentCapabilities implements AgentCapabilityProvider {
  @Override
  public AgentCapabilityManifest manifest() {
    return AgentCapabilityManifest.builder()
        .contractVersion(2)
        .agentName("com.shellaia.agent.spotify")
        .intents(List.of(
            "music.play",
            "music.pause",
            "music.stop",
            "music.playlist.play",
            "music.playback.device",
            "music.playback.device.select",
            "music.plan.inspect",
            "music.plan.export"
        ))
        .commands(List.of(
            command("process",
                List.of("<goal>"),
                List.of(),
                List.of("process play rivers of babylon", "process go to song 4")),
            command("health",
                List.of(),
                List.of(),
                List.of("health")),
            command("plan-status",
                List.of("<goalId>"),
                List.of(),
                List.of("plan-status 123e4567-e89b-12d3-a456-426614174000")),
            command("plan-export",
                List.of("<goalId>"),
                List.of(),
                List.of("plan-export 123e4567-e89b-12d3-a456-426614174000")),
            command("capabilities",
                List.of(),
                List.of(),
                List.of("capabilities"))
        ))
        .followUpPolicyHints(Map.of(
            "supports_plan_export", "true",
            "supports_plan_status", "true",
            "supports_device_selection", "true"
        ))
        .routingHints(Map.of(
            "toolComponentName", "com.shellaia.spotify",
            "routingKeywords", "music,spotify,todo,plan,playlist,track,execution,verification,devices,select-device,playback device",
            "canHandleOpaqueRelay", "true",
            "domain", "music",
            "confidenceBias", "medium"
        ))
        .build();
  }

  private static AgentCommandSchema command(String name,
                                            List<String> required,
                                            List<String> optional,
                                            List<String> examples) {
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
