package com.neurovox.server;

/**
 * Server settings.
 *
 * @param port            TCP port on 127.0.0.1 (0 lets the OS choose, used by tests)
 * @param maxBodyBytes    largest request body accepted by any endpoint
 * @param maxUriLength    longest request target (path + query) accepted
 * @param threads         worker threads handling requests
 */
public record ServerConfig(int port, long maxBodyBytes, int maxUriLength, int threads) {

    public static final int DEFAULT_PORT = 8080;
    /** 16 MiB: comfortably above the largest planned upload (30 s of mono float32 PCM at 48 kHz is about 5.8 MB). */
    public static final long DEFAULT_MAX_BODY_BYTES = 16L * 1024 * 1024;
    public static final int DEFAULT_MAX_URI_LENGTH = 2048;
    public static final int DEFAULT_THREADS = 8;

    public ServerConfig {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535, got " + port);
        }
        if (maxBodyBytes < 0) {
            throw new IllegalArgumentException("maxBodyBytes must not be negative");
        }
        if (maxUriLength < 16) {
            throw new IllegalArgumentException("maxUriLength must be at least 16");
        }
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least 1");
        }
    }

    public static ServerConfig defaults() {
        return withPort(DEFAULT_PORT);
    }

    public static ServerConfig withPort(int port) {
        return new ServerConfig(port, DEFAULT_MAX_BODY_BYTES, DEFAULT_MAX_URI_LENGTH, DEFAULT_THREADS);
    }
}
