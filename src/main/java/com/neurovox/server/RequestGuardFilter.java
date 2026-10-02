package com.neurovox.server;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Checks every request before it reaches a handler.
 *
 * <ol>
 *   <li><b>Host header</b> must name this computer (localhost, 127.0.0.1 or [::1]) on our port.
 *       This blocks DNS-rebinding, where a web page tricks the browser into talking to a local server.</li>
 *   <li><b>Request target</b> must not exceed the configured length (414).</li>
 *   <li><b>Origin</b> (cross-site request forgery): for anything other than GET/HEAD, a request that a browser marks
 *       as coming from another site ({@code Origin} not this server, or {@code Sec-Fetch-Site} other than
 *       {@code same-origin}/{@code none}) is refused (403). The Host check alone does not stop this, because a page on
 *       another site can still send a simple POST to {@code http://localhost:8080}.</li>
 *   <li><b>Content-Length</b> must be no larger than the body limit (413), checked before any body is read.
 *       Handlers that read a body still enforce the same limit while reading, via {@link RequestBodies},
 *       so chunked uploads without a Content-Length cannot bypass it.</li>
 * </ol>
 *
 * <p>Note: the JDK server itself rejects a malformed Content-Length (for example {@code abc}) with its own
 * plain 400 response before any filter runs, so those requests never reach this code.
 */
public final class RequestGuardFilter extends Filter {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private final ServerConfig config;
    private volatile int boundPort;

    public RequestGuardFilter(ServerConfig config) {
        this.config = config;
        this.boundPort = config.port();
    }

    /** Called once the server is bound, so port 0 (random port) resolves to the real port. */
    void setBoundPort(int port) {
        this.boundPort = port;
    }

    @Override
    public String description() {
        return "Host, URI length and request size guard";
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        HttpError rejection = check(exchange);
        if (rejection != null) {
            SecurityHeaders.apply(exchange.getResponseHeaders());
            // Do not try to keep the connection alive with an unread body behind it.
            exchange.getResponseHeaders().set("Connection", "close");
            try {
                Json.sendError(exchange, rejection);
            } finally {
                exchange.close();
            }
            return;
        }
        chain.doFilter(exchange);
    }

    private HttpError check(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (!isLocalHost(host, boundPort)) {
            return new HttpError(403, "forbidden_host", "Neurovox only answers requests addressed to localhost.");
        }

        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        if (!method.equals("GET") && !method.equals("HEAD")) {
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            String fetchSite = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
            boolean foreignOrigin = origin != null && !isLocalOrigin(origin, boundPort);
            boolean foreignSite = fetchSite != null && !fetchSite.equals("same-origin") && !fetchSite.equals("none");
            if (foreignOrigin || foreignSite) {
                return new HttpError(403, "cross_origin", "Neurovox only accepts changes from its own pages.");
            }
        }

        String target = exchange.getRequestURI().toString();
        if (target.length() > config.maxUriLength()) {
            return new HttpError(414, "uri_too_long", "The request address is longer than " + config.maxUriLength() + " characters.");
        }

        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            long declared;
            try {
                declared = Long.parseLong(length.trim());
            } catch (NumberFormatException e) {
                return HttpError.badRequest("bad_content_length", "The Content-Length header is not a valid size.");
            }
            if (declared < 0) {
                return HttpError.badRequest("bad_content_length", "The Content-Length header is not a valid size.");
            }
            if (declared > config.maxBodyBytes()) {
                return HttpError.payloadTooLarge(config.maxBodyBytes());
            }
        }
        return null;
    }

    /** True when an Origin header is http://localhost, 127.0.0.1 or [::1] on our port. */
    static boolean isLocalOrigin(String origin, int port) {
        String o = origin.trim().toLowerCase(Locale.ROOT);
        if (!o.startsWith("http://")) {
            return false;
        }
        return isLocalHost(o.substring("http://".length()), port);
    }

    /** True when the Host header is localhost, 127.0.0.1 or [::1], with our port (or no port). */
    static boolean isLocalHost(String hostHeader, int port) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String host = hostHeader.trim().toLowerCase(Locale.ROOT);
        String name = host;
        String portPart = null;
        int colon = host.lastIndexOf(':');
        if (colon > host.lastIndexOf(']')) {
            name = host.substring(0, colon);
            portPart = host.substring(colon + 1);
        }
        if (!LOCAL_HOSTS.contains(name)) {
            return false;
        }
        if (portPart == null) {
            return port == 80;
        }
        return portPart.equals(Integer.toString(port));
    }
}
