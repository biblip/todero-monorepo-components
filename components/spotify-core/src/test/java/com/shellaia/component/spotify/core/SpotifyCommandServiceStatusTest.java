package com.shellaia.component.spotify.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class SpotifyCommandServiceStatusTest {

  @Test
  void statusAllIncludesDeterministicPlaybackContextFields() throws Exception {
    SpotifyCommandService.PlaybackSnapshot snapshot = new SpotifyCommandService.PlaybackSnapshot(
        true,
        "spotify:track:0uR8U8m4y9XqgSwwR00W4V",
        "Neon Grave",
        "Tyler Braden",
        "spotify:playlist:7cpHMBDK9bGgj2XlogYX9F",
        "playlist",
        "device-123",
        "Arturo’s Mac mini",
        67,
        Boolean.FALSE,
        "off",
        0,
        233_000
    );

    Method mWithPlaylist = SpotifyCommandService.class.getDeclaredMethod(
        "formatStatus",
        SpotifyCommandService.PlaybackSnapshot.class,
        boolean.class,
        String.class);
    mWithPlaylist.setAccessible(true);
    String status = (String) mWithPlaylist.invoke(null, snapshot, true, "4/99");

    assertTrue(status.contains("Device: Arturo’s Mac mini"));
    assertTrue(status.contains("DeviceId: device-123"));
    assertTrue(status.contains("Playing: true"));
    assertTrue(status.contains("Context: playlist (spotify:playlist:7cpHMBDK9bGgj2XlogYX9F)"));
    assertTrue(status.contains("ContextType: playlist"));
    assertTrue(status.contains("ContextUri: spotify:playlist:7cpHMBDK9bGgj2XlogYX9F"));
    assertTrue(status.contains("ContextId: 7cpHMBDK9bGgj2XlogYX9F"));
    assertTrue(status.contains("Track: Neon Grave — Tyler Braden"));
    assertTrue(status.contains("TrackUri: spotify:track:0uR8U8m4y9XqgSwwR00W4V"));
    assertTrue(status.contains("Position: 00:00 / 03:53"));
    assertTrue(status.contains("ProgressMs: 0"));
    assertTrue(status.contains("DurationMs: 233000"));
    assertTrue(status.contains("PlaylistPosition: 4/99"));
    assertTrue(status.contains("Shuffle: false"));
    assertTrue(status.contains("Repeat: off"));
    assertTrue(status.contains("Volume: 67%"));
  }
}
