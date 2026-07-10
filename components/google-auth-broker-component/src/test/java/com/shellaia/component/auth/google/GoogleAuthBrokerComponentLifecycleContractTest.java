package com.shellaia.component.auth.google;

import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.aiatpio.AiatpResponse;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.generated.GoogleAuthBrokerComponentImpl;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoogleAuthBrokerComponentLifecycleContractTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void constructorRegistersToolCapabilitiesAndHtmlAction() {
    GoogleAuthBrokerComponentImpl component = new GoogleAuthBrokerComponentImpl((eventName, wrapper) -> {
    }, new EmptyStorage());

    assertNotNull(component.getComponentDescriptor());
    assertNotNull(component.getComponentDescriptor().getToolCapabilityManifest());
    assertEquals("com.shellaia.auth.google", component.getComponentDescriptor().getName());
    assertTrue(component.getComponentDescriptor().getToolCapabilityManifest().getCommands().stream()
        .anyMatch(command -> "capabilities".equals(command.getName())));
  }

  @Test
  void capabilitiesActionReturnsManifest() {
    GoogleAuthBrokerComponentImpl component = new GoogleAuthBrokerComponentImpl((eventName, wrapper) -> {
    }, new EmptyStorage());
    AtomicReference<AiatpResponse> seen = new AtomicReference<>();

    CommandContext context = CommandContext.builder()
        .sourceId("google-auth-capabilities")
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.auth.google/capabilities", AiatpIO.Body.ofString("", StandardCharsets.UTF_8)))
        .responseConsumer(seen::set)
        .build();

    assertTrue(component.execute("com.shellaia.auth.google", "capabilities", context));

    assertNotNull(seen.get());
    assertEquals(200, seen.get().getStatusCode());
    assertEquals("completed", seen.get().getReasonPhrase());
    String body = AiatpIO.bodyToString(seen.get().getBody(), StandardCharsets.UTF_8);
    JsonNode parsed = read(body);
    assertTrue(parsed.path("manifest").isObject());
    assertEquals("com.shellaia.auth.google", parsed.path("manifest").path("componentName").asText(""));
  }

  private JsonNode read(String jsonText) {
    try {
      return json.readTree(jsonText);
    } catch (Exception e) {
      throw new AssertionError("Unable to parse JSON: " + jsonText, e);
    }
  }

  private static final class EmptyStorage implements com.social100.todero.common.storage.Storage {
    @Override
    public void writeFile(String relativePath, byte[] bytes) {
      throw new UnsupportedOperationException();
    }

    @Override
    public byte[] readFile(String relativePath) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteFile(String relativePath) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<String> listFiles(String relativeDir) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void putSecret(String key, String value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getSecret(String key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteSecret(String key) {
      throw new UnsupportedOperationException();
    }
  }
}
