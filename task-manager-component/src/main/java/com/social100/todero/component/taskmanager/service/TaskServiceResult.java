package com.social100.todero.component.taskmanager.service;

public record TaskServiceResult<T>(
    boolean ok,
    String errorCode,
    String message,
    T data
) {
  public static <T> TaskServiceResult<T> success(T data, String message) {
    return new TaskServiceResult<>(true, null, message, data);
  }

  public static <T> TaskServiceResult<T> failure(String errorCode, String message) {
    return new TaskServiceResult<>(false, errorCode, message, null);
  }
}
