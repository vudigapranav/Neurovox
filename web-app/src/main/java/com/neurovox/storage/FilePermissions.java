package com.neurovox.storage;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Keeps voice data readable by the current user only (rwx------ folders, rw------- files) on macOS and Linux.
 * On file systems without POSIX permissions (Windows) the operating system defaults apply.
 */
final class FilePermissions {

    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private FilePermissions() {
    }

    static void createPrivateDirectories(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            return;
        }
        Path parent = dir.getParent();
        if (parent != null) {
            createPrivateDirectories(parent);
        }
        Files.createDirectories(dir);
        makePrivateDirectory(dir);
    }

    static void makePrivateDirectory(Path dir) throws IOException {
        if (POSIX) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
    }

    static void makePrivate(Path file) throws IOException {
        if (POSIX) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }
}
