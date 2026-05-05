package com.shellaia.component.spotify;

import com.social100.todero.common.runtime.auth.ComponentExternalAuthStatus;
import com.social100.todero.common.runtime.auth.ExternalAuthState;
import com.social100.todero.common.runtime.auth.ExternalAuthStatusProvider;
import com.social100.todero.common.runtime.auth.ExternalServiceAuthStatus;
import com.social100.todero.common.storage.Storage;
import com.shellaia.component.spotify.core.TokenStore;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.social100.todero.common.config.Util.parseDotenv;

public final class SpotifyExternalAuthStatusProvider implements ExternalAuthStatusProvider {
  private static final Set<String> REQUIRED_SCOPES = Set.of(
      "user-read-playback-state",
      "user-modify-playback-state",
      "user-read-recently-played",
      "user-top-read",
      "user-library-modify",
      "playlist-read-private",
      "playlist-read-collaborative",
      "playlist-modify-public",
      "playlist-modify-private"
  );

  @Override
  public ComponentExternalAuthStatus snapshot(Storage storage) {
    try {
      requireConfiguration(storage);
    } catch (IllegalStateException e) {
      return status(
          ExternalAuthState.unavailable,
          "Spotify authentication status is unavailable.",
          e.getMessage());
    }

    TokenStore tokenStore = new TokenStore(storage);
    TokenStore.TokenData tokenData = tokenStore.read().orElse(null);
    if (tokenData == null) {
      return status(
          ExternalAuthState.authentication_required,
          "Spotify authorization is required.",
          "No token stored. Run delegated auth-begin/auth-complete.");
    }

    if (TokenStore.isExpired(tokenData)) {
      return status(
          ExternalAuthState.authentication_required,
          "Spotify authorization is required.",
          "Stored Spotify token is expired. Re-run delegated auth.");
    }

    Set<String> missingScopes = missingRequiredScopes(tokenData.scope);
    if (!missingScopes.isEmpty()) {
      return status(
          ExternalAuthState.authentication_required,
          "Spotify authorization is required.",
          "Stored Spotify token is missing required scopes: " + String.join(" ", missingScopes));
    }

    return status(
        ExternalAuthState.authenticated,
        "Spotify is authenticated.",
        "Spotify token is present and has the required scopes.");
  }

  private static void requireConfiguration(Storage storage) {
    try {
      Map<String, String> env = new java.util.LinkedHashMap<>();
      try {
        env.putAll(parseDotenv(storage.readFile(".env")));
      } catch (IOException ignored) {
        // Defaults make the component usable without a .env file.
      }
      env = SpotifyComponentDefaults.withDefaults(env);
      if (blank(env.get(SpotifyComponentDefaults.ENV_SPOTIFY_CLIENT_ID))
          || blank(env.get(SpotifyComponentDefaults.ENV_SPOTIFY_REDIRECT_URI_APP))
          || blank(env.get(SpotifyComponentDefaults.ENV_SPOTIFY_REDIRECT_URI_CONSOLE))) {
        throw new IllegalStateException("Spotify configuration is incomplete.");
      }
    } catch (RuntimeException e) {
      throw new IllegalStateException("Spotify configuration could not be parsed.");
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static Set<String> missingRequiredScopes(String scopeText) {
    Set<String> granted = scopeText == null || scopeText.isBlank()
        ? Set.of()
        : Arrays.stream(scopeText.trim().split("\\s+"))
            .filter(scope -> scope != null && !scope.isBlank())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Set<String> missing = new LinkedHashSet<>(REQUIRED_SCOPES);
    missing.removeAll(granted);
    return missing;
  }

  private static ComponentExternalAuthStatus status(ExternalAuthState state,
                                                    String componentMessage,
                                                    String serviceMessage) {
    return ComponentExternalAuthStatus.builder()
        .state(state)
        .message(componentMessage)
        .services(List.of(
            ExternalServiceAuthStatus.builder()
                .serviceId("spotify")
                .displayName("Spotify")
                .state(state)
                .message(serviceMessage)
                .build()))
        .build();
  }
}
