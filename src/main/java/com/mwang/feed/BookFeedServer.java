package com.mwang.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Serves the current order book as JSON over HTTP, on {@code /book}.
 *
 * <p>Takes a supplier rather than the book, so it has no idea where the data comes from and
 * cannot reach matcher-owned state. The supplier is expected to return an immutable snapshot
 * that has already been published; handler threads then need no locks, which is why an
 * arbitrary number of concurrent requests is safe.
 *
 * <p>Start this last and {@link #close()} it first, so the exchange stops taking outside
 * traffic before its internals shut down.
 */
public final class BookFeedServer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(BookFeedServer.class);

    private static final String PATH = "/book";
    private static final String JSON = "application/json; charset=utf-8";

    /** Seconds {@code stop} waits for in-flight exchanges. Responses here take microseconds. */
    private static final int STOP_DELAY_SECONDS = 1;

    /** Expensive to build, thread-safe once configured, so one instance serves every request. */
    private final ObjectMapper mapper = new ObjectMapper();

    private final int requestedPort;
    private final Supplier<MarketDataSnapshot> source;

    private HttpServer server;
    private ExecutorService executor;

    /**
     * @param requestedPort the port to bind, or 0 to let the OS pick a free one. Tests should
     *                      pass 0 and read {@link #port()} back.
     * @param source        supplies the snapshot to serve; must be safe to call from any thread
     */
    public BookFeedServer(int requestedPort, Supplier<MarketDataSnapshot> source) {
        this.requestedPort = requestedPort;
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * Binds the port and begins serving.
     *
     * @throws IOException           if the port cannot be bound
     * @throws IllegalStateException if already started
     */
    public void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("feed server already started");
        }
        server = HttpServer.create(new InetSocketAddress(requestedPort), 0);
        server.createContext(PATH, this::handle);

        // Without an executor, requests are serialised onto one thread. Concurrency is safe
        // here because a handler only reads an immutable snapshot and shares nothing.
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();

        logger.info("Book feed listening on http://localhost:{}{}", port(), PATH);
    }

    /** @return the port actually bound, which differs from the requested one when that was 0 */
    public int port() {
        if (server == null) {
            throw new IllegalStateException("feed server is not started");
        }
        return server.getAddress().getPort();
    }

    /**
     * Stops serving and releases the port. Idempotent, and safe when never started.
     *
     * <p>Shuts the executor down explicitly: {@code HttpServer.stop} does not, so leaving it
     * out leaks its threads.
     */
    @Override
    public void close() {
        if (server == null) {
            return;
        }
        server.stop(STOP_DELAY_SECONDS);
        server = null;
        executor.close();
        logger.info("Book feed stopped");
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            // createContext matches by PREFIX, so /book/anything lands here too.
            if (!PATH.equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 404, "{\"error\":\"not found\"}");
                return;
            }
            respond(exchange, 200, mapper.writeValueAsString(source.get()));
        } catch (RuntimeException e) {
            // An exception escaping a handler closes the connection with no status at all, so
            // the client sees a truncated response rather than an error. Same reasoning as the
            // gateway's guard around a caller-supplied listener.
            logger.error("Failed to serve {}", exchange.getRequestURI(), e);
            respond(exchange, 500, "{\"error\":\"internal error\"}");
        } finally {
            exchange.close();
        }
    }

    /**
     * Writes a response. The length header counts <em>bytes</em>, not characters, so the body
     * is encoded once and its length taken from the result.
     */
    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", JSON);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
