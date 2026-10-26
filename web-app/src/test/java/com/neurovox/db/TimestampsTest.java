package com.neurovox.db;

import com.neurovox.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimestampsTest {

    @Test
    void fixedWidthUtcWithMilliseconds() {
        assertEquals("2026-09-26T17:03:05.000Z", Timestamps.format(Instant.parse("2026-09-26T17:03:05Z")));
        assertEquals("2026-09-26T17:03:05.120Z", Timestamps.format(Instant.parse("2026-09-26T17:03:05.12Z")));
        assertEquals("2026-09-26T17:03:05.123Z", Timestamps.format(Instant.parse("2026-09-26T17:03:05.123456789Z")));
    }

    @Test
    void roundTripsExactlyAtMillisecondPrecision() {
        Instant t = Instant.parse("2026-02-28T23:59:59.999Z");
        assertEquals(t, Timestamps.parse(Timestamps.format(t)));
        assertEquals(Instant.parse("2026-01-01T00:00:00.123Z"),
                Timestamps.now(new MutableClock(Instant.parse("2026-01-01T00:00:00.123987Z"))));
    }

    @Test
    void textOrderIsChronologicalOrder() {
        List<Instant> instants = List.of(
                Instant.parse("2026-09-26T17:03:05.1Z"), Instant.parse("2026-09-26T17:03:05.05Z"),
                Instant.parse("2026-09-26T17:03:05Z"), Instant.parse("2025-12-31T23:59:59.999Z"),
                Instant.parse("2026-09-26T17:03:05.123Z"));
        List<String> text = new ArrayList<>(instants.stream().map(Timestamps::format).toList());
        List<Instant> chronological = new ArrayList<>(instants);
        chronological.sort(null);
        text.sort(null);
        assertEquals(chronological.stream().map(Timestamps::format).toList(), text);
    }

    @Test
    void rejectsOtherShapes() {
        for (String bad : new String[] {"2026-09-26T17:03:05Z", "2026-09-26 17:03:05.000Z", "2026-09-26T17:03:05.000+01:00", "", "nonsense"}) {
            assertThrows(IllegalArgumentException.class, () -> Timestamps.parse(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> Timestamps.parse(null));
    }

    @Test
    void globPatternMatchesFormattedOutput() {
        String sample = Timestamps.format(Instant.parse("2026-09-26T17:03:05.120Z"));
        // Translate the GLOB character classes to a regex and check the formatter output matches.
        String regex = Timestamps.GLOB.replace("[0-9]", "\\d").replace(".", "\\.");
        assertEquals(true, sample.matches(regex));
    }
}
