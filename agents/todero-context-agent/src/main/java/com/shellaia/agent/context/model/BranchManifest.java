package com.shellaia.agent.context.model;

import java.time.Instant;

public record BranchManifest(
    String schemaVersion,
    String branchId,
    String subjectId,
    BranchFocus focus,
    BranchStatus status,
    String goal,
    String notes,
    String outcome,
    boolean canonical,
    Instant createdAt,
    Instant updatedAt,
    Instant mergedAt
) {
  public static final String SCHEMA_VERSION = "context.subject.branch.manifest.v1";

  public BranchManifest {
    schemaVersion = require(schemaVersion, "schemaVersion");
    branchId = require(branchId, "branchId");
    subjectId = require(subjectId, "subjectId");
    focus = require(focus, "focus");
    status = require(status, "status");
    goal = goal == null ? "" : goal.trim();
    notes = notes == null ? "" : notes.trim();
    outcome = outcome == null ? "" : outcome.trim();
  }

  public static BranchManifest draft(String branchId, String subjectId, BranchFocus focus, String goal, String notes, Instant now) {
    Instant at = now == null ? Instant.now() : now;
    return new BranchManifest(SCHEMA_VERSION, branchId, subjectId, focus, BranchStatus.DRAFT, goal, notes, "", false, at, at, null);
  }

  public BranchManifest activated(Instant now) {
    Instant at = now == null ? Instant.now() : now;
    return new BranchManifest(schemaVersion, branchId, subjectId, focus, BranchStatus.ACTIVE, goal, notes, outcome, canonical, createdAt, at, mergedAt);
  }

  public BranchManifest updated(String nextGoal, String nextNotes, Instant now) {
    Instant at = now == null ? Instant.now() : now;
    return new BranchManifest(schemaVersion, branchId, subjectId, focus, status, nextGoal, nextNotes, outcome, canonical, createdAt, at, mergedAt);
  }

  public BranchManifest merged(String nextOutcome, Instant now) {
    Instant at = now == null ? Instant.now() : now;
    return new BranchManifest(schemaVersion, branchId, subjectId, focus, BranchStatus.MERGED, goal, notes, nextOutcome, true, createdAt, at, at);
  }

  public BranchManifest failed(String nextOutcome, Instant now) {
    Instant at = now == null ? Instant.now() : now;
    return new BranchManifest(schemaVersion, branchId, subjectId, focus, BranchStatus.FAILED, goal, notes, nextOutcome, false, createdAt, at, null);
  }

  public BranchManifest archived(String nextOutcome, Instant now) {
    Instant at = now == null ? Instant.now() : now;
    return new BranchManifest(schemaVersion, branchId, subjectId, focus, BranchStatus.ARCHIVED, goal, notes, nextOutcome, false, createdAt, at, null);
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
