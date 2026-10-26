package com.neurovox.server;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Content types for the files the frontend ships. Anything not listed here is never served,
 * which also keeps class files and other resources on the classpath out of reach.
 */
public final class MimeTypes {

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("wav", "audio/wav"));

    private MimeTypes() {
    }

    /** Content type for a path such as {@code static/js/main.js}, or empty if the type is not served. */
    public static Optional<String> forPath(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash + 1 || dot == path.length() - 1) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_EXTENSION.get(path.substring(dot + 1).toLowerCase(Locale.ROOT)));
    }
}
