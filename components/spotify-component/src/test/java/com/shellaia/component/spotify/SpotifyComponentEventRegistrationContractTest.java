package com.shellaia.component.spotify;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpotifyComponentEventRegistrationContractTest {

  @Test
  void spotifyEventsMatchStrictEmittedChannels() {
    Set<String> expected = Set.of(
        "chat",
        "status",
        "html",
        "thought",
        "auth",
        "error",
        "BRIDGE_DEMO",
        "PLAYBACK_STATUS",
        "PLAYBACK_EVENT_V2"
    );

    Set<String> actual = Stream.of(SpotifyComponent.SpotifyEvent.values())
        .map(Enum::name)
        .collect(Collectors.toSet());

    assertEquals(expected, actual);
  }
}
