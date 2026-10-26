package com.neurovox.storage;

import com.neurovox.model.TaskType;
import com.neurovox.model.VoiceSample.AudioFormat;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * Stores recording files inside the data folder:
 * <pre>
 *   &lt;data&gt;/recordings/session-&lt;id&gt;/&lt;task&gt;-&lt;utc time&gt;-&lt;random&gt;.f32le
 * </pre>
 * Paths saved in the database are relative to the data folder, so the folder can be moved.
 * Each write gets a unique name, so re-recording a task never overwrites the file an existing
 * database row points to. Files are written to a temporary name and then renamed into place,
 * so a crash cannot leave a half-written recording under a real name.
 */
public final class RecordingStore {

    public static final String FOLDER = "recordings";

    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);

    private final Path dataDir;
    private final Path root;
    private final SecureRandom random = new SecureRandom();

    /** Result of a write: where the file is and what it contains. */
    public record StoredFile(String relativePath, long byteSize, String sha256) {
    }

    public RecordingStore(Path dataDir) {
        this.dataDir = dataDir.toAbsolutePath().normalize();
        this.root = this.dataDir.resolve(FOLDER);
    }

    public Path root() {
        return root;
    }

    public StoredFile write(long sessionId, TaskType task, AudioFormat format, byte[] data, Instant recordedAt) {
        Path folder = sessionFolder(sessionId);
        String name = task.code() + "-" + FILE_TIME.format(recordedAt) + "-" + randomHex() + "." + format.extension();
        Path target = folder.resolve(name);
        Path temp = null;
        try {
            FilePermissions.createPrivateDirectories(folder);
            temp = Files.createTempFile(folder, ".upload-", ".tmp");
            try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.WRITE, StandardOpenOption.SYNC)) {
                out.write(data);
            }
            FilePermissions.makePrivate(temp);
            moveIntoPlace(temp, target);
            temp = null;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save the recording", e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Best effort; a stray temp file holds no database reference.
                }
            }
        }
        return new StoredFile(FOLDER + "/" + folder.getFileName() + "/" + name, data.length, sha256(data));
    }

    /** Resolves a stored relative path, refusing anything that would point outside the recordings folder. */
    public Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank() || relativePath.startsWith("/") || relativePath.contains("\\")) {
            throw new IllegalArgumentException("Invalid recording path: " + relativePath);
        }
        Path resolved = dataDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException("Recording path escapes the recordings folder: " + relativePath);
        }
        return resolved;
    }

    public byte[] read(String relativePath) {
        try {
            return Files.readAllBytes(resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the recording " + relativePath, e);
        }
    }

    public boolean delete(String relativePath) {
        try {
            return Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete the recording " + relativePath, e);
        }
    }

    /** Deletes a session's folder and everything in it. Returns false if it did not exist. */
    public boolean deleteSessionFolder(long sessionId) {
        Path folder = sessionFolder(sessionId);
        if (!Files.exists(folder)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete the recordings for session " + sessionId, e);
        }
    }

    /** Deletes the whole recordings folder (used by "delete all local data"). */
    public void deleteAllRecordings() {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete the recordings folder", e);
        }
    }

    public Path sessionFolder(long sessionId) {
        if (sessionId <= 0) {
            throw new IllegalArgumentException("Invalid session id: " + sessionId);
        }
        return root.resolve("session-" + sessionId);
    }

    /** Hex SHA-256 of the bytes, stored with each sample to detect corruption. */
    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }

    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target);
        }
    }

    private String randomHex() {
        byte[] bytes = new byte[3];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
