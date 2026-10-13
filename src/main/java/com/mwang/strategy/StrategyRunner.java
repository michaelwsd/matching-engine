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
 * Runs a {@link Strategy} on its own thread, fed by a queue of fills.
 *
 * <p><strong>Why this class exists.</strong> A {@link MatchListener} runs on the matcher thread.
 * A strategy quoting from there would call {@code gateway.submit()} on the one thread that
 * drains the gateway's queue - adding two quotes per order it removes. The queue fills,
 * {@code put()} blocks the matcher, and nothing else can free a slot. It waits for itself,
 * permanently.
 *
 * <p>So {@link #onMatch} does one thing: {@code offer} onto this runner's own queue and return.
 * The strategy thread drains it and submits from there, so the thread that blocks on a full
 * gateway queue is this one - the matcher keeps draining and the block clears. A chain instead
 * of a cycle: back-pressure rather than deadlock.
 *
 * <p>Shut down <em>producer, then this, then the gateway</em>. This runner produces orders as
 * well as consuming fills, and the producer role wins: stopping it while the gateway still runs
 * lets its backlog drain and its last quotes land, where the reverse order takes an
 * {@code IllegalStateException} per pending fill. The cost is that trades from the gateway's own
 * final drain never reach the strategy - self-limiting, since the quotes that would have caused
 * them were never posted.
 */
public final class StrategyRunner implements MatchListener {

    private static final Logger logger = LoggerFactory.getLogger(StrategyRunner.class);

    /**
     * Generous, because a dropped fill is worse here than in the audit log: it leaves the
     * strategy's position permanently wrong, where a lost log line only loses a record.
     */
    private static final int CAPACITY = 100_000;

    private static final long SHUTDOWN_TIMEOUT_MILLIS = 5_000;

    /** One instance suffices: it carries no state and is matched by type, not identity. */
    private static final Command SHUTDOWN = new Command.Shutdown();

    private final Strategy strategy;
    private final BlockingQueue<Command> pending = new LinkedBlockingQueue<>(CAPACITY);

    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    private Thread worker;
    private volatile boolean running;

    /** @throws NullPointerException if {@code strategy} is null */
    public StrategyRunner(Strategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
    }

    /**
     * Hands the trades to the strategy thread. Runs on the <em>matcher</em> thread, so it does
     * nothing but {@code offer} - never {@code put}, which would restore the very deadlock this
     * class removes.
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
     * Drains fills to the strategy until the shutdown command arrives.
     *
     * <p>{@code while (true)} rather than {@code while (running)}: these fills are owed to the
     * strategy's position, so abandoning them would leave the final P&amp;L wrong.
     */
    private void runLoop() {
        try {
            delivering:
            while (true) {
                switch (pending.take()) {
                    case Command.Fill(Trade fill) -> deliver(fill);
                    case Command.Shutdown ignored -> {
                        break delivering;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs the strategy, absorbing anything it throws. It is caller-supplied code owning this
     * thread, so an escaping exception would kill the thread and leave the simulation quietly
     * not trading for the rest of the run.
     */
    private void deliver(Trade fill) {
        try {
            strategy.onFill(fill);
            delivered.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("strategy interrupted", e);
        } catch (RuntimeException e) {
            logger.error("Strategy failed on trade {}/{}", fill.buyOrderId(), fill.sellOrderId(), e);
        }
    }

    /**
     * Stops the strategy thread once its backlog has drained. Idempotent, and safe when never
     * started.
     *
     * <p>Offers the sentinel with a timeout, never {@code put} - see
     * {@link com.mwang.gateway.OrderGateway#shutdown()}.
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

    /** @return how many fills were dropped because the queue was full. Any drop is a fault. */
    public long dropped() {
        return dropped.get();
    }
}
