package com.neurovox.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StaticPathTest {

    @Test
    void rootAndFoldersMapToIndex() {
        assertEquals("static/index.html", StaticPath.toResource("/"));
        assertEquals("static/docs/index.html", StaticPath.toResource("/docs/"));
    }

    @Test
    void plainFilesMapUnderStatic() {
        assertEquals("static/style.css", StaticPath.toResource("/style.css"));
        assertEquals("static/js/main.js", StaticPath.toResource("/js/main.js"));
        assertEquals("static/js/voice-input.js", StaticPath.toResource("/js/voice-input.js"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/..", "/../pom.xml", "/js/../../pom.xml", "/js/./main.js", "/./index.html",
            "/%2e%2e/pom.xml", "/..%2fpom.xml", "/js%2f..%2f..%2fpom.xml", "/%2E%2E/x",
            "/js\\..\\pom.xml", "/js//main.js", "//etc/passwd", "/.DS_Store", "/.git/config",
            "/index.html%00.js", "/a b.html", "/ünicode.html", "relative.html", "",
    })
    void rejectsTraversalAndUnsafePaths(String raw) {
        HttpError error = assertThrows(HttpError.class, () -> StaticPath.toResource(raw));
        assertEquals(400, error.status());
        assertEquals("invalid_path", error.code());
    }

    @Test
    void rejectsNull() {
        assertThrows(HttpError.class, () -> StaticPath.toResource(null));
    }
}
