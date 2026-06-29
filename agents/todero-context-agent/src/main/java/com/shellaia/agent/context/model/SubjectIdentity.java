package com.shellaia.agent.context.model;

import java.time.Instant;

public record SubjectIdentity(
    String schemaVersion,
    String subjectId,
    String displayName,
    String activeBranchId,
    Instant createdAt,
    Instant updatedAt
) {
  public static final String SCHEMA_VERSION = "context.subject.identity.v1";

  public SubjectIdentity {
    schemaVersion = require(schemaVersion, "schemaVersion");
    subjectId = require(subjectId, "subjectId");
    displayName = require(displayName, "displayName");
  }

  public static SubjectIdentity create(String subjectId, String displayName, String activeBranchId, Instant now) {
    Instant at = now == null ? Instant.now() : now;
    return new SubjectIdentity(SCHEMA_VERSION, subjectId, displayName, activeBranchId, at, at);
  }

  public SubjectIdentity withActiveBranchId(String nextActiveBranchId, Instant now) {
    return new SubjectIdentity(schemaVersion, subjectId, displayName, nextActiveBranchId, createdAt, now == null ? Instant.now() : now);
  }

  public SubjectIdentity renamed(String nextDisplayName, Instant now) {
    return new SubjectIdentity(schemaVersion, subjectId, nextDisplayName, activeBranchId, createdAt, now == null ? Instant.now() : now);
  }

  private static String require(String value, String field) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return trimmed;
  }
}
