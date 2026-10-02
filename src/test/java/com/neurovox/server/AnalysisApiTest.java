package com.neurovox.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neurovox.TestSignals;
import com.neurovox.analysis.AnalysisService;
import com.neurovox.analysis.RecordingAnalyzer;
import com.neurovox.audio.AudioClip;
import com.neurovox.audio.PcmDecoder;
import com.neurovox.audio.WavCodec;
import com.neurovox.db.ScreeningResultRepository;
import com.neurovox.features.UciFeature;
import com.neurovox.ml.CompatibilityGate;
import com.neurovox.ml.ResearchIndicator;
import com.neurovox.ml.ResearchModel;
import com.neurovox.model.TaskType;
import com.neurovox.session.SessionService;
import com.neurovox.storage.LocalData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprints A, G and H end to end over HTTP: four uploads through the quality gate, analysis, report, history,
 * WAV export, deletion, the cross-origin guard, and the research indicator appearing only when the gate says go.
 */
class AnalysisApiTest {

    @TempDir
    Path dataDir;

    private AppServer server;
    private SessionService sessions;
    private LocalData data;
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private void start(ResearchIndicator indicator) throws IOException {
        data = LocalData.open(dataDir);
        sessions = new SessionService(data.database(), data.recordings(), Clock.systemUTC());
        AnalysisService analysis = new AnalysisService(data.database(), sessions, new RecordingAnalyzer(), indicator, Clock.systemUTC());
        server = AppServer.start(new ServerConfig(0, ServerConfig.DEFAULT_MAX_BODY_BYTES, 2048, 4), sessions, analysis);
    }

    private void startWithPackagedModel() throws IOException {
        start(ResearchIndicator.fromClasspath());
    }

    private record Reply(int status, JsonObject json, HttpResponse<byte[]> raw) {
        JsonObject error() {
            return json.getAsJsonObject("error");
        }
    }

    private Reply send(HttpRequest.Builder b) throws Exception {
        HttpResponse<byte[]> r = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        String type = r.headers().firstValue("Content-Type").orElse("");
        JsonObject json = type.startsWith("application/json") && r.body().length > 0
                ? JsonParser.parseString(new String(r.body())).getAsJsonObject() : new JsonObject();
        return new Reply(r.statusCode(), json, r);
    }

    private URI uri(String path) {
        return server.uri().resolve(path);
    }

    private long createSession() throws Exception {
        Reply r = send(HttpRequest.newBuilder(uri("/api/sessions")).POST(HttpRequest.BodyPublishers.noBody()));
        assertEquals(201, r.status());
        return r.json().getAsJsonObject("session").get("id").getAsLong();
    }

