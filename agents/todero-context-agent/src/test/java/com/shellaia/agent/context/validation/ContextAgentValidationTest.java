package com.shellaia.agent.context.validation;

import com.shellaia.agent.context.classify.BranchSignalKind;
import com.shellaia.agent.context.classify.ContextMessageKind;
import com.shellaia.agent.context.model.BranchFocus;
import com.shellaia.agent.context.model.BranchManifest;
import com.shellaia.agent.context.model.BranchStatus;
import com.shellaia.agent.context.model.SubjectIdentity;
import com.shellaia.agent.context.model.SubjectMemoryKind;
import com.shellaia.agent.context.model.SubjectMemoryRecord;
import com.shellaia.agent.context.model.SubjectMemoryView;
import com.shellaia.agent.context.runtime.ContextAgentRuntime;
import com.shellaia.agent.context.runtime.ContextConversationTurn;
import com.shellaia.agent.context.runtime.ContextRuntimeState;
import com.shellaia.agent.context.store.ContextAgentWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextAgentValidationTest {
  @TempDir
  Path tempDir;

  @Test
  void curationPreservesRawHistoryAndPromotesRememberedRecordsWithOriginalMetadata() {
    ContextAgentWorkspace workspace = new ContextAgentWorkspace(tempDir);
    workspace.createSubject("subject-alpha", "Alpha", "branch-main", Instant.parse("2025-01-01T00:00:00Z"));

    SubjectMemoryRecord raw = SubjectMemoryRecord.raw(
        "raw-1",
        SubjectMemoryKind.FACT,
        "The first raw note.",
        "subject-alpha",
        "branch-main",
        Instant.parse("2025-01-01T01:00:00Z")
    );
    workspace.appendRawRecord("subject-alpha", raw);
    workspace.remember("subject-alpha", raw, "Remembered: keep the first raw note.", Instant.parse("2025-01-02T01:00:00Z"));

    assertEquals(1, workspace.readRawRecords("subject-alpha").size());
    assertEquals(1, workspace.readRememberedRecords("subject-alpha").size());
    assertEquals(Instant.parse("2025-01-01T01:00:00Z"), workspace.readRememberedRecords("subject-alpha").get(0).occurredAt());
    assertEquals("raw-1", workspace.readRememberedRecords("subject-alpha").get(0).sourceRecordId());
  }

  @Test
  void consciousLayerUsesCuratedMemoryWhileBackgroundLayerCanStillSurfaceRawCandidates() {
    ContextAgentWorkspace workspace = new ContextAgentWorkspace(tempDir);
    workspace.createSubject("subject-alpha", "Alpha", "branch-main", Instant.parse("2025-01-01T00:00:00Z"));

    SubjectMemoryRecord raw = SubjectMemoryRecord.raw(
        "raw-1",
        SubjectMemoryKind.FACT,
        "Raw memory that should stay out of the conscious summary.",
        "subject-alpha",
        "branch-main",
        Instant.parse("2025-01-01T01:00:00Z")
    );
    SubjectMemoryRecord derived = SubjectMemoryRecord.derived(
        "derived-1",
        SubjectMemoryKind.SUMMARY,
        "Derived memory that belongs in the conscious summary.",
        "subject-alpha",
        "branch-main",
        Instant.parse("2025-01-01T02:00:00Z")
    );
    workspace.appendRawRecord("subject-alpha", raw);
    workspace.appendDerivedRecord("subject-alpha", derived);

    SubjectMemoryView memoryView = workspace.readMemoryView("subject-alpha", 8, 8, 8);
    String summary = memoryView.currentSummary(8, 1024);
    assertFalse(summary.contains("Raw memory that should stay out of the conscious summary."));
    assertTrue(summary.contains("Derived memory that belongs in the conscious summary."));

    assertEquals(1, workspace.scanBackground("subject-alpha", 0, 1, 0, 0).candidates().size());
    assertTrue(workspace.scanBackground("subject-alpha", 0, 1, 0, 0).gitHints().isEmpty());
  }

  @Test
  void branchTrackingSurvivesSubjectSwitchesAndMergedBranchesRemainSummarizedInTheLedger() {
    ContextAgentWorkspace workspace = new ContextAgentWorkspace(tempDir);
    ContextAgentRuntime runtime = new ContextAgentRuntime(workspace);

    SubjectIdentity alpha = workspace.createSubject("subject-alpha", "Alpha", "alpha-main", Instant.parse("2025-01-01T00:00:00Z"));
    BranchManifest alphaBranch = BranchManifest.draft(
        "alpha-main",
        "subject-alpha",
        BranchFocus.SINGLE_SUBJECT,
        "alpha goal",
        "alpha notes",
        Instant.parse("2025-01-01T01:00:00Z")
    ).activated(Instant.parse("2025-01-01T02:00:00Z"));
    workspace.writeBranchManifest(alphaBranch);
    workspace.registerSubject(alpha, "alpha goal", alphaBranch.status().name(), true);
    workspace.registerBranch(alphaBranch);
    workspace.mapCanonicalBranch("subject-alpha", "alpha-main");

    SubjectIdentity beta = workspace.createSubject("subject-beta", "Beta", "beta-main", Instant.parse("2025-01-01T03:00:00Z"));
    BranchManifest betaBranch = BranchManifest.draft(
        "beta-main",
        "subject-beta",
        BranchFocus.SINGLE_SUBJECT,
        "beta goal",
        "beta notes",
        Instant.parse("2025-01-01T04:00:00Z")
    ).activated(Instant.parse("2025-01-01T05:00:00Z"));
    workspace.writeBranchManifest(betaBranch);
    workspace.registerSubject(beta, "beta goal", betaBranch.status().name(), true);
    workspace.registerBranch(betaBranch);
    workspace.mapCanonicalBranch("subject-beta", "beta-main");

    ContextRuntimeState switched = runtime.switchSubject("subject-beta", 4, 4, 4, 0);
    assertEquals("subject-beta", switched.subjectId());
    assertEquals("beta-main", switched.branchId());
    assertEquals("alpha-main", workspace.readSubjectIdentity("subject-alpha").activeBranchId());
    assertEquals("beta-main", workspace.readSubjectIdentity("subject-beta").activeBranchId());

    BranchManifest merged = runtime.mergeBranch("beta-main", "merged beta branch", Instant.parse("2025-01-02T01:00:00Z"));
    assertEquals(BranchStatus.MERGED, merged.status());
    assertEquals(BranchStatus.MERGED, workspace.readLedger().branches().get("beta-main").status());
    assertEquals("beta-main", workspace.readLedger().canonicalMap().get("subject-beta"));
    assertTrue(workspace.readSnapshot("subject-beta", 4, 4, 4, 0).branchOverview().summary().contains("MERGED"));
  }

  @Test
  void runtimeTurnPrepKeepsClassificationAndBranchSignalsDistinct() {
    ContextAgentWorkspace workspace = new ContextAgentWorkspace(tempDir);
    SubjectIdentity identity = workspace.createSubject("subject-alpha", "Alpha", "alpha-main", Instant.parse("2025-01-01T00:00:00Z"));
    BranchManifest branch = BranchManifest.draft(
        "alpha-main",
        "subject-alpha",
        BranchFocus.SINGLE_SUBJECT,
        "alpha goal",
        "alpha notes",
        Instant.parse("2025-01-01T01:00:00Z")
    ).activated(Instant.parse("2025-01-01T02:00:00Z"));
    workspace.writeBranchManifest(branch);
    workspace.registerSubject(identity, "alpha goal", branch.status().name(), true);
    workspace.registerBranch(branch);
    workspace.mapCanonicalBranch("subject-alpha", "alpha-main");

    ContextConversationTurn turn = new ContextAgentRuntime(workspace).prepareTurn(
        "subject-alpha",
        "I think we should open a branch.",
        List.of("Turn one"),
        List.of(),
        List.of("Draft alpha"),
        4,
        4,
        4,
        0
    );

    assertEquals(ContextMessageKind.OPINION, turn.classification().kind());
    assertEquals(BranchSignalKind.OPEN, turn.classification().branchSignalKind());
    assertTrue(turn.confirmationRequired());
    assertTrue(turn.prompt().renderedPrompt().contains("Current mode: SUBJECT"));
  }
}
