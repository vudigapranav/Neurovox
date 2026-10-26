package com.neurovox.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/** SQL for the {@code sample_analyses} table: one row per sample and extractor version. */
public final class SampleAnalysisRepository {

    /** A stored analysis record; {@code detailsJson} holds the quality checks and analysis facts. */
    public record Row(long sampleId, String extractorVersion, String qualityStatus, String detailsJson, Instant computedAt) {
    }

    /** Inserts or replaces. Call inside a transaction together with the feature rows. */
    public void save(Connection c, Row row) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO sample_analyses "
                + "(sample_id, extractor_version, quality_status, details_json, computed_at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setLong(1, row.sampleId());
            ps.setString(2, row.extractorVersion());
            ps.setString(3, row.qualityStatus());
            ps.setString(4, row.detailsJson());
            ps.setString(5, Timestamps.format(row.computedAt()));
            ps.executeUpdate();
        }
    }

    public Optional<Row> find(Connection c, long sampleId, String extractorVersion) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT quality_status, details_json, computed_at FROM sample_analyses "
                + "WHERE sample_id = ? AND extractor_version = ?")) {
            ps.setLong(1, sampleId);
            ps.setString(2, extractorVersion);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Row(sampleId, extractorVersion, rs.getString(1), rs.getString(2),
                        Timestamps.parse(rs.getString(3))));
            }
        }
    }
}
