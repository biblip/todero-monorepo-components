package com.social100.todero.component.taskmanager.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class SqliteEventOutboxRepository implements EventOutboxRepository {
  private final SqliteTaskRepository taskRepository;

  public SqliteEventOutboxRepository(SqliteTaskRepository taskRepository) {
    this.taskRepository = taskRepository;
  }

  @Override
  public TaskEvent enqueue(TaskEvent event) {
    try (Connection c = taskRepository.open()) {
      c.setAutoCommit(true);
      return enqueueInConnection(c, event);
    } catch (SQLException e) {
      throw new TaskRepositoryException("Failed to enqueue event " + event.eventId() + ".", e);
    }
  }

  @Override
  public List<TaskEvent> listPending(String agentId, int limit) {
    int safeLimit = sanitizeLimit(limit);
    String sql = """
        SELECT * FROM task_events_outbox
        WHERE target_agent_id = ?
          AND acked_at IS NULL
        ORDER BY seq ASC
        LIMIT ?
        """;
    try (Connection c = taskRepository.open(); PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, agentId);
      ps.setInt(2, safeLimit);
      try (ResultSet rs = ps.executeQuery()) {
        List<TaskEvent> out = new ArrayList<>();
        while (rs.next()) {
          out.add(readEvent(rs));
        }
        return out;
      }
    } catch (SQLException e) {
      throw new TaskRepositoryException("Failed to list pending events for agent " + agentId + ".", e);
    }
  }

  @Override
  public List<TaskEvent> listPendingAll(int limit) {
    int safeLimit = sanitizeLimit(limit);
    String sql = """
        SELECT * FROM task_events_outbox
        WHERE acked_at IS NULL
        ORDER BY seq ASC
        LIMIT ?
        """;
    try (Connection c = taskRepository.open(); PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setInt(1, safeLimit);
      try (ResultSet rs = ps.executeQuery()) {
        List<TaskEvent> out = new ArrayList<>();
        while (rs.next()) {
          out.add(readEvent(rs));
        }
        return out;
      }
    } catch (SQLException e) {
      throw new TaskRepositoryException("Failed to list pending outbox events.", e);
    }
  }

  @Override
  public void markAcked(String agentId, String eventId, Instant ackedAt) {
    String sql = """
        UPDATE task_events_outbox
        SET acked_at = ?
        WHERE target_agent_id = ?
          AND event_id = ?
        """;
    try (Connection c = taskRepository.open(); PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, ackedAt.toString());
      ps.setString(2, agentId);
      ps.setString(3, eventId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new TaskRepositoryException("Failed to ack event " + eventId + " for agent " + agentId + ".", e);
    }
  }

  @Override
  public void markDeliveryAttempt(String eventId, Instant attemptAt, String errorMessage) {
    String sql = """
        UPDATE task_events_outbox
        SET delivery_attempts = delivery_attempts + 1,
            last_delivery_error = ?
        WHERE event_id = ?
          AND acked_at IS NULL
        """;
    String normalizedError = errorMessage == null || errorMessage.isBlank()
        ? "delivery attempt at " + attemptAt
        : errorMessage;
    try (Connection c = taskRepository.open(); PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, normalizedError);
      ps.setString(2, eventId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new TaskRepositoryException("Failed to mark delivery attempt for event " + eventId + ".", e);
    }
  }

  TaskEvent enqueueInConnection(Connection connection, TaskEvent event) throws SQLException {
    String sql = """
        INSERT INTO task_events_outbox (
          event_id,
          event_type,
          task_id,
          target_agent_id,
          task_version,
          payload_json,
          emitted_at,
          delivery_attempts,
          last_delivery_error,
          acked_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?)
        """;

    try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, event.eventId());
      ps.setString(2, event.eventType().name());
      ps.setString(3, event.taskId());
      ps.setString(4, event.targetAgentId());
      ps.setLong(5, event.taskVersion());
      ps.setString(6, event.payloadJson());
      ps.setString(7, event.emittedAt().toString());
      ps.setInt(8, event.deliveryAttempts());
      ps.setString(9, event.lastDeliveryError());
      ps.setString(10, event.ackedAt() == null ? null : event.ackedAt().toString());
      ps.executeUpdate();

      try (ResultSet keys = ps.getGeneratedKeys()) {
        if (!keys.next()) {
          throw new TaskRepositoryException("Failed to obtain generated sequence for outbox event.");
        }
        long seq = keys.getLong(1);
        return event.withSeq(seq);
      }
    }
  }

  private TaskEvent readEvent(ResultSet rs) throws SQLException {
    return new TaskEvent(
        rs.getLong("seq"),
        rs.getString("event_id"),
        TaskEventType.valueOf(rs.getString("event_type")),
        rs.getString("task_id"),
        rs.getString("target_agent_id"),
        rs.getLong("task_version"),
        rs.getString("payload_json"),
        Instant.parse(rs.getString("emitted_at")),
        rs.getInt("delivery_attempts"),
        rs.getString("last_delivery_error"),
        parseInstantNullable(rs.getString("acked_at"))
    );
  }

  private static Instant parseInstantNullable(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return Instant.parse(raw);
  }

  private static int sanitizeLimit(int limit) {
    if (limit <= 0) {
      return 100;
    }
    return Math.min(limit, 1000);
  }
}
