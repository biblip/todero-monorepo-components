package com.social100.todero.component.spotify;

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
}
