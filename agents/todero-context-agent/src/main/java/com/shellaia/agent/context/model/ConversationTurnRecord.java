package com.shellaia.agent.context.model;

import java.time.Instant;

public record ConversationTurnRecord(
    String schemaVersion,
    String threadId,
    String role,
    String text,
    String subjectId,
    String branchId,
    Instant occurredAt
) {
  public static final String SCHEMA_VERSION = "context.conversation.turn.v1";

  public ConversationTurnRecord {
    schemaVersion = require(schemaVersion, "schemaVersion");
    threadId = require(threadId, "threadId");
    role = require(role, "role");
    text = text == null ? "" : text.trim();
    subjectId = subjectId == null ? "" : subjectId.trim();
    branchId = branchId == null ? "" : branchId.trim();
    occurredAt = occurredAt == null ? Instant.now() : occurredAt;
  }

  public static ConversationTurnRecord user(String threadId, String text, String subjectId, String branchId, Instant occurredAt) {
    return new ConversationTurnRecord(SCHEMA_VERSION, threadId, "user", text, subjectId, branchId, occurredAt);
  }

  public static ConversationTurnRecord assistant(String threadId, String text, String subjectId, String branchId, Instant occurredAt) {
    return new ConversationTurnRecord(SCHEMA_VERSION, threadId, "assistant", text, subjectId, branchId, occurredAt);
  }

  private static String require(String value, String field) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return trimmed;
  }
}
