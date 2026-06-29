package com.shellaia.agent.context.runtime;

import com.shellaia.agent.context.classify.ContextMessageClassification;
import com.shellaia.agent.context.prompt.ContextAgentPrompt;
import com.shellaia.agent.context.model.SubjectWorkspaceSnapshot;

public record ContextConversationTurn(
    ContextRuntimeState state,
    SubjectWorkspaceSnapshot snapshot,
    ContextAgentPrompt prompt,
    ContextMessageClassification classification,
    boolean confirmationRequired,
    String confirmationMessage
) {
  public ContextConversationTurn {
    if (state == null) {
      throw new IllegalArgumentException("state is required");
    }
    confirmationMessage = confirmationMessage == null ? "" : confirmationMessage.trim();
  }
}
