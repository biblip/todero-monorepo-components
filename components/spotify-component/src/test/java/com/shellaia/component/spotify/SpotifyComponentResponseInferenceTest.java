package com.shellaia.component.spotify;

import com.social100.todero.component.testkit.TestStorage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  void classifyOutcomeMarksIntermediateAndAwaitingCommands() throws Exception {
    assertEquals("goal_completed", classifyOutcome("play", true));
    assertEquals("intermediate_result", classifyOutcome("status", true));
    assertEquals("await_external_completion", classifyOutcome("auth-begin", true));
    assertEquals("failure", classifyOutcome("play", false));
  }

  @Test
  void responseJsonIncludesResponseOutcomeAndChannels() throws Exception {
    SpotifyComponent component = newComponent();
    Method method = SpotifyComponent.class.getDeclaredMethod(
        "responseJson",
        String.class, String.class, String.class, boolean.class, String.class,
        String.class, String.class, Object.class, String.class, String.class, boolean.class);
    method.setAccessible(true);
    String json = (String) method.invoke(component, "events", "ON", "Events started.", true, null,
        "goal_completed", null, null, null, "none", false);
    assertTrue(json.contains("\"response\""));
    assertTrue(json.contains("\"outcome\":\"goal_completed\""));
    assertTrue(json.contains("\"channels\""));
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

  private static String classifyOutcome(String command, boolean ok) throws Exception {
    Method method = SpotifyComponent.class.getDeclaredMethod("classifyOutcome", String.class, boolean.class);
    method.setAccessible(true);
    return (String) method.invoke(null, command, ok);
  }

  private static SpotifyComponent newComponent() {
    return new SpotifyComponent(new TestStorage());
  }
}
