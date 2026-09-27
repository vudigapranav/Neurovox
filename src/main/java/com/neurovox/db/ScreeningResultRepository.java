package com.neurovox.db;

import com.neurovox.model.ScreeningResult;
import com.neurovox.model.TaskType;
import com.neurovox.model.VoiceSample;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQL for the {@code screening_results} table. Only a session's sustained-vowel sample may be used;
 * this is checked here with a clear message and enforced again by a database trigger.
 */
public final class ScreeningResultRepository {

    private final VoiceSampleRepository samples = new VoiceSampleRepository();

    public ScreeningResult insert(Connection c, long sessionId, long sampleId, String modelVersion, double researchScore,
                                  Instant createdAt) throws SQLException {
        ScreeningResult.validateScore(researchScore);
        Optional<VoiceSample> sample = samples.findById(c, sampleId);
        if (sample.isEmpty() || sample.get().sessionId() != sessionId) {
            throw new IllegalArgumentException("Sample " + sampleId + " does not belong to session " + sessionId + ".");
        }
        if (sample.get().taskType() != TaskType.SUSTAINED_VOWEL) {
            throw new IllegalArgumentException("Only the sustained vowel may feed the research classifier, not "
                    + sample.get().taskType().code() + ".");
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO screening_results (session_id, sample_id, model_version, "
                + "research_score, created_at) VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, sessionId);
            ps.setLong(2, sampleId);
            ps.setString(3, modelVersion);
            ps.setDouble(4, researchScore);
            ps.setString(5, Timestamps.format(createdAt));
            ps.executeUpdate();
            return new ScreeningResult(ProfileRepository.generatedId(ps), sessionId, sampleId, modelVersion, researchScore, createdAt);
        }
    }

    public List<ScreeningResult> listBySession(Connection c, long sessionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, session_id, sample_id, model_version, research_score, created_at "
                + "FROM screening_results WHERE session_id = ? ORDER BY created_at, id")) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ScreeningResult> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new ScreeningResult(rs.getLong("id"), rs.getLong("session_id"), rs.getLong("sample_id"),
                            rs.getString("model_version"), rs.getDouble("research_score"), Timestamps.parse(rs.getString("created_at"))));
                }
                return out;
            }
        }
    }
}
