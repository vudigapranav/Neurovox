package com.neurovox.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.neurovox.audio.InvalidAudioException;
import com.neurovox.audio.PcmDecoder;
import com.neurovox.audio.PcmSummary;
import com.neurovox.model.RecordingSession;
import com.neurovox.model.TaskType;
import com.neurovox.model.VoiceSample;
import com.neurovox.session.NotFoundException;
import com.neurovox.session.SessionDetails;
import com.neurovox.session.SessionService;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Recording-session endpoints.
 * <pre>
 *   POST /api/sessions                              start a session       body: {} or {"profileId": 1}
 *   GET  /api/sessions/{id}                         session + recordings
 *   PUT  /api/sessions/{id}/recordings/{task}       upload one task       body: raw PCM
 *        ?sampleRate=48000&amp;format=pcm_f32le&amp;channels=1
 * </pre>
 * Uploads must be {@code application/octet-stream}: mono 32-bit float samples, little-endian, at the
 * browser's real sample rate. Re-uploading a task replaces the earlier take.
 */
final class SessionApi extends SafeHandler {

    static final String PREFIX = "/api/sessions";
    private static final int MAX_JSON_BYTES = 4096;
    private static final Set<String> UPLOAD_PARAMS = Set.of("sampleRate", "format", "channels");

    private final SessionService sessions;
    private final long globalBodyLimit;

    SessionApi(SessionService sessions, long globalBodyLimit) {
        this.sessions = sessions;
        this.globalBodyLimit = globalBodyLimit;
    }

    @Override
    protected void handleRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        // The JDK matches contexts by string prefix, so "/api/sessionsX" also arrives here.
        String rest = path.substring(PREFIX.length());
        if (!rest.isEmpty() && !rest.startsWith("/")) {
            throw new HttpError(404, "no_such_endpoint", "There is no API endpoint at " + path + ".");
        }
        if (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        String[] parts = rest.isEmpty() ? new String[0] : rest.substring(1).split("/", -1);
        try {
            if (parts.length == 0) {
                requireMethod(exchange, "POST");
                createSession(exchange);
            } else if (parts.length == 1) {
                requireMethod(exchange, "GET", "HEAD");
                getSession(exchange, parseId(parts[0]));
            } else if (parts.length == 3 && parts[1].equals("recordings")) {
                requireMethod(exchange, "PUT");
                uploadRecording(exchange, parseId(parts[0]), parseTask(parts[2]));
            } else {
                throw new HttpError(404, "no_such_endpoint", "There is no API endpoint at " + path + ".");
            }
        } catch (NotFoundException e) {
            throw new HttpError(404, "not_found", e.getMessage());
        }
    }

    // ---------- POST /api/sessions ----------

