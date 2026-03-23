package com.shellaia.component.taskmanager.service;

import com.shellaia.component.taskmanager.persistence.TaskEvent;

@FunctionalInterface
public interface TaskEventSubscriber {
  void onEvent(TaskEvent event) throws Exception;
}
