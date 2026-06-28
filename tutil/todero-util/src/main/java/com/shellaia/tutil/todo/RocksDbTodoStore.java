package com.shellaia.tutil.todo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteOptions;

public final class RocksDbTodoStore implements TodoStore, AutoCloseable {
  private static final byte[] GOAL_PREFIX = bytes("goal:");
  private static final byte[] GOAL_INDEX_PREFIX = bytes("idx:goal:");

  private final ObjectMapper mapper;
  private final RocksDB db;
  private final DBOptions dbOptions;
  private final Options options;
  private final WriteOptions writeOptions;

  static {
    RocksDB.loadLibrary();
  }

  public RocksDbTodoStore(Path dbDir) {
    this(dbDir, TodoObjectMappers.create());
  }

  public RocksDbTodoStore(Path dbDir, ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    Objects.requireNonNull(dbDir, "dbDir");
    try {
      Files.createDirectories(dbDir);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to create todo RocksDB directory: " + dbDir, e);
    }
    try {
      this.options = new Options().setCreateIfMissing(true);
      this.dbOptions = new DBOptions().setCreateIfMissing(true);
      this.writeOptions = new WriteOptions().setSync(false);
      this.db = RocksDB.open(options, dbDir.toAbsolutePath().toString());
    } catch (RocksDBException e) {
      throw new IllegalStateException("Failed to open todo RocksDB: " + dbDir, e);
    }
  }

  @Override
  public synchronized TodoGoal create(TodoGoal goal) {
    if (findById(goal.id()).isPresent()) {
      throw new TodoConflictException("Goal already exists: " + goal.id());
    }
    return writeGoal(goal);
  }

  @Override
  public synchronized TodoGoal save(TodoGoal goal, long expectedVersion) {
    TodoGoal current = findById(goal.id())
        .orElseThrow(() -> new TodoNotFoundException("Goal not found: " + goal.id()));
    if (current.version() != expectedVersion) {
      throw new TodoConflictException("Version mismatch for goal " + goal.id() + ": expected " + expectedVersion + ", actual " + current.version());
    }
    return writeGoal(goal);
  }

  private TodoGoal writeGoal(TodoGoal goal) {
    try {
      db.put(writeOptions, keyForGoal(goal.id()), encode(goal));
      db.put(writeOptions, keyForGoalIndex(goal.id()), new byte[0]);
      return goal;
    } catch (RocksDBException e) {
      throw new IllegalStateException("Failed to save todo goal: " + goal.id(), e);
    }
  }

  @Override
  public synchronized Optional<TodoGoal> findById(String goalId) {
    try {
      byte[] payload = db.get(keyForGoal(goalId));
      if (payload == null) {
        return Optional.empty();
      }
      return Optional.of(decode(payload));
    } catch (RocksDBException e) {
      throw new IllegalStateException("Failed to load todo goal: " + goalId, e);
    }
  }

  @Override
  public synchronized List<TodoGoal> listGoals() {
    List<TodoGoal> goals = new ArrayList<>();
    try (RocksIterator iterator = db.newIterator()) {
      iterator.seek(GOAL_INDEX_PREFIX);
      while (iterator.isValid() && startsWith(iterator.key(), GOAL_INDEX_PREFIX)) {
        String goalId = decodeGoalIdFromIndex(iterator.key());
        findById(goalId).ifPresent(goals::add);
        iterator.next();
      }
    }
    return goals;
  }

  @Override
  public synchronized void delete(String goalId, long expectedVersion) {
    TodoGoal current = findById(goalId)
        .orElseThrow(() -> new TodoNotFoundException("Goal not found: " + goalId));
    if (current.version() != expectedVersion) {
      throw new TodoConflictException("Version mismatch for goal " + goalId + ": expected " + expectedVersion + ", actual " + current.version());
    }
    try {
      db.delete(writeOptions, keyForGoal(goalId));
      db.delete(writeOptions, keyForGoalIndex(goalId));
    } catch (RocksDBException e) {
      throw new IllegalStateException("Failed to delete todo goal: " + goalId, e);
    }
  }

  @Override
  public synchronized void close() {
    writeOptions.close();
    db.close();
    dbOptions.close();
    options.close();
  }

  private byte[] encode(TodoGoal goal) {
    try {
      return mapper.writeValueAsBytes(goal);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to encode todo goal: " + goal.id(), e);
    }
  }

  private TodoGoal decode(byte[] payload) {
    try {
      return mapper.readValue(payload, TodoGoal.class);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to decode todo goal payload.", e);
    }
  }

  private static boolean startsWith(byte[] value, byte[] prefix) {
    if (value.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (value[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  private static String decodeGoalIdFromIndex(byte[] key) {
    return new String(key, GOAL_INDEX_PREFIX.length, key.length - GOAL_INDEX_PREFIX.length, StandardCharsets.UTF_8);
  }

  private static byte[] keyForGoal(String goalId) {
    return bytes("goal:" + goalId);
  }

  private static byte[] keyForGoalIndex(String goalId) {
    return bytes("idx:goal:" + goalId);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
