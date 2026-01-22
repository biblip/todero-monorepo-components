package com.social100.todero.component.spotify;

final class AgentEventForwardingGate {
  private String lastForwardedStatus = "";
  private long lastForwardedAtMs = 0L;

  synchronized boolean shouldForward(String status, long nowMs, long minIntervalMs) {
    String normalized = status == null ? "" : status.trim();
    if (normalized.isEmpty()) {
      return false;
    }

    long effectiveMinIntervalMs = Math.max(0L, minIntervalMs);
    boolean changed = !normalized.equals(lastForwardedStatus);
    long elapsed = nowMs - lastForwardedAtMs;
    if (!changed) {
      return false;
    }
    if (lastForwardedAtMs > 0 && elapsed < effectiveMinIntervalMs) {
      return false;
    }

    lastForwardedStatus = normalized;
    lastForwardedAtMs = nowMs > 0 ? nowMs : System.currentTimeMillis();
    return true;
  }

  synchronized void reset() {
    lastForwardedStatus = "";
    lastForwardedAtMs = 0L;
  }
}
