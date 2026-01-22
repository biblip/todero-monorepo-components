package com.example.todero.agent.dj;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

final class AgentMemoryBuffer {
  private final int capacity;
  private final Deque<MemoryEntry> entries;

  AgentMemoryBuffer(int capacity) {
    this.capacity = Math.max(1, capacity);
    this.entries = new ArrayDeque<>(this.capacity);
  }

  synchronized void add(String type, String source, String content) {
    add(type, source, content, System.currentTimeMillis());
  }

  synchronized void add(String type, String source, String content, long atMs) {
    if (entries.size() >= capacity) {
      entries.removeFirst();
    }
    entries.addLast(new MemoryEntry(
        safe(type),
        safe(source),
        compactContent(content),
        atMs <= 0 ? System.currentTimeMillis() : atMs
    ));
  }

  synchronized String summary(int maxEntries, int maxChars) {
    int boundedEntries = Math.max(1, maxEntries);
    int boundedChars = Math.max(64, maxChars);
    if (entries.isEmpty()) {
      return "none";
    }

    List<MemoryEntry> snapshot = new ArrayList<>(entries);
    int from = Math.max(0, snapshot.size() - boundedEntries);
    StringBuilder out = new StringBuilder(Math.min(boundedChars + 32, 2048));
    for (int i = from; i < snapshot.size(); i++) {
      MemoryEntry e = snapshot.get(i);
      if (out.length() > 0) {
        out.append('\n');
      }
      out.append("- [").append(e.atMs).append("] ");
      out.append(e.type).append(" @ ").append(e.source).append(": ").append(e.content);
      if (out.length() >= boundedChars) {
        out.setLength(boundedChars);
        if (boundedChars > 3) {
          out.setLength(boundedChars - 3);
          out.append("...");
        }
        break;
      }
    }

    return out.length() == 0 ? "none" : out.toString();
  }

  synchronized int size() {
    return entries.size();
  }

  private static String safe(String value) {
    return value == null || value.isBlank() ? "unknown" : value.trim();
  }

  private static String compactContent(String value) {
    if (value == null) {
      return "";
    }
    String compact = value.trim().replace('\n', ' ').replace('\r', ' ');
    if (compact.length() > 280) {
      return compact.substring(0, 277) + "...";
    }
    return compact;
  }

  private record MemoryEntry(String type, String source, String content, long atMs) {
  }
}
