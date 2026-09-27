package com.neurovox.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neurovox.audio.PcmDecoder;
import com.neurovox.model.TaskType;
import com.neurovox.model.VoiceSample;
import com.neurovox.session.SessionService;
import com.neurovox.storage.LocalData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 3 acceptance for the API: four uploads per session, stored audio is real, bad uploads fail clearly. */
class SessionApiTest {

    @TempDir
    Path dataDir;

    private AppServer server;
    private SessionService sessions;
    private LocalData data;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws IOException {
        data = LocalData.open(dataDir);
        sessions = new SessionService(data.database(), data.recordings(), Clock.systemUTC());
        server = AppServer.start(new ServerConfig(0, ServerConfig.DEFAULT_MAX_BODY_BYTES, 2048, 4), sessions);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    // ---------- helpers ----------

    private record Reply(int status, JsonObject json, HttpResponse<String> raw) {
        JsonObject error() {
            return json.getAsJsonObject("error");
        }
    }

    private Reply send(HttpRequest.Builder builder) throws Exception {
        HttpResponse<String> r = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonObject json = r.body().isEmpty() ? new JsonObject() : JsonParser.parseString(r.body()).getAsJsonObject();
        return new Reply(r.statusCode(), json, r);
    }

    private URI uri(String path) {
        return server.uri().resolve(path);
    }

    private long createSession() throws Exception {
        Reply r = send(HttpRequest.newBuilder(uri("/api/sessions")).POST(HttpRequest.BodyPublishers.noBody()));
        assertEquals(201, r.status(), r.raw().body());
        return r.json().getAsJsonObject("session").get("id").getAsLong();
    }

    private Reply upload(long session, String task, String query, byte[] body, String contentType) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri("/api/sessions/" + session + "/recordings/" + task + query))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        return send(b);
    }

    private Reply upload(long session, TaskType task, int rate, byte[] body) throws Exception {
        return upload(session, task.code(), "?sampleRate=" + rate + "&format=pcm_f32le&channels=1", body, "application/octet-stream");
    }

    /** A vowel-like test signal: 140 Hz fundamental with harmonics, little-endian float32. */
    private static byte[] voice(double seconds, int rate) {
        float[] s = new float[(int) Math.round(seconds * rate)];
        for (int i = 0; i < s.length; i++) {
            double t = (double) i / rate;
            s[i] = (float) (0.25 * Math.sin(2 * Math.PI * 140 * t) + 0.1 * Math.sin(2 * Math.PI * 280 * t) + 0.05 * Math.sin(2 * Math.PI * 420 * t));
        }
        return PcmDecoder.encodeF32le(s);
    }

    private long storedFiles() throws IOException {
        Path root = data.recordings().root();
        if (!Files.exists(root)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    // ---------- the happy path ----------

    @Test
    void recordsAllFourTasksInOneSession() throws Exception {
        Reply tasks = send(HttpRequest.newBuilder(uri("/api/tasks")).GET());
        assertEquals(200, tasks.status());
        JsonArray list = tasks.json().getAsJsonArray("tasks");
        assertEquals(4, list.size());
        assertEquals("sustained_vowel", list.get(0).getAsJsonObject().get("code").getAsString());
        assertTrue(list.get(0).getAsJsonObject().get("feedsResearchClassifier").getAsBoolean());

        long session = createSession();
        for (TaskType task : TaskType.values()) {
            int rate = task == TaskType.READING_PASSAGE ? 44_100 : 48_000;
            Reply r = upload(session, task, rate, voice(task.targetSeconds(), rate));
            assertEquals(201, r.status(), r.raw().body());
            JsonObject rec = r.json().getAsJsonObject("recording");
            assertEquals(task.code(), rec.get("task").getAsString());
            assertEquals(rate, rec.get("sampleRateHz").getAsInt());
            assertEquals(task.targetSeconds(), rec.get("durationSeconds").getAsDouble(), 1e-3);
            assertFalse(rec.has("audioPath"), "internal file paths are not exposed");
            assertTrue(r.json().getAsJsonObject("audio").get("peak").getAsDouble() > 0.3);
        }

        Reply details = send(HttpRequest.newBuilder(uri("/api/sessions/" + session)).GET());
        assertEquals(200, details.status());
        JsonObject s = details.json().getAsJsonObject("session");
        assertEquals("complete", s.get("status").getAsString());
        assertEquals(0, s.getAsJsonArray("missingTasks").size());
        assertEquals(4, s.getAsJsonArray("recordings").size());

        // The saved vowel decodes to finite, non-silent audio identical to what was sent.
        VoiceSample vowel = sessions.details(session).orElseThrow().sample(TaskType.SUSTAINED_VOWEL).orElseThrow();
        float[] decoded = PcmDecoder.decodeF32le(sessions.readRecording(vowel));
        assertEquals(5 * 48_000, decoded.length);
        double sumSquares = 0;
        for (float x : decoded) {
            assertTrue(Float.isFinite(x));
            sumSquares += x * x;
        }
        double rms = Math.sqrt(sumSquares / decoded.length);
        assertTrue(rms > 0.1, "stored vowel should not be silent, rms=" + rms);
        assertEquals(4, storedFiles());
    }

    @Test
    void reuploadingATaskReplacesTheTake() throws Exception {
        long session = createSession();
        assertEquals(201, upload(session, TaskType.PA_TA_KA, 48_000, voice(6, 48_000)).status());
        Reply again = upload(session, TaskType.PA_TA_KA, 48_000, voice(5, 48_000));
        assertEquals(200, again.status());
        assertTrue(again.json().get("replaced").getAsBoolean());
        assertEquals(5.0, again.json().getAsJsonObject("recording").get("durationSeconds").getAsDouble(), 1e-3);
        assertEquals(1, storedFiles());
    }

    @Test
    void sessionsCanBeCreatedForAChosenProfile() throws Exception {
        long profile = sessions.createProfile("Demo").id();
        Reply r = send(HttpRequest.newBuilder(uri("/api/sessions")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"profileId\": " + profile + "}")));
        assertEquals(201, r.status(), r.raw().body());
        assertEquals(profile, r.json().getAsJsonObject("session").get("profileId").getAsLong());
        assertTrue(r.raw().headers().firstValue("Location").orElse("").startsWith("/api/sessions/"));
    }

    // ---------- malformed uploads fail clearly ----------

    @Test
    void malformedUploadsAreRejectedWithSpecificErrors() throws Exception {
        long session = createSession();
        byte[] good = voice(2, 48_000);
        String q = "?sampleRate=48000&format=pcm_f32le&channels=1";
        record Case(String name, String task, String query, byte[] body, String type, int status, String code) {
        }
        byte[] nan = PcmDecoder.encodeF32le(new float[] {0.1f, Float.NaN, 0.2f});
        List<Case> cases = List.of(
                new Case("wrong content type", "sustained_vowel", q, good, "audio/wav", 415, "unsupported_media_type"),
                new Case("no content type", "sustained_vowel", q, good, null, 415, "unsupported_media_type"),
                new Case("missing sample rate", "sustained_vowel", "?format=pcm_f32le", good, "application/octet-stream", 400, "missing_parameter"),
                new Case("text sample rate", "sustained_vowel", "?sampleRate=fast", good, "application/octet-stream", 400, "invalid_sample_rate"),
                new Case("absurd sample rate", "sustained_vowel", "?sampleRate=1000", good, "application/octet-stream", 400, "invalid_sample_rate"),
                new Case("stereo", "sustained_vowel", "?sampleRate=48000&channels=2", good, "application/octet-stream", 400, "unsupported_channels"),
                new Case("16-bit format", "sustained_vowel", "?sampleRate=48000&format=pcm_s16le", good, "application/octet-stream", 400, "unsupported_format"),
                new Case("unknown parameter", "sustained_vowel", "?sampleRate=48000&gain=2", good, "application/octet-stream", 400, "unknown_parameter"),
                new Case("duplicate parameter", "sustained_vowel", "?sampleRate=48000&sampleRate=44100", good, "application/octet-stream", 400, "duplicate_parameter"),
                new Case("empty body", "sustained_vowel", q, new byte[0], "application/octet-stream", 400, "invalid_audio"),
                new Case("partial sample", "sustained_vowel", q, new byte[4 * 30_000 + 3], "application/octet-stream", 400, "invalid_audio"),
                new Case("NaN sample", "sustained_vowel", q, nan, "application/octet-stream", 400, "invalid_audio"),
                new Case("too short", "sustained_vowel", q, voice(0.2, 48_000), "application/octet-stream", 400, "recording_too_short"),
                new Case("too long for the vowel", "sustained_vowel", q, voice(15.5, 48_000), "application/octet-stream", 413, "recording_too_long"),
                new Case("unknown task", "humming", q, good, "application/octet-stream", 404, "unknown_task"));
        for (Case c : cases) {
            Reply r = upload(session, c.task(), c.query(), c.body(), c.type());
            assertEquals(c.status(), r.status(), c.name() + ": " + r.raw().body());
            assertEquals(c.code(), r.error().get("code").getAsString(), c.name());
            assertFalse(r.error().get("message").getAsString().isBlank(), c.name());
        }
        assertEquals(0, storedFiles(), "rejected uploads leave no files");
        assertEquals(0, sessions.details(session).orElseThrow().samples().size());
    }

    @Test
    void uploadsToMissingSessionsOrWithWrongMethodsFail() throws Exception {
        Reply missing = upload(9_999, TaskType.SUSTAINED_VOWEL, 48_000, voice(1, 48_000));
        assertEquals(404, missing.status());
        assertEquals("not_found", missing.error().get("code").getAsString());

        long session = createSession();
        Reply get = send(HttpRequest.newBuilder(uri("/api/sessions/" + session + "/recordings/sustained_vowel")).GET());
        assertEquals(405, get.status());
        assertEquals("PUT", get.raw().headers().firstValue("Allow").orElse(""));
        assertEquals(404, send(HttpRequest.newBuilder(uri("/api/sessions/abc")).GET()).status());
        assertEquals(404, send(HttpRequest.newBuilder(uri("/api/sessions/0")).GET()).status());
        assertEquals(404, send(HttpRequest.newBuilder(uri("/api/sessionsX")).GET()).status());
        assertEquals(405, send(HttpRequest.newBuilder(uri("/api/sessions")).GET()).status());
        assertEquals(0, storedFiles());
    }

    @Test
    void chunkedUploadsWithoutLengthAreStillLimited() throws Exception {
        long session = createSession();
        byte[] tooLong = voice(16, 48_000); // vowel limit is 15 s
        HttpRequest request = HttpRequest.newBuilder(uri("/api/sessions/" + session + "/recordings/sustained_vowel?sampleRate=48000"))
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(tooLong)))
                .build();
        HttpResponse<String> r = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(413, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"recording_too_long\""), r.body());
        assertEquals(0, storedFiles());
    }

    @Test
    void badSessionBodiesAreRejected() throws Exception {
        for (String body : List.of("not json", "[]", "{\"profileId\": \"one\"}", "{\"profileId\": 1.5}", "{\"profileId\": -3}", "{\"name\": \"x\"}")) {
            Reply r = send(HttpRequest.newBuilder(uri("/api/sessions")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
            assertEquals(400, r.status(), body + " -> " + r.raw().body());
        }
        Reply missingProfile = send(HttpRequest.newBuilder(uri("/api/sessions")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"profileId\": 424242}")));
        assertEquals(404, missingProfile.status());
        Reply wrongType = send(HttpRequest.newBuilder(uri("/api/sessions")).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("{\"profileId\": 1}")));
        assertEquals(415, wrongType.status());
    }
}