    private void createSession(HttpExchange exchange) throws IOException {
        byte[] body = RequestBodies.readLimited(exchange.getRequestBody(), MAX_JSON_BYTES);
        long profileId;
        if (body.length == 0 || new String(body, StandardCharsets.UTF_8).isBlank()) {
            profileId = sessions.defaultProfile().id();
        } else {
            requireContentType(exchange, "application/json");
            profileId = parseProfileId(new String(body, StandardCharsets.UTF_8));
        }
        RecordingSession session = sessions.startSession(profileId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("session", JsonViews.session(new SessionDetails(session, List.of())));
        response.put("tasks", JsonViews.tasks());
        exchange.getResponseHeaders().set("Location", PREFIX + "/" + session.id());
        Json.send(exchange, 201, response);
    }

    private static long parseProfileId(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                throw new JsonParseException("not an object");
            }
            JsonObject object = root.getAsJsonObject();
            for (String key : object.keySet()) {
                if (!key.equals("profileId")) {
                    throw HttpError.badRequest("unknown_field", "Unknown field \"" + key + "\". Only \"profileId\" is accepted.");
                }
            }
            if (!object.has("profileId")) {
                throw HttpError.badRequest("missing_field", "\"profileId\" is required when a body is sent.");
            }
            JsonElement id = object.get("profileId");
            if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isNumber()) {
                throw HttpError.badRequest("invalid_field", "\"profileId\" must be a whole number.");
            }
            double value = id.getAsDouble();
            if (value != Math.rint(value) || value < 1 || value > Long.MAX_VALUE) {
                throw HttpError.badRequest("invalid_field", "\"profileId\" must be a positive whole number.");
            }
            return id.getAsLong();
        } catch (JsonParseException | IllegalStateException | NumberFormatException e) {
            throw HttpError.badRequest("invalid_json", "The request body is not valid JSON.");
        }
    }

    // ---------- GET /api/sessions/{id} ----------

    private void getSession(HttpExchange exchange, long id) throws IOException {
        SessionDetails details = sessions.details(id)
                .orElseThrow(() -> new NotFoundException("Session " + id + " does not exist."));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("session", JsonViews.session(details));
        response.put("tasks", JsonViews.tasks());
        Json.send(exchange, 200, response);
    }

    // ---------- PUT /api/sessions/{id}/recordings/{task} ----------

    private void uploadRecording(HttpExchange exchange, long sessionId, TaskType task) throws IOException {
        requireContentType(exchange, "application/octet-stream");
        QueryParams query = QueryParams.parse(exchange.getRequestURI().getRawQuery(), UPLOAD_PARAMS);
        int sampleRate = parseSampleRate(query.require("sampleRate"));
        String format = query.get("format").orElse(VoiceSample.AudioFormat.PCM_F32LE.code());
        if (!format.equals(VoiceSample.AudioFormat.PCM_F32LE.code())) {
            throw HttpError.badRequest("unsupported_format", "Only format=pcm_f32le (32-bit float, little-endian) is accepted.");
        }
        String channels = query.get("channels").orElse("1");
        if (!channels.equals("1")) {
            throw HttpError.badRequest("unsupported_channels", "Only mono recordings (channels=1) are accepted.");
        }

        // Size limit for this task at this sample rate, checked against the declared length before reading.
        long taskLimit = (long) task.maxUploadSeconds() * sampleRate * PcmDecoder.BYTES_PER_SAMPLE;
        long limit = Math.min(taskLimit, globalBodyLimit);
        String declared = exchange.getRequestHeaders().getFirst("Content-Length");
        if (declared != null && Long.parseLong(declared.trim()) > limit) {
            throw tooLong(task, limit == taskLimit);
        }
        byte[] body;
        try {
            body = RequestBodies.readLimited(exchange.getRequestBody(), limit);
        } catch (HttpError e) {
            if (e.status() == 413) {
                throw tooLong(task, limit == taskLimit);
            }
            throw e;
        }

        float[] samples;
        try {
            samples = PcmDecoder.decodeF32le(body);
        } catch (InvalidAudioException e) {
            throw HttpError.badRequest("invalid_audio", e.getMessage());
        }
        double seconds = (double) samples.length / sampleRate;
        if (seconds < TaskType.MIN_UPLOAD_SECONDS) {
            throw HttpError.badRequest("recording_too_short", String.format(Locale.ROOT,
                    "The recording is %.2f s long; at least %.1f s is needed.", seconds, TaskType.MIN_UPLOAD_SECONDS));
        }

        boolean replacing = sessions.details(sessionId)
                .orElseThrow(() -> new NotFoundException("Session " + sessionId + " does not exist."))
                .sample(task).isPresent();
        VoiceSample sample;
        try {
            sample = sessions.attachRecording(sessionId, task, sampleRate, body);
        } catch (IllegalArgumentException e) {
            throw HttpError.badRequest("invalid_recording", e.getMessage());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("recording", JsonViews.recording(sample));
        response.put("audio", JsonViews.audio(PcmSummary.of(samples, sampleRate)));
        response.put("replaced", replacing);
        response.put("session", JsonViews.session(sessions.details(sessionId).orElseThrow()));
        Json.send(exchange, replacing ? 200 : 201, response);
    }

    private static HttpError tooLong(TaskType task, boolean taskLimitApplies) {
        String message = taskLimitApplies
                ? "The recording is longer than the " + task.maxUploadSeconds() + "-second limit for the " + task.label().toLowerCase(Locale.ROOT) + " task."
                : "The recording is larger than the server's upload limit.";
        return new HttpError(413, "recording_too_long", message);
    }

    // ---------- parsing helpers ----------

    private static long parseId(String raw) {
        if (raw.isEmpty() || raw.length() > 18 || !raw.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new HttpError(404, "not_found", "There is no session \"" + raw + "\".");
        }
        long id = Long.parseLong(raw);
        if (id < 1) {
            throw new HttpError(404, "not_found", "There is no session " + raw + ".");
        }
        return id;
    }

    private static TaskType parseTask(String raw) {
        try {
            return TaskType.fromCode(raw);
        } catch (IllegalArgumentException e) {
            throw new HttpError(404, "unknown_task", "Unknown task \"" + raw + "\". Expected one of: sustained_vowel, pa_ta_ka, reading_passage, free_speech.");
        }
    }

    private static int parseSampleRate(String raw) {
        int rate;
        try {
            rate = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw HttpError.badRequest("invalid_sample_rate", "sampleRate must be a whole number of hertz, got \"" + raw + "\".");
        }
        if (rate < VoiceSample.MIN_SAMPLE_RATE || rate > VoiceSample.MAX_SAMPLE_RATE) {
            throw HttpError.badRequest("invalid_sample_rate", "sampleRate must be between " + VoiceSample.MIN_SAMPLE_RATE
                    + " and " + VoiceSample.MAX_SAMPLE_RATE + " Hz, got " + rate + ".");
        }
        return rate;
    }

    private static void requireContentType(HttpExchange exchange, String expected) {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        String base = type == null ? "" : type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!base.equals(expected)) {
            throw new HttpError(415, "unsupported_media_type", "Expected Content-Type " + expected
                    + (type == null ? "." : ", got " + type + "."));
        }
    }
}
