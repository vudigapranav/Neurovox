package com.neurovox.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {

    @TempDir
    Path dir;

    private static int userVersion(Database db) {
        return db.read(c -> {
            try (Statement s = c.createStatement()) {
                return Migrations.userVersion(s);
            }
        });
    }

    private static List<String> objects(Database db) {
        return db.read(c -> {
            List<String> names = new ArrayList<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT type || ':' || name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY 1")) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
            return names;
        });
    }

    @Test
    void createsTheSchema() {
        Database db = Database.open(dir.resolve("nv.db"));
        assertEquals(Migrations.LATEST_VERSION, userVersion(db));
        assertEquals(List.of(
                "index:idx_sessions_by_profile",
                "table:profiles", "table:recording_sessions", "table:screening_results", "table:speech_features", "table:voice_samples",
                "trigger:screening_sample_immutable", "trigger:screening_uses_sustained_vowel"), objects(db));
    }

    @Test
    void initialisingRepeatedlyIsHarmlessAndKeepsData() throws Exception {
        Path file = dir.resolve("nv.db");
        Database first = Database.open(file);
        first.transaction(c -> new ProfileRepository().insert(c, "Keep me", Instant.parse("2026-01-01T00:00:00Z")));
        List<String> schema = objects(first);

        first.initialize();
        first.initialize();
        Database reopened = Database.open(file);
        reopened.initialize();

        assertEquals(Migrations.LATEST_VERSION, userVersion(reopened));
        assertEquals(schema, objects(reopened));
        assertEquals("Keep me", reopened.read(c -> new ProfileRepository().findAll(c)).get(0).displayName());
        try (Connection c = reopened.connect()) {
            assertEquals(0, Migrations.apply(c), "an up-to-date database needs no migrations");
        }
    }

    @Test
    void concurrentStartupsMigrateExactlyOnce() throws Exception {
        Path file = dir.resolve("race.db");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Database>> tasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                tasks.add(() -> Database.open(file));
            }
            for (Future<Database> f : pool.invokeAll(tasks)) {
                assertEquals(Migrations.LATEST_VERSION, userVersion(f.get()));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void everyConnectionEnforcesForeignKeysAndUsesWal() throws SQLException {
        Database db = Database.open(dir.resolve("nv.db"));
        for (int i = 0; i < 3; i++) {
            try (Connection c = db.connect(); Statement s = c.createStatement()) {
                try (ResultSet rs = s.executeQuery("PRAGMA foreign_keys")) {
                    rs.next();
                    assertEquals(1, rs.getInt(1));
                }
                try (ResultSet rs = s.executeQuery("PRAGMA journal_mode")) {
                    rs.next();
                    assertEquals("wal", rs.getString(1));
                }
            }
        }
    }

    @Test
    void refusesADatabaseFromANewerVersion() throws SQLException {
        Path file = dir.resolve("future.db");
        Database db = Database.open(file);
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            s.execute("PRAGMA user_version = " + (Migrations.LATEST_VERSION + 1));
        }
        DataAccessException e = assertThrows(DataAccessException.class, () -> Database.open(file));
        assertTrue(e.getMessage().contains("newer version"), e.getMessage());
    }

    @Test
    void transactionsRollBackCompletelyOnFailure() {
        Database db = Database.open(dir.resolve("nv.db"));
        ProfileRepository profiles = new ProfileRepository();
        assertThrows(IllegalStateException.class, () -> db.transaction(c -> {
            profiles.insert(c, "Rolled back", Instant.parse("2026-01-01T00:00:00Z"));
            throw new IllegalStateException("fail after insert");
        }));
        assertEquals(0, db.read(profiles::findAll).size());
    }
}
