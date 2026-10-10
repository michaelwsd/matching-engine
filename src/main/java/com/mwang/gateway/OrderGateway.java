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

// without this, two concurrent orders could match the same resting order at once
public class OrderGateway {
    private static final int CAPACITY = 1000;
    private final BlockingQueue<Order> inbound = new LinkedBlockingQueue<>(CAPACITY);
    private final MatchingEngine engine;
    private final MatchListener listener;

    private Thread matcher; // a single thread that submits orders to the engine
    private volatile boolean running;
    private static final Order POISON = new Order(
            0, -1, 0, 1, Side.BUY, OrderType.MARKET, 0
    );
    // INFO: trades happening, WARN: rejections, ERROR: something is broken
    private static final Logger logger = LoggerFactory.getLogger(OrderGateway.class);

    public OrderGateway(MatchingEngine engine) {
        this(engine, MatchListener.NONE);
    }

    /**
     * @param engine   receives every order, one at a time, on the matcher thread
     * @param listener notified after each order, also on the matcher thread. See
     *                 {@link MatchListener} for what that obliges it to do.
     */
    public OrderGateway(MatchingEngine engine, MatchListener listener) {
        // we can take any engine and submit order to this engine
        this.engine = Objects.requireNonNull(engine, "engine");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public void submit(Order order) throws InterruptedException {
        if (!running) {
            throw new IllegalStateException("gateway is not running");
        }
        // inserts the order to the queue, if queue is full then wait
        // if interrupted while waiting then throws exception
        inbound.put(order);
    }

    // creates a matcher thread that processes all the orders one at a time
    public void start() {
        // check if a matcher thread is already created
        if (matcher != null) {
            throw new IllegalStateException("gateway already started");
        }
        running = true;

        // create new matcher thread to process the next order
        matcher = new Thread(this::matchLoop, "matcher");
        matcher.start();
    }

    private void matchLoop() {
        try {
            while (true) {
                Order order = inbound.take(); // if no orders, just wait
                if (order == POISON) break; // if the gateway has shutdown, exit the loop
                List<Trade> trades = List.of();
                try {
                    trades = engine.submit(order);
                } catch (OrderException e) { // catch the errors so the matcher won't crash
                    logger.warn("Rejected order {}: {}", order.getOrderId(), e.getMessage());
                }
                notifyListener(order, trades);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs the listener, absorbing anything it throws.
     *
     * <p>The listener is supplied by a caller and runs on the matcher thread, so an unchecked
     * exception from it would otherwise propagate out of {@link #matchLoop()} and kill the
     * matcher - silently, since nothing joins that thread until shutdown. Every order after
     * that point would queue up and never be processed. A broken listener must not be able to
     * take the exchange down with it.
     */
    private void notifyListener(Order order, List<Trade> trades) {
        try {
            listener.onMatch(order, trades);
        } catch (RuntimeException e) {
            logger.error("Listener failed on order {}", order.getOrderId(), e);
        }
    }

    public void shutdown() {
        if (!running) return;
        running = false;

        try {
            inbound.put(POISON);
            matcher.join(5000); // wait up to 5 seconds for the matcher thread to finish

            if (matcher.isAlive()) {
                logger.error("matcher did not stop within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
