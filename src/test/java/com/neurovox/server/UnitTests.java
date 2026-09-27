package com.neurovox.server;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Small unit tests for the server helpers. */
class UnitTests {

    @Nested
    class MimeTypesTest {
        @Test
        void knownTypes() {
            assertEquals(Optional.of("text/html; charset=utf-8"), MimeTypes.forPath("static/index.html"));
            assertEquals(Optional.of("text/css; charset=utf-8"), MimeTypes.forPath("static/style.css"));
            assertEquals(Optional.of("text/javascript; charset=utf-8"), MimeTypes.forPath("static/js/main.js"));
            assertEquals(Optional.of("image/svg+xml"), MimeTypes.forPath("static/a/icon.SVG"));
        }

        @Test
        void unknownOrMissingExtensionsAreNotServed() {
            assertEquals(Optional.empty(), MimeTypes.forPath("static/com/neurovox/Main.class"));
            assertEquals(Optional.empty(), MimeTypes.forPath("static/js"));
            assertEquals(Optional.empty(), MimeTypes.forPath("static/js/"));
            assertEquals(Optional.empty(), MimeTypes.forPath("static/README"));
            assertEquals(Optional.empty(), MimeTypes.forPath("static/file."));
            assertEquals(Optional.empty(), MimeTypes.forPath("static/archive.jar"));
        }
    }

    @Nested
    class JsonTest {
        @Test
        void errorBodyHasStableShape() {
            String json = Json.toJson(Json.errorBody(404, "not_found", "There is no page at /x."));
            assertEquals("{\"error\":{\"status\":404,\"code\":\"not_found\",\"message\":\"There is no page at /x.\"}}", json);
        }

        @Test
        void doesNotHtmlEscapeButStillEscapesJson() {
            String json = Json.toJson(Map.of("m", "<a> \"q\""));
            assertEquals("{\"m\":\"<a> \\\"q\\\"\"}", json);
        }
    }

    @Nested
    class RequestBodiesTest {
        @Test
        void readsBodiesWithinTheLimit() throws IOException {
            byte[] data = new byte[20_000];
            data[19_999] = 7;
            assertArrayEquals(data, RequestBodies.readLimited(new ByteArrayInputStream(data), 20_000));
        }

        @Test
        void stopsAsSoonAsTheLimitIsExceeded() {
            HttpError e = assertThrows(HttpError.class, () -> RequestBodies.readLimited(new ByteArrayInputStream(new byte[20_001]), 20_000));
            assertEquals(413, e.status());
        }
    }

    @Nested
    class RequestGuardTest {
        @Test
        void onlyLocalHostHeadersOnOurPortAreAccepted() {
            assertTrue(RequestGuardFilter.isLocalHost("localhost:8080", 8080));
            assertTrue(RequestGuardFilter.isLocalHost("LOCALHOST:8080", 8080));
            assertTrue(RequestGuardFilter.isLocalHost("127.0.0.1:8080", 8080));
            assertTrue(RequestGuardFilter.isLocalHost("[::1]:8080", 8080));
            assertTrue(RequestGuardFilter.isLocalHost("localhost", 80));
            assertFalse(RequestGuardFilter.isLocalHost("localhost", 8080));
            assertFalse(RequestGuardFilter.isLocalHost("localhost:9999", 8080));
            assertFalse(RequestGuardFilter.isLocalHost("evil.example:8080", 8080));
            assertFalse(RequestGuardFilter.isLocalHost("localhost.evil.example:8080", 8080));
            assertFalse(RequestGuardFilter.isLocalHost("192.168.1.5:8080", 8080));
            assertFalse(RequestGuardFilter.isLocalHost("", 8080));
            assertFalse(RequestGuardFilter.isLocalHost(null, 8080));
        }
    }
}
