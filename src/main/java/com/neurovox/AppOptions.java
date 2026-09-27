package com.neurovox;

import java.nio.file.Path;
import java.util.Map;

/**
 * Command-line options. Each option can also come from an environment variable; the command line wins.
 *
 * <pre>
 *   --port=N          NEUROVOX_PORT       default 8080
 *   --data-dir=PATH   NEUROVOX_DATA_DIR   default ./data (database and recordings)
 * </pre>
 */
public record AppOptions(int port, Path dataDir) {

    public static final int DEFAULT_PORT = 8080;
    public static final Path DEFAULT_DATA_DIR = Path.of("data");

    public static AppOptions parse(String[] args, Map<String, String> env) {
        String port = null;
        String dataDir = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String value;
            String name;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 0) {
                name = arg.substring(0, eq);
                value = arg.substring(eq + 1);
            } else {
                name = arg;
                if (!name.equals("--port") && !name.equals("--data-dir")) {
                    throw new IllegalArgumentException("Unknown option: " + arg);
                }
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(name + " needs a value, for example " + name + "=...");
                }
                value = args[++i];
            }
            switch (name) {
                case "--port" -> port = value;
                case "--data-dir" -> dataDir = value;
                default -> throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }
        if (port == null) {
            port = blankToNull(env.get("NEUROVOX_PORT"));
        }
        if (dataDir == null) {
            dataDir = blankToNull(env.get("NEUROVOX_DATA_DIR"));
        }
        int parsedPort = port == null ? DEFAULT_PORT : parsePort(port.trim());
        if (dataDir != null && dataDir.isBlank()) {
            throw new IllegalArgumentException("--data-dir cannot be empty");
        }
        Path parsedDir = dataDir == null ? DEFAULT_DATA_DIR : Path.of(dataDir.trim());
        return new AppOptions(parsedPort, parsedDir);
    }

    static int parsePort(String value) {
        int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Port must be a number, got \"" + value + "\"");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535, got " + port);
        }
        return port;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public static boolean wantsHelp(String[] args) {
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                return true;
            }
        }
        return false;
    }

    public static String usage() {
        return """
                Usage: java -jar neurovox.jar [--port=<1-65535>] [--data-dir=<folder>]

                Starts Neurovox on http://localhost:<port> (default 8080), reachable from this computer only.
                Recordings and the database are stored in the data folder (default ./data).
                Environment variables NEUROVOX_PORT and NEUROVOX_DATA_DIR are used when the options are not given.
                """;
    }
}
