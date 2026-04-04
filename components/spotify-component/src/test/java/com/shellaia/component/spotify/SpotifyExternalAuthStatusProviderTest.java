package com.shellaia.component.spotify;

import com.google.gson.Gson;
import com.social100.todero.common.runtime.auth.ComponentExternalAuthStatus;
import com.social100.todero.common.runtime.auth.ExternalAuthState;
import com.social100.todero.component.testkit.TestStorage;
import com.shellaia.component.spotify.core.TokenStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpotifyExternalAuthStatusProviderTest {
  private static final Gson GSON = new Gson();

  @Test
  void missingEnvReturnsUnavailable() {
    SpotifyExternalAuthStatusProvider provider = new SpotifyExternalAuthStatusProvider();

    ComponentExternalAuthStatus status = provider.snapshot(new TestStorage());

    assertEquals(ExternalAuthState.unavailable, status.getState());
  }

  @Test
  void missingTokenReturnsAuthenticationRequired() throws Exception {
    TestStorage storage = configuredStorage();
    SpotifyExternalAuthStatusProvider provider = new SpotifyExternalAuthStatusProvider();

    ComponentExternalAuthStatus status = provider.snapshot(storage);

    assertEquals(ExternalAuthState.authentication_required, status.getState());
    assertEquals("spotify", status.getServices().get(0).getServiceId());
  }

  @Test
  void validTokenReturnsAuthenticated() throws Exception {
    TestStorage storage = configuredStorage();
    TokenStore.TokenData tokenData = new TokenStore.TokenData();
    tokenData.accessToken = "a";
    tokenData.refreshToken = "r";
    tokenData.expiresAtEpoch = (System.currentTimeMillis() / 1000L) + 3600L;
    tokenData.scope = "user-read-playback-state user-modify-playback-state user-read-recently-played user-top-read user-library-modify playlist-read-private playlist-read-collaborative playlist-modify-public playlist-modify-private";
    storage.putSecret("auth/token", GSON.toJson(tokenData));

    SpotifyExternalAuthStatusProvider provider = new SpotifyExternalAuthStatusProvider();
    ComponentExternalAuthStatus status = provider.snapshot(storage);

    assertEquals(ExternalAuthState.authenticated, status.getState());
  }

  private static TestStorage configuredStorage() throws IOException {
    TestStorage storage = new TestStorage();
    storage.writeFile(
        ".env",
        ("SPOTIFY_CLIENT_ID=abc\n"
            + "SPOTIFY_REDIRECT_URI_APP=https://auth.shellaia.com/component/callback\n"
            + "SPOTIFY_REDIRECT_URI_CONSOLE=http://127.0.0.1:34895/spotify/callback\n").getBytes());
    return storage;
  }
}
