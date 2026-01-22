package com.social100.todero.component.taskmanager.domain;

public enum TaskStatus {
  NEW,
  READY,
  CLAIMED,
  IN_PROGRESS,
  BLOCKED,
  COMPLETED,
  FAILED,
  CANCELED,
  EXPIRED,
  SNOOZED;

  public boolean isTerminal() {
    return this == COMPLETED || this == FAILED || this == CANCELED || this == EXPIRED;
  }
}
