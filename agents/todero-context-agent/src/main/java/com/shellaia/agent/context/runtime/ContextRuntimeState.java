package com.shellaia.agent.context.runtime;

import com.shellaia.agent.context.prompt.AgentMode;
import com.shellaia.agent.context.model.SubjectWorkspaceSnapshot;

public record ContextRuntimeState(
    AgentMode mode,
    String subjectId,
    String branchId,
    SubjectWorkspaceSnapshot snapshot
) {
  public ContextRuntimeState {
    mode = mode == null ? AgentMode.GENERAL : mode;
    subjectId = subjectId == null ? "" : subjectId.trim();
    branchId = branchId == null ? "" : branchId.trim();
  }

  public static ContextRuntimeState empty() {
    return new ContextRuntimeState(AgentMode.GENERAL, "", "", null);
  }
}
