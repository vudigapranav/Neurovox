package com.neurovox.db;

import com.neurovox.model.LocalProfile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SQL for the {@code profiles} table. Every query uses a prepared statement with bound parameters. */
public final class ProfileRepository {

    public LocalProfile insert(Connection c, String displayName, Instant createdAt) throws SQLException {
        String name = LocalProfile.validateDisplayName(displayName);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO profiles (display_name, created_at) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, Timestamps.format(createdAt));
            ps.executeUpdate();
            return new LocalProfile(generatedId(ps), name, createdAt);
        }
    }

    public Optional<LocalProfile> findById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, display_name, created_at FROM profiles WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /** All profiles, oldest first. */
    public List<LocalProfile> findAll(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, display_name, created_at FROM profiles ORDER BY created_at, id");
             ResultSet rs = ps.executeQuery()) {
            List<LocalProfile> out = new ArrayList<>();
            while (rs.next()) {
                out.add(map(rs));
            }
            return out;
        }
    }

    /** Deletes the profile and, through ON DELETE CASCADE, its sessions, samples, features and results. */
    public boolean delete(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM profiles WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    private static LocalProfile map(ResultSet rs) throws SQLException {
        return new LocalProfile(rs.getLong("id"), rs.getString("display_name"), Timestamps.parse(rs.getString("created_at")));
    }

    static long generatedId(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("No generated key returned");
            }
            return keys.getLong(1);
        }
    }
}
