package com.mwang.gateway;

import com.mwang.engine.MatchingEngine;
import com.mwang.exception.OrderException;
import com.mwang.model.Order;
import com.mwang.model.OrderType;
import com.mwang.model.Side;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class OrderGateway {
    private static final int CAPACITY = 1000;
    private final BlockingQueue<Order> inbound = new LinkedBlockingQueue<>(CAPACITY);
    private final MatchingEngine engine;

    private Thread matcher; // a single thread that submits orders to the engine
    private volatile boolean running;
    private static final Order POISON = new Order(
            0, -1, 0, 1, Side.BUY, OrderType.MARKET, 0
    );

    public OrderGateway(MatchingEngine engine) {
        // we can take any engine and submit order to this engine
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public void submit(Order order) throws InterruptedException {
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
                try {
                    engine.submit(order);
                } catch (OrderException e) { // catch the errors so the matcher won't crash 
                    System.err.println("Rejected order " + order.getOrderId() + ": " + e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void shutdown() {
        if (!running) return;
        running = false;

        try {
            inbound.put(POISON);
            matcher.join(5000); // wait up to 5 seconds for the matcher thread to finish

            if (matcher.isAlive()) {
                System.err.println("matcher did not stop within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
