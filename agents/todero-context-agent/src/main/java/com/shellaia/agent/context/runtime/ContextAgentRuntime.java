package com.shellaia.agent.context.runtime;

import com.shellaia.agent.context.classify.ContextMessageClassification;
import com.shellaia.agent.context.classify.ContextMessageClassifier;
import com.shellaia.agent.context.model.BackgroundScanResult;
import com.shellaia.agent.context.model.BranchFocus;
import com.shellaia.agent.context.model.BranchManifest;
import com.shellaia.agent.context.model.SubjectIdentity;
import com.shellaia.agent.context.model.SubjectLedger;
import com.shellaia.agent.context.model.SubjectLedgerEntry;
import com.shellaia.agent.context.model.SubjectWorkspaceSnapshot;
import com.shellaia.agent.context.prompt.AgentMode;
import com.shellaia.agent.context.prompt.ContextAgentPrompt;
import com.shellaia.agent.context.prompt.ContextAgentPromptBuilder;
import com.shellaia.agent.context.store.ContextAgentWorkspace;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class ContextAgentRuntime {
  private final ContextAgentWorkspace workspace;
  private final ContextAgentPromptBuilder promptBuilder;
  private final ContextMessageClassifier classifier;

  public ContextAgentRuntime(ContextAgentWorkspace workspace) {
    this(workspace, new ContextAgentPromptBuilder(), new ContextMessageClassifier());
  }

  ContextAgentRuntime(ContextAgentWorkspace workspace, ContextAgentPromptBuilder promptBuilder, ContextMessageClassifier classifier) {
    this.workspace = Objects.requireNonNull(workspace, "workspace");
    this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
    this.classifier = Objects.requireNonNull(classifier, "classifier");
  }

  public ContextRuntimeState restoreSubject(String subjectId, int maxRaw, int maxDerived, int maxRemembered, int gitHintsLimit) {
    if (subjectId == null || subjectId.isBlank()) {
      return ContextRuntimeState.empty();
    }
    SubjectWorkspaceSnapshot snapshot = workspace.readSnapshot(subjectId, maxRaw, maxDerived, maxRemembered, gitHintsLimit);
    if (snapshot == null) {
      return ContextRuntimeState.empty();
    }
    String branchId = firstNonBlank(snapshot.identity().activeBranchId(), snapshot.branchOverview() == null ? "" : snapshot.branchOverview().manifest().branchId());
    return new ContextRuntimeState(AgentMode.SUBJECT, snapshot.identity().subjectId(), branchId, snapshot);
  }

  public ContextConversationTurn prepareTurn(String subjectId,
                                              String message,
                                              List<String> recentInteractions,
                                              List<String> durableInteractions,
                                              List<String> draftCandidates,
                                              int maxRaw,
                                              int maxDerived,
                                              int maxRemembered,
                                              int gitHintsLimit) {
    ContextRuntimeState state = restoreSubject(subjectId, maxRaw, maxDerived, maxRemembered, gitHintsLimit);
    SubjectWorkspaceSnapshot snapshot = state.snapshot();
    ContextMessageClassification classification = classifier.classify(message, snapshot == null ? "" : snapshot.identity().displayName());
    ContextAgentPrompt prompt = promptBuilder.build(state.mode(), snapshot, recentInteractions, durableInteractions, draftCandidates);
    boolean confirmationRequired = classification.confirmationRequired();
    String confirmationMessage = buildConfirmationMessage(classification, snapshot);
    return new ContextConversationTurn(state, snapshot, prompt, classification, confirmationRequired, confirmationMessage);
  }

  public BackgroundScanResult surfaceBackground(String subjectId, int maxRaw, int maxDerived, int maxRemembered, int gitHintsLimit) {
    if (subjectId == null || subjectId.isBlank()) {
      return new BackgroundScanResult("", List.of(), List.of());
    }
    return workspace.scanBackground(subjectId, maxRaw, maxDerived, maxRemembered, gitHintsLimit);
  }

  public BranchManifest openBranch(String subjectId, String branchId, BranchFocus focus, String goal, String notes, Instant now) {
    BranchManifest manifest = BranchManifest.draft(branchId, subjectId, focus, goal, notes, now).activated(now);
    workspace.writeBranchManifest(manifest);
    workspace.registerBranch(manifest);
    return manifest;
  }

  public ContextRuntimeState activateBranch(String subjectId, String branchId, Instant now) {
    SubjectIdentity identity = workspace.readSubjectIdentity(subjectId);
    if (identity == null) {
      return ContextRuntimeState.empty();
    }
    SubjectIdentity updatedIdentity = identity.withActiveBranchId(branchId, now);
    workspace.writeSubjectIdentity(updatedIdentity);
    SubjectLedger ledger = workspace.registerSubject(updatedIdentity, "", "ACTIVE", true);
    workspace.mapCanonicalBranch(subjectId, branchId);
    SubjectWorkspaceSnapshot snapshot = workspace.readSnapshot(subjectId, 8, 8, 8, 0);
    return new ContextRuntimeState(AgentMode.SUBJECT, subjectId, branchId, snapshot);
  }

  public BranchManifest mergeBranch(String branchId, String outcome, Instant now) {
    BranchManifest existing = workspace.readBranchManifest(branchId).orElseThrow(() -> new IllegalArgumentException("branch not found: " + branchId));
    BranchManifest merged = existing.merged(outcome, now);
    workspace.writeBranchManifest(merged);
    workspace.registerBranch(merged);
    workspace.mapCanonicalBranch(merged.subjectId(), merged.branchId());
    SubjectIdentity identity = workspace.readSubjectIdentity(merged.subjectId());
    if (identity != null) {
      SubjectIdentity updated = identity.withActiveBranchId(merged.branchId(), now);
      workspace.writeSubjectIdentity(updated);
      workspace.registerSubject(updated, merged.goal(), merged.status().name(), true);
    }
    return merged;
  }

  public BranchManifest closeBranch(String branchId, String outcome, Instant now) {
    BranchManifest existing = workspace.readBranchManifest(branchId).orElseThrow(() -> new IllegalArgumentException("branch not found: " + branchId));
    BranchManifest archived = existing.archived(outcome, now);
    workspace.writeBranchManifest(archived);
    workspace.registerBranch(archived);
    workspace.archiveBranch(branchId);
    return archived;
  }

  public ContextRuntimeState switchSubject(String subjectId, int maxRaw, int maxDerived, int maxRemembered, int gitHintsLimit) {
    return restoreSubject(subjectId, maxRaw, maxDerived, maxRemembered, gitHintsLimit);
  }

  private static String buildConfirmationMessage(ContextMessageClassification classification, SubjectWorkspaceSnapshot snapshot) {
    if (!classification.subjectSwitchSuggested()) {
      return classification.confirmationRequired() ? "Confirmation required for this action." : "";
    }
    String subject = classification.candidateSubject();
    if (snapshot == null) {
      return "Confirm switch to subject: " + subject;
    }
    return "Confirm switch from " + snapshot.identity().displayName() + " to " + subject;
  }

  private static String firstNonBlank(String first, String second) {
    if (first != null && !first.isBlank()) {
      return first.trim();
    }
    if (second != null && !second.isBlank()) {
      return second.trim();
    }
    return "";
  }
}
