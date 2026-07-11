package com.shellaia.component.spotify;

import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpResponse;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.aiatpio.RuntimeEnvelope;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.component.testkit.TestStorage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotifyEmitCommandBehaviorTest {

  @Test
  void emitChatUsesChatChannelAndPlainText() {
    CapturedEmit captured = executeEmit("/com.shellaia.spotify/emit?channel=chat&message=hello");

    assertEquals(200, captured.response.getStatusCode());
    assertNotNull(captured.event.getEvent());
    assertEquals("chat", captured.event.getEvent().getChannel());
    assertEquals(
        "text/plain; charset=utf-8",
        captured.event.getEvent().getHeaders().getFirst("Content-Type")
    );
    assertEquals(
        "hello",
        AiatpIO.bodyToString(captured.event.getEvent().getBody(), StandardCharsets.UTF_8)
    );
  }

  @Test
  void emitHtmlUsesHtmlChannelAndHtmlBody() {
    CapturedEmit captured = executeEmit("/com.shellaia.spotify/emit?channel=html&message=hello");

    assertEquals(200, captured.response.getStatusCode());
    assertNotNull(captured.event.getEvent());
    assertEquals("html", captured.event.getEvent().getChannel());
    assertEquals(
        "text/html; charset=utf-8",
        captured.event.getEvent().getHeaders().getFirst("Content-Type")
    );
    String body = AiatpIO.bodyToString(captured.event.getEvent().getBody(), StandardCharsets.UTF_8);
    assertTrue(body.contains("Spotify emit(html)"));
    assertTrue(body.contains("hello"));
  }

  @Test
  void emitThoughtUsesThoughtChannelAndPlainText() {
    CapturedEmit captured = executeEmit("/com.shellaia.spotify/emit?channel=thought&message=route%3Dspotify");

    assertEquals(200, captured.response.getStatusCode());
    assertNotNull(captured.event.getEvent());
    assertEquals("thought", captured.event.getEvent().getChannel());
    assertEquals(
        "route=spotify",
        AiatpIO.bodyToString(captured.event.getEvent().getBody(), StandardCharsets.UTF_8)
    );
  }

  @Test
  void invalidChannelReturnsUsageAndDoesNotEmitEvent() {
    SpotifyComponent component = new SpotifyComponent(new TestStorage());
    AtomicReference<RuntimeEnvelope> event = new AtomicReference<>();
    AtomicReference<AiatpResponse> response = new AtomicReference<>();

    CommandContext context = CommandContext.builder()
        .sourceId("spotify-emit-invalid")
        .aiatpRequest(AiatpRuntimeAdapter.request(
            "ACTION",
            "/com.shellaia.spotify/emit?channel=invalid",
            AiatpIO.Body.ofString("", StandardCharsets.UTF_8)))
        .eventConsumer(event::set)
        .responseConsumer(response::set)
        .build();

    component.emitCommand(context);

    assertNotNull(response.get());
    assertEquals(500, response.get().getStatusCode());
    assertEquals("invalid_arguments", response.get().getReasonPhrase());
    assertEquals(null, event.get());
    String body = AiatpIO.bodyToString(response.get().getBody(), StandardCharsets.UTF_8);
    assertTrue(body.contains("emit?channel=chat|html|thought&message=hello"));
  }

  private static CapturedEmit executeEmit(String route) {
    SpotifyComponent component = new SpotifyComponent(new TestStorage());
    AtomicReference<RuntimeEnvelope> event = new AtomicReference<>();
    AtomicReference<AiatpResponse> response = new AtomicReference<>();

    CommandContext context = CommandContext.builder()
        .sourceId("spotify-emit")
        .aiatpRequest(AiatpRuntimeAdapter.request(
            "ACTION",
            route,
            AiatpIO.Body.ofString("", StandardCharsets.UTF_8)))
        .eventConsumer(event::set)
        .responseConsumer(response::set)
        .build();

    component.emitCommand(context);

    assertNotNull(response.get());
    assertNotNull(event.get());
    return new CapturedEmit(event.get(), response.get());
  }

  private record CapturedEmit(RuntimeEnvelope event, AiatpResponse response) {}
}
