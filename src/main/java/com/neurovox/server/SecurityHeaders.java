package com.neurovox.server;

import com.sun.net.httpserver.Headers;

/**
 * Response headers applied to every response.
 *
 * <ul>
 *   <li>The Content-Security-Policy allows scripts, styles, audio worklets and requests only from this
 *       server, so the page cannot load code from, or send data to, any other site.
 *       Inline style attributes are allowed because the page uses them for layout values.</li>
 *   <li>{@code nosniff} makes browsers trust our Content-Type instead of guessing.</li>
 *   <li>The Permissions-Policy allows the microphone for this origin only.</li>
 * </ul>
 */
public final class SecurityHeaders {

    public static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data:",
            "connect-src 'self'",
            "media-src 'self' blob:",
            "worker-src 'self'",
            "object-src 'none'",
            "base-uri 'none'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    private SecurityHeaders() {
    }

    public static void apply(Headers headers) {
        headers.set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Cross-Origin-Opener-Policy", "same-origin");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Permissions-Policy", "microphone=(self), camera=(), geolocation=()");
    }
}
