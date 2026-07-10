package com.shellaia.agent.context.model;

import java.time.Instant;

public record SubjectMemoryRecord(
    String schemaVersion,
    String recordId,
    SubjectMemoryLayer layer,
    SubjectMemoryKind kind,
    String content,
    String subjectId,
    String branchId,
    String sourceRecordId,
    Instant occurredAt,
    Instant capturedAt
) {
  public static final String SCHEMA_VERSION = "context.subject.memory.record.v1";

  public SubjectMemoryRecord {
    schemaVersion = require(schemaVersion, "schemaVersion");
    recordId = require(recordId, "recordId");
    layer = require(layer, "layer");
    kind = require(kind, "kind");
    content = content == null ? "" : content.trim();
    subjectId = require(subjectId, "subjectId");
    occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    capturedAt = capturedAt == null ? Instant.now() : capturedAt;
  }

  public static SubjectMemoryRecord raw(String recordId, SubjectMemoryKind kind, String content, String subjectId, String branchId, Instant occurredAt) {
    return new SubjectMemoryRecord(SCHEMA_VERSION, recordId, SubjectMemoryLayer.RAW, kind, content, subjectId, branchId, null, occurredAt, Instant.now());
  }

  public static SubjectMemoryRecord derived(String recordId, SubjectMemoryKind kind, String content, String subjectId, String branchId, Instant occurredAt) {
    return new SubjectMemoryRecord(SCHEMA_VERSION, recordId, SubjectMemoryLayer.DERIVED, kind, content, subjectId, branchId, null, occurredAt, Instant.now());
  }

  public static SubjectMemoryRecord remembered(String recordId, SubjectMemoryKind kind, String content, String subjectId, String branchId, String sourceRecordId, Instant occurredAt, Instant capturedAt) {
    return new SubjectMemoryRecord(SCHEMA_VERSION, recordId, SubjectMemoryLayer.REMEMBERED, kind, content, subjectId, branchId, sourceRecordId, occurredAt, capturedAt);
  }

  public SubjectMemoryRecord asRemembered(String nextRecordId, String rememberedSummary, Instant promotedAt) {
    return remembered(
        nextRecordId,
        kind,
        rememberedSummary,
        subjectId,
        branchId,
        recordId,
        occurredAt,
        promotedAt
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
