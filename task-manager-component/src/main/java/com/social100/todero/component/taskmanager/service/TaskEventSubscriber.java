package com.social100.todero.component.taskmanager.service;

import com.social100.todero.component.taskmanager.persistence.TaskEvent;

@FunctionalInterface
public interface TaskEventSubscriber {
  void onEvent(TaskEvent event) throws Exception;
}
