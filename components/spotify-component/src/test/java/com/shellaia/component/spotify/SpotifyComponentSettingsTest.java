package com.shellaia.component.spotify;

import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpRequest;
import com.social100.todero.common.aiatpio.AiatpResponse;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.storage.Storage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotifyComponentSettingsTest {

  @Test
  void settingsSave_writesCanonicalDotenv() throws Exception {
    InMemoryStorage storage = new InMemoryStorage();
    SpotifyComponent component = new SpotifyComponent(storage);

    CapturedResponse response = invoke(component, "settings_save", """
        {
          "deviceId":"device-999"
        }
        """);

    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("\"ok\":true"));
    assertEquals("""
        # Spotify component settings
        SPOTIFY_CLIENT_ID=6a97c2f26f4c4043aef129247f4c7426
        SPOTIFY_REDIRECT_URI_APP=https://auth.shellaia.com/component/callback
        SPOTIFY_REDIRECT_URI_CONSOLE=http://127.0.0.1:34895/spotify/callback
        SPOTIFY_REDIRECT_URI_ALLOWLIST=https://auth.shellaia.com/component/callback,http://127.0.0.1:34895/spotify/callback
        SPOTIFY_DEVICE_ID=device-999
        """, storage.readText(".env"));
  }

  @Test
  void html_embedsSettingsModalValues() throws Exception {
    InMemoryStorage storage = new InMemoryStorage();
    storage.writeFile(".env", """
        SPOTIFY_DEVICE_ID=device-999
        """.getBytes(StandardCharsets.UTF_8));
    SpotifyComponent component = new SpotifyComponent(storage);

    CapturedResponse response = invoke(component, "html", "");

    assertEquals(200, response.statusCode());
    assertTrue(response.contentType().contains("text/html"));
    assertTrue(response.body().contains("btn-settings-save"));
    assertTrue(response.body().contains("SPOTIFY_DEVICE_ID"));
    assertTrue(response.body().contains("device-999"));
  }

  private static CapturedResponse invoke(SpotifyComponent component, String action, String body) {
    AtomicReference<AiatpResponse> responseRef = new AtomicReference<>();
    CommandContext context = CommandContext.builder()
        .sourceId("spotify-test-" + action)
        .responseConsumer(responseRef::set)
        .aiatpRequest(AiatpRequest.builder()
            .method("ACTION")
            .target("/com.shellaia.spotify/" + action)
            .body(AiatpIO.Body.ofString(body == null ? "" : body, StandardCharsets.UTF_8))
            .build())
        .build();

    switch (action) {
      case "html" -> component.htmlCommand(context);
      case "settings_save" -> component.settingsSaveCommand(context);
      default -> throw new IllegalArgumentException("unsupported action " + action);
    }

    AiatpResponse response = responseRef.get();
    if (response == null) {
      throw new AssertionError("expected response");
    }
    String responseBody = AiatpIO.bodyToString(response.getBody(), StandardCharsets.UTF_8);
    String contentType = response.getHeaders() == null ? "" : String.valueOf(response.getHeaders().getFirst("Content-Type"));
    return new CapturedResponse(response.getStatusCode(), contentType, responseBody);
  }

  private record CapturedResponse(int statusCode, String contentType, String body) {
  }

  private static final class InMemoryStorage implements Storage {
    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private final Map<String, String> secrets = new LinkedHashMap<>();

    @Override
    public void writeFile(String relativePath, byte[] bytes) {
      files.put(relativePath, bytes == null ? new byte[0] : bytes.clone());
    }

    @Override
    public byte[] readFile(String relativePath) throws IOException {
      byte[] bytes = files.get(relativePath);
      if (bytes == null) {
        throw new IOException("missing file: " + relativePath);
      }
      return bytes.clone();
    }

    @Override
    public void deleteFile(String relativePath) {
      files.remove(relativePath);
    }

    @Override
    public List<String> listFiles(String relativeDir) {
      return new ArrayList<>(files.keySet());
    }

    @Override
    public void putSecret(String key, String value) {
      secrets.put(key, value);
    }

    @Override
    public String getSecret(String key) {
      return secrets.get(key);
    }

    @Override
    public void deleteSecret(String key) {
      secrets.remove(key);
    }

    String readText(String relativePath) throws IOException {
      return new String(readFile(relativePath), StandardCharsets.UTF_8);
    }
  }
}
