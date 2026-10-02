package com.neurovox.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Versioned schema changes.
 *
 * <p>SQLite keeps an integer, {@code PRAGMA user_version}, in the database header. Version N means
 * migrations 1..N have been applied. On start-up, each missing migration runs in its own transaction
 * together with the version bump, so a crash can never leave a half-applied step. Re-running on an
 * up-to-date database does nothing. New schema changes are added as a new list entry; existing
 * entries are never edited.
 */
final class Migrations {

    private static final String TIMESTAMP = "GLOB '" + Timestamps.GLOB + "'";

    /** Migration 1: the initial schema. */
    private static final List<String> V1 = List.of(
            """
            CREATE TABLE profiles (
              id            INTEGER PRIMARY KEY AUTOINCREMENT,
              display_name  TEXT NOT NULL CHECK (length(display_name) BETWEEN 1 AND 60),
              created_at    TEXT NOT NULL CHECK (created_at %1$s)
            )""".formatted(TIMESTAMP),
            """
            CREATE TABLE recording_sessions (
              id            INTEGER PRIMARY KEY AUTOINCREMENT,
              profile_id    INTEGER NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
              started_at    TEXT NOT NULL CHECK (started_at %1$s),
              completed_at  TEXT CHECK (completed_at IS NULL OR (completed_at %1$s AND completed_at >= started_at)),
              status        TEXT NOT NULL CHECK (status IN ('in_progress', 'complete')),
              CHECK ((status = 'complete') = (completed_at IS NOT NULL))
            )""".formatted(TIMESTAMP),
            "CREATE INDEX idx_sessions_by_profile ON recording_sessions (profile_id, started_at DESC, id DESC)",
            """
            CREATE TABLE voice_samples (
              id              INTEGER PRIMARY KEY AUTOINCREMENT,
              session_id      INTEGER NOT NULL REFERENCES recording_sessions(id) ON DELETE CASCADE,
              task_type       TEXT NOT NULL CHECK (task_type IN ('sustained_vowel', 'pa_ta_ka', 'reading_passage', 'free_speech')),
              audio_path      TEXT NOT NULL UNIQUE,
              audio_format    TEXT NOT NULL CHECK (audio_format IN ('pcm_f32le')),
              sample_rate_hz  INTEGER NOT NULL CHECK (sample_rate_hz BETWEEN 8000 AND 192000),
              sample_count    INTEGER NOT NULL CHECK (sample_count > 0),
              byte_size       INTEGER NOT NULL CHECK (byte_size = sample_count * 4),
              sha256          TEXT NOT NULL CHECK (length(sha256) = 64),
              recorded_at     TEXT NOT NULL CHECK (recorded_at %1$s),
              UNIQUE (session_id, task_type)
            )""".formatted(TIMESTAMP),
            """
            CREATE TABLE speech_features (
              sample_id           INTEGER NOT NULL REFERENCES voice_samples(id) ON DELETE CASCADE,
              extractor_version   TEXT NOT NULL,
              position            INTEGER NOT NULL CHECK (position >= 0),
              feature_name        TEXT NOT NULL,
              value               REAL,
              unit                TEXT NOT NULL,
              unavailable_reason  TEXT,
              computed_at         TEXT NOT NULL CHECK (computed_at %1$s),
              PRIMARY KEY (sample_id, extractor_version, feature_name),
              UNIQUE (sample_id, extractor_version, position),
              CHECK ((value IS NULL) <> (unavailable_reason IS NULL))
            )""".formatted(TIMESTAMP),
            """
            CREATE TABLE screening_results (
              id              INTEGER PRIMARY KEY AUTOINCREMENT,
              session_id      INTEGER NOT NULL REFERENCES recording_sessions(id) ON DELETE CASCADE,
              sample_id       INTEGER NOT NULL REFERENCES voice_samples(id) ON DELETE CASCADE,
              model_version   TEXT NOT NULL,
              research_score  REAL NOT NULL CHECK (research_score >= 0.0 AND research_score <= 1.0),
              created_at      TEXT NOT NULL CHECK (created_at %1$s),
              UNIQUE (session_id, model_version)
            )""".formatted(TIMESTAMP),
            // Scope rule enforced by the database itself: the research classifier may only use the
            // sustained vowel, and only from the same session.
            """
            CREATE TRIGGER screening_uses_sustained_vowel
            BEFORE INSERT ON screening_results
            WHEN (SELECT task_type FROM voice_samples WHERE id = NEW.sample_id AND session_id = NEW.session_id)
                 IS NOT 'sustained_vowel'
            BEGIN
              SELECT RAISE(ABORT, 'screening results must use the sustained-vowel sample of the same session');
            END""",
            """
            CREATE TRIGGER screening_sample_immutable
            BEFORE UPDATE OF session_id, sample_id ON screening_results
            BEGIN
              SELECT RAISE(ABORT, 'screening results cannot be moved to another sample');
            END""");

    /**
     * Migration 2 (Sprint D/H): how each sample was analysed — the quality-gate result and small facts such as the
     * steady region used — stored as JSON next to the per-feature rows in {@code speech_features}.
     */
    private static final List<String> V2 = List.of(
            """
            CREATE TABLE sample_analyses (
              sample_id          INTEGER NOT NULL REFERENCES voice_samples(id) ON DELETE CASCADE,
              extractor_version  TEXT NOT NULL,
              quality_status     TEXT NOT NULL CHECK (quality_status IN ('pass', 'warn', 'fail')),
              details_json       TEXT NOT NULL CHECK (json_valid(details_json)),
              computed_at        TEXT NOT NULL CHECK (computed_at %1$s),
              PRIMARY KEY (sample_id, extractor_version)
            )""".formatted(TIMESTAMP));

    static final List<List<String>> STEPS = List.of(V1, V2);

    static final int LATEST_VERSION = STEPS.size();

    private Migrations() {
    }

    /** Brings the database up to {@link #LATEST_VERSION}; returns the number of migrations applied. */
    static int apply(Connection connection) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(true);
        int applied = 0;
        try (Statement statement = connection.createStatement()) {
            for (int target = 1; target <= LATEST_VERSION; target++) {
                // IMMEDIATE takes the write lock now, so two processes starting at once cannot both migrate.
                statement.execute("BEGIN IMMEDIATE");
                try {
                    int current = userVersion(statement);
                    if (current > LATEST_VERSION) {
                        throw new DataAccessException("The database was created by a newer version of Neurovox (schema "
                                + current + ", this build understands up to " + LATEST_VERSION + ").");
                    }
                    if (current < target) {
                        for (String sql : STEPS.get(target - 1)) {
                            statement.execute(sql);
                        }
                        statement.execute("PRAGMA user_version = " + target);
                        applied++;
                    }
                    statement.execute("COMMIT");
                } catch (SQLException | RuntimeException e) {
                    statement.execute("ROLLBACK");
                    throw e;
                }
            }
        } finally {
            connection.setAutoCommit(autoCommit);
        }
        return applied;
    }

    static int userVersion(Statement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
