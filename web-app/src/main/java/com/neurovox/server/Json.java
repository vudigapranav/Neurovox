package com.neurovox.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin wrapper around Gson plus helpers that write JSON responses.
 *
 * <p>Every error response has the same shape so the frontend can handle them uniformly:
 * <pre>{"error": {"status": 404, "code": "not_found", "message": "..."}}</pre>
 */
public final class Json {

    public static final String CONTENT_TYPE = "application/json; charset=utf-8";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private Json() {
    }

    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }

    /** The body of an error response, as a map (keeps field order stable). */
    public static Map<String, Object> errorBody(int status, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("status", status);
        error.put("code", code);
        error.put("message", message);
        return Map.of("error", error);
    }

    public static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        boolean head = "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
        exchange.sendResponseHeaders(status, head ? -1 : bytes.length);
        if (!head) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    public static void sendError(HttpExchange exchange, HttpError error) throws IOException {
        send(exchange, error.status(), errorBody(error.status(), error.code(), error.getMessage()));
    }

    public static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        send(exchange, status, errorBody(status, code, message));
    }
}
