package com.shellaia.agent.context.classify;

import java.util.Locale;
import java.util.Objects;

public final class ContextMessageClassifier {
  public ContextMessageClassification classify(String message, String activeSubjectName) {
    String normalized = normalize(message);
    if (normalized.isEmpty()) {
      return new ContextMessageClassification(
          ContextMessageKind.GENERAL,
          0.0d,
          BranchSignalKind.NONE,
          false,
          false,
          "",
          "empty message"
      );
    }

    BranchSignalKind branchSignalKind = classifyBranchSignal(normalized);
    ContextMessageKind kind = classifyKind(normalized);
    String candidateSubject = detectSubjectCandidate(normalized, activeSubjectName);
    boolean subjectSwitchSuggested = !candidateSubject.isBlank();
    boolean confirmationRequired = subjectSwitchSuggested || normalized.contains("maybe") || normalized.contains("might") || normalized.contains("i think");
    double confidence = confidenceFor(kind, branchSignalKind, subjectSwitchSuggested);
    String rationale = buildRationale(kind, branchSignalKind, subjectSwitchSuggested, confirmationRequired);
    return new ContextMessageClassification(
        kind,
        confidence,
        branchSignalKind,
        subjectSwitchSuggested,
        confirmationRequired,
        candidateSubject,
        rationale
    );
  }

  private static ContextMessageKind classifyKind(String normalized) {
    if (isQuestion(normalized)) {
      return ContextMessageKind.QUESTION;
    }
    if (containsAny(normalized, "remind me", "remember this", "please remember", "note that")) {
      return ContextMessageKind.REMINDER;
    }
    if (containsAny(normalized, "let's", "lets ", "we will", "i will", "decide", "decision", "settle on")) {
      return ContextMessageKind.DECISION;
    }
    if (containsAny(normalized, "i think", "i believe", "my opinion", "i feel", "in my view", "my view")) {
      return ContextMessageKind.OPINION;
    }
    return ContextMessageKind.FACT;
  }

  private static BranchSignalKind classifyBranchSignal(String normalized) {
    if (containsAny(normalized, "open a branch", "new branch", "create a branch", "branch this")) {
      return BranchSignalKind.OPEN;
    }
    if (containsAny(normalized, "merge branch", "merge this", "bring back to main", "merge back")) {
      return BranchSignalKind.MERGE;
    }
    if (containsAny(normalized, "close branch", "archive branch", "drop branch", "finish branch")) {
      return BranchSignalKind.CLOSE;
    }
    if (containsAny(normalized, "split subject", "split context", "split branch")) {
      return BranchSignalKind.SPLIT;
    }
    if (containsAny(normalized, "investigate", "investigation", "explore", "probe", "audit")) {
      return BranchSignalKind.INVESTIGATE;
    }
    return BranchSignalKind.NONE;
  }

  private static String detectSubjectCandidate(String normalized, String activeSubjectName) {
    if (containsAny(normalized, "switch to ", "change topic to ", "move to ", "new subject ")) {
      String extracted = extractAfterAny(normalized, "switch to ", "change topic to ", "move to ", "new subject ");
      if (!extracted.isBlank() && !sameSubject(extracted, activeSubjectName)) {
        return extracted;
      }
    }
    if (containsAny(normalized, "about ", "regarding ", "concerning ")) {
      String extracted = extractAfterAny(normalized, "about ", "regarding ", "concerning ");
      if (!extracted.isBlank() && !sameSubject(extracted, activeSubjectName)) {
        return extracted;
      }
    }
    return "";
  }

  private static double confidenceFor(ContextMessageKind kind, BranchSignalKind branchSignalKind, boolean subjectSwitchSuggested) {
    double confidence = switch (kind) {
      case QUESTION -> 0.95d;
      case REMINDER, DECISION -> 0.9d;
      case OPINION -> 0.85d;
      case FACT, GENERAL -> 0.7d;
    };
    if (branchSignalKind != BranchSignalKind.NONE) {
      confidence = Math.max(confidence, 0.88d);
    }
    if (subjectSwitchSuggested) {
      confidence = Math.max(confidence, 0.8d);
    }
    return confidence;
  }

  private static String buildRationale(ContextMessageKind kind, BranchSignalKind branchSignalKind, boolean subjectSwitchSuggested, boolean confirmationRequired) {
    StringBuilder out = new StringBuilder();
    out.append("kind=").append(kind);
    out.append(", branchSignal=").append(branchSignalKind);
    out.append(", subjectSwitchSuggested=").append(subjectSwitchSuggested);
    out.append(", confirmationRequired=").append(confirmationRequired);
    return out.toString();
  }

  private static boolean isQuestion(String normalized) {
    return normalized.endsWith("?") || containsAny(normalized, "what ", "why ", "when ", "where ", "who ", "how ", "can ", "could ", "is it ", "are we ");
  }

  private static boolean containsAny(String normalized, String... phrases) {
    for (String phrase : phrases) {
      if (normalized.contains(phrase)) {
        return true;
      }
    }
    return false;
  }

  private static String extractAfterAny(String normalized, String... phrases) {
    for (String phrase : phrases) {
      int index = normalized.indexOf(phrase);
      if (index >= 0) {
        String extracted = cleanCandidate(normalized.substring(index + phrase.length()));
        if (!extracted.isEmpty()) {
          return extracted;
        }
      }
    }
    return "";
  }

  private static String cleanCandidate(String candidate) {
    String cleaned = normalize(candidate);
    while (!cleaned.isEmpty() && ".:,;!?)]}\"'".indexOf(cleaned.charAt(cleaned.length() - 1)) >= 0) {
      cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
    }
    while (!cleaned.isEmpty() && "([{\"'".indexOf(cleaned.charAt(0)) >= 0) {
      cleaned = cleaned.substring(1).trim();
    }
    if (cleaned.startsWith("the ")) {
      cleaned = cleaned.substring(4).trim();
    } else if (cleaned.startsWith("a ")) {
      cleaned = cleaned.substring(2).trim();
    } else if (cleaned.startsWith("an ")) {
      cleaned = cleaned.substring(3).trim();
    }
    return cleaned;
  }

  private static boolean sameSubject(String extracted, String activeSubjectName) {
    String extractedNormalized = normalize(extracted);
    String activeNormalized = normalize(activeSubjectName);
    return !extractedNormalized.isBlank() && !activeNormalized.isBlank() && Objects.equals(extractedNormalized, activeNormalized);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
