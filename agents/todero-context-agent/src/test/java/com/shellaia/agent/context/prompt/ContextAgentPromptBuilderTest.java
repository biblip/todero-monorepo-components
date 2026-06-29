package com.shellaia.agent.context.prompt;

import com.shellaia.agent.context.model.BackgroundMemoryCandidate;
import com.shellaia.agent.context.model.BackgroundScanResult;
import com.shellaia.agent.context.model.BranchFocus;
import com.shellaia.agent.context.model.BranchManifest;
import com.shellaia.agent.context.model.SubjectIdentity;
import com.shellaia.agent.context.model.SubjectLedgerEntry;
import com.shellaia.agent.context.model.SubjectMemoryKind;
import com.shellaia.agent.context.model.SubjectMemoryRecord;
import com.shellaia.agent.context.model.SubjectMemoryView;
import com.shellaia.agent.context.model.SubjectBranchOverview;
import com.shellaia.agent.context.model.SubjectWorkspaceSnapshot;
import com.shellaia.agent.context.store.ContextAgentWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextAgentPromptBuilderTest {
  @TempDir
  Path tempDir;

  @Test
  void buildIncludesBranchContextOnlyWhenAvailable() {
    ContextAgentWorkspace workspace = new ContextAgentWorkspace(tempDir);
    SubjectIdentity identity = workspace.createSubject("subject-alpha", "Alpha", "branch-main", Instant.parse("2025-01-01T00:00:00Z"));
    BranchManifest manifest = BranchManifest.draft(
        "branch-main",
        "subject-alpha",
        BranchFocus.SINGLE_SUBJECT,
        "explore alpha",
        "branch notes",
        Instant.parse("2025-01-01T01:00:00Z")
    ).activated(Instant.parse("2025-01-01T02:00:00Z"));
    workspace.writeBranchManifest(manifest);
    SubjectLedgerEntry ledgerEntry = SubjectLedgerEntry.from(identity, "explore alpha", manifest.status().name(), true);
    SubjectMemoryRecord raw = SubjectMemoryRecord.raw(
        "raw-1",
        SubjectMemoryKind.FACT,
        "raw memory",
        "subject-alpha",
        "branch-main",
        Instant.parse("2025-01-01T03:00:00Z")
    );
    SubjectMemoryView memoryView = new SubjectMemoryView(List.of(raw), List.of(), List.of());
    BackgroundScanResult background = new BackgroundScanResult(
        "subject-alpha",
        List.of(BackgroundMemoryCandidate.from("raw", raw)),
        List.of("a1b2c3 subject-alpha")
    );
    SubjectWorkspaceSnapshot snapshot = new SubjectWorkspaceSnapshot(identity, ledgerEntry, SubjectBranchOverview.from(manifest), memoryView, background);

    ContextAgentPrompt prompt = new ContextAgentPromptBuilder().build(
        AgentMode.SUBJECT,
        snapshot,
        List.of("User asked about alpha"),
        List.of("TASK: follow up with alpha"),
        List.of("Draft candidate: alpha-v2")
    );

    assertEquals(AgentMode.SUBJECT, prompt.mode());
    assertEquals("subject-alpha", prompt.subjectId());
    assertTrue(prompt.branchContextIncluded());
    assertTrue(prompt.renderedPrompt().contains("Branch context:"));
    assertTrue(prompt.renderedPrompt().contains("Recent interactions:"));
    assertTrue(prompt.renderedPrompt().contains("Durable reminders and tasks:"));
    assertTrue(prompt.renderedPrompt().contains("Draft subject candidates:"));
    assertTrue(prompt.renderedPrompt().contains("Git hints:"));
    assertTrue(prompt.renderedPrompt().contains("Output style:"));
    assertTrue(prompt.renderedPrompt().contains("do not repeatedly ask \"how can I help\""));
    assertTrue(prompt.renderedPrompt().contains("never repeat the same clarification once the user has already answered it"));
    assertTrue(prompt.renderedPrompt().contains("only ask for confirmation when the current subject is genuinely ambiguous"));
    assertTrue(prompt.renderedPrompt().contains("surface durable reminders and tasks proactively when they are already known"));
    assertTrue(prompt.renderedPrompt().contains("whenever you claim that you changed something, verify the result first"));
  }

  @Test
  void buildOmitsBranchContextWhenSnapshotHasNoBranchOverview() {
    ContextAgentWorkspace workspace = new ContextAgentWorkspace(tempDir);
    SubjectIdentity identity = workspace.createSubject("subject-alpha", "Alpha", "", Instant.parse("2025-01-01T00:00:00Z"));
    SubjectWorkspaceSnapshot snapshot = new SubjectWorkspaceSnapshot(
        identity,
        null,
        null,
        new SubjectMemoryView(List.of(), List.of(), List.of()),
        new BackgroundScanResult("subject-alpha", List.of(), List.of())
    );

    ContextAgentPrompt prompt = new ContextAgentPromptBuilder().build(AgentMode.GENERAL, snapshot, List.of(), List.of(), List.of());

    assertFalse(prompt.branchContextIncluded());
    assertFalse(prompt.renderedPrompt().contains("Branch context:"));
    assertTrue(prompt.renderedPrompt().contains("Current mode: GENERAL"));
    assertTrue(prompt.renderedPrompt().contains("Active subject:"));
    assertTrue(prompt.renderedPrompt().contains("keep a small internal map of the active subject"));
    assertTrue(prompt.renderedPrompt().contains("promote user requests for reminders, tasks, decisions, and follow-ups into durable memory when safe"));
    assertTrue(prompt.renderedPrompt().contains("whenever you claim that you changed something, verify the result first"));
  }
}
