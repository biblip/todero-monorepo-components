package com.shellaia.agent.context.prompt;

import java.util.List;

public record ContextAgentPrompt(
    AgentMode mode,
    String subjectId,
    String branchId,
    boolean branchContextIncluded,
    String renderedPrompt,
    List<String> sections
) {
  public ContextAgentPrompt {
    mode = mode == null ? AgentMode.GENERAL : mode;
    subjectId = subjectId == null ? "" : subjectId.trim();
    branchId = branchId == null ? "" : branchId.trim();
    renderedPrompt = renderedPrompt == null ? "" : renderedPrompt;
    sections = List.copyOf(sections == null ? List.of() : sections);
  }
}
