package com.shellaia.component.taskmanager.domain;

public enum TaskTransitionType {
  CREATE,
  EVALUATE,
  CLAIM,
  RENEW_CLAIM,
  CLAIM_EXPIRED,
  FORCE_RELEASE_CLAIM,
  START,
  BLOCK,
  RESUME,
  COMPLETE,
  FAIL,
  CANCEL,
  SNOOZE
}
