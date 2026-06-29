package com.shellaia.agent.context.model;

import java.util.List;

public record BackgroundScanResult(
    String subjectId,
    List<BackgroundMemoryCandidate> candidates,
    List<String> gitHints
) {
  public BackgroundScanResult {
    subjectId = subjectId == null ? "" : subjectId.trim();
    candidates = List.copyOf(candidates == null ? List.of() : candidates);
    gitHints = List.copyOf(gitHints == null ? List.of() : gitHints);
  }
}
