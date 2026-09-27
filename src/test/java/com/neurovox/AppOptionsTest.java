package com.neurovox;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppOptionsTest {

    @Test
    void defaults() {
        AppOptions o = AppOptions.parse(new String[0], Map.of());
        assertEquals(8080, o.port());
        assertEquals(Path.of("data"), o.dataDir());
    }

    @Test
    void optionsFromArgumentsAndEnvironment() {
        assertEquals(9000, AppOptions.parse(new String[] {"--port=9000"}, Map.of()).port());
        assertEquals(9001, AppOptions.parse(new String[] {"--port", "9001"}, Map.of()).port());
        assertEquals(9002, AppOptions.parse(new String[0], Map.of("NEUROVOX_PORT", "9002")).port());
        assertEquals(9003, AppOptions.parse(new String[] {"--port=9003"}, Map.of("NEUROVOX_PORT", "9002")).port());
        assertEquals(Path.of("/tmp/nv"), AppOptions.parse(new String[] {"--data-dir=/tmp/nv"}, Map.of()).dataDir());
        assertEquals(Path.of("x y"), AppOptions.parse(new String[] {"--data-dir", "x y"}, Map.of()).dataDir());
        assertEquals(Path.of("envdir"), AppOptions.parse(new String[0], Map.of("NEUROVOX_DATA_DIR", "envdir")).dataDir());
        assertEquals(Path.of("cli"), AppOptions.parse(new String[] {"--data-dir=cli"}, Map.of("NEUROVOX_DATA_DIR", "envdir")).dataDir());
    }

    @Test
    void invalidValuesAreRejectedWithAMessage() {
        for (String[] args : List.of(new String[] {"--port=abc"}, new String[] {"--port=0"}, new String[] {"--port=70000"},
                new String[] {"--port"}, new String[] {"--verbose"}, new String[] {"--data-dir="}, new String[] {"--data-dir"},
                new String[] {"--colour=red"})) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AppOptions.parse(args, Map.of()));
            assertFalse(e.getMessage().isBlank());
        }
        assertThrows(IllegalArgumentException.class, () -> AppOptions.parse(new String[0], Map.of("NEUROVOX_PORT", "-1")));
    }

    @Test
    void helpFlag() {
        assertTrue(AppOptions.wantsHelp(new String[] {"--help"}));
        assertTrue(AppOptions.wantsHelp(new String[] {"-h"}));
        assertFalse(AppOptions.wantsHelp(new String[] {"--port=1"}));
    }
}
