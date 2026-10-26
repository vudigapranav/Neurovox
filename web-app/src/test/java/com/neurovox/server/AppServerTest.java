package com.neurovox.server;

import com.google.gson.JsonObject;
import com.neurovox.session.SessionService;
import com.neurovox.storage.LocalData;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starts the real server on a random local port and talks to it over HTTP. */
class AppServerTest {

    private static final long TEST_BODY_LIMIT = 1024;
    private static final int TEST_URI_LIMIT = 256;
    private static final Path STATIC_DIR = Path.of("src/main/resources/static");

    private static AppServer server;
    private static HttpClient client;
    private static URI base;

    @BeforeAll
    static void start() throws IOException {
        Path dataDir = Files.createTempDirectory("neurovox-apptest");
        LocalData data = LocalData.open(dataDir);
        SessionService sessions = new SessionService(data.database(), data.recordings(), Clock.systemUTC());
        server = AppServer.start(new ServerConfig(0, TEST_BODY_LIMIT, TEST_URI_LIMIT, 4), sessions);
        base = server.uri();
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static HttpResponse<byte[]> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static JsonObject errorOf(HttpResponse<byte[]> response) {
        assertEquals(Json.CONTENT_TYPE, response.headers().firstValue("Content-Type").orElse(""));
        return JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("error");
    }

    // ---------- binding ----------

    @Test
    void listensOnLoopbackOnly() {
        InetAddress address = server.address().getAddress();
        assertTrue(address.isLoopbackAddress(), "bound to " + address);
        assertEquals("127.0.0.1", address.getHostAddress());
    }

    // ---------- static files ----------

    @Test
    void servesTheLandingPage() throws Exception {
        HttpResponse<byte[]> response = get("/");
        assertEquals(200, response.statusCode());
        assertEquals("text/html; charset=utf-8", response.headers().firstValue("Content-Type").orElse(""));
        String html = new String(response.body(), StandardCharsets.UTF_8);
        assertTrue(html.contains("<title>Neurovox</title>"));
        assertTrue(html.contains("<script type=\"module\" src=\"js/main.js\">"));
        assertFalse(html.toLowerCase().contains("<video"));
    }

    @Test
    void servesEveryFrontendFileWithTheRightTypeAndExactBytes() throws Exception {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(STATIC_DIR)) {
            files = walk.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().startsWith(".")).toList();
        }
        assertTrue(files.size() >= 9, "expected the frontend files, found " + files);
        for (Path file : files) {
            String urlPath = "/" + STATIC_DIR.relativize(file).toString().replace('\\', '/');
            HttpResponse<byte[]> response = get(urlPath);
            assertEquals(200, response.statusCode(), urlPath);
            String expectedType = MimeTypes.forPath(urlPath).orElseThrow();
            assertEquals(expectedType, response.headers().firstValue("Content-Type").orElse(""), urlPath);
            assertArrayEquals(Files.readAllBytes(file), response.body(), urlPath);
        }
    }

    @Test
    void addsSecurityHeaders() throws Exception {
        HttpResponse<byte[]> response = get("/js/main.js");
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals(SecurityHeaders.CONTENT_SECURITY_POLICY, response.headers().firstValue("Content-Security-Policy").orElse(""));
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElse(""));
        assertTrue(response.headers().firstValue("Permissions-Policy").orElse("").contains("microphone=(self)"));
        // Errors carry them too.
        assertEquals("nosniff", get("/missing.css").headers().firstValue("X-Content-Type-Options").orElse(""));
    }

    @Test
    void headReturnsHeadersWithoutBody() throws Exception {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(base.resolve("/style.css"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertEquals("text/css; charset=utf-8", response.headers().firstValue("Content-Type").orElse(""));
        assertEquals(0, response.body().length);
    }

    @Test
    void missingFilesAreJson404s() throws Exception {
        for (String path : List.of("/missing.html", "/js/nope.js", "/session", "/js", "/js/", "/com/neurovox/Main.class", "/record")) {
            HttpResponse<byte[]> response = get(path);
            assertEquals(404, response.statusCode(), path);
            JsonObject error = errorOf(response);
            assertEquals(404, error.get("status").getAsInt());
            assertEquals("not_found", error.get("code").getAsString());
            assertFalse(error.get("message").getAsString().isBlank());
        }
    }

    @Test
    void writeMethodsAreRejectedWithAllowHeader() throws Exception {
        for (String method : List.of("POST", "PUT", "DELETE", "PATCH")) {
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(base.resolve("/index.html"))
                    .method(method, HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(405, response.statusCode(), method);
            assertEquals("GET, HEAD", response.headers().firstValue("Allow").orElse(""));
            assertEquals("method_not_allowed", errorOf(response).get("code").getAsString());
        }
    }

    // ---------- API ----------

    @Test
    void healthEndpointReportsOk() throws Exception {
        HttpResponse<byte[]> response = get("/api/health");
        assertEquals(200, response.statusCode());
        assertEquals(Json.CONTENT_TYPE, response.headers().firstValue("Content-Type").orElse(""));
        JsonObject body = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("ok", body.get("status").getAsString());
        assertEquals("neurovox", body.get("name").getAsString());
    }

    @Test
    void unknownApiPathsAreJson404s() throws Exception {
        HttpResponse<byte[]> response = get("/api/results");
        assertEquals(404, response.statusCode());
        assertEquals("no_such_endpoint", errorOf(response).get("code").getAsString());
    }

    // ---------- path traversal (raw sockets: HttpClient would normalise these paths) ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "/../pom.xml", "/../../../../etc/passwd", "/js/../../../pom.xml", "/%2e%2e/%2e%2e/pom.xml",
            "/..%2f..%2fpom.xml", "/js/..%5c..%5cpom.xml", "/static/../com/neurovox/Main.class",
            "/js/main.js/../../com/neurovox/Main.class", "/.DS_Store", "//etc/passwd", "/index.html%00",
    })
    void traversalAttemptsNeverEscapeTheStaticFolder(String path) throws IOException {
        RawResponse response = raw("GET " + path + " HTTP/1.1\r\nHost: " + host() + "\r\nConnection: close\r\n\r\n");
        assertTrue(response.status == 400 || response.status == 404, path + " -> " + response.status);
        assertTrue(response.body.startsWith("{\"error\":"), path + " -> " + response.body);
        assertFalse(response.body.contains("<project"), "leaked pom.xml");
        assertFalse(response.body.contains("root:"), "leaked /etc/passwd");
        assertFalse(response.bodyBytes.length > 4 && (response.bodyBytes[0] & 0xFF) == 0xCA && (response.bodyBytes[1] & 0xFF) == 0xFE, "leaked a class file");
    }

    // ---------- request guard ----------

    @Test
    void oversizedDeclaredBodiesAreRejectedBeforeReading() throws IOException {
        RawResponse response = raw("POST /api/health HTTP/1.1\r\nHost: " + host() + "\r\nContent-Length: " + (TEST_BODY_LIMIT + 1)
                + "\r\nConnection: close\r\n\r\n");
        assertEquals(413, response.status);
        assertTrue(response.body.contains("\"code\":\"payload_too_large\""), response.body);
    }

    @Test
    void malformedContentLengthIsRejected() throws IOException {
        // The JDK server rejects these itself (plain-text 400) before our filter runs.
        for (String value : List.of("abc", "10, 20")) {
            RawResponse response = raw("POST /api/health HTTP/1.1\r\nHost: " + host() + "\r\nContent-Length: " + value
                    + "\r\nConnection: close\r\n\r\n");
            assertEquals(400, response.status, value);
        }
    }

    @Test
    void overlongUrisAreRejected() throws Exception {
        HttpResponse<byte[]> response = get("/" + "a".repeat(TEST_URI_LIMIT) + ".html");
        assertEquals(414, response.statusCode());
        assertEquals("uri_too_long", errorOf(response).get("code").getAsString());
    }

    @Test
    void foreignHostHeadersAreRejected() throws IOException {
        for (String host : List.of("evil.example", "evil.example:" + server.port(), "192.168.1.20:" + server.port())) {
            RawResponse response = raw("GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n");
            assertEquals(403, response.status, host);
            assertTrue(response.body.contains("\"code\":\"forbidden_host\""), response.body);
        }
        assertEquals(200, raw("GET / HTTP/1.1\r\nHost: 127.0.0.1:" + server.port() + "\r\nConnection: close\r\n\r\n").status);
    }

    @Test
    void handlesConcurrentRequests() throws Exception {
        List<CompletableFuture<HttpResponse<byte[]>>> futures = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            futures.add(client.sendAsync(HttpRequest.newBuilder(base.resolve(i % 2 == 0 ? "/js/orb.js" : "/")).build(),
                    HttpResponse.BodyHandlers.ofByteArray()));
        }
        for (CompletableFuture<HttpResponse<byte[]>> future : futures) {
            assertEquals(200, future.get().statusCode());
        }
    }

    // ---------- unexpected errors ----------

    @Test
    void unexpectedExceptionsBecomeGeneric500sWithoutDetails() throws Exception {
        HttpServer bare = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        bare.createContext("/boom", new SafeHandler() {
            @Override
            protected void handleRequest(com.sun.net.httpserver.HttpExchange exchange) {
                throw new IllegalStateException("secret internal detail");
            }
        });
        bare.start();
        try {
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + bare.getAddress().getPort() + "/boom")).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(500, response.statusCode());
            String body = new String(response.body(), StandardCharsets.UTF_8);
            assertTrue(body.contains("\"code\":\"internal_error\""), body);
            assertFalse(body.contains("secret internal detail"));
            assertFalse(body.contains("IllegalStateException"));
        } finally {
            bare.stop(0);
        }
    }

    // ---------- raw HTTP helper ----------

    private static String host() {
        return "localhost:" + server.port();
    }

    private record RawResponse(int status, String body, byte[] bodyBytes) {
    }

    /**
     * Sends a raw request and reads one response: headers, then exactly Content-Length body bytes
     * (or until the server closes the connection when no length is given).
     */
    private static RawResponse raw(String request) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                head.write(b);
                byte[] h = head.toByteArray();
                int n = h.length;
                if (n >= 4 && h[n - 4] == '\r' && h[n - 3] == '\n' && h[n - 2] == '\r' && h[n - 1] == '\n') {
                    break;
                }
            }
            String headers = head.toString(StandardCharsets.ISO_8859_1);
            int status = Integer.parseInt(headers.substring(9, 12));
            long length = -1;
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    length = Long.parseLong(line.substring(15).trim());
                }
            }
            byte[] body = length >= 0 ? in.readNBytes((int) length) : in.readAllBytes();
            return new RawResponse(status, new String(body, StandardCharsets.UTF_8), body);
        }
    }
}
