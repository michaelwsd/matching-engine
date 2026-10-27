package com.mwang.strategy;

import com.mwang.gateway.MatchListener;
import com.mwang.model.Order;
import com.mwang.model.Trade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs a {@link Strategy} on its own thread, fed by a queue of fills and requote ticks.
 *
 * <p>A {@link MatchListener} runs on the matcher thread, and a strategy quoting from there would
 * submit to the very queue that thread drains - two orders in for every one out, until the queue
 * fills and the matcher blocks waiting on itself. So {@link #onMatch} and {@link #requote()} only
 * enqueue, and the strategy thread does the submitting: back-pressure instead of deadlock. One
 * thread owning the strategy also means its position needs no locking.
 *
 * <p>Shut down <em>producer, then this, then the gateway</em>. This runner is itself a producer,
 * so stopping it first lets its last quotes land; the reverse order throws per pending fill.
 */
public final class StrategyRunner implements MatchListener {

    private static final Logger logger = LoggerFactory.getLogger(StrategyRunner.class);

    /** Generous: a dropped fill leaves the position permanently wrong, not just unrecorded. */
    private static final int CAPACITY = 100_000;

    private static final long SHUTDOWN_TIMEOUT_MILLIS = 5_000;

    /** One instance each: they carry no state and are matched by type, not identity. */
    private static final Command SHUTDOWN = new Command.Shutdown();
    private static final Command REQUOTE = new Command.Requote();

    private final Strategy strategy;
    private final BlockingQueue<Command> pending = new LinkedBlockingQueue<>(CAPACITY);

    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong requotesSkipped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    private Thread worker;
    private volatile boolean running;

    /** @throws NullPointerException if {@code strategy} is null */
    public StrategyRunner(Strategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
    }

    /**
     * Hands the trades to the strategy thread. Runs on the matcher thread, so it only
     * {@code offer}s - a {@code put} here would restore the deadlock this class removes.
     */
    @Override
    public void onMatch(Order order, List<Trade> trades) {
        for (Trade trade : trades) {
            if (!pending.offer(new Command.Fill(trade))) {
                // Logged once: a full queue produces one of these per trade otherwise.
                if (dropped.incrementAndGet() == 1) {
                    logger.error("Strategy queue is full ({}); fills are being dropped and the "
                            + "strategy's position will be wrong", CAPACITY);
                }
            }
        }
    }

    /**
     * Asks the strategy to replace its quotes, from any thread. The requote scheduler's entire
     * body is a call to this.
     *
     * <p>A tick that will not fit is dropped, and that is not a fault: a stale tick is worthless.
     */
    public void requote() {
        if (!pending.offer(REQUOTE)) {
            requotesSkipped.incrementAndGet();
        }
    }

    /**
     * Starts the strategy thread.
     *
     * @throws IllegalStateException if already started. A runner is not restartable.
     */
    public synchronized void start() {
        if (worker != null) {
            throw new IllegalStateException("strategy runner already started");
        }
        running = true;

        worker = new Thread(this::runLoop, "strategy");
        worker.start();
        logger.info("Strategy started, fill queue capacity {}", CAPACITY);
    }

    /**
     * Drains commands until the shutdown command arrives. {@code while (true)} rather than
     * {@code while (running)}, because queued fills are owed to the strategy's position.
     *
     * <p>Everything but shutdown goes to {@link #deliver}, which holds the exhaustive switch.
     */
    private void runLoop() {
        try {
            while (true) {
                Command command = pending.take();
                if (command instanceof Command.Shutdown) {
                    break;
                }
                deliver(command);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs the strategy, absorbing anything it throws. An escaping exception would kill this
     * thread and leave the simulation quietly not trading for the rest of the run.
     */
    private void deliver(Command command) {
        try {
            switch (command) {
                // notify a fill
                case Command.Fill(Trade fill) -> {
                    strategy.onFill(fill);
                    // Counted after the call returns, so a throw is not counted as delivered.
                    delivered.incrementAndGet();
                }
                case Command.Requote ignored -> strategy.onRequote();
                case Command.Shutdown ignored -> throw new AssertionError("runLoop handles shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("strategy interrupted", e);
        } catch (RuntimeException e) {
            // Counted as well as logged: a strategy failing every call is otherwise invisible
            // to a caller, which cannot tell it apart from one that simply did nothing.
            failed.incrementAndGet();
            // A record's toString names the command, so this needs no per-case message.
            logger.error("Strategy failed on {}", command, e);
        }
    }

    /**
     * Stops the strategy thread once its backlog has drained. Idempotent, and safe when never
     * started. Offers the sentinel with a timeout rather than {@code put}, so a wedged strategy
     * cannot block the caller forever.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;

        try {
            if (!pending.offer(SHUTDOWN, SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                logger.error("Could not queue the shutdown command within {}ms; interrupting the strategy",
                        SHUTDOWN_TIMEOUT_MILLIS);
                worker.interrupt();
            }
            worker.join(SHUTDOWN_TIMEOUT_MILLIS);
            if (worker.isAlive()) {
                logger.error("Strategy did not stop within {}ms", SHUTDOWN_TIMEOUT_MILLIS);
                worker.interrupt();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (dropped.get() > 0) {
            logger.error("Strategy dropped {} fills; its position is not trustworthy", dropped.get());
        }
        logger.info("Strategy stopped after {} fills", delivered.get());
    }

    /** @return how many fills reached the strategy */
    public long delivered() {
        return delivered.get();
    }

    /** @return fills dropped because the queue was full. Any drop is a fault. */
    public long dropped() {
        return dropped.get();
    }

    /** @return requote ticks dropped because the strategy was behind. Not a fault. */
    public long requotesSkipped() {
        return requotesSkipped.get();
    }

    /** @return calls the strategy threw out of. Any failure is a fault; the log says why. */
    public long failures() {
        return failed.get();
    }
}
