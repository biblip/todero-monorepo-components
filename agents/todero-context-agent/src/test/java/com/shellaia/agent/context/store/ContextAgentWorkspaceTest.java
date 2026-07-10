package com.shellaia.agent.context.store;

import com.shellaia.agent.context.model.BranchFocus;
import com.shellaia.agent.context.model.BranchManifest;
import com.shellaia.agent.context.model.BranchStatus;
import com.shellaia.agent.context.model.BackgroundScanResult;
import com.shellaia.agent.context.model.ConversationDurableKind;
import com.shellaia.agent.context.model.ConversationDurableRecord;
import com.shellaia.agent.context.model.ConversationDurableStatus;
import com.shellaia.agent.context.model.SubjectIdentity;
import com.shellaia.agent.context.model.SubjectLedger;
import com.shellaia.agent.context.model.SubjectMemoryKind;
import com.shellaia.agent.context.model.SubjectMemoryRecord;
import com.shellaia.agent.context.model.SubjectMemoryView;
import com.shellaia.agent.context.model.SubjectWorkspaceSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextAgentWorkspaceTest {
  @TempDir
  Path tempDir;

  @Test
  void createSubjectWritesIdentityAndDirectories() {
    ContextAgentWorkspace workspace = new ContextAgentWorkspace(tempDir);

    SubjectIdentity identity = workspace.createSubject("subject-alpha", "Alpha", "branch-main", Instant.parse("2025-01-01T00:00:00Z"));

    assertEquals("subject-alpha", identity.subjectId());
    assertEquals("Alpha", identity.displayName());
    assertTrue(Files.exists(workspace.subjectIdentityFile("subject-alpha")));
    assertTrue(Files.exists(workspace.subjectDir("subject-alpha")));
    assertTrue(Files.exists(workspace.root().resolve("subjects")));
    assertTrue(Files.exists(workspace.root().resolve("branches")));
    assertTrue(Files.exists(workspace.root().resolve("ledger")));
  }

  @Test
  void appendMemoryAndRememberKeepsOriginalTimestampAndReference() {
    ContextAgentWorkspace workspace = new ContextAgentWorkspace(tempDir);
    workspace.createSubject("subject-alpha", "Alpha", "branch-main", Instant.parse("2025-01-01T00:00:00Z"));

    SubjectMemoryRecord raw = SubjectMemoryRecord.raw(
        "record-1",
        SubjectMemoryKind.FACT,
        "The user said the subject should stay focused.",
        "subject-alpha",
        "branch-main",
        Instant.parse("2025-01-02T12:00:00Z")
    );

    workspace.appendRawRecord("subject-alpha", raw);

    SubjectMemoryRecord remembered = workspace.remember(
        "subject-alpha",
        raw,
        "Remembered: keep the subject focused.",
        Instant.parse("2025-01-05T12:00:00Z")
    );

    List<SubjectMemoryRecord> rawRecords = workspace.readRawRecords("subject-alpha");
    List<SubjectMemoryRecord> rememberedRecords = workspace.readRememberedRecords("subject-alpha");

    assertEquals(1, rawRecords.size());
    assertEquals("record-1", rawRecords.get(0).recordId());
    assertEquals(1, rememberedRecords.size());
    assertEquals(SubjectMemoryRecord.SCHEMA_VERSION, rememberedRecords.get(0).schemaVersion());
    assertEquals(SubjectMemoryKind.FACT, rememberedRecords.get(0).kind());
    assertEquals("Remembered: keep the subject focused.", rememberedRecords.get(0).content());
    assertEquals("record-1", rememberedRecords.get(0).sourceRecordId());
    assertEquals(Instant.parse("2025-01-02T12:00:00Z"), rememberedRecords.get(0).occurredAt());
    assertEquals(Instant.parse("2025-01-05T12:00:00Z"), rememberedRecords.get(0).capturedAt());
    assertEquals(remembered, rememberedRecords.get(0));
  }

  @Test
  void branchManifestAndLedgerRoundTrip() {
    ContextAgentWorkspace workspace = new ContextAgentWorkspace(tempDir);
    SubjectIdentity identity = workspace.createSubject("subject-alpha", "Alpha", "branch-main", Instant.parse("2025-01-01T00:00:00Z"));

    BranchManifest manifest = BranchManifest.draft(
        "branch-main",
        "subject-alpha",
        BranchFocus.SINGLE_SUBJECT,
        "Explore subject alpha context",
        "keep this branch focused",
        Instant.parse("2025-01-01T01:00:00Z")
    ).activated(Instant.parse("2025-01-01T02:00:00Z"));

    workspace.writeBranchManifest(manifest);
    workspace.registerSubject(identity, "Explore subject alpha context", manifest.status().name(), true);
    workspace.registerBranch(manifest);
    workspace.mapCanonicalBranch("subject-alpha", "branch-main");

    SubjectLedger ledger = workspace.readLedger();

    assertEquals(1, ledger.subjects().size());
    assertEquals(1, ledger.branches().size());
    assertEquals("branch-main", ledger.canonicalMap().get("subject-alpha"));
    assertEquals(BranchStatus.ACTIVE, ledger.branches().get("branch-main").status());
    assertEquals("Explore subject alpha context", ledger.subjects().get("subject-alpha").branchGoal());
    assertEquals("Alpha", ledger.subjects().get("subject-alpha").displayName());
  }

  @Test
  void readMemoryViewTrimsEachLayerAndScanBackgroundKeepsCandidatesAndNoGitHintsOutsideRepo() {
    ContextAgentWorkspace workspace = new ContextAgentWorkspace(tempDir);
    workspace.createSubject("subject-alpha", "Alpha", "branch-main", Instant.parse("2025-01-01T00:00:00Z"));

    SubjectMemoryRecord rawOne = SubjectMemoryRecord.raw(
        "raw-1",
        SubjectMemoryKind.FACT,
        "raw one",
        "subject-alpha",
        "branch-main",
        Instant.parse("2025-01-01T01:00:00Z")
    );
    SubjectMemoryRecord rawTwo = SubjectMemoryRecord.raw(
        "raw-2",
        SubjectMemoryKind.QUESTION,
        "raw two",
        "subject-alpha",
        "branch-main",
        Instant.parse("2025-01-01T02:00:00Z")
    );
    SubjectMemoryRecord derived = SubjectMemoryRecord.derived(
        "derived-1",
        SubjectMemoryKind.SUMMARY,
        "derived summary",
        "subject-alpha",
        "branch-main",
        Instant.parse("2025-01-01T03:00:00Z")
    );
    workspace.appendRawRecord("subject-alpha", rawOne);
    workspace.appendRawRecord("subject-alpha", rawTwo);
    workspace.appendDerivedRecord("subject-alpha", derived);

    SubjectMemoryView memoryView = workspace.readMemoryView("subject-alpha", 1, 1, 1);
    assertEquals(List.of(rawTwo), memoryView.rawRecords());
    assertEquals(List.of(derived), memoryView.derivedRecords());
    assertTrue(memoryView.rememberedRecords().isEmpty());

    BackgroundScanResult backgroundScanResult = workspace.scanBackground("subject-alpha", 1, 1, 1, 3);
    assertEquals("subject-alpha", backgroundScanResult.subjectId());
    assertEquals(2, backgroundScanResult.candidates().size());
    assertEquals("raw", backgroundScanResult.candidates().get(0).source());
    assertEquals(SubjectMemoryKind.QUESTION, backgroundScanResult.candidates().get(0).kind());
    assertEquals("raw two", backgroundScanResult.candidates().get(0).excerpt());
    assertTrue(backgroundScanResult.gitHints().isEmpty());
  }

  @Test
  void readSnapshotCombinesIdentityLedgerBranchAndMemoryLayers() {
    ContextAgentWorkspace workspace = new ContextAgentWorkspace(tempDir);
    SubjectIdentity identity = workspace.createSubject("subject-alpha", "Alpha", "branch-main", Instant.parse("2025-01-01T00:00:00Z"));
    BranchManifest manifest = BranchManifest.draft(
        "branch-main",
        "subject-alpha",
        BranchFocus.SINGLE_SUBJECT,
        "Explore subject alpha context",
        "keep this branch focused",
        Instant.parse("2025-01-01T01:00:00Z")
    ).activated(Instant.parse("2025-01-01T02:00:00Z"));
    workspace.writeBranchManifest(manifest);
    workspace.registerSubject(identity, "Explore subject alpha context", manifest.status().name(), true);
    workspace.registerBranch(manifest);
    workspace.mapCanonicalBranch("subject-alpha", "branch-main");

    SubjectMemoryRecord raw = SubjectMemoryRecord.raw(
        "raw-1",
        SubjectMemoryKind.FACT,
        "raw one",
        "subject-alpha",
        "branch-main",
        Instant.parse("2025-01-01T03:00:00Z")
    );
    workspace.appendRawRecord("subject-alpha", raw);

    SubjectWorkspaceSnapshot snapshot = workspace.readSnapshot("subject-alpha", 10, 10, 10, 2);
    assertNotNull(snapshot);
    assertEquals("subject-alpha", snapshot.identity().subjectId());
    assertNotNull(snapshot.ledgerEntry());
    assertEquals("branch-main", snapshot.branchOverview().manifest().branchId());
    assertEquals(List.of(raw), snapshot.memoryView().rawRecords());
    assertEquals("subject-alpha", snapshot.backgroundScanResult().subjectId());
  }

  @Test
  void appendConversationDurableWritesPerThreadDurablesFile() {
    ContextAgentWorkspace workspace = new ContextAgentWorkspace(tempDir);
    ConversationDurableRecord reminder = ConversationDurableRecord.reminder(
        "durable-1",
        "thread-alpha",
        "subject-alpha",
        "branch-main",
        "Buy wine for Friday",
        "I need to buy wine for the party on Friday.",
        Instant.parse("2025-01-01T04:00:00Z"),
        Instant.parse("2025-01-01T05:00:00Z")
    );

    workspace.appendConversationDurable("thread-alpha", reminder);

    assertTrue(Files.exists(workspace.conversationDurablesFile("thread-alpha")));
    List<ConversationDurableRecord> records = workspace.readConversationDurables("thread-alpha", 10);
    assertEquals(1, records.size());
    assertEquals(ConversationDurableKind.REMINDER, records.get(0).kind());
    assertEquals("Buy wine for Friday", records.get(0).content());
    assertEquals("subject-alpha", records.get(0).subjectId());
  }

  @Test
  void updateConversationDurableStatusRewritesMatchingRecordInPlace() {
    ContextAgentWorkspace workspace = new ContextAgentWorkspace(tempDir);
    ConversationDurableRecord reminder = ConversationDurableRecord.reminder(
        "durable-1",
        "thread-alpha",
        "subject-alpha",
        "branch-main",
        "Buy wine for Friday",
        "I need to buy wine for the party on Friday.",
        Instant.parse("2025-01-01T04:00:00Z"),
        Instant.parse("2025-01-01T05:00:00Z")
    );
    workspace.appendConversationDurable("thread-alpha", reminder);

    ConversationDurableRecord updated = workspace.updateConversationDurableStatus(
        "thread-alpha",
        "durable-1",
        ConversationDurableStatus.DONE,
        Instant.parse("2025-01-02T06:00:00Z")
    );

    assertNotNull(updated);
    assertEquals(ConversationDurableStatus.DONE, updated.status());
    assertEquals(Instant.parse("2025-01-01T04:00:00Z"), updated.occurredAt());
    assertEquals(Instant.parse("2025-01-02T06:00:00Z"), updated.capturedAt());

    List<ConversationDurableRecord> records = workspace.readConversationDurables("thread-alpha", 10);
    assertEquals(1, records.size());
    assertEquals(ConversationDurableStatus.DONE, records.get(0).status());
  }
}