    private Reply upload(long session, TaskType task, AudioClip clip) throws Exception {
        float[] f = new float[clip.length()];
        for (int i = 0; i < f.length; i++) {
            f[i] = (float) clip.samples()[i];
        }
        return send(HttpRequest.newBuilder(uri("/api/sessions/" + session + "/recordings/" + task.code()
                        + "?sampleRate=" + clip.sampleRate() + "&format=pcm_f32le&channels=1"))
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(PcmDecoder.encodeF32le(f))));
    }

    /** A vowel at 48 kHz with natural-sized perturbations. */
    static AudioClip vowel(double seconds) {
        int n = (int) (seconds * 125);
        return TestSignals.vowel(TestSignals.periods(125, n, 0.006, 9), TestSignals.amplitudes(n, 0.04, 9), 48_000, 0.15);
    }

    /** Speech-like: the vowel switched on and off in ~200 ms "syllables" with a pause every 2 s. */
    static AudioClip speechLike(double seconds) {
        AudioClip v = vowel(seconds);
        double[] x = v.samples().clone();
        for (int i = 0; i < x.length; i++) {
            double t = (double) i / v.sampleRate();
            double syllable = 0.5 - 0.5 * Math.cos(2 * Math.PI * 5 * t);
            boolean pause = (t % 2.0) > 1.6;
            x[i] *= pause ? 0.0 : syllable;
        }
        return new AudioClip(x, v.sampleRate());
    }

    private long recordFullSession() throws Exception {
        long session = createSession();
        for (TaskType task : TaskType.values()) {
            AudioClip clip = task == TaskType.SUSTAINED_VOWEL ? vowel(5) : speechLike(task.minSeconds() + 1);
            Reply r = upload(session, task, clip);
            assertEquals(201, r.status(), task + ": " + new String(r.raw().body()));
            assertTrue(r.json().getAsJsonObject("quality").get("status").getAsString().matches("pass|warn"));
        }
        return session;
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

    // ---------- the complete flow ----------

    @Test
    void completeSessionIsAnalysedReportedListedExportedAndDeleted() throws Exception {
        startWithPackagedModel();
        long session = recordFullSession();

        assertEquals(409, send(HttpRequest.newBuilder(uri("/api/sessions/" + session + "/report")).GET()).status());

        Reply analysed = send(HttpRequest.newBuilder(uri("/api/sessions/" + session + "/analysis")).POST(HttpRequest.BodyPublishers.noBody()));
        assertEquals(200, analysed.status(), new String(analysed.raw().body()));
        JsonObject report = analysed.json().getAsJsonObject("report");
        JsonArray tasks = report.getAsJsonArray("tasks");
        assertEquals(4, tasks.size());

        JsonObject vowelTask = tasks.get(0).getAsJsonObject();
        assertEquals("sustained_vowel", vowelTask.get("task").getAsString());
        assertTrue(vowelTask.get("feedsResearchClassifier").getAsBoolean());
        JsonArray vowelFeatures = vowelTask.getAsJsonArray("features");
        for (int i = 0; i < 16; i++) {
            JsonObject f = vowelFeatures.get(i).getAsJsonObject();
            assertEquals(UciFeature.ORDER.get(i).key(), f.get("name").getAsString());
            assertTrue(f.get("available").getAsBoolean(), f.toString());
            assertEquals("uci", f.get("group").getAsString());
        }
        double f0 = vowelFeatures.get(0).getAsJsonObject().get("value").getAsDouble();
        assertEquals(125, f0, 1.0);

        for (int t = 1; t < 4; t++) {
            JsonObject speech = tasks.get(t).getAsJsonObject();
            assertFalse(speech.get("feedsResearchClassifier").getAsBoolean());
            for (JsonElement e : speech.getAsJsonArray("features")) {
                String name = e.getAsJsonObject().get("name").getAsString();
                assertFalse(UciFeature.keys().contains(name), "speech tasks never produce classifier inputs: " + name);
            }
        }
        JsonObject indicator = report.getAsJsonObject("researchIndicator");
        assertEquals("sustained_vowel", indicator.get("input").getAsString());
        assertFalse(report.getAsJsonArray("notComputed").isEmpty());

        Reply again = send(HttpRequest.newBuilder(uri("/api/sessions/" + session + "/report")).GET());
        assertEquals(200, again.status());
        assertEquals(report.get("analysedAt"), again.json().getAsJsonObject("report").get("analysedAt"));

        Reply history = send(HttpRequest.newBuilder(uri("/api/sessions")).GET());
        assertEquals(200, history.status());
        JsonObject entry = history.json().getAsJsonArray("sessions").get(0).getAsJsonObject();
        assertEquals(session, entry.get("id").getAsLong());
        assertTrue(entry.get("analysed").getAsBoolean());
        assertEquals(f0, entry.getAsJsonObject("vowel").get("MDVP:Fo(Hz)").getAsDouble(), 1e-9);
        assertEquals(16, history.json().getAsJsonArray("features").size());

        Reply wav = send(HttpRequest.newBuilder(uri("/api/sessions/" + session + "/recordings/sustained_vowel/audio.wav")).GET());
        assertEquals(200, wav.status());
        assertEquals("audio/wav", wav.raw().headers().firstValue("Content-Type").orElse(""));
        AudioClip exported = WavCodec.decode(wav.raw().body());
        assertEquals(48_000, exported.sampleRate());
        assertEquals(vowel(5).length(), exported.length());

        assertEquals(4, storedFiles());
        Reply deleted = send(HttpRequest.newBuilder(uri("/api/sessions/" + session)).DELETE());
        assertEquals(200, deleted.status());
        assertEquals(0, storedFiles());
        assertEquals(404, send(HttpRequest.newBuilder(uri("/api/sessions/" + session)).GET()).status());
        assertEquals(404, send(HttpRequest.newBuilder(uri("/api/sessions/" + session)).DELETE()).status());
        assertEquals(0, send(HttpRequest.newBuilder(uri("/api/sessions")).GET()).json().getAsJsonArray("sessions").size());
    }

    @Test
    void analysisNeedsAllFourTasks() throws Exception {
        startWithPackagedModel();
        long session = createSession();
        assertEquals(201, upload(session, TaskType.SUSTAINED_VOWEL, vowel(5)).status());
        Reply r = send(HttpRequest.newBuilder(uri("/api/sessions/" + session + "/analysis")).POST(HttpRequest.BodyPublishers.noBody()));
        assertEquals(409, r.status());
        assertEquals("session_incomplete", r.error().get("code").getAsString());
        assertTrue(r.error().get("message").getAsString().contains("Syllable repetition"));
    }

    // ---------- quality gate on upload ----------

    @Test
    void unusableTakesAreRejectedWithRecoveryAdviceAndNotStored() throws Exception {
        startWithPackagedModel();
        long session = createSession();
        record Case(String name, AudioClip clip, String check) {
        }
        double[] loud = vowel(5).samples().clone();
        for (int i = 0; i < loud.length; i++) {
            loud[i] = Math.max(-1, Math.min(1, loud[i] * 30));
        }
        for (Case c : List.of(
                new Case("silence", TestSignals.silence(5, 48_000), "too_quiet"),
                new Case("clipped", new AudioClip(loud, 48_000), "clipped"),
                new Case("too short", vowel(2), "too_short"),
                new Case("noise", TestSignals.whiteNoise(5, 48_000, 0.05, 1), "no_voice"))) {
            Reply r = upload(session, TaskType.SUSTAINED_VOWEL, c.clip());
            assertEquals(422, r.status(), c.name());
            assertEquals("audio_quality", r.error().get("code").getAsString());
            JsonArray checks = r.json().getAsJsonObject("quality").getAsJsonArray("checks");
            boolean found = false;
            for (JsonElement e : checks) {
                JsonObject check = e.getAsJsonObject();
                if (check.get("code").getAsString().equals(c.check())) {
                    found = true;
                    assertEquals("fail", check.get("status").getAsString());
                    assertFalse(check.get("recovery").getAsString().isBlank());
                }
            }
            assertTrue(found, c.name() + " → " + checks);
        }
        assertEquals(0, storedFiles());
    }

    // ---------- delete everything ----------

    @Test
    void deleteAllDataNeedsConfirmationThenRemovesEverything() throws Exception {
        startWithPackagedModel();
        recordFullSession();
        createSession();
        Reply refused = send(HttpRequest.newBuilder(uri("/api/data")).header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString("{}")));
        assertEquals(400, refused.status());
        assertEquals("confirmation_required", refused.error().get("code").getAsString());
        assertEquals(4, storedFiles());

        Reply done = send(HttpRequest.newBuilder(uri("/api/data")).header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString("{\"confirm\": \"delete all\"}")));
        assertEquals(200, done.status());
        assertEquals(2, done.json().get("deletedSessions").getAsInt());
        assertEquals(0, storedFiles());
        assertTrue(sessions.profiles().isEmpty());
        assertEquals(0, send(HttpRequest.newBuilder(uri("/api/sessions")).GET()).json().getAsJsonArray("sessions").size());
    }

    // ---------- cross-site request forgery ----------

    @Test
    void changesFromOtherSitesAreRefused() throws Exception {
        startWithPackagedModel();
        Reply evil = send(HttpRequest.newBuilder(uri("/api/sessions")).header("Origin", "http://evil.example")
                .POST(HttpRequest.BodyPublishers.noBody()));
        assertEquals(403, evil.status());
        assertEquals("cross_origin", evil.error().get("code").getAsString());
        Reply crossSite = send(HttpRequest.newBuilder(uri("/api/sessions")).header("Sec-Fetch-Site", "cross-site")
                .POST(HttpRequest.BodyPublishers.noBody()));
        assertEquals(403, crossSite.status());
        Reply otherPort = send(HttpRequest.newBuilder(uri("/api/sessions")).header("Origin", "http://localhost:1")
                .POST(HttpRequest.BodyPublishers.noBody()));
        assertEquals(403, otherPort.status());
        Reply own = send(HttpRequest.newBuilder(uri("/api/sessions")).header("Origin", "http://localhost:" + server.port())
                .header("Sec-Fetch-Site", "same-origin").POST(HttpRequest.BodyPublishers.noBody()));
        assertEquals(201, own.status());
        // Reading is still allowed from anywhere on this machine (nothing changes).
        assertEquals(200, send(HttpRequest.newBuilder(uri("/api/health")).header("Origin", "http://evil.example").GET()).status());
    }

    // ---------- research indicator and the gate ----------

    private static ResearchModel tinyModel() {
        double[] mean = new double[16];
        double[] scale = new double[16];
        double[] coef = new double[16];
        java.util.Arrays.fill(scale, 1);
        coef[0] = 0.01;   // only Fo matters: s = 0.01·(Fo − 0) − 1
        return new ResearchModel("test-model", UciFeature.keys(), mean, scale, coef, -1.0, 0.5);
    }

    @Test
    void indicatorIsShownAndStoredOnlyWhenTheGateSaysGo() throws Exception {
        CompatibilityGate go = new CompatibilityGate(CompatibilityGate.Decision.GO, "2026-10-02", "test go", List.of("test"), "");
        start(new ResearchIndicator(go, Optional.of(tinyModel())));
        long session = recordFullSession();
        JsonObject indicator = send(HttpRequest.newBuilder(uri("/api/sessions/" + session + "/analysis"))
                .POST(HttpRequest.BodyPublishers.noBody())).json().getAsJsonObject("report").getAsJsonObject("researchIndicator");
        assertTrue(indicator.get("shown").getAsBoolean());
        double fo = 125;
        double expected = 1 / (1 + Math.exp(-(0.01 * fo - 1)));
        assertEquals(expected, indicator.get("modelOutput").getAsDouble(), 0.003);
        assertTrue(indicator.get("aboveThreshold").getAsBoolean());
        assertTrue(indicator.get("label").getAsString().contains("not a diagnosis"));
        int stored = data.database().read(c -> new ScreeningResultRepository().listBySession(c, session)).size();
        assertEquals(1, stored);
    }

    @Test
    void indicatorIsWithheldWhenTheGateSaysNoGo() throws Exception {
        start(new ResearchIndicator(CompatibilityGate.missing(), Optional.of(tinyModel())));
        long session = recordFullSession();
        JsonObject indicator = send(HttpRequest.newBuilder(uri("/api/sessions/" + session + "/analysis"))
                .POST(HttpRequest.BodyPublishers.noBody())).json().getAsJsonObject("report").getAsJsonObject("researchIndicator");
        assertFalse(indicator.get("shown").getAsBoolean());
        assertTrue(indicator.get("modelOutput").isJsonNull());
        assertTrue(indicator.get("reason").getAsString().contains("compatibility"));
        assertEquals(0, data.database().read(c -> new ScreeningResultRepository().listBySession(c, session)).size());

        Reply model = send(HttpRequest.newBuilder(uri("/api/model")).GET());
        assertEquals("no_go", model.json().getAsJsonObject("gate").get("decision").getAsString());
    }
}
