package com.shellaia.processor.pre.googlegate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shellaia.tutil.auth.AuthStatePaths;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpRequest;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.preprocessor.PreprocessResult;
import com.social100.todero.common.preprocessor.PreprocessTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoogleAuthGatePreprocessorTest {
  private final ObjectMapper json = new ObjectMapper();
  private Path tempDir;
  private String previousRoot;

  @BeforeEach
  void setUp() throws IOException {
    tempDir = Files.createTempDirectory("google-auth-gate-test");
    previousRoot = System.getProperty("todero.auth.root");
    System.setProperty("todero.auth.root", tempDir.resolve("auth-root").toString());
  }

  @AfterEach
  void tearDown() {
    if (previousRoot == null) {
      System.clearProperty("todero.auth.root");
    } else {
      System.setProperty("todero.auth.root", previousRoot);
    }
  }

  @Test
  void blocksWhenNoPrincipalExists() {
    GoogleAuthGatePreprocessor preprocessor = new GoogleAuthGatePreprocessor(tempDir);
    PreprocessResult result = preprocessor.before(context(), request(), new PreprocessTarget("com.shellaia.context", "process"));
    assertEquals(PreprocessResult.Action.BLOCK, result.action());
    String message = AiatpIO.bodyToString(result.result().getBody(), StandardCharsets.UTF_8);
    assertTrue(message.contains("Google authentication is required"));
  }

  @Test
  void allowsProtectedRequestsWhenAuthenticatedPrincipalExists() throws Exception {
    Files.createDirectories(AuthStatePaths.principalFile().getParent());
    Files.writeString(AuthStatePaths.principalFile(), """
        {
          "provider":"google",
          "subjectId":"sub-1",
          "email":"arturo@example.com",
          "displayName":"Arturo",
          "authenticatedAt":"2026-06-28T20:00:00Z",
          "expiresAt":"2999-06-28T20:00:00Z",
          "authenticated":true,
          "sessionId":"sess-1"
        }
        """.trim(), StandardCharsets.UTF_8);
    GoogleAuthGatePreprocessor preprocessor = new GoogleAuthGatePreprocessor(tempDir);
    PreprocessResult result = preprocessor.before(context(), request(), new PreprocessTarget("com.shellaia.context", "process"));
    assertEquals(PreprocessResult.Action.CONTINUE, result.action());
  }

  @Test
  void exemptsBrokerRequests() {
    GoogleAuthGatePreprocessor preprocessor = new GoogleAuthGatePreprocessor(tempDir);
    PreprocessResult result = preprocessor.before(context(), request(), new PreprocessTarget("com.shellaia.auth.google", "html"));
    assertEquals(PreprocessResult.Action.CONTINUE, result.action());
  }

  private static CommandContext context() {
    return CommandContext.builder()
        .sourceId("google-auth-gate-test")
        .responseConsumer(response -> {})
        .aiatpRequest(request())
        .build();
  }

  private static AiatpRequest request() {
    return AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.context/process",
        AiatpIO.Body.ofString("{}", StandardCharsets.UTF_8));
  }
}
