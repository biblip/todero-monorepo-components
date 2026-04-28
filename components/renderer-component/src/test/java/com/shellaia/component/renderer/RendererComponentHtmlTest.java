package com.shellaia.component.renderer;

import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpResponse;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.component.testkit.TestStorage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RendererComponentHtmlTest {

  @Test
  void htmlRendersFallbackPageForRequestedComponent() {
    RendererComponent component = new RendererComponent(new TestStorage());
    AtomicReference<AiatpResponse> response = new AtomicReference<>();

    CommandContext context = CommandContext.builder()
        .sourceId("renderer-html")
        .aiatpRequest(AiatpRuntimeAdapter.request(
            "ACTION",
            "/com.shellaia.renderer/html?target-component=com.shellaia.contacts&target-path=%2Fcom.shellaia.contacts%2Fhtml",
            headers("brumor.pbxkey.com"),
            AiatpIO.Body.none()))
        .responseConsumer(response::set)
        .build();

    component.htmlCommand(context);

    assertNotNull(response.get());
    assertEquals(200, response.get().getStatusCode());
    assertEquals("text/html; charset=utf-8", response.get().getHeaders().getFirst("Content-Type"));
    String body = AiatpIO.bodyToString(response.get().getBody(), StandardCharsets.UTF_8);
    // The renderer fallback HTML is intentionally minimal; it primarily provides the webview bridge feed.
    assertTrue(body.contains("Webview Bridge"));
    assertTrue(body.contains("Waiting..."));
    assertTrue(body.contains("__todero_onAiatpMessage"));
  }

  @Test
  void htmlRendersRootFallbackWithoutTargetComponent() {
    RendererComponent component = new RendererComponent(new TestStorage());
    AtomicReference<AiatpResponse> response = new AtomicReference<>();

    CommandContext context = CommandContext.builder()
        .sourceId("renderer-root")
        .aiatpRequest(AiatpRuntimeAdapter.request(
            "ACTION",
            "/com.shellaia.renderer/html?target-path=%2F",
            headers("brumor.pbxkey.com"),
            AiatpIO.Body.none()))
        .responseConsumer(response::set)
        .build();

    component.htmlCommand(context);

    assertNotNull(response.get());
    String body = AiatpIO.bodyToString(response.get().getBody(), StandardCharsets.UTF_8);
    assertTrue(body.contains("Webview Bridge"));
    assertTrue(body.contains("Waiting..."));
  }
  private static AiatpIO.Headers headers(String host) {
    AiatpIO.Headers headers = new AiatpIO.Headers();
    headers.set("Host", host);
    return headers;
  }
}
