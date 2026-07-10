package com.shellaia.processor.pre.googlegate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shellaia.tutil.auth.AuthStatePaths;
import com.social100.todero.common.aiatpio.AiatpRequest;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.preprocessor.PreprocessResult;
import com.social100.todero.common.preprocessor.PreprocessTarget;
import com.social100.todero.common.preprocessor.PreprocessorInterface;
import com.social100.todero.common.preprocessor.PreprocessorMeta;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

public class GoogleAuthGatePreprocessor implements PreprocessorInterface {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String BROKER_COMPONENT = "com.shellaia.auth.google";

  private final java.nio.file.Path baseDir;

  public GoogleAuthGatePreprocessor(java.nio.file.Path baseDir) {
    this.baseDir = baseDir;
  }

  @Override
  public PreprocessorMeta meta() {
    return new PreprocessorMeta("google-auth-gate", "Requires a verified Google principal before protected requests proceed", 20);
  }

  @Override
  public PreprocessResult before(CommandContext context, AiatpRequest request, PreprocessTarget target) {
    if (target != null && BROKER_COMPONENT.equals(target.component())) {
      return PreprocessResult.continueWith(request, target);
    }
    if (hasAuthenticatedPrincipal(request)) {
      return PreprocessResult.continueWith(request, target);
    }
    return PreprocessResult.block(AiatpRuntimeAdapter.failureText(
        "google_auth_required",
        "Google authentication is required. Open the auth broker HTML surface and complete sign-in before retrying."
    ));
  }

  private boolean hasAuthenticatedPrincipal(AiatpRequest request) {
    try {
      String header = headerValue(request, "Authorization");
      if (header != null && header.trim().toLowerCase().startsWith("bearer ")) {
        return true;
      }
      header = headerValue(request, "X-Todero-Principal");
      if (header != null && !header.trim().isEmpty()) {
        return true;
      }
      if (!Files.exists(AuthStatePaths.principalFile())) {
        return false;
      }
      JsonNode principal = JSON.readTree(Files.readString(AuthStatePaths.principalFile(), StandardCharsets.UTF_8));
      if (principal == null || !principal.path("authenticated").asBoolean(false)) {
        return false;
      }
      String expiresAt = principal.path("expiresAt").asText("");
      if (expiresAt == null || expiresAt.isBlank()) {
        return true;
      }
      return Instant.parse(expiresAt).isAfter(Instant.now());
    } catch (Exception e) {
      return false;
    }
  }

  private static String headerValue(AiatpRequest request, String headerName) {
    if (request == null || request.getHeaders() == null || headerName == null) {
      return null;
    }
    return request.getHeaders().getFirst(headerName);
  }
}
