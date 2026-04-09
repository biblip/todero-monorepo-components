package com.shellaia.component.spotify;

import com.social100.todero.common.command.CommandContext;
import com.social100.todero.component.testkit.TestCommandContext;
import com.social100.todero.component.testkit.TestStorage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SpotifyComponentResponseInferenceTest {

  @Test
  void inferSuccessHandlesErrorCodeMarkerAndJavaExceptions() throws Exception {
    assertFalse(inferSuccess("top-tracks failed [error_code=execution_failed]: boom"));
    assertFalse(inferSuccess("java.lang.IllegalStateException: connection"));
    assertTrue(inferSuccess("Events started."));
  }

  @Test
  void inferErrorCodePrefersEmbeddedMarker() throws Exception {
    assertEquals("forbidden", inferErrorCode("previous failed [error_code=forbidden]: denied"));
    assertEquals("execution_failed", inferErrorCode("java.lang.IllegalStateException: connection"));
    assertEquals("invalid_arguments", inferErrorCode("Usage: skip <+/-seconds>"));
  }

  @Test
  void wireResponseIsNotJsonEnvelope() throws Exception {
    SpotifyComponent component = newComponent();
    Method method = SpotifyComponent.class.getDeclaredMethod(
        "buildWireResponse",
        String.class, String.class, String.class, String.class, String.class,
        String.class, Boolean.class, String.class);
    method.setAccessible(true);
    Object response = method.invoke(null,
        "chat",
        "success",
        "completed",
        "Events started.",
        "text/plain; charset=utf-8",
        null,
        null,
        null);
    assertNotNull(response);
  }

  @Test
  void authCompleteParsingPrefersQueryParams() throws Exception {
    Method method = SpotifyComponent.class.getDeclaredMethod(
        "parseAuthCompleteRequest",
        CommandContext.class,
        String.class);
    method.setAccessible(true);
    CommandContext context = new TestCommandContext(new TestStorage())
        .create("/com.shellaia.spotify/auth-complete?code=abc&state=s1", "code=body state=body");

    Object request = method.invoke(null, context, "code=body state=body");

    Method code = request.getClass().getDeclaredMethod("code");
    Method state = request.getClass().getDeclaredMethod("state");
    Method sessionId = request.getClass().getDeclaredMethod("sessionId");
    assertEquals("abc", code.invoke(request));
    assertEquals("s1", state.invoke(request));
    assertNull(sessionId.invoke(request));
  }

  @Test
  void authCompleteParsingResolvesSessionIdFromEncodedState() throws Exception {
    Method method = SpotifyComponent.class.getDeclaredMethod(
        "parseAuthCompleteRequest",
        CommandContext.class,
        String.class);
    method.setAccessible(true);
    String encodedState = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"session-id\":\"sess-123\",\"auth-complete\":\"aia://host/com.shellaia.spotify/auth-complete\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)
    );
    CommandContext context = new TestCommandContext(new TestStorage())
        .create("/com.shellaia.spotify/auth-complete?code=abc&state=" + encodedState, "");

    Object request = method.invoke(null, context, "");

    Method code = request.getClass().getDeclaredMethod("code");
    Method state = request.getClass().getDeclaredMethod("state");
    Method sessionId = request.getClass().getDeclaredMethod("sessionId");
    assertEquals("abc", code.invoke(request));
    assertEquals(encodedState, state.invoke(request));
    assertEquals("sess-123", sessionId.invoke(request));
  }

  private static boolean inferSuccess(String message) throws Exception {
    Method method = SpotifyComponent.class.getDeclaredMethod("inferSuccess", String.class);
    method.setAccessible(true);
    return (boolean) method.invoke(null, message);
  }

  private static String inferErrorCode(String message) throws Exception {
    Method method = SpotifyComponent.class.getDeclaredMethod("inferErrorCode", String.class);
    method.setAccessible(true);
    return (String) method.invoke(null, message);
  }

  private static SpotifyComponent newComponent() {
    return new SpotifyComponent(new TestStorage());
  }
}
