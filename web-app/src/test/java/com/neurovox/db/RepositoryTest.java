package com.neurovox.db;

import com.neurovox.model.FeatureValue;
import com.neurovox.model.LocalProfile;
import com.neurovox.model.RecordingSession;
import com.neurovox.model.ScreeningResult;
import com.neurovox.model.SpeechFeatures;
import com.neurovox.model.TaskType;
import com.neurovox.model.VoiceSample;
import com.neurovox.model.VoiceSample.AudioFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Repository behaviour and the database constraints behind it. */
class RepositoryTest {

    private static final Instant T0 = Instant.parse("2026-09-26T10:00:00.000Z");
    private static final String SHA = "a".repeat(64);

    @TempDir
    Path dir;

    private Database db;
    private final ProfileRepository profiles = new ProfileRepository();
    private final SessionRepository sessions = new SessionRepository();
    private final VoiceSampleRepository samples = new VoiceSampleRepository();
    private final SpeechFeatureRepository features = new SpeechFeatureRepository();
    private final ScreeningResultRepository results = new ScreeningResultRepository();

    @BeforeEach
    void open() {
        db = Database.open(dir.resolve("nv.db"));
    }

    private VoiceSampleRepository.Draft draft(long sessionId, TaskType task, String path) {
        return new VoiceSampleRepository.Draft(sessionId, task, path, AudioFormat.PCM_F32LE, 48_000, 240_000, 960_000, SHA, T0);
    }

    private long count(String table) {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table); ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    @Test
    void profileRoundTripAndValidation() {
        LocalProfile saved = db.transaction(c -> profiles.insert(c, "  Pranav  ", T0));
        assertEquals("Pranav", saved.displayName());
        assertEquals(saved, db.read(c -> profiles.findById(c, saved.id())).orElseThrow());
        for (String bad : new String[] {"", "   ", "x".repeat(61), "tab\there"}) {
            assertThrows(IllegalArgumentException.class, () -> db.transaction(c -> profiles.insert(c, bad, T0)), bad);
        }
        assertEquals(1, count("profiles"));
    }

    @Test
    void sessionHistoryIsNewestFirstWithStableTieBreak() {
        long p = db.transaction(c -> profiles.insert(c, "A", T0)).id();
        long other = db.transaction(c -> profiles.insert(c, "B", T0)).id();
        RecordingSession s1 = db.transaction(c -> sessions.insert(c, p, T0));
        RecordingSession s2 = db.transaction(c -> sessions.insert(c, p, T0.plusSeconds(86_400)));
        RecordingSession s3 = db.transaction(c -> sessions.insert(c, p, T0.plusSeconds(86_400))); // same start as s2
        db.transaction(c -> sessions.insert(c, other, T0.plusSeconds(999_999)));

        List<RecordingSession> history = db.read(c -> sessions.listByProfile(c, p));
        assertEquals(List.of(s3.id(), s2.id(), s1.id()), history.stream().map(RecordingSession::id).toList());
    }

    @Test
    void markCompleteOnlyOnce() {
        long p = db.transaction(c -> profiles.insert(c, "A", T0)).id();
        long s = db.transaction(c -> sessions.insert(c, p, T0)).id();
        boolean first = db.transaction(c -> sessions.markComplete(c, s, T0.plusSeconds(60)));
        boolean second = db.transaction(c -> sessions.markComplete(c, s, T0.plusSeconds(120)));
        assertTrue(first);
        assertFalse(second);
        RecordingSession done = db.read(c -> sessions.findById(c, s)).orElseThrow();
        assertEquals(RecordingSession.Status.COMPLETE, done.status());
        assertEquals(T0.plusSeconds(60), done.completedAt());
    }

    @Test
    void foreignKeysRejectOrphans() {
        assertThrows(DataAccessException.class, () -> db.transaction(c -> sessions.insert(c, 999, T0)));
        assertThrows(DataAccessException.class, () -> db.transaction(c -> samples.insert(c, draft(999, TaskType.SUSTAINED_VOWEL, "recordings/x.f32le"))));
        assertEquals(0, count("recording_sessions"));
        assertEquals(0, count("voice_samples"));
    }

