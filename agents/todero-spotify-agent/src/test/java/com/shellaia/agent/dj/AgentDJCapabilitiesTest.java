package com.shellaia.agent.dj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.social100.todero.common.ai.llm.LLMClient;
import com.social100.todero.common.ai.llm.LLMInstance;
import com.social100.todero.common.ai.llm.LLMProviderDefinition;
import com.social100.todero.common.ai.llm.LLMRegistry;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.aiatpio.AiatpResponse;
import com.social100.todero.common.base.ComponentManagerInterface;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.model.component.ComponentDescriptor;
import com.social100.todero.common.routing.ToolCapabilityManifest;
import com.social100.todero.common.routing.ToolCommandSchema;
import com.social100.todero.common.storage.Storage;
import com.social100.todero.console.base.OutputType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDJCapabilitiesTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void capabilitiesReturnsManifestSkillSummary() throws Exception {
    AgentDJComponent component = new AgentDJComponent(new EmptyStorage());
    AtomicReference<AiatpResponse> out = new AtomicReference<>();
    CommandContext context = CommandContext.builder()
        .sourceId("dj-cap-test")
        .componentManager(new FakeComponentManager())
        .responseConsumer(out::set)
        .llmRegistry(fakeRegistry())
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.dj/capabilities",
            AiatpIO.Body.ofString("", StandardCharsets.UTF_8)))
        .build();

    assertTrue(component.capabilities(context));

    assertEquals("status", out.get().getChannel());
    assertEquals("capabilities", out.get().getResponseReason());
    JsonNode root = JSON.readTree(AiatpIO.bodyToString(out.get().getBody(), StandardCharsets.UTF_8));
    assertEquals("Handles music playback, playlists, recommendations, and delegated Spotify authorization through a specialized DJ workflow.",
        root.path("manifest").path("routingHints").path("skillSummary").asText());
    assertEquals("Handles Spotify music playback and playlist workflows.",
        root.path("manifest").path("routingHints").path("oneLineSkillSummary").asText());
  }

  private static LLMRegistry fakeRegistry() {
    LLMClient client = (systemPrompt, userPrompt, contextJson) ->
        "{\"skillSummary\":\"Handles music playback, playlists, recommendations, and delegated Spotify authorization through a specialized DJ workflow.\","
            + "\"oneLineSkillSummary\":\"Handles Spotify music playback and playlist workflows.\"}";
    LLMInstance instance = new LLMInstance(
        new LLMProviderDefinition("test", "external", "planner", "test", true, 100, java.util.Set.of("system"), Map.of()),
        client
    );
    return new LLMRegistry() {
      @Override
      public List<LLMInstance> list() {
        return List.of(instance);
      }

      @Override
      public List<LLMInstance> list(String category) {
        return List.of(instance);
      }

      @Override
      public Optional<LLMInstance> get(String name) {
        return Optional.of(instance);
      }

      @Override
      public Optional<LLMInstance> select(String category, String explicitName) {
        return Optional.of(instance);
      }

      @Override
      public Optional<LLMInstance> system() {
        return Optional.of(instance);
      }
    };
  }

  private static final class FakeComponentManager implements ComponentManagerInterface {
    @Override
    public List<String> generateAutocompleteStrings() {
      return List.of();
    }

    @Override
    public String getHelp(String componentName, String commandName, OutputType outputType) {
      return "";
    }

    @Override
    public java.util.List<ComponentDescriptor> getComponents(boolean includeHidden, ServerType typeFilter) {
      return List.of(getComponent("com.shellaia.spotify", true));
    }

    @Override
    public ComponentDescriptor getComponent(String componentName, boolean includeHidden) {
      if (!"com.shellaia.spotify".equals(componentName)) {
        return null;
      }
      return ComponentDescriptor.builder()
          .name("com.shellaia.spotify")
          .type(ServerType.AIA)
          .componentVersion("1.0.0")
          .toolCapabilityManifest(ToolCapabilityManifest.builder()
              .contractVersion(1)
              .componentName("com.shellaia.spotify")
              .componentVersion("1.0.0")
              .toolSummary("Spotify playback tool")
              .commands(List.of(ToolCommandSchema.builder().name("play").description("Play content").build()))
              .build())
          .build();
    }

    @Override
    public void execute(String componentName, String command, CommandContext context, boolean useComponentsAll) {
    }
  }

  private static final class EmptyStorage implements Storage {
    @Override
    public void writeFile(String relativePath, byte[] bytes) {
    }

    @Override
    public byte[] readFile(String relativePath) throws IOException {
      throw new IOException("not found");
    }

    @Override
    public void deleteFile(String relativePath) {
    }

    @Override
    public List<String> listFiles(String relativeDir) {
      return List.of();
    }

    @Override
    public void putSecret(String key, String value) {
    }

    @Override
    public String getSecret(String key) {
      return null;
    }

    @Override
    public void deleteSecret(String key) {
    }
  }
}
