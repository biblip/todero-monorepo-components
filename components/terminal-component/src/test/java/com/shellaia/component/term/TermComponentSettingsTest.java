package com.shellaia.component.term;

import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpRequest;
import com.social100.todero.common.aiatpio.AiatpResponse;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.storage.Storage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TermComponentSettingsTest {

  @Test
  void settingsSave_writesCanonicalDotenvAndReloadsEffectiveEnv() throws Exception {
    InMemoryStorage storage = new InMemoryStorage();
    TermComponent component = new TermComponent(storage);
    Path nativeLib = Files.createTempFile("libtodero_term", ".so");
    try {
      CapturedResponse response = invoke(component, "settings_save", """
          {"allowedWorkspaces":" /tmp\\n/var/tmp ","nativeLibPath":"%s"}
          """.formatted(nativeLib.toString()));

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"ok\":true"));
      assertTrue(response.body().contains("\"reloadRequired\":false"));
      assertEquals("/tmp;/var/tmp", component.effectiveEnv("TODERO_TERM_ALLOWED_WORKSPACES"));
      assertEquals(nativeLib.toString(), component.effectiveEnv("TODERO_TERM_NATIVE_LIB_PATH"));
      assertEquals("""
          # Your component settings
          TODERO_TERM_ALLOWED_WORKSPACES=/tmp;/var/tmp
          TODERO_TERM_NATIVE_LIB_PATH=%s
          """.formatted(nativeLib), storage.readText(".env"));
    } finally {
      Files.deleteIfExists(nativeLib);
    }
  }

  @Test
  void settingsSave_rejectsRelativeWorkspaceEntry() throws Exception {
    InMemoryStorage storage = new InMemoryStorage();
    TermComponent component = new TermComponent(storage);
    Path nativeLib = Files.createTempFile("libtodero_term", ".so");
    try {
      CapturedResponse response = invoke(component, "settings_save", """
          {"allowedWorkspaces":"tmp","nativeLibPath":"%s"}
          """.formatted(nativeLib.toString()));

      assertEquals(400, response.statusCode());
      assertTrue(response.body().contains("allowed workspace entry must be an absolute path"));
    } finally {
      Files.deleteIfExists(nativeLib);
    }
  }

  @Test
  void settingsSave_updatesAllowlistUsedByResolver() throws Exception {
    InMemoryStorage storage = new InMemoryStorage();
    TermComponent component = new TermComponent(storage);
    Path root = Files.createTempDirectory("term-settings-root");
    Path nested = Files.createDirectories(root.resolve("a").resolve("b"));
    Path nativeLib = Files.createTempFile("libtodero_term", ".so");
    try {
      CapturedResponse response = invoke(component, "settings_save", """
          {"allowedWorkspaces":"%s","nativeLibPath":"%s"}
          """.formatted(root.toString(), nativeLib.toString()));

      assertEquals(200, response.statusCode());
      assertEquals(root.toRealPath(), component.resolveAllowedRootForCwd(nested.toString()));
      assertNull(component.resolveAllowedRootForCwd(root.resolveSibling("other").toString()));
    } finally {
      Files.deleteIfExists(nativeLib);
      Files.walk(root)
          .sorted((a, b) -> b.getNameCount() - a.getNameCount())
          .forEach(path -> {
            try {
              Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
          });
    }
  }

  @Test
  void html_rendersEmbeddedSettingsControlsWithCurrentValues() throws Exception {
    InMemoryStorage storage = new InMemoryStorage();
    storage.writeFile(".env", """
        TODERO_TERM_ALLOWED_WORKSPACES=/
        TODERO_TERM_NATIVE_LIB_PATH=/usr/lib/todero/native/term/current/aarch64/libtodero_term.so
        """.getBytes(StandardCharsets.UTF_8));
    TermComponent component = new TermComponent(storage);

    CapturedResponse response = invoke(component, "html", "");

    assertEquals(200, response.statusCode());
    assertTrue(response.contentType().contains("text/html"));
    assertTrue(response.body().contains("TODERO_TERM_ALLOWED_WORKSPACES"));
    assertTrue(response.body().contains("/usr/lib/todero/native/term/current/aarch64/libtodero_term.so"));
    assertTrue(response.body().contains("btn-settings-save"));
    assertTrue(response.body().contains("btn-settings-detect-native-lib"));
  }

  private static CapturedResponse invoke(TermComponent component, String action, String body) {
    AtomicReference<AiatpResponse> responseRef = new AtomicReference<>();
    CommandContext context = CommandContext.builder()
        .sourceId("test-" + action)
        .responseConsumer(responseRef::set)
        .aiatpRequest(AiatpRequest.builder()
            .method("ACTION")
            .target("/com.shellaia.term/" + action)
            .body(AiatpIO.Body.ofString(body == null ? "" : body, StandardCharsets.UTF_8))
            .build())
        .build();

      switch (action) {
      case "html" -> component.html(context);
      case "settings_save" -> component.settingsSave(context);
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
