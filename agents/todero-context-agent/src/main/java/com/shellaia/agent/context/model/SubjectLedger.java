package com.shellaia.agent.context.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record SubjectLedger(
    String schemaVersion,
    Instant updatedAt,
    Map<String, SubjectLedgerEntry> subjects,
    Map<String, BranchManifest> branches,
    Map<String, String> canonicalMap
) {
  public static final String SCHEMA_VERSION = "context.subject.ledger.v1";

  public SubjectLedger {
    schemaVersion = require(schemaVersion, "schemaVersion");
    subjects = copySubjects(subjects);
    branches = copyBranches(branches);
    canonicalMap = copyCanonical(canonicalMap);
  }

  public static SubjectLedger empty(Instant now) {
    return new SubjectLedger(SCHEMA_VERSION, now == null ? Instant.now() : now, new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
  }

  public SubjectLedger withSubject(SubjectLedgerEntry entry, Instant now) {
    Map<String, SubjectLedgerEntry> nextSubjects = new LinkedHashMap<>(subjects);
    nextSubjects.put(entry.subjectId(), entry);
    return new SubjectLedger(schemaVersion, now == null ? Instant.now() : now, nextSubjects, branches, canonicalMap);
  }

  public SubjectLedger withBranch(BranchManifest manifest, Instant now) {
    Map<String, BranchManifest> nextBranches = new LinkedHashMap<>(branches);
    nextBranches.put(manifest.branchId(), manifest);
    return new SubjectLedger(schemaVersion, now == null ? Instant.now() : now, subjects, nextBranches, canonicalMap);
  }

  public SubjectLedger withCanonicalMapping(String subjectId, String branchId, Instant now) {
    Map<String, String> nextMap = new LinkedHashMap<>(canonicalMap);
    nextMap.put(subjectId, branchId);
    return new SubjectLedger(schemaVersion, now == null ? Instant.now() : now, subjects, branches, nextMap);
  }

  public SubjectLedger archiveBranch(String branchId, Instant now) {
    Map<String, BranchManifest> nextBranches = new LinkedHashMap<>(branches);
    BranchManifest existing = nextBranches.get(branchId);
    if (existing != null) {
      nextBranches.put(branchId, existing.archived("archived in ledger", now));
    }
    return new SubjectLedger(schemaVersion, now == null ? Instant.now() : now, subjects, nextBranches, canonicalMap);
  }

  private static Map<String, SubjectLedgerEntry> copySubjects(Map<String, SubjectLedgerEntry> input) {
    Map<String, SubjectLedgerEntry> out = new LinkedHashMap<>();
    if (input != null) {
      out.putAll(input);
    }
    return out;
  }

  private static Map<String, BranchManifest> copyBranches(Map<String, BranchManifest> input) {
    Map<String, BranchManifest> out = new LinkedHashMap<>();
    if (input != null) {
      out.putAll(input);
    }
    return out;
  }

  private static Map<String, String> copyCanonical(Map<String, String> input) {
    Map<String, String> out = new LinkedHashMap<>();
    if (input != null) {
      out.putAll(input);
    }
    return out;
  }

  private static String require(String value, String field) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return trimmed;
  }
}
