package com.shellaia.tutil.todo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class TodoJsonCodec {
  public static final String DEFAULT_SCHEMA_VERSION = "tutil.todo.plan.v1";
  public static final String DEFAULT_SCHEMA_RESOURCE = "/todo/todo-plan.schema.json";

  private final ObjectMapper mapper;
  private final Clock clock;
  private final String schemaVersion;

  public TodoJsonCodec() {
    this(TodoObjectMappers.create(), Clock.systemUTC(), DEFAULT_SCHEMA_VERSION);
  }

  public TodoJsonCodec(ObjectMapper mapper, Clock clock, String schemaVersion) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.schemaVersion = requireValue(schemaVersion, "schemaVersion");
  }

  public TodoPlanDocument documentOf(TodoGoal goal, String source) {
    return documentOf(List.of(goal), source);
  }

  public TodoPlanDocument documentOf(List<TodoGoal> goals, String source) {
    return new TodoPlanDocument(schemaVersion, Instant.now(clock), source, goals);
  }

  public String exportGoal(TodoGoal goal, String source) {
    return exportDocument(documentOf(goal, source));
  }

  public String exportGoals(List<TodoGoal> goals, String source) {
    return exportDocument(documentOf(goals, source));
  }

  public String exportDocument(TodoPlanDocument document) {
    try {
      return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize todo plan document.", e);
    }
  }

  public TodoPlanDocument importDocument(String json) {
    try {
      TodoPlanDocument document = mapper.readValue(requireValue(json, "json"), TodoPlanDocument.class);
      if (!schemaVersion.equals(document.schemaVersion())) {
        throw new IllegalArgumentException("Unsupported schema version: " + document.schemaVersion());
      }
      return document;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to parse todo plan document.", e);
    }
  }

  public TodoGoal importSingleGoal(String json) {
    TodoPlanDocument document = importDocument(json);
    if (document.goals().size() != 1) {
      throw new IllegalArgumentException("Expected exactly one goal in the plan document.");
    }
    return document.goals().get(0);
  }

  public String loadJsonSchema() {
    try (InputStream input = TodoJsonCodec.class.getResourceAsStream(DEFAULT_SCHEMA_RESOURCE)) {
      if (input == null) {
        throw new IllegalStateException("Schema resource not found: " + DEFAULT_SCHEMA_RESOURCE);
      }
      return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load todo plan schema.", e);
    }
  }

  private static String requireValue(String value, String fieldName) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is required.");
    }
    return trimmed;
  }
}
