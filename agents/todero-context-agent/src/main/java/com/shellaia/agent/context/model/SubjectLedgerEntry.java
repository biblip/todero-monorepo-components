package com.shellaia.agent.context.model;

import java.time.Instant;

public record SubjectLedgerEntry(
    String schemaVersion,
    String subjectId,
    String displayName,
    String activeBranchId,
    String branchGoal,
    String branchStatus,
    boolean canonical,
    Instant createdAt,
    Instant updatedAt
) {
  public static final String SCHEMA_VERSION = "context.subject.ledger.entry.v1";

  public SubjectLedgerEntry {
    schemaVersion = require(schemaVersion, "schemaVersion");
    subjectId = require(subjectId, "subjectId");
    displayName = require(displayName, "displayName");
  }

  public static SubjectLedgerEntry from(SubjectIdentity identity, String branchGoal, String branchStatus, boolean canonical) {
    return new SubjectLedgerEntry(
        SCHEMA_VERSION,
        identity.subjectId(),
        identity.displayName(),
        identity.activeBranchId(),
        branchGoal == null ? "" : branchGoal.trim(),
        branchStatus == null ? "" : branchStatus.trim(),
        canonical,
        identity.createdAt(),
        identity.updatedAt()
    );
  }

  private static String require(String value, String field) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return trimmed;
  }
}
