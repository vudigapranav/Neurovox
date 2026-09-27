package com.neurovox.server;

import com.neurovox.session.SessionService;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * The local web server, built on the JDK's {@link HttpServer}.
 *
 * <p>It binds to 127.0.0.1 only, so other machines on the network cannot connect.
 * Routes (the longest matching prefix wins):
 * <pre>
 *   /api/health     JSON status
 *   /api/tasks      the four voice tasks and their durations
 *   /api/sessions   recording sessions and uploads ({@link SessionApi})
 *   /api/...        JSON 404 for anything else under /api/
 *   /               the frontend, from classpath static/
 * </pre>
 * Every route passes through {@link RequestGuardFilter} first.
 */
public final class AppServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(AppServer.class.getName());

    private final HttpServer server;
    private final ExecutorService executor;

    private AppServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static AppServer start(ServerConfig config, SessionService sessions) throws IOException {
        InetAddress loopback = InetAddress.getByAddress("localhost", new byte[] {127, 0, 0, 1});
        HttpServer server = HttpServer.create(new InetSocketAddress(loopback, config.port()), 0);
        RequestGuardFilter guard = new RequestGuardFilter(config);
        guard.setBoundPort(server.getAddress().getPort());

        route(server, "/api/health", new ApiHandlers.Health(), guard);
        route(server, "/api/tasks", new ApiHandlers.Tasks(), guard);
        route(server, SessionApi.PREFIX, new SessionApi(sessions, config.maxBodyBytes()), guard);
        route(server, "/api/", new ApiHandlers.NotFound(), guard);
        route(server, "/", new StaticFileHandler(), guard);

        ExecutorService executor = Executors.newFixedThreadPool(config.threads(), namedThreads());
        server.setExecutor(executor);
        server.start();
        LOG.fine(() -> "Listening on " + server.getAddress());
        return new AppServer(server, executor);
    }

    private static void route(HttpServer server, String path, HttpHandler handler, RequestGuardFilter guard) {
        HttpContext context = server.createContext(path, handler);
        context.getFilters().add(guard);
    }

    private static ThreadFactory namedThreads() {
        AtomicInteger count = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "neurovox-http-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** The address to open in a browser, e.g. {@code http://localhost:8080/}. */
    public URI uri() {
        return URI.create("http://localhost:" + port() + "/");
    }

    /** Version from the JAR manifest, or {@code dev} when running from compiled classes. */
    public static String version() {
        String version = AppServer.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
