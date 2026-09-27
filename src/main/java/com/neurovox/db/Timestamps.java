package com.neurovox.db;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * How timestamps are stored: UTC, millisecond precision, fixed width, e.g. {@code 2026-09-26T17:03:05.120Z}.
 *
 * <p>Fixed width matters. {@link DateTimeFormatter#ISO_INSTANT} drops trailing zeros ({@code :05.12Z} vs
 * {@code :05.123Z}), which breaks text sorting. With a fixed width, sorting the text column in SQL gives
 * chronological order, and the value is still readable when inspecting the database by hand.
 * The schema checks the shape with a GLOB constraint.
 */
public final class Timestamps {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** SQLite GLOB pattern matching {@link #format(Instant)} output, used in CHECK constraints. */
    static final String GLOB = "[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]Z";

    private Timestamps() {
    }

    /** Current time from {@code clock}, truncated to milliseconds so it round-trips exactly. */
    public static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    public static String format(Instant instant) {
        return FORMAT.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }

    public static Instant parse(String text) {
        if (text == null || text.length() != 24) {
            throw new IllegalArgumentException("Not a stored timestamp: " + text);
        }
        try {
            return Instant.from(FORMAT.parse(text));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Not a stored timestamp: " + text, e);
        }
    }

    /** Parses a nullable column value. */
    static Instant parseNullable(String text) {
        return text == null ? null : parse(text);
    }
}
