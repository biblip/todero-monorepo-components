package com.social100.todero.component.taskmanager.persistence;

public enum TaskEventType {
  TASK_CREATED,
  TASK_UPDATED,
  TASK_DUE,
  TASK_CLAIMED,
  TASK_STARTED,
  TASK_BLOCKED,
  TASK_RESUMED,
  TASK_COMPLETED,
  TASK_FAILED,
  TASK_CANCELED,
  TASK_EXPIRED,
  TASK_SNOOZED,
  CLAIM_EXPIRED
}
