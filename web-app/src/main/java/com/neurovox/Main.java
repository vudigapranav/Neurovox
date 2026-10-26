package com.neurovox;

import com.neurovox.db.DataAccessException;
import com.neurovox.server.AppServer;
import com.neurovox.server.ServerConfig;
import com.neurovox.session.SessionService;
import com.neurovox.storage.LocalData;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point: opens the local data folder, starts the local Neurovox server and keeps it running
 * until the process is stopped.
 *
 * <pre>
 *   java -jar target/neurovox.jar                      # http://localhost:8080, data in ./data
 *   java -jar target/neurovox.jar --port=9090 --data-dir=/path/to/data
 * </pre>
 */
public final class Main {

    static {
        // Must be set before the logging system initialises: "14:02:11 INFO  Neurovox - message"
        if (System.getProperty("java.util.logging.SimpleFormatter.format") == null) {
            System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tH:%1$tM:%1$tS %4$-7s %3$s - %5$s%6$s%n");
        }
    }

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private Main() {
    }

    public static void main(String[] args) {
        if (AppOptions.wantsHelp(args)) {
            System.out.println(AppOptions.usage());
            return;
        }

        AppOptions options;
        try {
            options = AppOptions.parse(args, System.getenv());
        } catch (IllegalArgumentException e) {
            LOG.severe(e.getMessage());
            System.err.println(AppOptions.usage());
            System.exit(2);
            return;
        }

        LocalData data;
        try {
            data = LocalData.open(options.dataDir());
        } catch (DataAccessException | UncheckedIOException e) {
            LOG.severe("Could not open the data folder " + options.dataDir().toAbsolutePath() + ": " + e.getMessage());
            System.exit(1);
            return;
        }
        LOG.info("Data folder: " + data.dataDir());

        AppServer server;
        try {
            SessionService sessions = new SessionService(data.database(), data.recordings(), Clock.systemUTC());
            server = AppServer.start(ServerConfig.withPort(options.port()), sessions);
        } catch (BindException e) {
            LOG.severe("Port " + options.port() + " is already in use. Stop the other program or run with --port=<another port>.");
            System.exit(1);
            return;
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Could not start the server", e);
            System.exit(1);
            return;
        }

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            stopped.countDown();
        }, "neurovox-shutdown"));

        LOG.info("Neurovox is running at " + server.uri() + " (local only). Press Ctrl+C to stop.");
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
