package com.social100.todero.component.taskmanager.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.social100.todero.component.taskmanager.domain.TaskAttemptEntity;
import com.social100.todero.component.taskmanager.domain.TaskAttemptStatus;
import com.social100.todero.component.taskmanager.domain.TaskEntity;
import com.social100.todero.component.taskmanager.domain.TaskStatus;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

public class SqliteTaskRepository implements TaskRepository {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
  };

  private final String jdbcUrl;
  private final SQLiteDataSource dataSource;

  public SqliteTaskRepository(Path dbPath) {
    this("jdbc:sqlite:" + dbPath.toAbsolutePath());
  }

  public SqliteTaskRepository(String jdbcUrl) {
    this.jdbcUrl = jdbcUrl;
    ensureSqliteDriverLoaded();
    this.dataSource = new SQLiteDataSource();
    this.dataSource.setUrl(jdbcUrl);
    try (Connection c = open()) {
      TaskSchema.migrate(c);
    } catch (SQLException e) {
      throw new TaskRepositoryException("Failed to initialize task repository schema.", e);
    }
  }

  @Override
  public TaskEntity create(TaskEntity task) {
    String sql = """
        INSERT INTO tasks (
          task_id, title, description, assigned_to_json, created_at, updated_at, created_by, status,
          priority, tags_json, scheduled_for, not_before, deadline, window_start, window_end, recurrence,
          attempts_count, max_attempts, active_attempt_id, claimed_by, claim_expires_at, version,
          last_emitted_due_at, idempotency_key
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """;
    try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
      bindTask(ps, task);
      ps.executeUpdate();
      return task;
    } catch (SQLException e) {
      throw new TaskRepositoryException("Failed to create task " + task.taskId() + ".", e);
    }
  }

  @Override
  public Optional<TaskEntity> getById(String taskId) {
    String sql = "SELECT * FROM tasks WHERE task_id = ?";
    try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, taskId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(readTask(rs));
      }
    } catch (SQLException e) {
      throw new TaskRepositoryException("Failed to read task " + taskId + ".", e);
    }
  }

  @Override
  public List<TaskEntity> list(TaskListFilter filter) {
    TaskListFilter effective = filter == null ? TaskListFilter.defaults() : filter;
    StringBuilder sql = new StringBuilder("SELECT * FROM tasks");
    List<Object> args = new ArrayList<>();

    appendFilter(sql, args, effective.statuses(), effective.assignedTo());
    sql.append(" ORDER BY updated_at DESC LIMIT ? OFFSET ?");
    args.add(effective.limit());
    args.add(effective.offset());

    return queryMany(sql.toString(), args);
  }

  @Override
  public TaskEntity update(TaskEntity task, long expectedVersion) {
    String sql = """
        UPDATE tasks SET
          title = ?,
          description = ?,
          assigned_to_json = ?,
          created_at = ?,
          updated_at = ?,
          created_by = ?,
          status = ?,
          priority = ?,
          tags_json = ?,
          scheduled_for = ?,
          not_before = ?,
          deadline = ?,
          window_start = ?,
          window_end = ?,
          recurrence = ?,
          attempts_count = ?,
          max_attempts = ?,
          active_attempt_id = ?,
          claimed_by = ?,
          claim_expires_at = ?,
          version = ?,
          last_emitted_due_at = ?,
          idempotency_key = ?
        WHERE task_id = ? AND version = ?
        """;

    try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
      c.setAutoCommit(true);
      int idx = bindTaskWithoutTaskId(ps, task);
      ps.setString(idx++, task.taskId());
      ps.setLong(idx, expectedVersion);
      int updated = ps.executeUpdate();
      if (updated == 0) {
        throw new TaskVersionConflictException(task.taskId(), expectedVersion);
      }
      return task;
    } catch (SQLException e) {
      throw new TaskRepositoryException("Failed to update task " + task.taskId() + ".", e);
    }
  }

  TaskEntity updateInConnection(Connection connection, TaskEntity task, long expectedVersion) throws SQLException {
    String sql = """
        UPDATE tasks SET
          title = ?,
          description = ?,
          assigned_to_json = ?,
          created_at = ?,
          updated_at = ?,
          created_by = ?,
          status = ?,
          priority = ?,
          tags_json = ?,
          scheduled_for = ?,
          not_before = ?,
          deadline = ?,
          window_start = ?,
          window_end = ?,
          recurrence = ?,
          attempts_count = ?,
          max_attempts = ?,
          active_attempt_id = ?,
          claimed_by = ?,
          claim_expires_at = ?,
          version = ?,
          last_emitted_due_at = ?,
          idempotency_key = ?
        WHERE task_id = ? AND version = ?
        """;
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      int idx = bindTaskWithoutTaskId(ps, task);
      ps.setString(idx++, task.taskId());
      ps.setLong(idx, expectedVersion);
      int updated = ps.executeUpdate();
      if (updated == 0) {
        throw new TaskVersionConflictException(task.taskId(), expectedVersion);
      }
      return task;
    }
  }

  @Override
  public List<TaskEntity> findDueCandidates(Instant now, int limit) {
    int effectiveLimit = sanitizeLimit(limit);
    String sql = """
        SELECT * FROM tasks
        WHERE status IN ('NEW','SNOOZED')
          AND (window_start IS NULL OR window_start <= ?)
          AND (not_before IS NULL OR not_before <= ?)
          AND (scheduled_for IS NULL OR scheduled_for <= ?)
          AND (window_end IS NULL OR window_end >= ?)
          AND (deadline IS NULL OR deadline >= ?)
        ORDER BY COALESCE(scheduled_for, not_before, created_at) ASC
        LIMIT ?
        """;
    return queryMany(sql, List.of(ts(now), ts(now), ts(now), ts(now), ts(now), effectiveLimit));
  }

  @Override
  public List<TaskEntity> findExpiryCandidates(Instant now, int limit) {
    int effectiveLimit = sanitizeLimit(limit);
    String sql = """
        SELECT * FROM tasks
        WHERE status IN ('NEW','READY','CLAIMED','BLOCKED','IN_PROGRESS','SNOOZED')
          AND (
            (window_end IS NOT NULL AND window_end < ?)
            OR (deadline IS NOT NULL AND deadline < ?)
          )
        ORDER BY COALESCE(window_end, deadline) ASC
        LIMIT ?
        """;
    return queryMany(sql, List.of(ts(now), ts(now), effectiveLimit));
  }

  @Override
  public List<TaskEntity> findClaimExpiryCandidates(Instant now, int limit) {
    int effectiveLimit = sanitizeLimit(limit);
    String sql = """
        SELECT * FROM tasks
        WHERE status = 'CLAIMED'
          AND claim_expires_at IS NOT NULL
          AND claim_expires_at <= ?
        ORDER BY claim_expires_at ASC
        LIMIT ?
        """;
    return queryMany(sql, List.of(ts(now), effectiveLimit));
  }

  @Override
  public List<TaskAttemptEntity> listAttempts(String taskId, int limit, int offset) {
    if (taskId == null || taskId.isBlank()) {
      throw new TaskRepositoryException("taskId is required.");
    }
    String sql = """
        SELECT * FROM task_attempts
        WHERE task_id = ?
        ORDER BY attempt_number DESC
        LIMIT ? OFFSET ?
        """;
    int safeLimit = sanitizeLimit(limit);
    int safeOffset = Math.max(offset, 0);
    try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, taskId.trim());
      ps.setInt(2, safeLimit);
      ps.setInt(3, safeOffset);
      try (ResultSet rs = ps.executeQuery()) {
        List<TaskAttemptEntity> out = new ArrayList<>();
        while (rs.next()) {
          out.add(readAttempt(rs));
        }
        return out;
      }
    } catch (SQLException e) {
      throw new TaskRepositoryException("Failed to list task attempts for task " + taskId + ".", e);
    }
  }

  @Override
  public Optional<TaskAttemptEntity> getAttempt(String taskId, int attemptNumber) {
    if (taskId == null || taskId.isBlank()) {
      throw new TaskRepositoryException("taskId is required.");
    }
    if (attemptNumber <= 0) {
      throw new TaskRepositoryException("attemptNumber must be > 0.");
    }
    String sql = """
        SELECT * FROM task_attempts
        WHERE task_id = ?
          AND attempt_number = ?
        LIMIT 1
        """;
    try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, taskId.trim());
      ps.setInt(2, attemptNumber);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(readAttempt(rs));
      }
    } catch (SQLException e) {
      throw new TaskRepositoryException(
          "Failed to read attempt " + attemptNumber + " for task " + taskId + ".",
          e
      );
    }
  }

  @Override
  public TaskAttemptEntity openAttempt(String taskId, String attemptId, String actor, Instant startedAt) {
    try (Connection c = open()) {
      c.setAutoCommit(false);
      try {
        TaskAttemptEntity created = openAttemptInConnection(c, taskId, attemptId, actor, startedAt);
        c.commit();
        return created;
      } catch (Exception e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(true);
      }
    } catch (TaskRepositoryException e) {
      throw e;
    } catch (Exception e) {
      throw new TaskRepositoryException("Failed to open task attempt for task " + taskId + ".", e);
    }
  }

  @Override
  public TaskAttemptEntity closeActiveAttempt(String taskId,
                                              String actor,
                                              Instant endedAt,
                                              String status,
                                              String errorCode,
                                              String errorMessage,
                                              String note) {
    try (Connection c = open()) {
      c.setAutoCommit(false);
      try {
        TaskAttemptEntity closed = closeActiveAttemptInConnection(c, taskId, actor, endedAt, status, errorCode, errorMessage, note);
        c.commit();
        return closed;
      } catch (Exception e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(true);
      }
    } catch (TaskRepositoryException e) {
      throw e;
    } catch (Exception e) {
      throw new TaskRepositoryException("Failed to close task attempt for task " + taskId + ".", e);
    }
  }

  public TaskAttemptEntity openAttemptInConnection(Connection connection,
                                                   String taskId,
                                                   String attemptId,
                                                   String actor,
                                                   Instant startedAt) throws SQLException {
    if (taskId == null || taskId.isBlank()) {
      throw new TaskRepositoryException("taskId is required.");
    }
    if (attemptId == null || attemptId.isBlank()) {
      throw new TaskRepositoryException("attemptId is required.");
    }
    if (startedAt == null) {
      throw new TaskRepositoryException("startedAt is required.");
    }

    int nextAttemptNumber = readNextAttemptNumber(connection, taskId);

    String sql = """
        INSERT INTO task_attempts (
          attempt_id, task_id, attempt_number, status, started_at, ended_at, started_by, ended_by,
          error_code, error_message, progress_note, meta_json, created_at, updated_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """;

    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setString(1, attemptId);
      ps.setString(2, taskId);
      ps.setInt(3, nextAttemptNumber);
      ps.setString(4, TaskAttemptStatus.STARTED.name());
      ps.setString(5, ts(startedAt));
      ps.setString(6, null);
      ps.setString(7, actor);
      ps.setString(8, null);
      ps.setString(9, null);
      ps.setString(10, null);
      ps.setString(11, null);
      ps.setString(12, null);
      ps.setString(13, ts(startedAt));
      ps.setString(14, ts(startedAt));
      ps.executeUpdate();
    }

    return findAttemptByIdInConnection(connection, attemptId)
        .orElseThrow(() -> new TaskRepositoryException("Attempt row not found after insert."));
  }

  public TaskAttemptEntity closeActiveAttemptInConnection(Connection connection,
                                                          String taskId,
                                                          String actor,
                                                          Instant endedAt,
                                                          String status,
                                                          String errorCode,
                                                          String errorMessage,
                                                          String note) throws SQLException {
    if (taskId == null || taskId.isBlank()) {
      throw new TaskRepositoryException("taskId is required.");
    }
    if (endedAt == null) {
      throw new TaskRepositoryException("endedAt is required.");
    }

    String currentAttemptId = getActiveAttemptId(connection, taskId);
    if (currentAttemptId == null || currentAttemptId.isBlank()) {
      currentAttemptId = findOpenAttemptId(connection, taskId);
    }
    if (currentAttemptId == null || currentAttemptId.isBlank()) {
      throw new TaskRepositoryException("No open attempt for task " + taskId + ".");
    }

    String normalizedStatus = status == null || status.isBlank()
        ? TaskAttemptStatus.CANCELED.name()
        : status.trim().toUpperCase();

    String sql = """
        UPDATE task_attempts
        SET status = ?, ended_at = ?, ended_by = ?, error_code = ?, error_message = ?, progress_note = ?, updated_at = ?
        WHERE attempt_id = ?
        """;
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setString(1, normalizedStatus);
      ps.setString(2, ts(endedAt));
      ps.setString(3, actor);
      ps.setString(4, errorCode);
      ps.setString(5, errorMessage);
      ps.setString(6, note);
      ps.setString(7, ts(endedAt));
      ps.setString(8, currentAttemptId);
      ps.executeUpdate();
    }

    return findAttemptByIdInConnection(connection, currentAttemptId)
        .orElseThrow(() -> new TaskRepositoryException("Attempt row not found after close."));
  }

  private Optional<TaskAttemptEntity> findAttemptByIdInConnection(Connection connection, String attemptId) throws SQLException {
    String sql = "SELECT * FROM task_attempts WHERE attempt_id = ?";
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setString(1, attemptId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(readAttempt(rs));
      }
    }
  }

  private int readNextAttemptNumber(Connection connection, String taskId) throws SQLException {
    String sql = "SELECT COALESCE(MAX(attempt_number), 0) FROM task_attempts WHERE task_id = ?";
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setString(1, taskId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getInt(1) + 1;
        }
        return 1;
      }
    }
  }

  private String getActiveAttemptId(Connection connection, String taskId) throws SQLException {
    String sql = "SELECT active_attempt_id FROM tasks WHERE task_id = ?";
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setString(1, taskId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new TaskRepositoryException("Task not found: " + taskId);
        }
        return rs.getString(1);
      }
    }
  }

  private String findOpenAttemptId(Connection connection, String taskId) throws SQLException {
    String sql = """
        SELECT attempt_id FROM task_attempts
        WHERE task_id = ?
          AND ended_at IS NULL
        ORDER BY attempt_number DESC
        LIMIT 1
        """;
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setString(1, taskId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getString(1);
        }
        return null;
      }
    }
  }

  private List<TaskEntity> queryMany(String sql, List<?> args) {
    try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
      bindArgs(ps, args);
      try (ResultSet rs = ps.executeQuery()) {
        List<TaskEntity> out = new ArrayList<>();
        while (rs.next()) {
          out.add(readTask(rs));
        }
        return out;
      }
    } catch (SQLException e) {
      throw new TaskRepositoryException("Failed query execution.", e);
    }
  }

  private void appendFilter(StringBuilder sql, List<Object> args, Set<TaskStatus> statuses, String assignedTo) {
    boolean hasWhere = false;
    if (statuses != null && !statuses.isEmpty()) {
      hasWhere = true;
      StringJoiner placeholders = new StringJoiner(",");
      for (TaskStatus ignored : statuses) {
        placeholders.add("?");
      }
      sql.append(" WHERE status IN (").append(placeholders).append(")");
      for (TaskStatus s : statuses) {
        args.add(s.name());
      }
    }
    if (assignedTo != null && !assignedTo.trim().isEmpty()) {
      sql.append(hasWhere ? " AND " : " WHERE ");
      sql.append("assigned_to_json LIKE ?");
      args.add("%\"" + assignedTo.trim() + "\"%");
    }
  }

  private void bindArgs(PreparedStatement ps, List<?> args) throws SQLException {
    int idx = 1;
    for (Object arg : args) {
      if (arg instanceof Integer value) {
        ps.setInt(idx++, value);
      } else if (arg instanceof Long value) {
        ps.setLong(idx++, value);
      } else {
        ps.setString(idx++, arg == null ? null : String.valueOf(arg));
      }
    }
  }

  private void bindTask(PreparedStatement ps, TaskEntity task) throws SQLException {
    int idx = 1;
    ps.setString(idx++, task.taskId());
    bindTaskWithoutTaskIdFrom(idx, ps, task);
  }

  private int bindTaskWithoutTaskId(PreparedStatement ps, TaskEntity task) throws SQLException {
    return bindTaskWithoutTaskIdFrom(1, ps, task);
  }

  private int bindTaskWithoutTaskIdFrom(int startIndex, PreparedStatement ps, TaskEntity task) throws SQLException {
    int idx = startIndex;
    ps.setString(idx++, task.title());
    ps.setString(idx++, task.description());
    ps.setString(idx++, toJson(task.assignedTo()));
    ps.setString(idx++, ts(task.createdAt()));
    ps.setString(idx++, ts(task.updatedAt()));
    ps.setString(idx++, task.createdBy());
    ps.setString(idx++, task.status().name());
    if (task.priority() == null) {
      ps.setNull(idx++, java.sql.Types.INTEGER);
    } else {
      ps.setInt(idx++, task.priority());
    }
    ps.setString(idx++, toJson(task.tags()));
    ps.setString(idx++, ts(task.scheduledFor()));
    ps.setString(idx++, ts(task.notBefore()));
    ps.setString(idx++, ts(task.deadline()));
    ps.setString(idx++, ts(task.windowStart()));
    ps.setString(idx++, ts(task.windowEnd()));
    ps.setString(idx++, task.recurrence());
    ps.setInt(idx++, task.attemptsCount());
    if (task.maxAttempts() == null) {
      ps.setNull(idx++, java.sql.Types.INTEGER);
    } else {
      ps.setInt(idx++, task.maxAttempts());
    }
    ps.setString(idx++, task.activeAttemptId());
    ps.setString(idx++, task.claimedBy());
    ps.setString(idx++, ts(task.claimExpiresAt()));
    ps.setLong(idx++, task.version());
    ps.setString(idx++, ts(task.lastEmittedDueAt()));
    ps.setString(idx++, task.idempotencyKey());
    return idx;
  }

  private TaskEntity readTask(ResultSet rs) throws SQLException {
    return new TaskEntity(
        rs.getString("task_id"),
        rs.getString("title"),
        rs.getString("description"),
        fromJsonList(rs.getString("assigned_to_json")),
        parseTs(rs.getString("created_at")),
        parseTs(rs.getString("updated_at")),
        rs.getString("created_by"),
        TaskStatus.valueOf(rs.getString("status")),
        (Integer) rs.getObject("priority"),
        fromJsonList(rs.getString("tags_json")),
        parseTs(rs.getString("scheduled_for")),
        parseTs(rs.getString("not_before")),
        parseTs(rs.getString("deadline")),
        parseTs(rs.getString("window_start")),
        parseTs(rs.getString("window_end")),
        rs.getString("recurrence"),
        rs.getInt("attempts_count"),
        (Integer) rs.getObject("max_attempts"),
        rs.getString("active_attempt_id"),
        rs.getString("claimed_by"),
        parseTs(rs.getString("claim_expires_at")),
        rs.getLong("version"),
        parseTs(rs.getString("last_emitted_due_at")),
        rs.getString("idempotency_key")
    );
  }

  private TaskAttemptEntity readAttempt(ResultSet rs) throws SQLException {
    return new TaskAttemptEntity(
        rs.getString("attempt_id"),
        rs.getString("task_id"),
        rs.getInt("attempt_number"),
        TaskAttemptStatus.valueOf(rs.getString("status")),
        parseTs(rs.getString("started_at")),
        parseTs(rs.getString("ended_at")),
        rs.getString("started_by"),
        rs.getString("ended_by"),
        rs.getString("error_code"),
        rs.getString("error_message"),
        rs.getString("progress_note"),
        rs.getString("meta_json"),
        parseTs(rs.getString("created_at")),
        parseTs(rs.getString("updated_at"))
    );
  }

  Connection open() throws SQLException {
    Connection c = dataSource.getConnection();
    c.setAutoCommit(true);
    return c;
  }

  private static void ensureSqliteDriverLoaded() {
    try {
      Class.forName("org.sqlite.JDBC");
    } catch (ClassNotFoundException e) {
      throw new TaskRepositoryException(
          "SQLite JDBC driver not found. Ensure sqlite-jdbc dependency is present at runtime.",
          e
      );
    }
  }

  private String toJson(List<String> values) {
    try {
      return JSON.writeValueAsString(values == null ? List.of() : values);
    } catch (JsonProcessingException e) {
      throw new TaskRepositoryException("Failed to serialize list json.", e);
    }
  }

  private List<String> fromJsonList(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return JSON.readValue(json, STRING_LIST);
    } catch (Exception e) {
      throw new TaskRepositoryException("Failed to parse list json.", e);
    }
  }

  private static String ts(Instant value) {
    return value == null ? null : value.toString();
  }

  private static Instant parseTs(String value) {
    return value == null || value.isBlank() ? null : Instant.parse(value);
  }

  private static int sanitizeLimit(int limit) {
    if (limit <= 0) {
      return 100;
    }
    return Math.min(limit, 1000);
  }
}
