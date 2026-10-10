package com.mwang.feed;

import com.mwang.book.BookSnapshot;
import com.mwang.book.DepthLevel;
import com.mwang.book.OrderBook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookFeedServerTest {

    /** Shared, and closed at the end: its own thread pool would otherwise outlive the suite. */
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private BookFeedServer server;

    @AfterEach
    void closeServer() {
        if (server != null) {
            server.close();
        }
    }

    @AfterAll
    static void closeClient() {
        CLIENT.close();
    }

    // --- serving ---

    @Test
    void shouldServeTheSnapshotAsJson() throws Exception {
        start(() -> MarketDataSnapshot.from(populatedBook(), 1_423, 1_700_000_000_000L));

        HttpResponse<String> response = get("/book");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValue("application/json; charset=utf-8");
        assertThat(response.body()).isEqualTo("{"
                + "\"sequence\":1423,"
                + "\"timestamp\":1700000000000,"
                + "\"bestBid\":9998,"
                + "\"bestAsk\":10002,"
                + "\"bids\":[{\"price\":9998,\"quantity\":300}],"
                + "\"asks\":[{\"price\":10002,\"quantity\":150}]"
                + "}");
    }

    /** The supplier is read per request, not cached, so the feed reflects the live book. */
    @Test
    void shouldReadTheSupplierOnEveryRequest() throws Exception {
        AtomicBoolean second = new AtomicBoolean();
        start(() -> MarketDataSnapshot.from(emptyBook(), second.getAndSet(true) ? 2 : 1, 0));

        assertThat(get("/book").body()).contains("\"sequence\":1");
        assertThat(get("/book").body()).contains("\"sequence\":2");
    }

    // --- failure cases ---

    /**
     * {@code createContext} matches by prefix, so /book/anything reaches the handler too. The
     * handler checks the path itself rather than silently serving the book for any URL under it.
     */
    @Test
    void shouldRejectAPathBeyondTheContext() throws Exception {
        startWithEmptyBook();

        assertThat(get("/book/depth/5").statusCode()).isEqualTo(404);
    }

    /** No context is registered here at all, so HttpServer answers before the handler runs. */
    @Test
    void shouldRejectAnUnregisteredPath() throws Exception {
        startWithEmptyBook();

        assertThat(get("/orders").statusCode()).isEqualTo(404);
    }

    @Test
    void shouldRejectANonGetRequest() throws Exception {
        startWithEmptyBook();

        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(uri("/book")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
    }

    /**
     * An exception escaping a handler closes the connection with no status, so the client sees
     * a truncated response rather than an error. The handler must absorb it - and must keep
     * serving afterwards, exactly as the matcher survives a listener that throws.
     */
    @Test
    void shouldReturn500AndKeepServingWhenTheSupplierThrows() throws Exception {
        AtomicBoolean broken = new AtomicBoolean(true);
        start(() -> {
            if (broken.get()) {
                throw new IllegalStateException("supplier is broken");
            }
            return MarketDataSnapshot.from(emptyBook(), 7, 0);
        });

        assertThat(get("/book").statusCode()).isEqualTo(500);

        broken.set(false);
        HttpResponse<String> recovered = get("/book");
        assertThat(recovered.statusCode()).isEqualTo(200);
        assertThat(recovered.body()).contains("\"sequence\":7");
    }

    // --- concurrency ---

    /**
     * Safe without a lock only because a handler reads an immutable snapshot and shares
     * nothing. Would fail, or return nonsense, if the server reached into the order book.
     */
    @Test
    void shouldServeManyRequestsAtOnce() throws Exception {
        startWithEmptyBook();

        List<CompletableFuture<HttpResponse<String>>> inFlight = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            inFlight.add(CLIENT.sendAsync(HttpRequest.newBuilder(uri("/book")).build(),
                    HttpResponse.BodyHandlers.ofString()));
        }

        assertThat(inFlight).allSatisfy(future ->
                assertThat(future.get().statusCode()).isEqualTo(200));
    }

    // --- lifecycle ---

    /**
     * A server that holds its port makes the <em>next</em> run fail, with a confusing message
     * about an address already in use. Binding a second server to the same port proves it let go.
     */
    @Test
    void shouldReleaseThePortWhenClosed() throws Exception {
        startWithEmptyBook();
        int port = server.port();
        server.close();

        BookFeedServer second = new BookFeedServer(port, () ->
                MarketDataSnapshot.from(emptyBook(), 0, 0));
        assertThatCode(second::start).doesNotThrowAnyException();
        second.close();
    }

    @Test
    void shouldRejectBeingStartedTwice() throws Exception {
        startWithEmptyBook();

        assertThatThrownBy(() -> server.start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already started");
    }

    @Test
    void shouldReturnQuietlyWhenClosedBeforeBeingStarted() {
        server = new BookFeedServer(0, () -> MarketDataSnapshot.from(emptyBook(), 0, 0));

        assertThatCode(server::close).doesNotThrowAnyException();
    }

    @Test
    void shouldTolerateBeingClosedTwice() throws Exception {
        startWithEmptyBook();
        server.close();

        assertThatCode(server::close).doesNotThrowAnyException();
    }

    @Test
    void shouldRefuseToReportAPortBeforeItIsBound() {
        server = new BookFeedServer(0, () -> MarketDataSnapshot.from(emptyBook(), 0, 0));

        assertThatThrownBy(() -> server.port())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not started");
    }

    @Test
    void shouldRejectANullSupplier() {
        assertThatThrownBy(() -> new BookFeedServer(0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("source");
    }

    // --- fixtures ---

    /** Port 0 lets the OS pick a free one, so the suite never collides with a running process. */
    private void start(Supplier<MarketDataSnapshot> source) throws IOException {
        server = new BookFeedServer(0, source);
        server.start();
    }

    private void startWithEmptyBook() throws IOException {
        start(() -> MarketDataSnapshot.from(emptyBook(), 0, 0));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(uri(path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + server.port() + path);
    }

    private static BookSnapshot populatedBook() {
        return new BookSnapshot(List.of(new DepthLevel(9998, 300)),
                List.of(new DepthLevel(10002, 150)), 9998, 10002);
    }

    private static BookSnapshot emptyBook() {
        return new BookSnapshot(List.of(), List.of(), OrderBook.NO_PRICE, OrderBook.NO_PRICE);
    }
}
