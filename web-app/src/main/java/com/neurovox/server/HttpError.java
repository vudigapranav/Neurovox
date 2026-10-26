package com.neurovox.server;

/**
 * An expected failure that maps directly to an HTTP error response.
 * The message is shown to the user, so it must be plain language and must not leak internals.
 */
public final class HttpError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;

    public HttpError(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    /** Stable, machine-readable identifier such as {@code not_found} or {@code payload_too_large}. */
    public String code() {
        return code;
    }

    public static HttpError badRequest(String code, String message) {
        return new HttpError(400, code, message);
    }

    public static HttpError notFound(String message) {
        return new HttpError(404, "not_found", message);
    }

    public static HttpError payloadTooLarge(long limit) {
        return new HttpError(413, "payload_too_large", "The request body is larger than the " + limit + "-byte limit.");
    }
}
