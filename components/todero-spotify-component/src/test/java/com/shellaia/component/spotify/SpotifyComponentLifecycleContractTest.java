package com.shellaia.component.spotify;

import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.aiatpio.AiatpResponse;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.component.testkit.TestStorage;
import com.social100.todero.generated.SpotifyComponentImpl;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotifyComponentLifecycleContractTest {

  @Test
  void constructorDoesNotRequireEnvConfiguration() {
    SpotifyComponent component = new SpotifyComponent(new TestStorage());
    assertNotNull(component);
  }

  @Test
  void generatedWrapperRegistersDescriptorAndReturnsNotReadyErrorWhenEnvIsMissing() {
    SpotifyComponentImpl component = new SpotifyComponentImpl((eventName, wrapper) -> {
    }, new TestStorage());
    AtomicReference<AiatpResponse> seen = new AtomicReference<>();

    CommandContext context = CommandContext.builder()
        .sourceId("spotify-lifecycle")
        .aiatpRequest(AiatpRuntimeAdapter.request(
            "ACTION",
            "/com.shellaia.spotify/play",
            AiatpIO.Body.ofString("enya caribbean blue", StandardCharsets.UTF_8)))
        .responseConsumer(seen::set)
        .build();

    assertEquals("com.shellaia.spotify", component.getComponentDescriptor().getName());
    assertFalse(component.execute("com.shellaia.spotify", "play", context));

    assertNotNull(seen.get());
    assertEquals("error", seen.get().getChannel());
    assertEquals("failure", seen.get().getOutcome());
    String body = AiatpIO.bodyToString(seen.get().getBody(), StandardCharsets.UTF_8);
    assertTrue(body.contains("\"channels\""));
    assertTrue(body.contains("\"errorCode\":\"missing_configuration\""));
    assertTrue(body.contains("Spotify component is not ready"));
  }
}
