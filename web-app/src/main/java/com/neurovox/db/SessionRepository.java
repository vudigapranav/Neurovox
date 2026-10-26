package com.neurovox.db;

import com.neurovox.model.RecordingSession;
import com.neurovox.model.RecordingSession.Status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SQL for the {@code recording_sessions} table. */
public final class SessionRepository {

    private static final String COLUMNS = "id, profile_id, started_at, completed_at, status";

    public RecordingSession insert(Connection c, long profileId, Instant startedAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO recording_sessions (profile_id, started_at, status) VALUES (?, ?, 'in_progress')",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, profileId);
            ps.setString(2, Timestamps.format(startedAt));
            ps.executeUpdate();
            return new RecordingSession(ProfileRepository.generatedId(ps), profileId, startedAt, null, Status.IN_PROGRESS);
        }
    }

    public Optional<RecordingSession> findById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM recording_sessions WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /** A profile's session history, newest first. Ties on start time are broken by id so the order is stable. */
    public List<RecordingSession> listByProfile(Connection c, long profileId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS
                + " FROM recording_sessions WHERE profile_id = ? ORDER BY started_at DESC, id DESC")) {
            ps.setLong(1, profileId);
            try (ResultSet rs = ps.executeQuery()) {
                List<RecordingSession> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        }
    }

    public List<Long> idsByProfile(Connection c, long profileId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM recording_sessions WHERE profile_id = ?")) {
            ps.setLong(1, profileId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Long> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getLong(1));
                }
                return out;
            }
        }
    }

    /** Marks an in-progress session complete. Returns false if it was already complete or does not exist. */
    public boolean markComplete(Connection c, long id, Instant completedAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE recording_sessions SET status = 'complete', completed_at = ? WHERE id = ? AND status = 'in_progress'")) {
            ps.setString(1, Timestamps.format(completedAt));
            ps.setLong(2, id);
            return ps.executeUpdate() == 1;
        }
    }

    /** Deletes the session; its samples, features and results go with it (ON DELETE CASCADE). */
    public boolean delete(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM recording_sessions WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    private static RecordingSession map(ResultSet rs) throws SQLException {
        return new RecordingSession(
                rs.getLong("id"),
                rs.getLong("profile_id"),
                Timestamps.parse(rs.getString("started_at")),
                Timestamps.parseNullable(rs.getString("completed_at")),
                Status.fromCode(rs.getString("status")));
    }
}
