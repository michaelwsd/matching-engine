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
 * <p>A {@link MatchListener} runs on the matcher thread, so the exchange waits for whatever it
 * does - and a file write takes orders of magnitude longer than a match. So {@link #onMatch}
 * only queues and returns, and a writer thread does the I/O: the same producer-consumer shape
 * as {@link com.mwang.gateway.OrderGateway}, one layer out.
 *
 * <p><strong>Flush policy:</strong> the writer flushes whenever the queue empties, so writes
 * batch under a burst and are pushed out the moment it ends. {@code flush()} reaches the
 * operating system, not the disk, so this survives a process crash but not a power cut -
 * {@code FileChannel.force()} would be the next step.
 *
 * <p><strong>Back-pressure:</strong> {@code offer}, never {@code put}, because a full queue
 * must not stall the matcher. Drops are possible, and are counted, logged and reported at
 * close. The opposite trade-off from the gateway, because the caller differs: there a client
 * who can wait, here the matching engine, which cannot.
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

    /** One instance suffices: it carries no state and is matched by type, not identity. */
    private static final Command SHUTDOWN = new Command.Shutdown();

    private final Path file;
    private final BlockingQueue<Command> pending = new LinkedBlockingQueue<>(CAPACITY);
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    private BufferedWriter out;
    private Thread writer;
    private volatile boolean running;

    /**
     * @param file where to append trades. Parent directories are created on {@link #start()};
     *             an existing file is appended to and the header written only when new, so a
     *             restart cannot corrupt an earlier run's records.
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
            if (!pending.offer(new Command.Append(trade))) {
                // offer() rather than put(): blocking here would stall the whole exchange.
                if (dropped.incrementAndGet() == 1) {
                    logger.error("Trade log queue is full ({}); audit records are being dropped",
                            CAPACITY);
                }
            }
        }
    }

    /**
     * Drains the queue onto disk until the shutdown command arrives. A queued sentinel rather
     * than a flag, because this is a consumer with a backlog it has already promised to write.
     */
    private void writeLoop() {
        try {
            writing:
            while (true) {
                switch (pending.take()) {
                    case Command.Append(Trade trade) -> {
                        write(trade);
                        if (pending.isEmpty()) {
                            // Caught up: make everything written so far durable. Under load
                            // this is skipped and the writes batch in the buffer instead.
                            flush();
                        }
                    }
                    case Command.Shutdown ignored -> {
                        break writing;
                    }
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
            // offer with a timeout, never put - see OrderGateway.shutdown().
            if (!pending.offer(SHUTDOWN, SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                logger.error("Could not queue the shutdown command; interrupting the writer");
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
