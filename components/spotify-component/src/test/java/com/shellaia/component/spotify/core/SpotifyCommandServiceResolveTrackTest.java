package com.shellaia.component.spotify.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.social100.todero.common.storage.Storage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class SpotifyCommandServiceResolveTrackTest {

  @Test
  void resolveTrackRejectsBlankQueryClearly() {
    SpotifyConfig config = SpotifyConfig.builder()
        .clientId("test-client")
        .redirectUrlApp("https://auth.shellaia.com/component/callback")
        .redirectUrlConsole("http://127.0.0.1:34895/spotify/callback")
        .build();
    SpotifyCommandService service = new SpotifyCommandService(
        new SpotifyPkceService(config, new InMemoryStorage()),
        null);

    String result = service.resolveTrack("   ");

    assertTrue(result.contains("resolve-track failed [error_code=invalid_arguments]"));
    assertTrue(result.contains("non-empty query"));
  }

  private static final class InMemoryStorage implements Storage {
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Map<String, String> secrets = new ConcurrentHashMap<>();

    @Override
    public void writeFile(String relativePath, byte[] bytes) {
      files.put(relativePath, bytes == null ? new byte[0] : bytes);
    }

    @Override
    public byte[] readFile(String relativePath) {
      return files.getOrDefault(relativePath, new byte[0]);
    }

    @Override
    public void deleteFile(String relativePath) {
      files.remove(relativePath);
    }

    @Override
    public List<String> listFiles(String relativeDir) {
      return List.copyOf(files.keySet());
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
  }
}
