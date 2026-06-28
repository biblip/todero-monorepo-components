package com.shellaia.component.spotify;

import com.social100.todero.common.routing.ToolCapabilityManifest;
import com.social100.todero.common.routing.ToolCommandSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SpotifyToolCapabilitiesTest {
  @Test
  void manifestIncludesFullSpotifyCommandSchemas() {
    ToolCapabilityManifest manifest = new SpotifyToolCapabilities().manifest();

    assertEquals(1, manifest.getContractVersion());
    assertEquals("com.shellaia.spotify", manifest.getComponentName());
    assertEquals("Spotify playback, device, queue, search resolution, playlist, and delegated authorization tool.", manifest.getToolSummary());

    Map<String, ToolCommandSchema> commands = manifest.getCommands().stream()
        .collect(Collectors.toMap(ToolCommandSchema::getName, c -> c));

    assertEquals(43, commands.size());
    assertCommand(commands.get("events"), "events", List.of("ON|OFF"),
        List.of("intervalMs", "notify-agent=true|false", "notify-min-ms=<ms>", "output=typed|legacy", "filter=all|track|playback|device|context"),
        List.of("events ON", "events OFF 1500 notify-agent=true output=typed filter=track"));
    assertCommand(commands.get("html"), "html", List.of(), List.of(), List.of("html"));
    assertCommand(commands.get("emit"), "emit", List.of(), List.of("channel=chat|html|thought", "message=<text>"),
        List.of("emit?channel=chat&message=hello", "emit?channel=html&message=<b>hello</b>"));
    assertCommand(commands.get("auth-status"), "auth-status", List.of(), List.of(), List.of("auth-status"));
    assertCommand(commands.get("auth-begin"), "auth-begin", List.of(), List.of("redirect-profile=app|console", "redirect-uri=<uri>", "owner=<binding>"),
        List.of("auth-begin", "auth-begin redirect-profile=console", "auth-begin owner=owner-a"));
    assertCommand(commands.get("auth-complete"), "auth-complete", List.of("state=<state>", "code=<code>"), List.of("session-id=<id>", "secureEnvelope=<json>"),
        List.of("auth-complete state=<state> code=<code>", "auth-complete session-id=<id> state=<state> code=<code>"));
    assertCommand(commands.get("auth-session"), "auth-session", List.of(), List.of("session-id=<id>"), List.of("auth-session", "auth-session session-id=abc123"));
    assertCommand(commands.get("auth-cancel"), "auth-cancel", List.of(), List.of("session-id=<id>"), List.of("auth-cancel", "auth-cancel session-id=abc123"));
    assertCommand(commands.get("move"), "move", List.of("<position>"), List.of(), List.of("move 01:23", "move 90"));
    assertCommand(commands.get("mute"), "mute", List.of(), List.of(), List.of("mute"));
    assertCommand(commands.get("pause"), "pause", List.of(), List.of(), List.of("pause"));
    assertCommand(commands.get("play"), "play", List.of(), List.of("[media]"), List.of("play", "play enya caribbean blue"));
    assertCommand(commands.get("skip"), "skip", List.of("<+/-seconds>"), List.of(), List.of("skip 30", "skip -15"));
    assertCommand(commands.get("status"), "status", List.of(), List.of("[all]"), List.of("status", "status all"));
    assertCommand(commands.get("capabilities"), "capabilities", List.of(), List.of(), List.of("capabilities"));
    assertCommand(commands.get("stop"), "stop", List.of(), List.of(), List.of("stop"));
    assertCommand(commands.get("volume"), "volume", List.of("<level>"), List.of(), List.of("volume 75"));
    assertCommand(commands.get("volume-down"), "volume-down", List.of(), List.of(), List.of("volume-down"));
    assertCommand(commands.get("volume-up"), "volume-up", List.of(), List.of(), List.of("volume-up"));
    assertCommand(commands.get("playlist-remove"), "playlist-remove", List.of(), List.of(), List.of("playlist-remove"));
    assertCommand(commands.get("playlist-next"), "playlist-next", List.of(), List.of(), List.of("playlist-next"));
    assertCommand(commands.get("devices"), "devices", List.of(), List.of(), List.of("devices"));
    assertCommand(commands.get("metrics"), "metrics", List.of(), List.of(), List.of("metrics"));
    assertCommand(commands.get("select-device"), "select-device", List.of("<deviceId>"), List.of(), List.of("select-device device-123"));
    assertCommand(commands.get("queue-add"), "queue-add", List.of("<spotify:track|episode:uri>"), List.of(), List.of("queue-add spotify:track:123", "queue-add spotify:episode:456"));
    assertCommand(commands.get("queue"), "queue", List.of(), List.of(), List.of("queue"));
    assertCommand(commands.get("previous"), "previous", List.of(), List.of(), List.of("previous"));
    assertCommand(commands.get("resolve-track"), "resolve-track", List.of("<query>"), List.of(), List.of("resolve-track enya caribbean blue"));
    assertCommand(commands.get("recently-played"), "recently-played", List.of(), List.of("[limit<=50]"), List.of("recently-played", "recently-played 20"));
    assertCommand(commands.get("top-tracks"), "top-tracks", List.of(), List.of("[limit<=50]", "[short_term|medium_term|long_term]"), List.of("top-tracks", "top-tracks 10 medium_term"));
    assertCommand(commands.get("top-artists"), "top-artists", List.of(), List.of("[limit<=50]", "[short_term|medium_term|long_term]"), List.of("top-artists", "top-artists 10 short_term"));
    assertCommand(commands.get("shuffle"), "shuffle", List.of("<on|off>"), List.of(), List.of("shuffle on", "shuffle off"));
    assertCommand(commands.get("repeat"), "repeat", List.of("<off|track|context>"), List.of(), List.of("repeat off", "repeat track"));
    assertCommand(commands.get("like"), "like", List.of(), List.of(), List.of("like"));
    assertCommand(commands.get("playlists"), "playlists", List.of(), List.of("[limit<=50]", "[offset>=0]"), List.of("playlists", "playlists 20 0"));
    assertCommand(commands.get("playlist-list"), "playlist-list", List.of("<playlistId>"), List.of("[limit]"), List.of("playlist-list abc123", "playlist-list abc123 20"));
    assertCommand(commands.get("playlist-add"), "playlist-add", List.of("<playlistId>", "<trackUri>"), List.of("[trackUri ...]"), List.of("playlist-add abc123 spotify:track:1", "playlist-add abc123 spotify:track:1 spotify:track:2"));
    assertCommand(commands.get("playlist-add-current"), "playlist-add-current", List.of("<songTitle>"), List.of(), List.of("playlist-add-current caribbean blue"));
    assertCommand(commands.get("playlist-create"), "playlist-create", List.of("<name>"), List.of("[public=true|false]", "[description=<text>]"), List.of("playlist-create Road Trip", "playlist-create Road Trip public=true description=Driving"));
    assertCommand(commands.get("playlist-reorder"), "playlist-reorder", List.of("<playlistId>", "<rangeStart>", "<insertBefore>"), List.of("[rangeLength<=100]"), List.of("playlist-reorder abc123 0 5", "playlist-reorder abc123 0 5 3"));
    assertCommand(commands.get("playlist-remove-pos"), "playlist-remove-pos", List.of("<playlistId>", "<position>"), List.of(), List.of("playlist-remove-pos abc123 2"));
    assertCommand(commands.get("playlist-play"), "playlist-play", List.of("<playlistId|spotify:playlist:uri>"), List.of("[offset]"), List.of("playlist-play abc123", "playlist-play spotify:playlist:abc123 3"));
    assertCommand(commands.get("play-context"), "play-context", List.of("<spotify:album|artist|playlist:uri>"), List.of("[offset]"), List.of("play-context spotify:playlist:abc123", "play-context spotify:album:abc123 2"));
  }

  private static void assertCommand(ToolCommandSchema command,
                                    String name,
                                    List<String> requiredArgs,
                                    List<String> optionalArgs,
                                    List<String> examples) {
    assertNotNull(command, "Missing command: " + name);
    assertEquals(name, command.getName(), "name mismatch for " + name);
    assertEquals(requiredArgs, command.getRequiredArgs(), "requiredArgs mismatch for " + name);
    assertEquals(optionalArgs, command.getOptionalArgs(), "optionalArgs mismatch for " + name);
    assertIterableEquals(examples, command.getExamples(), "examples mismatch for " + name);
  }
}
