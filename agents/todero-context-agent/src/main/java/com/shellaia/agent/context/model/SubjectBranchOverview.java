package com.shellaia.agent.context.model;

public record SubjectBranchOverview(
    BranchManifest manifest,
    String summary
) {
  public SubjectBranchOverview {
    if (manifest == null) {
      throw new IllegalArgumentException("manifest is required");
    }
    summary = summary == null ? "" : summary.trim();
  }

  public static SubjectBranchOverview from(BranchManifest manifest) {
    String summary = manifest.subjectId() + " / " + manifest.branchId() + " / " + manifest.status() + " / " + manifest.focus();
    if (!manifest.goal().isBlank()) {
      summary = summary + " / goal=" + manifest.goal();
    }
    if (!manifest.outcome().isBlank()) {
      summary = summary + " / outcome=" + manifest.outcome();
    }
    return new SubjectBranchOverview(manifest, summary);
  }
}
