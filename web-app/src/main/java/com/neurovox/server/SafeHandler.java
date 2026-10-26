package com.neurovox.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Base class for every handler. It adds the security headers and turns failures into JSON:
 * an {@link HttpError} becomes its own status and message; anything unexpected becomes a
 * generic 500 whose details are logged locally but never sent to the browser.
 */
public abstract class SafeHandler implements HttpHandler {

    private static final Logger LOG = Logger.getLogger(SafeHandler.class.getName());

    @Override
    public final void handle(HttpExchange exchange) throws IOException {
        try {
            SecurityHeaders.apply(exchange.getResponseHeaders());
            handleRequest(exchange);
        } catch (HttpError error) {
            respondWithError(exchange, error);
        } catch (Exception unexpected) {
            LOG.log(Level.SEVERE, "Unhandled error for " + exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath(), unexpected);
            respondWithError(exchange, new HttpError(500, "internal_error", "Something went wrong on the local server. Check the server log for details."));
        } finally {
            exchange.close();
        }
    }

    protected abstract void handleRequest(HttpExchange exchange) throws IOException;

    /** Rejects any method not in {@code allowed}, advertising the allowed ones in the Allow header. */
    protected static void requireMethod(HttpExchange exchange, String... allowed) {
        String method = exchange.getRequestMethod();
        for (String candidate : allowed) {
            if (candidate.equalsIgnoreCase(method)) {
                return;
            }
        }
        exchange.getResponseHeaders().set("Allow", String.join(", ", allowed));
        throw new HttpError(405, "method_not_allowed", method + " is not supported here. Allowed: " + String.join(", ", allowed) + ".");
    }

    private static void respondWithError(HttpExchange exchange, HttpError error) throws IOException {
        if (exchange.getResponseCode() != -1) {
            // Headers were already sent; the connection can only be closed.
            LOG.warning("Error after response started: " + error.getMessage());
            return;
        }
        Json.sendError(exchange, error);
    }
}
