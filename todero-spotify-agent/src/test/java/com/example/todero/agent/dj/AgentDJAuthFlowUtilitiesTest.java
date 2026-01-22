package com.example.todero.agent.dj;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class AgentDJAuthFlowUtilitiesTest {

  @Test
  void parseAuthCompletionIntentRequiresSessionAndCode() throws Exception {
    Method parse = AgentDJComponent.class.getDeclaredMethod("parseAuthCompletionIntent", String.class);
    parse.setAccessible(true);

    Object valid = parse.invoke(null, "auth-complete session-id=s1 state=st1 code=c1");
    assertNotNull(valid);

    Object missingCode = parse.invoke(null, "auth-complete session-id=s1 state=st1");
    assertNull(missingCode);

    Object missingSession = parse.invoke(null, "auth-complete state=st1 code=c1");
    assertNull(missingSession);
  }

  @Test
  void extractAuthSessionIdReadsAuthOrSessionNodes() throws Exception {
    Method extract = AgentDJComponent.class.getDeclaredMethod("extractAuthSessionId", String.class);
    extract.setAccessible(true);

    String fromSession = (String) extract.invoke(null,
        "{\"session\":{\"sessionId\":\"sess-a\"}}"
    );
    assertEquals("sess-a", fromSession);

    String fromAuth = (String) extract.invoke(null,
        "{\"auth\":{\"sessionId\":\"sess-b\"}}"
    );
    assertEquals("sess-b", fromAuth);
  }

  @Test
  void redactedForLogsMasksSecureEnvelopeFields() throws Exception {
    Method redact = AgentDJComponent.class.getDeclaredMethod("redactedForLogs", String.class);
    redact.setAccessible(true);

    String redacted = (String) redact.invoke(null,
        "{\"auth\":{\"secureEnvelope\":{\"opaquePayload\":\"abc\",\"integrity\":\"sig\"}}}"
    );
    assertTrue(redacted.contains("\"secureEnvelope\":\"<redacted>\""));
    assertFalse(redacted.contains("opaquePayload\":\"abc\""));
  }

  @Test
  void isAuthRequiredErrorRecognizesKnownCodes() throws Exception {
    Method method = AgentDJComponent.class.getDeclaredMethod("isAuthRequiredError", String.class);
    method.setAccessible(true);

    assertEquals(true, method.invoke(null, "auth_required"));
    assertEquals(true, method.invoke(null, "auth_scope_missing"));
    assertEquals(false, method.invoke(null, "execution_failed"));
  }

  @Test
  void isAuthRequiredToolResultDetectsAuthByCodeAndMessage() throws Exception {
    Class<?> toolExecClass = Class.forName("com.example.todero.agent.dj.AgentDJComponent$ToolExecution");
    Constructor<?> ctor = toolExecClass.getDeclaredConstructor(
        boolean.class, String.class, String.class, String.class, String.class, String.class
    );
    ctor.setAccessible(true);
    Method method = AgentDJComponent.class.getDeclaredMethod("isAuthRequiredToolResult", toolExecClass);
    method.setAccessible(true);

    Object byCode = ctor.newInstance(
        false, "play", "x", "failed", "auth_required", "{\"ok\":false,\"errorCode\":\"auth_required\"}"
    );
    assertEquals(true, method.invoke(null, byCode));

    Object byMessage = ctor.newInstance(
        true, "play", "x",
        "com.social100.todero.component.spotify.core.SpotifyAuthorizationRequiredException: Spotify authorization is required. Run auth-begin then auth-complete.",
        "",
        "{\"ok\":true,\"message\":\"Spotify authorization is required\"}"
    );
    assertEquals(true, method.invoke(null, byMessage));

    Object nonAuth = ctor.newInstance(
        true, "status", "all", "No active playback.", "", "{\"ok\":true,\"message\":\"No active playback.\"}"
    );
    assertEquals(false, method.invoke(null, nonAuth));
  }
}
