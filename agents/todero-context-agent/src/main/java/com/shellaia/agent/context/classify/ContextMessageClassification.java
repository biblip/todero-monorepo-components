package com.shellaia.agent.context.classify;

public record ContextMessageClassification(
    ContextMessageKind kind,
    double confidence,
    BranchSignalKind branchSignalKind,
    boolean subjectSwitchSuggested,
    boolean confirmationRequired,
    String candidateSubject,
    String rationale
) {
  public ContextMessageClassification {
    kind = kind == null ? ContextMessageKind.GENERAL : kind;
    confidence = Math.max(0.0d, Math.min(1.0d, confidence));
    branchSignalKind = branchSignalKind == null ? BranchSignalKind.NONE : branchSignalKind;
    candidateSubject = candidateSubject == null ? "" : candidateSubject.trim();
    rationale = rationale == null ? "" : rationale.trim();
  }
}
