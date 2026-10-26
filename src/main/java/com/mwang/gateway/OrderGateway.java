package com.mwang.gateway;

import com.mwang.engine.MatchingEngine;
import com.mwang.exception.OrderException;
import com.mwang.exception.UnknownOrderException;
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
 * The boundary between many order sources and one matching engine.
 *
 * <p>Callers {@link #submit} and {@link #cancel} from any thread; one matcher thread drains the
 * queue and applies a {@link Command} at a time. The engine and book therefore need no locks,
 * and arrival order - so price-time priority - is decided once, by the queue.
 *
 * <p>The queue is bounded, so a full queue blocks the submitter rather than dropping an order
 * already accepted.
 *
 * <p>Start this before anything submits to it, and {@link #shutdown()} it before closing
 * whatever its listener writes to.
 */
public final class OrderGateway {

    private static final Logger logger = LoggerFactory.getLogger(OrderGateway.class);

    /** Bounded so that a stalled matcher shows up as back-pressure rather than as heap growth. */
    private static final int CAPACITY = 1000;

    /** How long {@link #shutdown()} waits at each step before giving up and saying so. */
    private static final long SHUTDOWN_TIMEOUT_MILLIS = 5_000;

    /** One instance suffices: it carries no state and is matched by type, not identity. */
    private static final Command SHUTDOWN = new Command.Shutdown();

    private final BlockingQueue<Command> inbound = new LinkedBlockingQueue<>(CAPACITY);
    private final MatchingEngine engine;
    private final MatchListener listener;

    /** Run totals. Written by the matcher thread, read by anyone, so not plain {@code long}s. */
    private final AtomicLong ordersProcessed = new AtomicLong();
    private final AtomicLong tradesExecuted = new AtomicLong();
    private final AtomicLong ordersRejected = new AtomicLong();
    private final AtomicLong ordersCancelled = new AtomicLong();
    private final AtomicLong cancelsMissed = new AtomicLong();

    private Thread matcher; // the single thread that applies commands to the engine
    private volatile boolean running;

    /** Creates a gateway that notifies nothing. */
    public OrderGateway(MatchingEngine engine) {
        this(engine, MatchListener.NONE);
    }

    /**
     * @param engine   receives every order, one at a time, on the matcher thread
     * @param listener notified after each order, also on the matcher thread. See
     *                 {@link MatchListener} for what that obliges it to do.
     * @throws NullPointerException if either argument is null
     */
    public OrderGateway(MatchingEngine engine, MatchListener listener) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Hands an order to the matcher. Safe to call from any thread. Blocks while the queue is
     * full, pacing the caller to whatever the matcher can absorb.
     *
     * @throws InterruptedException  if interrupted while waiting for queue space
     * @throws IllegalStateException if the gateway is not running
     * @throws NullPointerException  if {@code order} is null
     */
    public void submit(Order order) throws InterruptedException {
        if (!running) {
            throw new IllegalStateException("gateway is not running");
        }
        inbound.put(new Command.Submit(order));
    }

    /**
     * Asks the matcher to take a resting order off the book. Safe to call from any thread.
     *
     * <p>Returns nothing, since a result would mean waiting for the matcher. The outcome lands
     * in {@link #ordersCancelled()} or {@link #cancelsMissed()}; cancelling an order that
     * already filled is routine, not an error.
     *
     * @param orderId the order to remove
     * @throws InterruptedException  if interrupted while waiting for queue space
     * @throws IllegalStateException if the gateway is not running
     */
    public void cancel(long orderId) throws InterruptedException {
        if (!running) {
            throw new IllegalStateException("gateway is not running");
        }
        inbound.put(new Command.Cancel(orderId));
    }

    /**
     * Starts the matcher thread.
     *
     * @throws IllegalStateException if already started. A gateway is not restartable.
     */
    public synchronized void start() {
        if (matcher != null) {
            throw new IllegalStateException("gateway already started");
        }
        running = true;

        matcher = new Thread(this::matchLoop, "matcher");
        matcher.start();
        logger.info("Gateway started, queue capacity {}", CAPACITY);
    }

    /**
     * Drains the queue into the engine until the shutdown command arrives. {@code while (true)}
     * rather than {@code while (running)}, so an accepted backlog is never abandoned.
     *
     * <p>{@link Command} is sealed, so a command added later will not compile until handled here.
     */
    private void matchLoop() {
        try {
            matching: // name tag for the while loop
            while (true) {
                switch (inbound.take()) { // parks here when there is nothing to do
                    case Command.Submit(Order order) -> applySubmit(order);
                    case Command.Cancel(long orderId) -> applyCancel(orderId);
                    case Command.Shutdown ignored -> {
                        break matching; // break out of the entire while look
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Matches one order and tells the listener, whatever the outcome. */
    private void applySubmit(Order order) {
        List<Trade> trades = List.of();
        try {
            // submit the order to the engine to get matching trades
            trades = engine.submit(order);
        } catch (OrderException e) {
            // A bad order must not kill the matcher. Anything that is not an
            // OrderException is a genuine fault and is allowed to propagate.
            ordersRejected.incrementAndGet();
            logger.warn("Rejected order {}: {}", order.getOrderId(), e.getMessage());
        }
        ordersProcessed.incrementAndGet();
        tradesExecuted.addAndGet(trades.size());
        logTrades(trades);
        notifyListener(order, trades);
    }

    /**
     * Removes one resting order. {@link UnknownOrderException} logs at {@code DEBUG}, not
     * {@code WARN}: a quoting strategy loses that race constantly.
     */
    private void applyCancel(long orderId) {
        try {
            engine.cancel(orderId);
            ordersCancelled.incrementAndGet();
        } catch (UnknownOrderException e) {
            cancelsMissed.incrementAndGet();
            logger.debug("Cancel for order {} arrived after it left the book", orderId);
        }
    }

    /**
     * Logs each trade at {@code DEBUG}, enabled with {@code -DLOG_LEVEL=DEBUG}. The level check
     * guards the loop: SLF4J defers formatting on its own but still iterates the list.
     */
    private void logTrades(List<Trade> trades) {
        if (!logger.isDebugEnabled()) {
            return;
        }
        for (Trade trade : trades) {
            logger.debug("Traded {} @ {} (buy {} / sell {})",
                    trade.quantity(), trade.price(), trade.buyOrderId(), trade.sellOrderId());
        }
    }

    /**
     * Runs the listener, absorbing anything it throws. It runs on the matcher thread, so an
     * escaping exception would kill the matcher and queue every later order forever.
     */
    private void notifyListener(Order order, List<Trade> trades) {
        try {
            listener.onMatch(order, trades);
        } catch (RuntimeException e) {
            logger.error("Listener failed on order {}", order.getOrderId(), e);
        }
    }

    /**
     * Drains what is already queued, stops the matcher and waits for its thread. Idempotent, and
     * safe when never started.
     *
     * <p>Queues the sentinel with {@code offer} and a timeout, never {@code put}: against a
     * wedged matcher a {@code put} would wait forever and never reach the {@code join}.
     * Interruption is the fallback, last because it abandons the backlog.
     */
    public synchronized void shutdown() {
        if (!running) {
            return;
        }
        running = false;

        try {
            // interrupt if the shutdown command can't be added
            if (!inbound.offer(SHUTDOWN, SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                logger.error("Could not queue the shutdown command within {}ms; "
                        + "interrupting the matcher", SHUTDOWN_TIMEOUT_MILLIS);
                matcher.interrupt();
            }
            matcher.join(SHUTDOWN_TIMEOUT_MILLIS);
            if (matcher.isAlive()) {
                logger.error("Matcher did not stop within {}ms", SHUTDOWN_TIMEOUT_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        logger.info("Gateway stopped: {} orders processed, {} trades, {} rejected, "
                        + "{} cancelled, {} cancels missed",
                ordersProcessed.get(), tradesExecuted.get(), ordersRejected.get(),
                ordersCancelled.get(), cancelsMissed.get());
    }

    /** @return how many orders the matcher has handed to the engine, rejections included */
    public long ordersProcessed() {
        return ordersProcessed.get();
    }

    /** @return how many trades the engine has produced over this run */
    public long tradesExecuted() {
        return tradesExecuted.get();
    }

    /** @return how many orders the engine refused; always 0 for well-formed flow */
    public long ordersRejected() {
        return ordersRejected.get();
    }

    /** @return how many cancels removed a resting order */
    public long ordersCancelled() {
        return ordersCancelled.get();
    }

    /** @return how many cancels named an order already gone from the book. Routine, not a fault. */
    public long cancelsMissed() {
        return cancelsMissed.get();
    }
}
