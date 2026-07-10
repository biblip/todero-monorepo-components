package com.shellaia.agent.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.social100.todero.common.ai.llm.LLMClient;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpResponse;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.storage.Storage;
import com.shellaia.agent.context.model.BranchFocus;
import com.shellaia.agent.context.model.BranchManifest;
import com.shellaia.agent.context.model.ConversationDurableKind;
import com.shellaia.agent.context.model.ConversationDurableRecord;
import com.shellaia.agent.context.model.ConversationDurableStatus;
import com.shellaia.agent.context.model.SubjectIdentity;
import com.shellaia.agent.context.model.SubjectMemoryKind;
import com.shellaia.agent.context.model.SubjectMemoryRecord;
import com.shellaia.agent.context.store.ContextAgentWorkspace;
import com.social100.todero.common.routing.AgentCapabilityManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextAgentComponentTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir
  Path tempDir;

  @Test
  void processRawTextReturnsGeneralModeBundle() throws Exception {
    StubLlmClient llm = new StubLlmClient("{\"reply\":\"Hello from the conversational context agent.\"}");
    ContextAgentComponent component = new ContextAgentComponent(new EmptyStorage(), tempDir.resolve("workspace"), ctx -> llm);
    AtomicReference<AiatpResponse> out = new AtomicReference<>();

    assertTrue(component.process(CommandContext.builder()
        .sourceId("context-agent-raw")
        .responseConsumer(out::set)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.context/process",
            AiatpIO.Body.ofString("Hello context agent.", StandardCharsets.UTF_8)))
        .build()));

    assertEquals(200, out.get().getStatusCode());
    String body = AiatpIO.bodyToString(out.get().getBody(), StandardCharsets.UTF_8);
    assertEquals("Hello from the conversational context agent.", body);
    assertTrue(llm.lastSystemPrompt.contains("You are a conversational context agent."));
    assertEquals("Hello context agent.", llm.lastUserPrompt);
  }

  @Test
  void processRemembersTheConversationAcrossTurnsForTheSameSource() throws Exception {
    StubLlmClient llm = new StubLlmClient(call -> {
      return switch (call.callIndex()) {
        case 0, 2 -> "{\"action\":\"none\"}";
        case 1 -> "{\"reply\":\"Would you like me to create a reminder or a subject for this?\"}";
        case 3 -> "{\"reply\":\"I can create a reminder for buying a needle to sew a button.\"}";
        default -> "{\"reply\":\"Unexpected turn.\"}";
      };
    });
    ContextAgentComponent component = new ContextAgentComponent(new EmptyStorage(), tempDir.resolve("workspace"), ctx -> llm);

    AtomicReference<AiatpResponse> first = new AtomicReference<>();
    assertTrue(component.process(CommandContext.builder()
        .sourceId("context-agent-thread")
        .responseConsumer(first::set)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.context/process",
            AiatpIO.Body.ofString("I need to remember to buy needle to sew a button.", StandardCharsets.UTF_8)))
        .build()));
    assertEquals("Would you like me to create a reminder or a subject for this?",
        AiatpIO.bodyToString(first.get().getBody(), StandardCharsets.UTF_8));

    AtomicReference<AiatpResponse> second = new AtomicReference<>();
    assertTrue(component.process(CommandContext.builder()
        .sourceId("context-agent-thread")
        .responseConsumer(second::set)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.context/process",
            AiatpIO.Body.ofString("yes", StandardCharsets.UTF_8)))
        .build()));

    assertEquals("I can create a reminder for buying a needle to sew a button.",
        AiatpIO.bodyToString(second.get().getBody(), StandardCharsets.UTF_8));
    assertTrue(llm.lastContextJson.contains("user: I need to remember to buy needle to sew a button."));
    assertTrue(llm.lastContextJson.contains("assistant: Would you like me to create a reminder or a subject for this?"));
    assertEquals("yes", llm.lastUserPrompt);
  }

  @Test
  void processJsonRequestRestoresSubjectAndIncludesBranchContext() throws Exception {
    ContextAgentWorkspace workspace = new ContextAgentWorkspace(tempDir.resolve("workspace"));
    SubjectIdentity identity = workspace.createSubject("subject-alpha", "Alpha", "branch-main", Instant.parse("2025-01-01T00:00:00Z"));
    BranchManifest manifest = BranchManifest.draft(
        "branch-main",
        "subject-alpha",
        BranchFocus.SINGLE_SUBJECT,
        "alpha goal",
        "alpha notes",
        Instant.parse("2025-01-01T01:00:00Z")
    ).activated(Instant.parse("2025-01-01T02:00:00Z"));
    workspace.writeBranchManifest(manifest);
    workspace.registerSubject(identity, "alpha goal", manifest.status().name(), true);
    workspace.registerBranch(manifest);
    workspace.mapCanonicalBranch("subject-alpha", "branch-main");
    workspace.appendRawRecord("subject-alpha", SubjectMemoryRecord.raw(
        "raw-1",
        SubjectMemoryKind.FACT,
        "Raw note",
        "subject-alpha",
        "branch-main",
        Instant.parse("2025-01-01T03:00:00Z")
    ));

    StubLlmClient llm = new StubLlmClient("{\"reply\":\"Stay on subject-alpha and confirm the branch switch.\"}");
    ContextAgentComponent component = new ContextAgentComponent(new EmptyStorage(), tempDir.resolve("workspace"), ctx -> llm);
    AtomicReference<AiatpResponse> out = new AtomicReference<>();

    assertTrue(component.process(CommandContext.builder()
        .sourceId("context-agent-subject")
        .responseConsumer(out::set)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.context/process",
            AiatpIO.Body.ofString("""
                {
                  "subjectId": "subject-alpha",
                  "message": "I think we should open a branch to investigate this.",
                  "recentInteractions": ["Recent note one"],
                  "draftCandidates": ["Draft subject alpha v2"],
                  "includeMetadata": true
                }
                """, StandardCharsets.UTF_8)))
        .build()));

    assertEquals(200, out.get().getStatusCode());
    JsonNode root = JSON.readTree(AiatpIO.bodyToString(out.get().getBody(), StandardCharsets.UTF_8));
    assertTrue(root.path("ok").asBoolean());
    assertEquals("Stay on subject-alpha and confirm the branch switch.", root.path("reply").asText());
    assertEquals("subject-alpha", root.path("subjectId").asText());
    assertEquals("branch-main", root.path("branchId").asText());
    assertEquals("SUBJECT", root.path("mode").asText());
    assertTrue(root.path("confirmationRequired").asBoolean());
    assertTrue(root.path("prompt").path("branchContextIncluded").asBoolean());
    assertTrue(root.path("prompt").path("renderedPrompt").asText().contains("Active subject:"));
    assertTrue(root.path("prompt").path("renderedPrompt").asText().contains("Branch context:"));
    assertTrue(llm.lastContextJson.contains("\"subjectId\":\"subject-alpha\""));
    assertTrue(llm.lastSystemPrompt.contains("Current mode: SUBJECT"));
    assertTrue(AiatpIO.bodyToString(out.get().getBody(), StandardCharsets.UTF_8).contains("\"ok\":true"));
  }

  @Test
  void processPersistsReminderLikeMessagesAsDurableThreadRecords() throws Exception {
    StubLlmClient llm = new StubLlmClient(call -> {
      return switch (call.callIndex()) {
        case 0 -> "{\"action\":\"create\",\"kind\":\"TASK\",\"content\":\"buy wine for the party on Friday\",\"reason\":\"user asked for a reminder/task\"}";
        case 1 -> "{\"reply\":\"Noted.\"}";
        default -> "{\"reply\":\"Unexpected stub call.\"}";
      };
    });
    ContextAgentComponent component = new ContextAgentComponent(new EmptyStorage(), tempDir.resolve("workspace"), ctx -> llm);
    AtomicReference<AiatpResponse> out = new AtomicReference<>();
    String sourceId = "context-agent-durable";
    String threadId = CommandContext.builder().sourceId(sourceId).build().getId();

    assertTrue(component.process(CommandContext.builder()
        .sourceId(sourceId)
        .responseConsumer(out::set)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.context/process",
            AiatpIO.Body.ofString("I need to buy wine for the party on Friday.", StandardCharsets.UTF_8)))
        .build()));

    ContextAgentWorkspace workspace = new ContextAgentWorkspace(tempDir.resolve("workspace"));
    List<ConversationDurableRecord> records = workspace.readConversationDurables(threadId, 10);
    assertEquals(1, records.size());
    assertEquals(ConversationDurableKind.TASK, records.get(0).kind());
    assertTrue(records.get(0).content().contains("buy wine for the party on Friday"));
    assertTrue(llm.lastContextJson.contains("created durable kind=TASK content=buy wine for the party on Friday"));
  }

  @Test
  void durablesListsCurrentThreadDurableRecords() throws Exception {
    StubLlmClient llm = new StubLlmClient(call -> {
      return switch (call.callIndex()) {
        case 0 -> "{\"action\":\"create\",\"kind\":\"TASK\",\"content\":\"buy milk tomorrow\",\"reason\":\"user asked for a task\"}";
        case 1 -> "{\"reply\":\"Noted.\"}";
        default -> "{\"reply\":\"Unexpected stub call.\"}";
      };
    });
    ContextAgentComponent component = new ContextAgentComponent(new EmptyStorage(), tempDir.resolve("workspace"), ctx -> llm);
    String sourceId = "context-agent-durable-list";
    String threadId = CommandContext.builder().sourceId(sourceId).build().getId();

    assertTrue(component.process(CommandContext.builder()
        .sourceId(sourceId)
        .responseConsumer(response -> {})
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.context/process",
            AiatpIO.Body.ofString("I need to buy milk tomorrow.", StandardCharsets.UTF_8)))
        .build()));

    AtomicReference<AiatpResponse> out = new AtomicReference<>();
    assertTrue(component.durables(CommandContext.builder()
        .sourceId(sourceId)
        .responseConsumer(out::set)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.context/durables",
            AiatpIO.Body.ofString("20", StandardCharsets.UTF_8)))
        .build()));

    assertEquals(200, out.get().getStatusCode());
    String body = AiatpIO.bodyToString(out.get().getBody(), StandardCharsets.UTF_8);
    assertTrue(body.contains("milk tomorrow"));
    assertTrue(body.contains("[TASK]"));

    ContextAgentWorkspace workspace = new ContextAgentWorkspace(tempDir.resolve("workspace"));
    assertEquals(1, workspace.readConversationDurables(threadId, 10).size());
  }

  @Test
  void durableSetMarksCurrentThreadRecordDone() throws Exception {
    AtomicReference<String> recordIdRef = new AtomicReference<>("");
    StubLlmClient llm = new StubLlmClient(call -> {
      return switch (call.callIndex()) {
        case 0 -> "{\"action\":\"create\",\"kind\":\"TASK\",\"content\":\"buy milk tomorrow\",\"reason\":\"user asked for a task\"}";
        case 1 -> "{\"reply\":\"Noted.\"}";
        case 2, 3 -> "{\"recordId\":\"" + recordIdRef.get() + "\",\"ambiguous\":false,\"reason\":\"selected by record id\"}";
        default -> "{\"reply\":\"Unexpected stub call.\"}";
      };
    });
    ContextAgentComponent component = new ContextAgentComponent(new EmptyStorage(), tempDir.resolve("workspace"), ctx -> llm);
    String sourceId = "context-agent-durable-set";
    String threadId = CommandContext.builder().sourceId(sourceId).build().getId();

    assertTrue(component.process(CommandContext.builder()
        .sourceId(sourceId)
        .responseConsumer(response -> {})
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.context/process",
            AiatpIO.Body.ofString("I need to buy milk tomorrow.", StandardCharsets.UTF_8)))
        .build()));

    ContextAgentWorkspace workspace = new ContextAgentWorkspace(tempDir.resolve("workspace"));
    List<ConversationDurableRecord> records = workspace.readConversationDurables(threadId, 10);
    assertEquals(1, records.size());
    String recordId = records.get(0).recordId();
    recordIdRef.set(recordId);

    AtomicReference<AiatpResponse> out = new AtomicReference<>();
    assertTrue(component.durableSet(CommandContext.builder()
        .sourceId(sourceId)
        .responseConsumer(out::set)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.context/durable-set",
            AiatpIO.Body.ofString("{\"target\":\"" + recordId + "\",\"status\":\"done\",\"refresh\":true,\"limit\":10}", StandardCharsets.UTF_8)))
        .build()));

    assertEquals(200, out.get().getStatusCode());
    String body = AiatpIO.bodyToString(out.get().getBody(), StandardCharsets.UTF_8);
    assertTrue(body.contains("\"verified\":true"));
    assertTrue(body.contains("\"status\":\"DONE\""));
    assertTrue(body.contains(recordId));

    AtomicReference<AiatpResponse> canceled = new AtomicReference<>();
    assertTrue(component.durableSet(CommandContext.builder()
        .sourceId(sourceId)
        .responseConsumer(canceled::set)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.context/durable-set",
            AiatpIO.Body.ofString("{\"target\":\"" + recordId + "\",\"status\":\"canceled\",\"refresh\":true,\"limit\":10}", StandardCharsets.UTF_8)))
        .build()));

    assertEquals(200, canceled.get().getStatusCode());
    String canceledBody = AiatpIO.bodyToString(canceled.get().getBody(), StandardCharsets.UTF_8);
    assertTrue(canceledBody.contains("\"verified\":true"));
    assertTrue(canceledBody.contains("\"status\":\"CANCELED\""));
    assertEquals(ConversationDurableStatus.CANCELED, workspace.readConversationDurables(threadId, 10).get(0).status());

    AtomicReference<AiatpResponse> activeDurables = new AtomicReference<>();
    assertTrue(component.durables(CommandContext.builder()
        .sourceId(sourceId)
        .responseConsumer(activeDurables::set)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.context/durables",
            AiatpIO.Body.ofString("{\"limit\":10,\"format\":\"json\",\"view\":\"active\"}", StandardCharsets.UTF_8)))
        .build()));
    JsonNode activeRoot = JSON.readTree(AiatpIO.bodyToString(activeDurables.get().getBody(), StandardCharsets.UTF_8));
    assertTrue(activeRoot.path("records").isArray());
    assertEquals(0, activeRoot.path("records").size());

    AtomicReference<AiatpResponse> finalizedDurables = new AtomicReference<>();
    assertTrue(component.durables(CommandContext.builder()
        .sourceId(sourceId)
        .responseConsumer(finalizedDurables::set)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.context/durables",
            AiatpIO.Body.ofString("{\"limit\":10,\"format\":\"json\",\"view\":\"finalized\",\"days\":30}", StandardCharsets.UTF_8)))
        .build()));
    JsonNode finalizedRoot = JSON.readTree(AiatpIO.bodyToString(finalizedDurables.get().getBody(), StandardCharsets.UTF_8));
    assertTrue(finalizedRoot.path("records").isArray());
    assertEquals(1, finalizedRoot.path("records").size());
    assertEquals("CANCELED", finalizedRoot.path("records").get(0).path("status").asText());
  }

  @Test
  void durableSetResolvesTargetPhraseInCurrentThread() throws Exception {
    ContextAgentWorkspace workspace = new ContextAgentWorkspace(tempDir.resolve("workspace"));
    String sourceId = "context-agent-phrase-thread";
    String threadId = CommandContext.builder().sourceId(sourceId).build().getId();
    workspace.appendConversationDurable(threadId, ConversationDurableRecord.task(
        "durable-jira-1",
        threadId,
        "",
        "",
        "Finish the Jira ticket",
        "I have to finish the Jira ticket today.",
        Instant.parse("2026-06-28T18:38:01Z"),
        Instant.parse("2026-06-28T18:38:01Z")
    ));

    ContextAgentComponent component = new ContextAgentComponent(new EmptyStorage(), tempDir.resolve("workspace"), ctx -> new StubLlmClient(call -> {
      return "{\"recordId\":\"durable-jira-1\",\"ambiguous\":false,\"reason\":\"selected by record id\"}";
    }));
    AtomicReference<AiatpResponse> out = new AtomicReference<>();

    assertTrue(component.durableSet(CommandContext.builder()
        .sourceId(sourceId)
        .responseConsumer(out::set)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.context/durable-set",
            AiatpIO.Body.ofString("{\"target\":\"the Jira ticket\",\"status\":\"done\",\"refresh\":true,\"limit\":10}", StandardCharsets.UTF_8)))
        .build()));

    assertEquals(200, out.get().getStatusCode());
    String body = AiatpIO.bodyToString(out.get().getBody(), StandardCharsets.UTF_8);
    assertTrue(body.contains("\"verified\":true"));
    assertTrue(body.contains("durable-jira-1"));
    assertTrue(body.contains("\"status\":\"DONE\""));
    assertEquals(ConversationDurableStatus.DONE, workspace.readConversationDurables(threadId, 10).get(0).status());
  }

  @Test
  void htmlRendersInteractiveContextSurface() throws Exception {
    ContextAgentComponent component = new ContextAgentComponent(new EmptyStorage(), tempDir.resolve("workspace"), ctx -> new StubLlmClient("{\"reply\":\"Ignored\"}"));
    AtomicReference<AiatpResponse> out = new AtomicReference<>();

    assertTrue(component.html(CommandContext.builder()
        .sourceId("context-agent-html")
        .responseConsumer(out::set)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.context/html",
            AiatpIO.Body.ofString("", StandardCharsets.UTF_8)))
        .build()));

    assertEquals(200, out.get().getStatusCode());
    String body = AiatpIO.bodyToString(out.get().getBody(), StandardCharsets.UTF_8);
    assertTrue(body.contains("Conversational Context Agent"));
    assertTrue(body.contains("Track durable reminders, tasks, and active subject context in one place."));
    assertFalse(body.contains("Subject id"));
    assertTrue(body.contains("Current context"));
    assertTrue(body.contains("Selected"));
    assertTrue(body.contains("Tracked"));
    assertTrue(body.contains("Refresh durables"));
    assertTrue(body.contains("Active tasks"));
    assertTrue(body.contains("Archived tasks"));
    assertTrue(body.contains("Archived window"));
    assertTrue(body.contains("all archived"));
    assertTrue(body.contains("Metadata: on"));
  }

  @Test
  void capabilitiesIncludeHtmlAndDurableLifecycleCommands() {
    AgentCapabilityManifest manifest = new ContextAgentCapabilities().manifest();
    List<String> commands = manifest.getCommands().stream().map(command -> command.getName()).toList();
    assertTrue(commands.contains("html"));
    assertTrue(commands.contains("durables"));
    assertTrue(commands.contains("durable-set"));
  }

  private static final class EmptyStorage implements Storage {
    @Override
    public void writeFile(String relativePath, byte[] bytes) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public byte[] readFile(String relativePath) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteFile(String relativePath) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<String> listFiles(String relativeDir) throws IOException {
      return List.of();
    }

    @Override
    public void putSecret(String key, String value) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getSecret(String key) throws IOException {
      return null;
    }

    @Override
    public void deleteSecret(String key) throws IOException {
      throw new UnsupportedOperationException();
    }
  }

  private static final class StubLlmClient implements LLMClient {
    private final Function<Call, String> responder;
    private final List<String> responses;
    private int index = 0;
    private String lastSystemPrompt = "";
    private String lastUserPrompt = "";
    private String lastContextJson = "";

    private StubLlmClient(String... responses) {
      this.responder = null;
      this.responses = List.of(responses);
    }

    private StubLlmClient(Function<Call, String> responder) {
      this.responder = responder;
      this.responses = List.of();
    }

    @Override
    public String chat(String systemPrompt, String userPrompt, String contextJson) {
      this.lastSystemPrompt = systemPrompt == null ? "" : systemPrompt;
      this.lastUserPrompt = userPrompt == null ? "" : userPrompt;
      this.lastContextJson = contextJson == null ? "" : contextJson;
      if (responder != null) {
        return responder.apply(new Call(lastSystemPrompt, lastUserPrompt, lastContextJson, index++));
      }
      if (responses.isEmpty()) {
        return "";
      }
      if (index < responses.size()) {
        return responses.get(index++);
      }
      return responses.get(responses.size() - 1);
    }

    private record Call(String systemPrompt, String userPrompt, String contextJson, int callIndex) {
    }
  }
}
