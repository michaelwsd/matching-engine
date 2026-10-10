package com.mwang.gateway;

import com.mwang.engine.MatchingEngine;
import com.mwang.exception.OrderException;
import com.mwang.model.Order;
import com.mwang.model.OrderType;
import com.mwang.model.Side;
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
 * <p>Callers {@link #submit} from any thread; a single matcher thread drains the queue and
 * feeds the engine one order at a time. That is the whole design: the engine and book need no
 * locks because only one thread ever touches them, deadlock is structurally impossible, and
 * price-time priority stays meaningful because arrival order is decided once, by the queue.
 * Without this, two concurrent orders could match against the same resting order at once.
 *
 * <p>The queue is bounded. A full queue blocks the submitting thread, which is the correct
 * back-pressure here - a client can wait, and silently dropping an accepted order cannot be
 * walked back.
 *
 * <p>Start this <strong>before</strong> anything submits to it, and {@link #shutdown()} it
 * before closing whatever its listener writes to.
 */
public final class OrderGateway {

    private static final Logger logger = LoggerFactory.getLogger(OrderGateway.class);

    /** Bounded so that a stalled matcher shows up as back-pressure rather than as heap growth. */
    private static final int CAPACITY = 1000;

    /** How long {@link #shutdown()} waits at each step before giving up and saying so. */
    private static final long SHUTDOWN_TIMEOUT_MILLIS = 5_000;

    /**
     * End-of-stream marker, compared by identity. Queued rather than signalled with a flag, so
     * it takes its place behind every order already accepted and nothing acknowledged is lost.
     */
    private static final Order POISON = new Order(
            0, -1, 0, 1, Side.BUY, OrderType.MARKET, 0
    );

    private final BlockingQueue<Order> inbound = new LinkedBlockingQueue<>(CAPACITY);
    private final MatchingEngine engine;
    private final MatchListener listener;

    /**
     * Run totals. Written only by the matcher thread but read by whoever calls
     * {@link #shutdown()} or the accessors, so they cannot be plain {@code long}s.
     */
    private final AtomicLong ordersProcessed = new AtomicLong();
    private final AtomicLong tradesExecuted = new AtomicLong();
    private final AtomicLong ordersRejected = new AtomicLong();

    private Thread matcher; // the single thread that submits orders to the engine
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
     * Hands an order to the matcher. Safe to call from any thread.
     *
     * <p>Blocks while the queue is full, which paces the caller to whatever the matcher can
     * actually absorb.
     *
     * @throws InterruptedException  if interrupted while waiting for queue space
     * @throws IllegalStateException if the gateway is not running
     */
    public void submit(Order order) throws InterruptedException {
        if (!running) {
            throw new IllegalStateException("gateway is not running");
        }
        inbound.put(order);
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
     * Drains the queue into the engine until the poison pill arrives.
     *
     * <p>{@code while (true)} rather than {@code while (running)}: this is a consumer with a
     * backlog it has already accepted, and exiting on a flag would abandon it.
     */
    private void matchLoop() {
        try {
            while (true) {
                Order order = inbound.take(); // parks here when there is nothing to do
                if (order == POISON) {
                    break;
                }
                List<Trade> trades = List.of();
                try {
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Logs each trade at {@code DEBUG}, not {@code INFO}.
     *
     * <p>Two reasons. The demo executes around 34 trades a second against a view that redraws
     * 5 times a second, so per-trade {@code INFO} lines would scroll away the thing they are
     * meant to annotate. And Logback's appenders are synchronous, so a line here puts console
     * and file I/O on the matcher's hot path - the very cost {@link com.mwang.audit.TradeLogger}
     * exists to move off this thread. The permanent record of a trade is that CSV audit trail;
     * this is for diagnosing one specific run, enabled with {@code -DLOG_LEVEL=DEBUG}.
     *
     * <p>The level check guards the loop, not the formatting: SLF4J already defers formatting
     * for a disabled call, but it cannot skip iterating the list.
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
     * Runs the listener, absorbing anything it throws. The listener is caller-supplied and runs
     * on the matcher thread, so an unchecked exception from it would otherwise kill the matcher
     * silently and every later order would queue up forever.
     */
    private void notifyListener(Order order, List<Trade> trades) {
        try {
            listener.onMatch(order, trades);
        } catch (RuntimeException e) {
            logger.error("Listener failed on order {}", order.getOrderId(), e);
        }
    }

    /**
     * Drains what is already queued, stops the matcher and waits for its thread. Idempotent,
     * and safe when never started.
     *
     * <p>Queues the pill with {@code offer} and a timeout rather than {@code put}: a full queue
     * plus a wedged matcher would make a blocking {@code put} wait forever, and the
     * {@code join} below - the thing meant to bound this wait - would never be reached.
     * Interruption is the fallback, and it abandons the backlog, which is why it is last resort
     * rather than the first move.
     */
    public synchronized void shutdown() {
        if (!running) {
            return;
        }
        running = false;

        try {
            if (!inbound.offer(POISON, SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                logger.error("Could not queue the poison pill within {}ms; interrupting the matcher",
                        SHUTDOWN_TIMEOUT_MILLIS);
                matcher.interrupt();
            }
            matcher.join(SHUTDOWN_TIMEOUT_MILLIS);
            if (matcher.isAlive()) {
                logger.error("Matcher did not stop within {}ms", SHUTDOWN_TIMEOUT_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        logger.info("Gateway stopped: {} orders processed, {} trades, {} rejected",
                ordersProcessed.get(), tradesExecuted.get(), ordersRejected.get());
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
}
