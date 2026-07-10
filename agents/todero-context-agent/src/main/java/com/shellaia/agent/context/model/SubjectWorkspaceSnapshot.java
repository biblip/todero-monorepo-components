package com.shellaia.agent.context.model;

import java.util.List;

public record SubjectWorkspaceSnapshot(
    SubjectIdentity identity,
    SubjectLedgerEntry ledgerEntry,
    SubjectBranchOverview branchOverview,
    SubjectMemoryView memoryView,
    BackgroundScanResult backgroundScanResult
) {
  public SubjectWorkspaceSnapshot {
    if (identity == null) {
      throw new IllegalArgumentException("identity is required");
    }
    memoryView = memoryView == null ? new SubjectMemoryView(List.of(), List.of(), List.of()) : memoryView;
    backgroundScanResult = backgroundScanResult == null ? new BackgroundScanResult(identity.subjectId(), List.of(), List.of()) : backgroundScanResult;
  }
}
