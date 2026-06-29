package com.shellaia.agent.context.model;

import java.util.List;

public record SubjectMemoryView(
    List<SubjectMemoryRecord> rawRecords,
    List<SubjectMemoryRecord> derivedRecords,
    List<SubjectMemoryRecord> rememberedRecords
) {
  public SubjectMemoryView {
    rawRecords = List.copyOf(rawRecords == null ? List.of() : rawRecords);
    derivedRecords = List.copyOf(derivedRecords == null ? List.of() : derivedRecords);
    rememberedRecords = List.copyOf(rememberedRecords == null ? List.of() : rememberedRecords);
  }

  public List<SubjectMemoryRecord> currentRecords() {
    return concat(derivedRecords, rememberedRecords);
  }

  public List<SubjectMemoryRecord> allRecords() {
    return concat(rawRecords, currentRecords());
  }

  public String currentSummary(int maxEntries, int maxChars) {
    return summarize(currentRecords(), maxEntries, maxChars);
  }

  private static List<SubjectMemoryRecord> concat(List<SubjectMemoryRecord> left, List<SubjectMemoryRecord> right) {
    return java.util.stream.Stream.concat(left.stream(), right.stream()).toList();
  }

  private static String summarize(List<SubjectMemoryRecord> records, int maxEntries, int maxChars) {
    int boundedEntries = Math.max(1, maxEntries);
    int boundedChars = Math.max(64, maxChars);
    if (records.isEmpty()) {
      return "none";
    }
    int from = Math.max(0, records.size() - boundedEntries);
    StringBuilder out = new StringBuilder(Math.min(2048, boundedChars + 32));
    for (int i = from; i < records.size(); i++) {
      SubjectMemoryRecord record = records.get(i);
      if (out.length() > 0) {
        out.append('\n');
      }
      out.append("- [").append(record.layer()).append("] ");
      out.append(record.kind()).append(" @ ").append(record.subjectId()).append(": ").append(record.content());
      if (out.length() >= boundedChars) {
        out.setLength(Math.max(0, boundedChars - 3));
        out.append("...");
        break;
      }
    }
    return out.length() == 0 ? "none" : out.toString();
  }
}
