package com.shellaia.agent.context.runtime;

import com.shellaia.agent.context.classify.BranchSignalKind;
import com.shellaia.agent.context.classify.ContextMessageKind;
import com.shellaia.agent.context.model.BranchFocus;
import com.shellaia.agent.context.model.BranchManifest;
import com.shellaia.agent.context.model.BranchStatus;
import com.shellaia.agent.context.model.SubjectIdentity;
import com.shellaia.agent.context.model.SubjectMemoryKind;
import com.shellaia.agent.context.model.SubjectMemoryRecord;
import com.shellaia.agent.context.prompt.AgentMode;
import com.shellaia.agent.context.store.ContextAgentWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextAgentRuntimeTest {
  @TempDir
  Path tempDir;

  @Test
  void prepareTurnBuildsPromptAndClassificationFromSnapshot() {
    ContextAgentWorkspace workspace = new ContextAgentWorkspace(tempDir);
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
        "raw memory",
        "subject-alpha",
        "branch-main",
        Instant.parse("2025-01-01T03:00:00Z")
    ));

    ContextAgentRuntime runtime = new ContextAgentRuntime(workspace);
    ContextConversationTurn turn = runtime.prepareTurn(
        "subject-alpha",
        "I think we should open a branch to investigate this.",
        List.of("Recent note one"),
        List.of("TASK: follow up"),
        List.of("Draft subject alpha v2"),
        4,
        4,
        4,
        2
    );

    assertEquals("subject-alpha", turn.state().subjectId());
    assertEquals(ContextMessageKind.OPINION, turn.classification().kind());
    assertEquals(BranchSignalKind.OPEN, turn.classification().branchSignalKind());
    assertTrue(turn.confirmationRequired());
    assertTrue(turn.prompt().renderedPrompt().contains("Current mode: SUBJECT"));
    assertTrue(turn.prompt().renderedPrompt().contains("Branch context:"));
  }

  @Test
  void openMergeAndCloseBranchUpdateBranchStateWithoutChangingSubjectRestoration() {
    ContextAgentWorkspace workspace = new ContextAgentWorkspace(tempDir);
    SubjectIdentity identity = workspace.createSubject("subject-alpha", "Alpha", "branch-main", Instant.parse("2025-01-01T00:00:00Z"));
    BranchManifest mainManifest = BranchManifest.draft(
        "branch-main",
        "subject-alpha",
        BranchFocus.SINGLE_SUBJECT,
        "alpha goal",
        "alpha notes",
        Instant.parse("2025-01-01T01:00:00Z")
    ).activated(Instant.parse("2025-01-01T02:00:00Z"));
    workspace.writeBranchManifest(mainManifest);
    workspace.registerSubject(identity, "alpha goal", mainManifest.status().name(), true);
    workspace.registerBranch(mainManifest);
    workspace.mapCanonicalBranch("subject-alpha", "branch-main");

    ContextAgentRuntime runtime = new ContextAgentRuntime(workspace);
    BranchManifest branch = runtime.openBranch(
        "subject-alpha",
        "branch-investigation",
        BranchFocus.SINGLE_SUBJECT,
        "Investigate alternative framing",
        "branch notes",
        Instant.parse("2025-01-02T01:00:00Z")
    );
    assertEquals(BranchStatus.ACTIVE, branch.status());
    assertEquals("branch-main", workspace.readSubjectIdentity("subject-alpha").activeBranchId());
    assertEquals(BranchStatus.ACTIVE, workspace.readBranchManifest("branch-investigation").orElseThrow().status());

    BranchManifest merged = runtime.mergeBranch("branch-investigation", "merged after investigation", Instant.parse("2025-01-03T01:00:00Z"));
    assertEquals(BranchStatus.MERGED, merged.status());
    assertEquals("branch-investigation", workspace.readLedger().canonicalMap().get("subject-alpha"));
    assertEquals("branch-investigation", workspace.readSubjectIdentity("subject-alpha").activeBranchId());

    BranchManifest archived = runtime.closeBranch("branch-investigation", "closed after merge", Instant.parse("2025-01-04T01:00:00Z"));
    assertEquals(BranchStatus.ARCHIVED, archived.status());
    assertEquals(BranchStatus.ARCHIVED, workspace.readBranchManifest("branch-investigation").orElseThrow().status());
  }

  @Test
  void restoreSubjectReturnsGeneralModeWhenSubjectMissing() {
    ContextAgentWorkspace workspace = new ContextAgentWorkspace(tempDir);
    ContextAgentRuntime runtime = new ContextAgentRuntime(workspace);

    ContextRuntimeState state = runtime.restoreSubject("missing", 3, 3, 3, 1);

    assertEquals("", state.subjectId());
    assertEquals("", state.branchId());
    assertEquals(AgentMode.GENERAL, state.mode());
    assertNull(state.snapshot());
  }
}
