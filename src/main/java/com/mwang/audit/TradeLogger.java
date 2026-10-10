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
 * <h2>Why this has its own thread</h2>
 *
 * <p>A {@link MatchListener} runs on the matcher thread, so whatever it does, the exchange
 * waits for. Matching an order takes microseconds; a file write that reaches the operating
 * system takes tens to hundreds of microseconds, and one that reaches the disk takes
 * milliseconds. Writing from the listener directly would make the exchange as slow as the
 * disk.
 *
 * <p>So this class is the same producer-consumer shape as {@link
 * com.mwang.gateway.OrderGateway} itself, one layer further out: {@link #onMatch} only drops
 * trades onto a queue and returns, and a dedicated writer thread does the I/O.
 *
 * <h2>Flush policy</h2>
 *
 * <p>The writer flushes <strong>whenever it has caught up</strong> - that is, every time the
 * queue is empty after a write. Under a burst, writes batch into the buffer and cost one
 * syscall between them; the moment the burst ends, everything is pushed out. The worst-case
 * loss on a JVM crash is therefore however far behind the writer had fallen, rather than a
 * fixed timer's worth, and in a quiet market it is zero.
 *
 * <p>The alternatives were flushing per trade (durable but a syscall per record even under
 * load) and flushing on a timer (a fixed loss window regardless of how idle the system is).
 *
 * <p>Note what {@code flush()} actually guarantees: the bytes reach the <em>operating
 * system</em>. They survive this process crashing. They do not necessarily survive the
 * machine losing power, because the OS may still be holding them in its page cache - that
 * needs {@code FileChannel.force()}, which a real venue would use and pay for.
 *
 * <h2>Back-pressure</h2>
 *
 * <p>The queue is bounded and {@link #onMatch} uses {@code offer}, never {@code put}: a full
 * queue must not stall the matcher, since avoiding exactly that is why this class exists.
 * That makes dropping possible, which for an audit trail is a serious event - so drops are
 * counted, logged, and reported at close. They are never silent. The queue is sized so that
 * filling it means something is badly wrong, not that the market got busy.
 *
 * <p>This is the opposite trade-off from the gateway, where blocking the producer is the
 * correct behaviour. The difference is who is upstream: there it is a client that can wait,
 * here it is the matching engine, which cannot.
 */
public final class TradeLogger implements MatchListener, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(TradeLogger.class);

    /** Large enough that filling it indicates a failure, not a busy market. */
    private static final int CAPACITY = 100_000;

    private static final long SHUTDOWN_TIMEOUT_MILLIS = 5_000;

    private static final String HEADER = "timestamp,buyOrderId,sellOrderId,price,quantity";

    /**
     * Sentinel marking the end of the stream. Compared by identity, never by value, so a real
     * trade can never be mistaken for it. Its field values are arbitrary but must satisfy
     * {@link Trade}'s own validation, which is why the price and quantity are 1 rather than 0.
     */
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
     *             an existing file is appended to, and the header is written only when the
     *             file is new, so restarting does not corrupt an earlier run's records.
     */
    public TradeLogger(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /**
     * Opens the file and starts the writer thread.
     *
     * <p>Must be called before the gateway starts, or the first trades will have nowhere to go.
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

    /**
     * Queues this order's trades for writing. Runs on the matcher thread, so it must not block
     * and must not do I/O - it does neither.
     */
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
     * Drains the queue onto disk until the poison pill arrives.
     *
     * <p>{@code while (true)} with a sentinel, not {@code while (running)}: this is a consumer
     * with a backlog it has already accepted, and stopping early would discard audit records
     * that have been promised. Same reasoning as the gateway's matcher.
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

    /**
     * Writes one CSV row. No quoting or escaping is needed or performed: every column is a
     * number, so none can contain a comma, a quote or a newline. A format that admitted free
     * text would need a real CSV writer.
     */
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
     * Drains what is queued, flushes, and closes the file. Idempotent, and safe to call when
     * the logger was never started.
     *
     * <p>Must be called <strong>after</strong> the gateway has shut down. Close it first and
     * the matcher's final trades have nowhere to go.
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
