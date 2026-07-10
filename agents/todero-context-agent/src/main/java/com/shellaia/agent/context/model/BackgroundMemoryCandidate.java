package com.shellaia.agent.context.model;

import java.time.Instant;

public record BackgroundMemoryCandidate(
    String source,
    String subjectId,
    String branchId,
    SubjectMemoryLayer layer,
    SubjectMemoryKind kind,
    String excerpt,
    Instant occurredAt,
    Instant capturedAt
) {
  public BackgroundMemoryCandidate {
    source = source == null ? "" : source.trim();
    subjectId = subjectId == null ? "" : subjectId.trim();
    branchId = branchId == null ? "" : branchId.trim();
    excerpt = excerpt == null ? "" : excerpt.trim();
    occurredAt = occurredAt == null ? Instant.EPOCH : occurredAt;
    capturedAt = capturedAt == null ? Instant.EPOCH : capturedAt;
  }

  public static BackgroundMemoryCandidate from(String source, SubjectMemoryRecord record) {
    return new BackgroundMemoryCandidate(
        source,
        record.subjectId(),
        record.branchId(),
        record.layer(),
        record.kind(),
        record.content(),
        record.occurredAt(),
        record.capturedAt()
    );
  }
}
