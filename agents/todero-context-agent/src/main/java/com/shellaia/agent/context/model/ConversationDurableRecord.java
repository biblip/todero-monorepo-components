package com.shellaia.agent.context.model;

import java.time.Instant;

public record ConversationDurableRecord(
    String schemaVersion,
    String recordId,
    ConversationDurableKind kind,
    ConversationDurableStatus status,
    String threadId,
    String subjectId,
    String branchId,
    String content,
    String sourceText,
    Instant occurredAt,
    Instant capturedAt
) {
  public static final String SCHEMA_VERSION = "context.conversation.durable.v1";

  public ConversationDurableRecord {
    schemaVersion = require(schemaVersion, "schemaVersion");
    recordId = require(recordId, "recordId");
    kind = require(kind, "kind");
    status = status == null ? ConversationDurableStatus.OPEN : status;
    threadId = require(threadId, "threadId");
    subjectId = subjectId == null ? "" : subjectId.trim();
    branchId = branchId == null ? "" : branchId.trim();
    content = require(content, "content");
    sourceText = sourceText == null ? "" : sourceText.trim();
    occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    capturedAt = capturedAt == null ? Instant.now() : capturedAt;
  }

  public static ConversationDurableRecord reminder(String recordId,
                                                   String threadId,
                                                   String subjectId,
                                                   String branchId,
                                                   String content,
                                                   String sourceText,
                                                   Instant occurredAt,
                                                   Instant capturedAt) {
    return new ConversationDurableRecord(
        SCHEMA_VERSION,
        recordId,
        ConversationDurableKind.REMINDER,
        ConversationDurableStatus.OPEN,
        threadId,
        subjectId,
        branchId,
        content,
        sourceText,
        occurredAt,
        capturedAt
    );
  }

  public static ConversationDurableRecord task(String recordId,
                                               String threadId,
                                               String subjectId,
                                               String branchId,
                                               String content,
                                               String sourceText,
                                               Instant occurredAt,
                                               Instant capturedAt) {
    return new ConversationDurableRecord(
        SCHEMA_VERSION,
        recordId,
        ConversationDurableKind.TASK,
        ConversationDurableStatus.OPEN,
        threadId,
        subjectId,
        branchId,
        content,
        sourceText,
        occurredAt,
        capturedAt
    );
  }

  public ConversationDurableRecord withStatus(ConversationDurableStatus nextStatus, Instant updatedAt) {
    return new ConversationDurableRecord(
        schemaVersion,
        recordId,
        kind,
        nextStatus,
        threadId,
        subjectId,
        branchId,
        content,
        sourceText,
        occurredAt,
        updatedAt
    );
  }

  private static String require(String value, String field) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return trimmed;
  }

  private static <T> T require(T value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }
}
