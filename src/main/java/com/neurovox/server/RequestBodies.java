package com.neurovox.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Reads request bodies without ever holding more than a fixed number of bytes in memory. */
public final class RequestBodies {

    private RequestBodies() {
    }

    /**
     * Reads the whole stream, failing with 413 as soon as more than {@code maxBytes} arrive.
     * This protects endpoints even when the client sends a chunked body with no Content-Length.
     */
    public static byte[] readLimited(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw HttpError.payloadTooLarge(maxBytes);
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