    @Test
    void oneSamplePerTaskPerSession() {
        long p = db.transaction(c -> profiles.insert(c, "A", T0)).id();
        long s = db.transaction(c -> sessions.insert(c, p, T0)).id();
        db.transaction(c -> samples.insert(c, draft(s, TaskType.PA_TA_KA, "recordings/a.f32le")));
        assertThrows(DataAccessException.class, () -> db.transaction(c -> samples.insert(c, draft(s, TaskType.PA_TA_KA, "recordings/b.f32le"))));
        // The same file path can never be referenced twice either.
        assertThrows(DataAccessException.class, () -> db.transaction(c -> samples.insert(c, draft(s, TaskType.FREE_SPEECH, "recordings/a.f32le"))));
    }

    @Test
    void databaseConstraintsBackUpModelValidation() throws SQLException {
        long p = db.transaction(c -> profiles.insert(c, "A", T0)).id();
        long s = db.transaction(c -> sessions.insert(c, p, T0)).id();
        // Bypass the repository to prove the schema itself rejects bad rows.
        String[] badRows = {
                "INSERT INTO voice_samples (session_id, task_type, audio_path, audio_format, sample_rate_hz, sample_count, byte_size, sha256, recorded_at) "
                        + "VALUES (" + s + ", 'humming', 'p1', 'pcm_f32le', 48000, 10, 40, '" + SHA + "', '2026-09-26T10:00:00.000Z')",
                "INSERT INTO voice_samples (session_id, task_type, audio_path, audio_format, sample_rate_hz, sample_count, byte_size, sha256, recorded_at) "
                        + "VALUES (" + s + ", 'free_speech', 'p2', 'pcm_f32le', 48000, 10, 41, '" + SHA + "', '2026-09-26T10:00:00.000Z')",
                "INSERT INTO voice_samples (session_id, task_type, audio_path, audio_format, sample_rate_hz, sample_count, byte_size, sha256, recorded_at) "
                        + "VALUES (" + s + ", 'free_speech', 'p3', 'pcm_f32le', 1000, 10, 40, '" + SHA + "', '2026-09-26T10:00:00.000Z')",
                "INSERT INTO voice_samples (session_id, task_type, audio_path, audio_format, sample_rate_hz, sample_count, byte_size, sha256, recorded_at) "
                        + "VALUES (" + s + ", 'free_speech', 'p4', 'pcm_f32le', 48000, 10, 40, '" + SHA + "', '2026-09-26 10:00')",
                "UPDATE recording_sessions SET status = 'complete' WHERE id = " + s,
                "UPDATE recording_sessions SET status = 'complete', completed_at = '2020-01-01T00:00:00.000Z' WHERE id = " + s,
        };
        try (Connection c = db.connect()) {
            for (String sql : badRows) {
                assertThrows(SQLException.class, () -> c.createStatement().execute(sql), sql);
            }
        }
    }

    @Test
    void featuresKeepOrderAndExplicitUnavailability() {
        long p = db.transaction(c -> profiles.insert(c, "A", T0)).id();
        long s = db.transaction(c -> sessions.insert(c, p, T0)).id();
        long sample = db.transaction(c -> samples.insert(c, draft(s, TaskType.SUSTAINED_VOWEL, "recordings/v.f32le"))).id();
        SpeechFeatures saved = new SpeechFeatures(sample, "test-1", T0, List.of(
                FeatureValue.of("zeta", 3.5, "Hz"),
                FeatureValue.unavailable("alpha", "%", "fewer than 3 valid vocal pulses"),
                FeatureValue.of("mid", -12.25, "dB")));
        db.transaction(c -> {
            features.save(c, saved);
            return null;
        });
        assertEquals(saved, db.read(c -> features.find(c, sample, "test-1")).orElseThrow());

        // Saving again for the same version replaces the whole set.
        SpeechFeatures replacement = new SpeechFeatures(sample, "test-1", T0.plusSeconds(5), List.of(FeatureValue.of("only", 1.0, "ratio")));
        db.transaction(c -> {
            features.save(c, replacement);
            return null;
        });
        assertEquals(replacement, db.read(c -> features.find(c, sample, "test-1")).orElseThrow());
        assertTrue(db.read(c -> features.find(c, sample, "other-version")).isEmpty());
    }

