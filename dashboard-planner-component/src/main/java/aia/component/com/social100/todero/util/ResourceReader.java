package aia.component.com.social100.todero.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public class ResourceReader {
  public String loadResourceAsString(String resourcePath) {
    // resourcePath should start with "/" if using Class#getResourceAsStream
    try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
      if (is == null) {
        throw new IllegalArgumentException("Resource not found: " + resourcePath);
      }
      // Java 9+: you can call is.readAllBytes()
      byte[] bytes = is.readAllBytes();
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read resource: " + resourcePath, e);
    }
  }
}
