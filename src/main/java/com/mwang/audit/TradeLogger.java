package com.mwang.audit;

import com.mwang.gateway.MatchListener;
import com.mwang.model.Order;
import com.mwang.model.Trade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Appends every executed trade to a CSV file, as an audit trail.
 *
 * <p>A {@link MatchListener} runs on the matcher thread, so anything it does the exchange waits
 * for. Matching takes microseconds; a file write takes tens to thousands of times longer. So
 * {@link #onMatch} only queues trades and returns, and a dedicated writer thread does the I/O -
 * the same producer-consumer shape as {@link com.mwang.gateway.OrderGateway}, one layer out.
 *
 * <p><strong>Flush policy:</strong> the writer flushes whenever the queue empties. Under a
 * burst, writes batch in the buffer; the moment the burst ends everything is pushed out. Worst
 * case on a JVM crash is however far behind the writer had fallen, and nothing in a quiet
 * market. Note {@code flush()} reaches the operating system, not the disk - surviving a process
 * crash but not a power cut, which would need {@code FileChannel.force()}.
 *
 * <p><strong>Back-pressure:</strong> {@link #onMatch} uses {@code offer}, never {@code put},
 * because a full queue must not stall the matcher. Drops are therefore possible; they are
 * counted, logged and reported at close, never silent. This is the opposite trade-off from the
 * gateway, where blocking the producer is correct - there the producer is a client who can
 * wait, here it is the matching engine, which cannot.
 *
 * <p>Close this <strong>after</strong> the gateway has shut down, or the matcher's final trades
 * have nowhere to go.
 */
public final class TradeLogger implements MatchListener, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(TradeLogger.class);

    /** Large enough that filling it indicates a failure, not a busy market. */
    private static final int CAPACITY = 100_000;

    private static final long SHUTDOWN_TIMEOUT_MILLIS = 5_000;

    private static final String HEADER = "timestamp,buyOrderId,sellOrderId,price,quantity";

    /** End-of-stream marker, compared by identity. Its values are arbitrary but must satisfy
     * {@link Trade}'s validation, hence 1 rather than 0. */
    private static final Trade POISON = new Trade(-1, -1, 1, 1, 0);

    private final Path file;
    private final BlockingQueue<Trade> pending = new LinkedBlockingQueue<>(CAPACITY);
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    private BufferedWriter out;
    private Thread writer;
    private volatile boolean running;

    /**
     * @param file where to append trades. Parent directories are created on {@link #start()};
     *             an existing file is appended to and the header written only when new, so
     *             restarting does not corrupt an earlier run's records.
     */
    public TradeLogger(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /**
     * Opens the file and starts the writer thread. Must be called before the gateway starts.
     *
     * @throws IOException           if the file cannot be opened
     * @throws IllegalStateException if already started
     */
    public synchronized void start() throws IOException {
        if (writer != null) {
            throw new IllegalStateException("trade logger already started");
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        boolean isNew = Files.notExists(file) || Files.size(file) == 0;

        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (isNew) {
            out.write(HEADER);
            out.newLine();
            out.flush();
        }

        running = true;
        writer = new Thread(this::writeLoop, "trade-writer");
        writer.start();
        logger.info("Trade log open at {}", file.toAbsolutePath());
    }

    /** Queues this order's trades. Runs on the matcher thread, so it neither blocks nor does I/O. */
    @Override
    public void onMatch(Order order, List<Trade> trades) {
        for (Trade trade : trades) {
            if (!pending.offer(trade)) {
                // offer() rather than put(): blocking here would stall the whole exchange.
                if (dropped.incrementAndGet() == 1) {
                    logger.error("Trade log queue is full ({}); audit records are being dropped",
                            CAPACITY);
                }
            }
        }
    }

    /**
     * Drains the queue onto disk until the poison pill arrives. A sentinel rather than a flag,
     * because this is a consumer with a backlog it has already promised to write.
     */
    private void writeLoop() {
        try {
            while (true) {
                Trade trade = pending.take();
                if (trade == POISON) {
                    break;
                }
                write(trade);
                if (pending.isEmpty()) {
                    // Caught up: make everything written so far durable. Under load this is
                    // skipped and the writes batch in the buffer instead.
                    flush();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flush();
    }

    /** Writes one CSV row. No quoting needed: every column is a number. */
    private void write(Trade trade) {
        try {
            out.write(trade.timestamp() + "," + trade.buyOrderId() + "," + trade.sellOrderId()
                    + "," + trade.price() + "," + trade.quantity());
            out.newLine();
            written.incrementAndGet();
        } catch (IOException e) {
            logger.error("Failed to write trade {}/{}", trade.buyOrderId(), trade.sellOrderId(), e);
        }
    }

    private void flush() {
        try {
            out.flush();
        } catch (IOException e) {
            logger.error("Failed to flush the trade log", e);
        }
    }

    /**
     * Drains what is queued, flushes and closes the file. Idempotent, and safe when never
     * started. Must run after the gateway has shut down.
     */
    @Override
    public synchronized void close() {
        if (!running) {
            return;
        }
        running = false;

        try {
            // offer with a timeout, not put: if the queue were full, a blocking put would wait
            // for a writer that is itself waiting for space - and the join below would never
            // be reached, so its timeout would buy nothing.
            if (!pending.offer(POISON, SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                logger.error("Could not queue the poison pill; interrupting the writer");
                writer.interrupt();
            }
            writer.join(SHUTDOWN_TIMEOUT_MILLIS);
            if (writer.isAlive()) {
                logger.error("Trade writer did not stop within {}ms", SHUTDOWN_TIMEOUT_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (!writer.isAlive()) {
            // Only safe once the writer has finished; closing underneath a live thread would
            // race with its own writes.
            try {
                out.close();
            } catch (IOException e) {
                logger.error("Failed to close the trade log", e);
            }
        }

        if (dropped.get() > 0) {
            logger.error("Trade log dropped {} of {} trades", dropped.get(),
                    dropped.get() + written.get());
        }
        logger.info("Trade log closed, {} trades written to {}", written.get(), file);
    }

    /** @return how many trades have reached the file so far */
    public long written() {
        return written.get();
    }

    /** @return how many trades were discarded because the queue was full; should always be 0 */
    public long dropped() {
        return dropped.get();
    }
}
