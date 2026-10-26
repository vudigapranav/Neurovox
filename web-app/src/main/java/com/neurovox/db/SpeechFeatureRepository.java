package com.neurovox.db;

import com.neurovox.model.FeatureValue;
import com.neurovox.model.SpeechFeatures;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQL for the {@code speech_features} table: one row per measurement, keyed by sample, extractor
 * version and feature name. A {@code position} column keeps the feature order exactly as saved.
 */
public final class SpeechFeatureRepository {

    /** Stores the features, replacing any earlier set from the same extractor version. Call inside a transaction. */
    public void save(Connection c, SpeechFeatures features) throws SQLException {
        try (PreparedStatement delete = c.prepareStatement(
                "DELETE FROM speech_features WHERE sample_id = ? AND extractor_version = ?")) {
            delete.setLong(1, features.sampleId());
            delete.setString(2, features.extractorVersion());
            delete.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO speech_features (sample_id, extractor_version, position, "
                + "feature_name, value, unit, unavailable_reason, computed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            String computedAt = Timestamps.format(features.computedAt());
            List<FeatureValue> values = features.values();
            for (int i = 0; i < values.size(); i++) {
                FeatureValue v = values.get(i);
                ps.setLong(1, features.sampleId());
                ps.setString(2, features.extractorVersion());
                ps.setInt(3, i);
                ps.setString(4, v.name());
                if (v.value() == null) {
                    ps.setNull(5, Types.REAL);
                } else {
                    ps.setDouble(5, v.value());
                }
                ps.setString(6, v.unit());
                ps.setString(7, v.unavailableReason());
                ps.setString(8, computedAt);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public Optional<SpeechFeatures> find(Connection c, long sampleId, String extractorVersion) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT feature_name, value, unit, unavailable_reason, computed_at "
                + "FROM speech_features WHERE sample_id = ? AND extractor_version = ? ORDER BY position")) {
            ps.setLong(1, sampleId);
            ps.setString(2, extractorVersion);
            try (ResultSet rs = ps.executeQuery()) {
                List<FeatureValue> values = new ArrayList<>();
                Instant computedAt = null;
                while (rs.next()) {
                    double raw = rs.getDouble("value");
                    Double value = rs.wasNull() ? null : raw;
                    values.add(new FeatureValue(rs.getString("feature_name"), value, rs.getString("unit"), rs.getString("unavailable_reason")));
                    computedAt = Timestamps.parse(rs.getString("computed_at"));
                }
                return computedAt == null ? Optional.empty() : Optional.of(new SpeechFeatures(sampleId, extractorVersion, computedAt, values));
            }
        }
    }
}
