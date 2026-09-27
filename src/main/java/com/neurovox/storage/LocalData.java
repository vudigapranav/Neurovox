package com.neurovox.storage;

import com.neurovox.db.Database;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * The local data folder and what lives in it:
 * <pre>
 *   &lt;data&gt;/neurovox.db     SQLite database (profiles, sessions, sample metadata, features, results)
 *   &lt;data&gt;/recordings/     audio files
 * </pre>
 */
public record LocalData(Path dataDir, Database database, RecordingStore recordings) {

    public static final String DATABASE_FILE = "neurovox.db";

    /** Creates the folder if needed (owner-only permissions), opens the database and migrates it. */
    public static LocalData open(Path dataDir) {
        Path dir = dataDir.toAbsolutePath().normalize();
        try {
            // Folders created here get owner-only permissions; an existing folder is left as it is.
            FilePermissions.createPrivateDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the data folder " + dir, e);
        }
        Database database = Database.open(dir.resolve(DATABASE_FILE));
        try {
            // SQLite creates its -wal and -shm files with the same permissions as the database file.
            FilePermissions.makePrivate(database.file());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot restrict permissions on " + database.file(), e);
        }
        return new LocalData(dir, database, new RecordingStore(dir));
    }
}
