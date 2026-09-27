package com.neurovox.storage;

import com.neurovox.model.TaskType;
import com.neurovox.model.VoiceSample.AudioFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingStoreTest {

    private static final Instant T = Instant.parse("2026-09-26T10:15:00.123Z");

    @TempDir
    Path dataDir;

    @Test
    void writesUnderTheSessionFolderWithAUniqueName() throws Exception {
        RecordingStore store = new RecordingStore(dataDir);
        byte[] data = {1, 2, 3, 4, 5, 6, 7, 8};
        RecordingStore.StoredFile a = store.write(7, TaskType.SUSTAINED_VOWEL, AudioFormat.PCM_F32LE, data, T);
        RecordingStore.StoredFile b = store.write(7, TaskType.SUSTAINED_VOWEL, AudioFormat.PCM_F32LE, data, T);

        assertTrue(a.relativePath().matches("recordings/session-7/sustained_vowel-20260926T101500123Z-[0-9a-f]{6}\\.f32le"), a.relativePath());
        assertNotEquals(a.relativePath(), b.relativePath(), "a retake must never overwrite an earlier file");
        assertArrayEquals(data, store.read(a.relativePath()));
        assertEquals(8, a.byteSize());
        assertEquals("66840dda154e8a113c31dd0ad32f7f3a366a80e8136979d8f5a101d3d29d6f72", a.sha256());
        try (Stream<Path> files = Files.list(store.sessionFolder(7))) {
            assertEquals(2, files.count(), "no temporary files are left behind");
        }
    }

    @Test
    void filesAndFoldersArePrivateToTheUser() throws Exception {
        if (!dataDir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        RecordingStore store = new RecordingStore(dataDir);
        RecordingStore.StoredFile f = store.write(1, TaskType.FREE_SPEECH, AudioFormat.PCM_F32LE, new byte[4], T);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.resolve(f.relativePath()))));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.sessionFolder(1))));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.root())));
    }

    @Test
    void theDatabaseFileIsPrivateEvenInAnExistingSharedFolder() throws Exception {
        if (!dataDir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Files.setPosixFilePermissions(dataDir, PosixFilePermissions.fromString("rwxr-xr-x"));
        LocalData data = LocalData.open(dataDir);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(data.database().file())));
        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(dataDir)), "existing folders are not changed");
    }

    @Test
    void resolveRefusesPathsOutsideTheRecordingsFolder() {
        RecordingStore store = new RecordingStore(dataDir);
        for (String bad : new String[] {"../secret", "recordings/../neurovox.db", "/etc/passwd", "recordings", "recordings/..\\x", "", "neurovox.db"}) {
            assertThrows(IllegalArgumentException.class, () -> store.resolve(bad), bad);
        }
        assertEquals(dataDir.toAbsolutePath().normalize().resolve("recordings/session-1/a.f32le"), store.resolve("recordings/session-1/a.f32le"));
    }

    @Test
    void deletesFilesAndWholeSessionFolders() {
        RecordingStore store = new RecordingStore(dataDir);
        RecordingStore.StoredFile a = store.write(3, TaskType.PA_TA_KA, AudioFormat.PCM_F32LE, new byte[4], T);
        store.write(3, TaskType.FREE_SPEECH, AudioFormat.PCM_F32LE, new byte[4], T);
        assertTrue(store.delete(a.relativePath()));
        assertFalse(store.delete(a.relativePath()));
        assertTrue(store.deleteSessionFolder(3));
        assertFalse(Files.exists(store.sessionFolder(3)));
        assertFalse(store.deleteSessionFolder(3));
        assertThrows(IllegalArgumentException.class, () -> store.sessionFolder(0));
    }
}
