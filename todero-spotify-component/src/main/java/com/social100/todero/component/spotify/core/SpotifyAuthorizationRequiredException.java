package com.social100.todero.component.spotify.core;

import com.social100.todero.common.runtime.auth.AuthorizationErrorCode;

import java.util.Set;

public final class SpotifyAuthorizationRequiredException extends RuntimeException {
  private final String errorCode;

  private SpotifyAuthorizationRequiredException(String errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public String errorCode() {
    return errorCode;
  }

  public static SpotifyAuthorizationRequiredException missingToken() {
    return new SpotifyAuthorizationRequiredException(
        AuthorizationErrorCode.AUTH_REQUIRED,
        "Spotify authorization is required. Run auth-begin then auth-complete."
    );
  }

  public static SpotifyAuthorizationRequiredException refreshFailed() {
    return new SpotifyAuthorizationRequiredException(
        AuthorizationErrorCode.AUTH_REQUIRED,
        "Spotify authorization is invalid or expired. Run auth-begin then auth-complete."
    );
  }

  public static SpotifyAuthorizationRequiredException missingScopes(Set<String> missingScopes) {
    String suffix = (missingScopes == null || missingScopes.isEmpty())
        ? ""
        : " Missing scopes: " + String.join(", ", missingScopes) + ".";
    return new SpotifyAuthorizationRequiredException(
        AuthorizationErrorCode.AUTH_SCOPE_MISSING,
        "Spotify token is missing required scopes. Run auth-begin then auth-complete." + suffix
    );
  }
}
