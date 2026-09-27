package com.neurovox.server;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small JSON endpoints under {@code /api/}. Feature endpoints arrive in later sprints. */
final class ApiHandlers {

    private ApiHandlers() {
    }

    /** {@code GET /api/health} — lets the frontend and tests confirm the Java server is running. */
    static final class Health extends SafeHandler {
        @Override
        protected void handleRequest(HttpExchange exchange) throws IOException {
            requireMethod(exchange, "GET", "HEAD");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ok");
            body.put("name", "neurovox");
            body.put("version", AppServer.version());
            Json.send(exchange, 200, body);
        }
    }

    /** {@code GET /api/tasks} — the four tasks with their target and maximum durations (single source of truth). */
    static final class Tasks extends SafeHandler {
        @Override
        protected void handleRequest(HttpExchange exchange) throws IOException {
            requireMethod(exchange, "GET", "HEAD");
            if (!exchange.getRequestURI().getRawPath().equals("/api/tasks")) {
                throw new HttpError(404, "no_such_endpoint", "There is no API endpoint at " + exchange.getRequestURI().getRawPath() + ".");
            }
            Json.send(exchange, 200, Map.of("tasks", JsonViews.tasks()));
        }
    }

    /** Catch-all for unknown {@code /api/...} paths, so API clients always receive JSON. */
    static final class NotFound extends SafeHandler {
        @Override
        protected void handleRequest(HttpExchange exchange) {
            throw new HttpError(404, "no_such_endpoint", "There is no API endpoint at " + exchange.getRequestURI().getRawPath() + ".");
        }
    }
}
