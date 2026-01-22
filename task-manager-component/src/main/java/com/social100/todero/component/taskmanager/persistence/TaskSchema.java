package com.social100.todero.component.taskmanager.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class TaskSchema {
  private static final int SCHEMA_VERSION = 2;

  private TaskSchema() {
  }

  static void migrate(Connection connection) throws SQLException {
    if (isLegacyLayout(connection)) {
      resetAll(connection);
    }

    try (Statement st = connection.createStatement()) {
      st.execute("""
          CREATE TABLE IF NOT EXISTS tasks (
            task_id TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            description TEXT,
            assigned_to_json TEXT NOT NULL,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            created_by TEXT,
            status TEXT NOT NULL,
            priority INTEGER,
            tags_json TEXT,
            scheduled_for TEXT,
            not_before TEXT,
            deadline TEXT,
            window_start TEXT,
            window_end TEXT,
            recurrence TEXT,
            attempts_count INTEGER NOT NULL,
            max_attempts INTEGER,
            active_attempt_id TEXT,
            claimed_by TEXT,
            claim_expires_at TEXT,
            version INTEGER NOT NULL,
            last_emitted_due_at TEXT,
            idempotency_key TEXT
          )
          """);

      st.execute("""
          CREATE TABLE IF NOT EXISTS task_attempts (
            attempt_id TEXT PRIMARY KEY,
            task_id TEXT NOT NULL,
            attempt_number INTEGER NOT NULL,
            status TEXT NOT NULL,
            started_at TEXT NOT NULL,
            ended_at TEXT,
            started_by TEXT,
            ended_by TEXT,
            error_code TEXT,
            error_message TEXT,
            progress_note TEXT,
            meta_json TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            FOREIGN KEY(task_id) REFERENCES tasks(task_id)
          )
          """);

      st.execute("""
          CREATE TABLE IF NOT EXISTS task_events_outbox (
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            event_id TEXT NOT NULL,
            event_type TEXT NOT NULL,
            task_id TEXT NOT NULL,
            target_agent_id TEXT NOT NULL,
            task_version INTEGER NOT NULL,
            payload_json TEXT NOT NULL,
            emitted_at TEXT NOT NULL,
            delivery_attempts INTEGER NOT NULL DEFAULT 0,
            last_delivery_error TEXT,
            acked_at TEXT
          )
          """);

      st.execute("""
          CREATE TABLE IF NOT EXISTS task_claim_audit (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            task_id TEXT NOT NULL,
            actor TEXT NOT NULL,
            action TEXT NOT NULL,
            at_ts TEXT NOT NULL,
            details TEXT
          )
          """);

      st.execute("CREATE INDEX IF NOT EXISTS idx_tasks_status_due ON tasks(status, scheduled_for, not_before)");
      st.execute("CREATE INDEX IF NOT EXISTS idx_tasks_window_end ON tasks(window_end)");
      st.execute("CREATE INDEX IF NOT EXISTS idx_tasks_claim_expiry ON tasks(status, claim_expires_at)");
      st.execute("CREATE INDEX IF NOT EXISTS idx_outbox_target_ack ON task_events_outbox(target_agent_id, acked_at, seq)");
      st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_attempts_task_number ON task_attempts(task_id, attempt_number)");
      st.execute("CREATE INDEX IF NOT EXISTS idx_attempts_task_status ON task_attempts(task_id, status)");
      st.execute("CREATE INDEX IF NOT EXISTS idx_attempts_started_at ON task_attempts(started_at)");

      st.execute("PRAGMA user_version = " + SCHEMA_VERSION);
    }
  }

  private static boolean isLegacyLayout(Connection connection) throws SQLException {
    int userVersion = 0;
    try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
      if (rs.next()) {
        userVersion = rs.getInt(1);
      }
    }
    if (userVersion == SCHEMA_VERSION) {
      return false;
    }

    if (!tableExists(connection, "tasks")) {
      return false;
    }

    // Hard-cut migration: any previous schema is reset.
    return columnExists(connection, "tasks", "last_error")
        || !columnExists(connection, "tasks", "active_attempt_id")
        || !tableExists(connection, "task_attempts");
  }

  private static void resetAll(Connection connection) throws SQLException {
    try (Statement st = connection.createStatement()) {
      st.execute("DROP TABLE IF EXISTS task_events_outbox");
      st.execute("DROP TABLE IF EXISTS task_claim_audit");
      st.execute("DROP TABLE IF EXISTS task_attempts");
      st.execute("DROP TABLE IF EXISTS tasks");
      st.execute("PRAGMA user_version = 0");
    }
  }

  private static boolean tableExists(Connection connection, String table) throws SQLException {
    String sql = "SELECT name FROM sqlite_master WHERE type='table' AND name=?";
    try (var ps = connection.prepareStatement(sql)) {
      ps.setString(1, table);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
    try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rs.next()) {
        if (column.equalsIgnoreCase(rs.getString("name"))) {
          return true;
        }
      }
      return false;
    }
  }
}
