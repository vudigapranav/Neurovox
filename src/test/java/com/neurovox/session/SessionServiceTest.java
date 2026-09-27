package com.neurovox.session;

import com.neurovox.MutableClock;
import com.neurovox.model.LocalProfile;
import com.neurovox.model.RecordingSession;
import com.neurovox.model.TaskType;
import com.neurovox.model.VoiceSample;
import com.neurovox.storage.LocalData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 2 acceptance: sessions group four recordings, history is ordered, deletion is complete. */
class SessionServiceTest {

    private static final Instant START = Instant.parse("2026-09-26T09:00:00.000Z");

    @TempDir
    Path dataDir;

    private MutableClock clock;
    private LocalData data;
    private SessionService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        data = LocalData.open(dataDir);
        service = new SessionService(data.database(), data.recordings(), clock);
    }

    /** A little-endian float32 sine, as the browser would send it. */
    private static byte[] tone(double seconds, int sampleRate, double hz) {
        int n = (int) Math.round(seconds * sampleRate);
        ByteBuffer buffer = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            buffer.putFloat((float) (0.3 * Math.sin(2 * Math.PI * hz * i / sampleRate)));
        }
        return buffer.array();
    }

    private long fileCount() throws Exception {
        Path root = data.recordings().root();
        if (!Files.exists(root)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void aSessionGroupsAllFourTasksAndCompletes() throws Exception {
        LocalProfile profile = service.createProfile("Pranav");
        RecordingSession session = service.startSession(profile.id());
        assertFalse(session.isComplete());

        // Record out of order to show the stored order is the guided order, not arrival order.
        TaskType[] arrival = {TaskType.READING_PASSAGE, TaskType.SUSTAINED_VOWEL, TaskType.FREE_SPEECH, TaskType.PA_TA_KA};
        for (TaskType task : arrival) {
            clock.advance(Duration.ofSeconds(40));
            int rate = task == TaskType.FREE_SPEECH ? 44_100 : 48_000;
            byte[] pcm = tone(task.targetSeconds(), rate, 150);
            VoiceSample sample = service.attachRecording(session.id(), task, rate, pcm);
            assertEquals(task, sample.taskType());
            assertEquals(rate, sample.sampleRateHz());
            assertEquals(task.targetSeconds(), sample.durationSeconds(), 1e-6);
            assertArrayEquals(pcm, service.readRecording(sample), "stored bytes must be exactly what was sent");
            boolean last = task == TaskType.PA_TA_KA;
            assertEquals(last, service.details(session.id()).orElseThrow().session().isComplete());
        }

        SessionDetails details = service.details(session.id()).orElseThrow();
        assertEquals(List.of(TaskType.SUSTAINED_VOWEL, TaskType.PA_TA_KA, TaskType.READING_PASSAGE, TaskType.FREE_SPEECH),
                details.samples().stream().map(VoiceSample::taskType).toList());
        assertEquals(List.of(), details.missingTasks());
        assertEquals(RecordingSession.Status.COMPLETE, details.session().status());
        assertEquals(START.plusSeconds(160), details.session().completedAt());
        assertEquals(4, fileCount());
        for (VoiceSample s : details.samples()) {
            assertTrue(s.audioPath().startsWith("recordings/session-" + session.id() + "/" + s.taskType().code() + "-"), s.audioPath());
            assertTrue(Files.exists(data.recordings().resolve(s.audioPath())));
        }
    }

    @Test
    void historyIsReturnedNewestFirst() {
        LocalProfile me = service.createProfile("Me");
        LocalProfile someoneElse = service.createProfile("Someone else");
        RecordingSession monday = service.startSession(me.id());
        clock.advance(Duration.ofDays(1));
        RecordingSession tuesday = service.startSession(me.id());
        service.startSession(someoneElse.id());
        clock.advance(Duration.ofDays(1));
        RecordingSession wednesday = service.startSession(me.id());

        List<RecordingSession> history = service.history(me.id());
        assertEquals(List.of(wednesday.id(), tuesday.id(), monday.id()), history.stream().map(RecordingSession::id).toList());
        assertEquals(List.of(START.plus(Duration.ofDays(2)), START.plus(Duration.ofDays(1)), START),
                history.stream().map(RecordingSession::startedAt).toList());
        assertEquals(1, service.history(someoneElse.id()).size());
    }

    @Test
    void retakingATaskReplacesTheSampleAndItsFile() throws Exception {
        long session = service.startSession(service.defaultProfile().id()).id();
        VoiceSample first = service.attachRecording(session, TaskType.SUSTAINED_VOWEL, 48_000, tone(5, 48_000, 120));
        clock.advance(Duration.ofSeconds(10));
        VoiceSample retake = service.attachRecording(session, TaskType.SUSTAINED_VOWEL, 48_000, tone(4, 48_000, 220));

        assertNotEquals(first.audioPath(), retake.audioPath());
        assertFalse(Files.exists(data.recordings().resolve(first.audioPath())), "old take deleted");
        assertEquals(List.of(retake), service.details(session).orElseThrow().samples());
        assertEquals(1, fileCount());
    }

    @Test
    void invalidRecordingsAreRejectedWithoutLeavingFiles() throws Exception {
        long session = service.startSession(service.defaultProfile().id()).id();
        assertThrows(IllegalArgumentException.class, () -> service.attachRecording(session, TaskType.PA_TA_KA, 48_000, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> service.attachRecording(session, TaskType.PA_TA_KA, 48_000, new byte[7]));
        assertThrows(IllegalArgumentException.class, () -> service.attachRecording(session, TaskType.PA_TA_KA, 4_000, new byte[8]));
        assertThrows(NotFoundException.class, () -> service.attachRecording(9_999, TaskType.PA_TA_KA, 48_000, new byte[8]));
        assertThrows(NotFoundException.class, () -> service.startSession(9_999));
        assertEquals(0, fileCount());
        assertEquals(List.of(), service.details(session).orElseThrow().samples());
    }

    @Test
    void aFailedDatabaseWriteRemovesTheNewFile() throws Exception {
        long session = service.startSession(service.defaultProfile().id()).id();
        // A trigger that rejects every sample insert stands in for any database failure after the file is written.
        data.database().transaction(c -> {
            c.createStatement().execute("CREATE TRIGGER fail_insert BEFORE INSERT ON voice_samples "
                    + "BEGIN SELECT RAISE(ABORT, 'simulated failure'); END");
            return null;
        });
        assertThrows(RuntimeException.class, () -> service.attachRecording(session, TaskType.FREE_SPEECH, 48_000, tone(1, 48_000, 100)));
        assertEquals(0, fileCount(), "the file written before the failed insert must be removed");
    }

    @Test
    void corruptedFilesAreDetected() throws Exception {
        long session = service.startSession(service.defaultProfile().id()).id();
        VoiceSample sample = service.attachRecording(session, TaskType.PA_TA_KA, 48_000, tone(1, 48_000, 100));
        Path file = data.recordings().resolve(sample.audioPath());
        byte[] bytes = Files.readAllBytes(file);
        bytes[100] ^= 0x01;
        Files.write(file, bytes);
        assertThrows(IllegalStateException.class, () -> service.readRecording(sample));
    }

    @Test
    void deletingASessionRemovesRowsAndFiles() throws Exception {
        LocalProfile p = service.createProfile("P");
        long keep = service.startSession(p.id()).id();
        long remove = service.startSession(p.id()).id();
        service.attachRecording(keep, TaskType.SUSTAINED_VOWEL, 48_000, tone(1, 48_000, 100));
        for (TaskType task : TaskType.values()) {
            service.attachRecording(remove, task, 48_000, tone(1, 48_000, 100));
        }
        assertEquals(5, fileCount());

        assertTrue(service.deleteSession(remove));
        assertTrue(service.details(remove).isEmpty());
        assertFalse(Files.exists(data.recordings().sessionFolder(remove)));
        assertEquals(1, fileCount());
        assertEquals(List.of(keep), service.history(p.id()).stream().map(RecordingSession::id).toList());
        assertFalse(service.deleteSession(remove), "deleting twice reports nothing to delete");
    }

    @Test
    void deletingAProfileRemovesEverythingOfTheirs() throws Exception {
        LocalProfile a = service.createProfile("A");
        LocalProfile b = service.createProfile("B");
        for (LocalProfile p : List.of(a, b)) {
            long s = service.startSession(p.id()).id();
            service.attachRecording(s, TaskType.SUSTAINED_VOWEL, 48_000, tone(1, 48_000, 100));
        }
        assertTrue(service.deleteProfile(a.id()));
        assertEquals(List.of(b), service.profiles());
        assertEquals(List.of(), service.history(a.id()));
        assertEquals(1, fileCount());
    }

    @Test
    void defaultProfileIsCreatedOnceAndCarriesNoPersonalDetails() {
        LocalProfile first = service.defaultProfile();
        LocalProfile again = service.defaultProfile();
        assertEquals(first, again);
        assertEquals(SessionService.DEFAULT_PROFILE_LABEL, first.displayName());
        assertEquals(1, service.profiles().size());
        // The profile record has exactly these components: nothing else about the person is stored.
        assertEquals(List.of("id", "displayName", "createdAt"),
                Stream.of(LocalProfile.class.getRecordComponents()).map(rc -> rc.getName()).toList());
    }

    @Test
    void sessionStateSurvivesReopeningTheDataFolder() {
        long p = service.createProfile("Persist").id();
        long s = service.startSession(p).id();
        service.attachRecording(s, TaskType.FREE_SPEECH, 44_100, tone(1, 44_100, 90));

        LocalData reopened = LocalData.open(dataDir);
        SessionService again = new SessionService(reopened.database(), reopened.recordings(), clock);
        SessionDetails details = again.details(s).orElseThrow();
        assertEquals(1, details.samples().size());
        assertNull(details.session().completedAt());
        assertEquals(44_100, details.samples().get(0).sampleRateHz());
    }
}
