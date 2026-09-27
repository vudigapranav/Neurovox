package com.neurovox.session;

import com.neurovox.db.Database;
import com.neurovox.db.ProfileRepository;
import com.neurovox.db.SessionRepository;
import com.neurovox.db.Timestamps;
import com.neurovox.db.VoiceSampleRepository;
import com.neurovox.model.LocalProfile;
import com.neurovox.model.RecordingSession;
import com.neurovox.model.TaskType;
import com.neurovox.model.VoiceSample;
import com.neurovox.model.VoiceSample.AudioFormat;
import com.neurovox.storage.RecordingStore;
import com.neurovox.storage.RecordingStore.StoredFile;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Profiles, sessions and their recordings. Keeps the audio files and the database rows consistent:
 *
 * <ul>
 *   <li><b>Attach:</b> write the file first, then insert the row in a transaction. If the database step
 *       fails, the new file is deleted, so no file is left without a row. A re-recorded task replaces the
 *       old row in the same transaction; the old file is removed only after the commit.</li>
 *   <li><b>Delete:</b> remove the rows first (cascading to samples, features and results), then the files.</li>
 * </ul>
 */
public final class SessionService {

    public static final String DEFAULT_PROFILE_LABEL = "Local profile";

    private static final Logger LOG = Logger.getLogger(SessionService.class.getName());

    private final Database database;
    private final RecordingStore store;
    private final Clock clock;
    private final ProfileRepository profiles = new ProfileRepository();
    private final SessionRepository sessions = new SessionRepository();
    private final VoiceSampleRepository samples = new VoiceSampleRepository();

    public SessionService(Database database, RecordingStore store, Clock clock) {
        this.database = database;
        this.store = store;
        this.clock = clock;
    }

    // ---------- profiles ----------

    public LocalProfile createProfile(String label) {
        return database.transaction(c -> profiles.insert(c, label, Timestamps.now(clock)));
    }

    public List<LocalProfile> profiles() {
        return database.read(profiles::findAll);
    }

    /** The oldest profile, created with a neutral label if none exists yet. */
    public LocalProfile defaultProfile() {
        return database.transaction(c -> {
            List<LocalProfile> all = profiles.findAll(c);
            return all.isEmpty() ? profiles.insert(c, DEFAULT_PROFILE_LABEL, Timestamps.now(clock)) : all.get(0);
        });
    }

    // ---------- sessions ----------

    public RecordingSession startSession(long profileId) {
        return database.transaction(c -> {
            if (profiles.findById(c, profileId).isEmpty()) {
                throw new NotFoundException("Profile " + profileId + " does not exist.");
            }
            return sessions.insert(c, profileId, Timestamps.now(clock));
        });
    }

    /** A profile's sessions, newest first. */
    public List<RecordingSession> history(long profileId) {
        return database.read(c -> sessions.listByProfile(c, profileId));
    }

    public Optional<SessionDetails> details(long sessionId) {
        return database.read(c -> {
            Optional<RecordingSession> session = sessions.findById(c, sessionId);
            if (session.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new SessionDetails(session.get(), samples.listBySession(c, sessionId)));
        });
    }

    /**
     * Stores one task's recording in a session, replacing any earlier take of the same task.
     * When all four tasks are present the session is marked complete.
     *
     * @param pcmF32le mono 32-bit float samples, little-endian, exactly as captured
     */
    public VoiceSample attachRecording(long sessionId, TaskType task, int sampleRateHz, byte[] pcmF32le) {
        AudioFormat format = AudioFormat.PCM_F32LE;
        VoiceSample.validateSampleRate(sampleRateHz);
        if (pcmF32le == null || pcmF32le.length == 0) {
            throw new IllegalArgumentException("The recording is empty.");
        }
        if (pcmF32le.length % format.bytesPerSample() != 0) {
            throw new IllegalArgumentException("The recording length is not a whole number of 32-bit samples.");
        }
        if (database.read(c -> sessions.findById(c, sessionId)).isEmpty()) {
            throw new NotFoundException("Session " + sessionId + " does not exist.");
        }

        Instant recordedAt = Timestamps.now(clock);
        StoredFile file = store.write(sessionId, task, format, pcmF32le, recordedAt);
        long sampleCount = pcmF32le.length / format.bytesPerSample();

        record Outcome(VoiceSample sample, String replacedPath) {
        }
        Outcome outcome;
        try {
            outcome = database.transaction(c -> {
                String replaced = null;
                Optional<VoiceSample> previous = samples.findBySessionAndTask(c, sessionId, task);
                if (previous.isPresent()) {
                    samples.delete(c, previous.get().id());
                    replaced = previous.get().audioPath();
                }
                VoiceSample sample = samples.insert(c, new VoiceSampleRepository.Draft(sessionId, task, file.relativePath(), format,
                        sampleRateHz, sampleCount, file.byteSize(), file.sha256(), recordedAt));
                if (samples.countTasks(c, sessionId) == TaskType.values().length) {
                    sessions.markComplete(c, sessionId, recordedAt);
                }
                return new Outcome(sample, replaced);
            });
        } catch (RuntimeException e) {
            store.delete(file.relativePath());
            throw e;
        }

        if (outcome.replacedPath() != null) {
            try {
                store.delete(outcome.replacedPath());
            } catch (RuntimeException e) {
                LOG.warning("Could not delete the replaced recording " + outcome.replacedPath() + ": " + e.getMessage());
            }
        }
        return outcome.sample();
    }

    /** Reads a stored recording's bytes and checks them against the saved SHA-256. */
    public byte[] readRecording(VoiceSample sample) {
        byte[] data = store.read(sample.audioPath());
        if (!RecordingStore.sha256(data).equals(sample.sha256())) {
            throw new IllegalStateException("The recording file for sample " + sample.id() + " has been changed or corrupted.");
        }
        return data;
    }

    // ---------- deletion ----------

    /** Deletes a session's rows and recording files. Returns false if the session did not exist. */
    public boolean deleteSession(long sessionId) {
        boolean existed = database.transaction(c -> sessions.delete(c, sessionId));
        // Always attempt the folder too, so a retry cleans up files left by an earlier failure.
        store.deleteSessionFolder(sessionId);
        return existed;
    }

    /** Deletes a profile with all its sessions, recordings, features and results. */
    public boolean deleteProfile(long profileId) {
        List<Long> sessionIds = new ArrayList<>();
        boolean existed = database.transaction(c -> {
            sessionIds.addAll(sessions.idsByProfile(c, profileId));
            return profiles.delete(c, profileId);
        });
        for (long id : sessionIds) {
            store.deleteSessionFolder(id);
        }
        return existed;
    }
}