    @Test
    void nonFiniteFeatureValuesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> FeatureValue.of("f0", Double.NaN, "Hz"));
        assertThrows(IllegalArgumentException.class, () -> FeatureValue.of("f0", Double.POSITIVE_INFINITY, "Hz"));
        assertThrows(IllegalArgumentException.class, () -> new FeatureValue("f0", 1.0, "Hz", "also a reason"));
        assertThrows(IllegalArgumentException.class, () -> new FeatureValue("f0", null, "Hz", null));
    }

    @Test
    void screeningResultsOnlyFromTheSessionsSustainedVowel() throws SQLException {
        long p = db.transaction(c -> profiles.insert(c, "A", T0)).id();
        long s = db.transaction(c -> sessions.insert(c, p, T0)).id();
        long otherSession = db.transaction(c -> sessions.insert(c, p, T0)).id();
        long vowel = db.transaction(c -> samples.insert(c, draft(s, TaskType.SUSTAINED_VOWEL, "recordings/v.f32le"))).id();
        long reading = db.transaction(c -> samples.insert(c, draft(s, TaskType.READING_PASSAGE, "recordings/r.f32le"))).id();
        long otherVowel = db.transaction(c -> samples.insert(c, draft(otherSession, TaskType.SUSTAINED_VOWEL, "recordings/o.f32le"))).id();

        ScreeningResult ok = db.transaction(c -> results.insert(c, s, vowel, "model-1", 0.25, T0));
        assertEquals(List.of(ok), db.read(c -> results.listBySession(c, s)));

        assertThrows(IllegalArgumentException.class, () -> db.transaction(c -> results.insert(c, s, reading, "model-2", 0.5, T0)));
        assertThrows(IllegalArgumentException.class, () -> db.transaction(c -> results.insert(c, s, otherVowel, "model-2", 0.5, T0)));
        assertThrows(IllegalArgumentException.class, () -> db.transaction(c -> results.insert(c, s, vowel, "model-2", 1.5, T0)));

        // The trigger enforces the rule even for SQL that bypasses the repository.
        try (Connection c = db.connect()) {
            SQLException e = assertThrows(SQLException.class, () -> c.createStatement().execute(
                    "INSERT INTO screening_results (session_id, sample_id, model_version, research_score, created_at) "
                            + "VALUES (" + s + ", " + reading + ", 'raw', 0.5, '2026-09-26T10:00:00.000Z')"));
            assertTrue(e.getMessage().contains("sustained-vowel"), e.getMessage());
            assertThrows(SQLException.class, () -> c.createStatement().execute(
                    "UPDATE screening_results SET sample_id = " + reading + " WHERE id = " + ok.id()));
        }
        assertEquals(1, count("screening_results"));
    }

    @Test
    void deletingCascadesThroughEveryTable() {
        long p = db.transaction(c -> profiles.insert(c, "A", T0)).id();
        long s = db.transaction(c -> sessions.insert(c, p, T0)).id();
        long vowel = db.transaction(c -> samples.insert(c, draft(s, TaskType.SUSTAINED_VOWEL, "recordings/v.f32le"))).id();
        db.transaction(c -> {
            features.save(c, new SpeechFeatures(vowel, "v1", T0, List.of(FeatureValue.of("f0", 120.0, "Hz"))));
            return results.insert(c, s, vowel, "m", 0.1, T0);
        });
        assertEquals(1, count("speech_features"));

        boolean deleted = db.transaction(c -> profiles.delete(c, p));
        assertTrue(deleted);
        for (String table : List.of("profiles", "recording_sessions", "voice_samples", "speech_features", "screening_results")) {
            assertEquals(0, count(table), table);
        }
    }

    @Test
    void voiceSampleModelChecksItsInvariants() {
        assertThrows(IllegalArgumentException.class, () -> new VoiceSample(1, 1, TaskType.PA_TA_KA, "p", AudioFormat.PCM_F32LE, 48_000, 10, 41, SHA, T0));
        assertThrows(IllegalArgumentException.class, () -> new VoiceSample(1, 1, TaskType.PA_TA_KA, "p", AudioFormat.PCM_F32LE, 7_999, 10, 40, SHA, T0));
        assertThrows(IllegalArgumentException.class, () -> new VoiceSample(1, 1, TaskType.PA_TA_KA, "p", AudioFormat.PCM_F32LE, 48_000, 0, 0, SHA, T0));
        assertThrows(IllegalArgumentException.class, () -> new VoiceSample(1, 1, TaskType.PA_TA_KA, "p", AudioFormat.PCM_F32LE, 48_000, 10, 40, "XYZ", T0));
        VoiceSample ok = new VoiceSample(1, 1, TaskType.PA_TA_KA, "p", AudioFormat.PCM_F32LE, 48_000, 288_000, 1_152_000, SHA, T0);
        assertEquals(6.0, ok.durationSeconds(), 1e-12);
    }
}
