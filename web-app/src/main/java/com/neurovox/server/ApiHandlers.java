package com.neurovox.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.neurovox.features.UciFeature;
import com.neurovox.ml.ModelLoader;
import com.neurovox.ml.ResearchIndicator;
import com.neurovox.ml.ResearchModel;
import com.neurovox.session.SessionService;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small JSON endpoints under {@code /api/}: health, tasks, model/gate information and local-data deletion. */
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

    /**
     * {@code GET /api/model} — what the research model is, how it was evaluated, and the recorded go/no-go decision.
     * Lets the results page explain why the live indicator is or is not shown.
     */
    static final class Model extends SafeHandler {
        private final ResearchIndicator indicator;

        Model(ResearchIndicator indicator) {
            this.indicator = indicator;
        }

        @Override
        protected void handleRequest(HttpExchange exchange) throws IOException {
            requireMethod(exchange, "GET", "HEAD");
            if (!exchange.getRequestURI().getRawPath().equals("/api/model")) {
                throw new HttpError(404, "no_such_endpoint", "There is no API endpoint at " + exchange.getRequestURI().getRawPath() + ".");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("gate", JsonViews.gate(indicator.gate()));
            body.put("modelVersion", indicator.model().map(ResearchModel::modelVersion).orElse(null));
            body.put("featureOrder", UciFeature.keys());
            body.put("notComputed", UciFeature.NOT_COMPUTED);
            body.put("threshold", indicator.model().map(ResearchModel::threshold).orElse(null));
            body.put("evaluation", ModelLoader.loadEvaluation().map(o -> (JsonElement) o).orElse(new JsonObject()));
            Json.send(exchange, 200, body);
        }
    }

    /**
     * {@code DELETE /api/data} with body {@code {"confirm": "delete all"}} — removes every session, recording,
     * measurement and profile from this computer. The confirmation phrase guards against accidental calls.
     */
    static final class LocalDataApi extends SafeHandler {
        static final String CONFIRMATION = "delete all";
        private final SessionService sessions;

        LocalDataApi(SessionService sessions) {
            this.sessions = sessions;
        }

        @Override
        protected void handleRequest(HttpExchange exchange) throws IOException {
            requireMethod(exchange, "DELETE");
            if (!exchange.getRequestURI().getRawPath().equals("/api/data")) {
                throw new HttpError(404, "no_such_endpoint", "There is no API endpoint at " + exchange.getRequestURI().getRawPath() + ".");
            }
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
                throw new HttpError(415, "unsupported_media_type", "Expected Content-Type application/json.");
            }
            String body = new String(RequestBodies.readLimited(exchange.getRequestBody(), 1024), StandardCharsets.UTF_8);
            String confirm;
            try {
                JsonObject o = JsonParser.parseString(body).getAsJsonObject();
                confirm = o.has("confirm") ? o.get("confirm").getAsString() : "";
            } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
                throw HttpError.badRequest("invalid_json", "The request body is not valid JSON.");
            }
            if (!CONFIRMATION.equals(confirm)) {
                throw HttpError.badRequest("confirmation_required", "Send {\"confirm\": \"" + CONFIRMATION + "\"} to delete all local data.");
            }
            int removed = sessions.deleteAllData();
            Json.send(exchange, 200, Map.of("deletedSessions", removed));
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
