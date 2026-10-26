package com.mwang.audit;

import com.mwang.model.Order;
import com.mwang.model.OrderType;
import com.mwang.model.Side;
import com.mwang.model.Trade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradeLoggerTest {

    /** JUnit creates this per test and deletes it afterwards, so nothing lands in the repo. */
    @TempDir
    Path directory;

    /** Large enough that the writer thread cannot keep pace with the caller. */
    private static final int TRADE_COUNT = 50_000;

    private TradeLogger tradeLogger;

    @AfterEach
    void closeLogger() {
        if (tradeLogger != null) {
            tradeLogger.close();      // idempotent, so safe even if the test closed it
        }
    }

    // --- the file it produces ---

    @Test
    void shouldWriteAHeaderToANewFile() throws Exception {
        start();
        tradeLogger.close();

        assertThat(lines()).first().isEqualTo("timestamp,buyOrderId,sellOrderId,price,quantity");
    }

    @Test
    void shouldWriteOneRowPerTrade() throws Exception {
        start();
        tradeLogger.onMatch(anOrder(), List.of(trade(1, 2), trade(3, 4), trade(5, 6)));
        tradeLogger.close();

        assertThat(lines()).hasSize(4);      // the header plus three trades
    }

    @Test
    void shouldWriteTheColumnsInHeaderOrder() throws Exception {
        start();
        tradeLogger.onMatch(anOrder(), List.of(new Trade(7, 9, 10_001, 50, 1_700_000_000_000L)));
        tradeLogger.close();

        assertThat(lines()).element(1).isEqualTo("1700000000000,7,9,10001,50");
    }

    /**
     * The file must preserve match order. A queue plus one writer thread guarantees that; a
     * thread pool would not.
     */
    @Test
    void shouldPreserveTheOrderTradesWereMatchedIn() throws Exception {
        start();
        for (int i = 1; i <= 200; i++) {
            tradeLogger.onMatch(anOrder(), List.of(trade(i, i)));
        }
        tradeLogger.close();

        assertThat(lines().subList(1, 201))
                .extracting(line -> Long.parseLong(line.split(",")[1]))
                .containsExactlyElementsOf(buyIdsFromOneTo(200));
    }

    /**
     * A second run must not corrupt the first run's records, so an existing file is appended
     * to and the header is written only when the file is new.
     */
    @Test
    void shouldAppendToAnExistingFileWithoutRepeatingTheHeader() throws Exception {
        start();
        tradeLogger.onMatch(anOrder(), List.of(trade(1, 2)));
        tradeLogger.close();

        tradeLogger = new TradeLogger(file());
        tradeLogger.start();
        tradeLogger.onMatch(anOrder(), List.of(trade(3, 4)));
        tradeLogger.close();

        List<String> lines = lines();
        assertThat(lines).hasSize(3);
        assertThat(lines).filteredOn(line -> line.startsWith("timestamp")).hasSize(1);
    }

    @Test
    void shouldCreateMissingParentDirectories() throws Exception {
        tradeLogger = new TradeLogger(directory.resolve("deeply/nested/trades.csv"));
        tradeLogger.start();
        tradeLogger.close();

        assertThat(directory.resolve("deeply/nested/trades.csv")).exists();
    }

    // --- durability ---

    /**
     * The flush policy is "flush once the queue is empty", so {@code close()} must force out
     * whatever is still buffered or the tail of every run goes missing.
     */
    @Test
    void shouldFlushEverythingBufferedWhenClosed() throws Exception {
        start();
        for (int i = 1; i <= 500; i++) {
            tradeLogger.onMatch(anOrder(), List.of(trade(i, i)));
        }

        tradeLogger.close();

        assertThat(lines()).hasSize(501);
        assertThat(tradeLogger.written()).isEqualTo(500);
    }

    /**
     * The property the whole class exists for: {@link TradeLogger#onMatch} runs on the matcher
     * thread, so it must hand the work off rather than do it.
     *
     * <p>Asserts on how much is already on disk when {@code onMatch} returns, not on elapsed
     * time, which is flaky and not even discriminating here. Measured: 2,028 of 50,000 when
     * asynchronous, all 50,000 when synchronous, so this sits in the gap with a wide margin.
     */
    @Test
    void shouldHandOffTheWritingInsteadOfDoingItOnTheCallingThread() throws Exception {
        start();
        List<Trade> manyTrades = new ArrayList<>();
        for (int i = 1; i <= TRADE_COUNT; i++) {
            manyTrades.add(trade(i, i));
        }

        tradeLogger.onMatch(anOrder(), manyTrades);
        long writtenWhenOnMatchReturned = tradeLogger.written();

        assertThat(writtenWhenOnMatchReturned)
                .as("onMatch must return before the writer has caught up, not after")
                .isLessThan(TRADE_COUNT / 2);

        tradeLogger.close();
        assertThat(tradeLogger.written())
                .as("and everything must still reach the file once it is closed")
                .isEqualTo(TRADE_COUNT);
        assertThat(lines()).hasSize(TRADE_COUNT + 1);
        assertThat(tradeLogger.dropped())
                .as("the queue is sized so a normal load cannot fill it")
                .isZero();
    }

    // --- lifecycle ---

    @Test
    void shouldRejectBeingStartedTwice() throws Exception {
        start();

        assertThatThrownBy(() -> tradeLogger.start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already started");
    }

    @Test
    void shouldReturnQuietlyWhenClosedBeforeBeingStarted() {
        tradeLogger = new TradeLogger(file());

        assertThatCode(tradeLogger::close).doesNotThrowAnyException();
    }

    @Test
    void shouldTolerateBeingClosedTwice() throws Exception {
        start();
        tradeLogger.close();

        assertThatCode(tradeLogger::close).doesNotThrowAnyException();
    }

    /** It is AutoCloseable, so try-with-resources must flush and close it. */
    @Test
    void shouldWorkInATryWithResourcesBlock() throws Exception {
        try (TradeLogger logger = new TradeLogger(file())) {
            logger.start();
            logger.onMatch(anOrder(), List.of(trade(1, 2)));
        }

        assertThat(lines()).hasSize(2);
    }

    @Test
    void shouldRejectANullPath() {
        assertThatThrownBy(() -> new TradeLogger(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("file");
    }

    // --- fixtures ---

    private void start() throws IOException {
        tradeLogger = new TradeLogger(file());
        tradeLogger.start();
    }

    private Path file() {
        return directory.resolve("trades.csv");
    }

    private List<String> lines() throws IOException {
        return Files.readAllLines(file());
    }

    private static Trade trade(long buyOrderId, long sellOrderId) {
        return new Trade(buyOrderId, sellOrderId, 10_000, 10, 0);
    }

    private static Order anOrder() {
        return new Order(1, 1, 10_000, 10, Side.BUY, OrderType.LIMIT, 0);
    }

    private static List<Long> buyIdsFromOneTo(int last) {
        List<Long> ids = new ArrayList<>(last);
        for (long i = 1; i <= last; i++) {
            ids.add(i);
        }
        return ids;
    }
}
