package com.neurovox.server;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;

/**
 * Serves the frontend (HTML, CSS, JavaScript) from {@code static/} on the classpath, so the
 * packaged JAR contains everything needed. Only GET and HEAD are allowed; there are no
 * directory listings; unknown file types are never served.
 */
public final class StaticFileHandler extends SafeHandler {

    private final ClassLoader classLoader;

    public StaticFileHandler() {
        this(StaticFileHandler.class.getClassLoader());
    }

    StaticFileHandler(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    protected void handleRequest(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "GET", "HEAD");

        String rawPath = exchange.getRequestURI().getRawPath();
        String resource = StaticPath.toResource(rawPath);
        String contentType = MimeTypes.forPath(resource)
                .orElseThrow(() -> HttpError.notFound("There is no page at " + rawPath + "."));
        URL url = classLoader.getResource(resource);
        if (url == null) {
            throw HttpError.notFound("There is no page at " + rawPath + ".");
        }

        byte[] body;
        try (InputStream in = url.openStream()) {
            body = in.readAllBytes();
        }

        exchange.getResponseHeaders().set("Content-Type", contentType);
        // Always revalidate so a rebuilt JAR is picked up immediately during development.
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        boolean head = "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
        exchange.sendResponseHeaders(200, head ? -1 : body.length);
        if (!head) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
