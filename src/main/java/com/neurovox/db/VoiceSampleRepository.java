package com.neurovox.db;

import com.neurovox.model.TaskType;
import com.neurovox.model.VoiceSample;
import com.neurovox.model.VoiceSample.AudioFormat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** SQL for the {@code voice_samples} table. At most one sample per task per session (UNIQUE constraint). */
public final class VoiceSampleRepository {

    private static final String COLUMNS =
            "id, session_id, task_type, audio_path, audio_format, sample_rate_hz, sample_count, byte_size, sha256, recorded_at";

    /** The fields of a new sample; the database assigns the id. */
    public record Draft(long sessionId, TaskType taskType, String audioPath, AudioFormat format, int sampleRateHz,
                        long sampleCount, long byteSize, String sha256, Instant recordedAt) {
    }

    public VoiceSample insert(Connection c, Draft d) throws SQLException {
        // Validate through the model first so bad values fail with a clear message, not a constraint error.
        new VoiceSample(0, d.sessionId(), d.taskType(), d.audioPath(), d.format(), d.sampleRateHz(), d.sampleCount(),
                d.byteSize(), d.sha256(), d.recordedAt());
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO voice_samples (session_id, task_type, audio_path, audio_format, "
                + "sample_rate_hz, sample_count, byte_size, sha256, recorded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, d.sessionId());
            ps.setString(2, d.taskType().code());
            ps.setString(3, d.audioPath());
            ps.setString(4, d.format().code());
            ps.setInt(5, d.sampleRateHz());
            ps.setLong(6, d.sampleCount());
            ps.setLong(7, d.byteSize());
            ps.setString(8, d.sha256());
            ps.setString(9, Timestamps.format(d.recordedAt()));
            ps.executeUpdate();
            return new VoiceSample(ProfileRepository.generatedId(ps), d.sessionId(), d.taskType(), d.audioPath(), d.format(),
                    d.sampleRateHz(), d.sampleCount(), d.byteSize(), d.sha256(), d.recordedAt());
        }
    }

    public Optional<VoiceSample> findById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM voice_samples WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<VoiceSample> findBySessionAndTask(Connection c, long sessionId, TaskType task) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM voice_samples WHERE session_id = ? AND task_type = ?")) {
            ps.setLong(1, sessionId);
            ps.setString(2, task.code());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /** A session's samples in guided-task order (vowel, pa-ta-ka, reading, free speech). */
    public List<VoiceSample> listBySession(Connection c, long sessionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM voice_samples WHERE session_id = ?")) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<VoiceSample> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                out.sort(Comparator.comparingInt(s -> s.taskType().order()));
                return out;
            }
        }
    }

    /** Audio paths of every sample in the given session, for file clean-up after deletion. */
    public List<String> audioPathsBySession(Connection c, long sessionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT audio_path FROM voice_samples WHERE session_id = ?")) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
                return out;
            }
        }
    }

    public int countTasks(Connection c, long sessionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(DISTINCT task_type) FROM voice_samples WHERE session_id = ?")) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public boolean delete(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM voice_samples WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    private static VoiceSample map(ResultSet rs) throws SQLException {
        return new VoiceSample(
                rs.getLong("id"),
                rs.getLong("session_id"),
                TaskType.fromCode(rs.getString("task_type")),
                rs.getString("audio_path"),
                AudioFormat.fromCode(rs.getString("audio_format")),
                rs.getInt("sample_rate_hz"),
                rs.getLong("sample_count"),
                rs.getLong("byte_size"),
                rs.getString("sha256"),
                Timestamps.parse(rs.getString("recorded_at")));
    }
}
