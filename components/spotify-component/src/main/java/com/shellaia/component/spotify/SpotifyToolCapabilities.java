package com.shellaia.component.spotify;

import com.social100.todero.common.routing.ToolCapabilityManifest;
import com.social100.todero.common.routing.ToolCapabilityProvider;
import com.social100.todero.common.routing.ToolCommandSchema;

import java.util.List;

public final class SpotifyToolCapabilities implements ToolCapabilityProvider {
  @Override
  public ToolCapabilityManifest manifest() {
    return ToolCapabilityManifest.builder()
        .contractVersion(1)
        .componentName("com.shellaia.spotify")
        .toolSummary("Spotify playback, device, queue, search resolution, playlist, and delegated authorization tool.")
        .commands(List.of(
            cmd("events", "Manage playback event streaming and optional DJ notifications."),
            cmd("html", "Render an HTML page representing this component."),
            cmd("emit", "Emit a demo AIATP event on a selected channel (chat|html|thought)."),
            cmd("auth-status", "Inspect delegated Spotify authorization state."),
            cmd("auth-begin", "Begin delegated Spotify authorization."),
            cmd("auth-complete", "Complete delegated Spotify authorization."),
            cmd("auth-session", "Inspect a delegated authorization session."),
            cmd("auth-cancel", "Cancel a delegated authorization session."),
            cmd("move", "Seek within playback."),
            cmd("mute", "Mute playback."),
            cmd("pause", "Pause playback."),
            cmd("play", "Play a track, search query, or context."),
            cmd("skip", "Skip to the next track."),
            cmd("status", "Inspect current playback state."),
            cmd("stop", "Stop playback."),
            cmd("volume", "Set volume."),
            cmd("volume-down", "Decrease volume."),
            cmd("volume-up", "Increase volume."),
            cmd("playlist-remove", "Remove current playlist item."),
            cmd("playlist-next", "Advance within the active playlist."),
            cmd("devices", "List available devices."),
            cmd("metrics", "Inspect playback metrics."),
            cmd("select-device", "Select the active playback device."),
            cmd("queue-add", "Add an item to the queue."),
            cmd("queue", "Inspect current queue."),
            cmd("previous", "Go to previous track."),
            cmd("resolve-track", "Resolve a search query to a concrete Spotify track."),
            cmd("recently-played", "List recently played tracks."),
            cmd("top-tracks", "List top tracks."),
            cmd("top-artists", "List top artists."),
            cmd("shuffle", "Control shuffle mode."),
            cmd("repeat", "Control repeat mode."),
            cmd("like", "Save the current track."),
            cmd("playlists", "List playlists."),
            cmd("playlist-list", "List tracks in a playlist."),
            cmd("playlist-add", "Add tracks to a playlist."),
            cmd("playlist-add-current", "Add current track to a playlist."),
            cmd("playlist-create", "Create a playlist."),
            cmd("playlist-reorder", "Reorder playlist tracks."),
            cmd("playlist-remove-pos", "Remove playlist track by position."),
            cmd("playlist-play", "Play a playlist."),
            cmd("play-context", "Play a context URI.")
        ))
        .build();
  }

  private static ToolCommandSchema cmd(String name, String description) {
    return ToolCommandSchema.builder()
        .name(name)
        .description(description)
        .requiredArgs(List.of())
        .optionalArgs(List.of())
        .examples(List.of())
        .build();
  }
}
