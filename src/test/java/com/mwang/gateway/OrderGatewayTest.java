package com.mwang.gateway;

import com.mwang.engine.MatchingEngine;
import com.mwang.exception.InvalidOrderException;
import com.mwang.model.Order;
import com.mwang.model.OrderType;
import com.mwang.model.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;

class OrderGatewayTest {

    private OrderGateway gateway;

    /**
     * Every test must stop its gateway, or the matcher thread outlives the test and the
     * build hangs. Shutdown is idempotent, so this is safe even if the test stopped it.
     */
    @AfterEach
    void stopGateway() {
        if (gateway != null) {
            gateway.shutdown();
        }
    }

    @Test
    void shouldDeliverASubmittedOrderToTheEngine() throws Exception {
        CountDownLatch matched = new CountDownLatch(1); // counts down to 0
        // starts a thread with a fake engine and a fake submit method
        // the thread is responsible for submitting orders using the engine
        startWith(order -> {
            // decreases count by 1
            matched.countDown();
            return List.of();
        });

        // submits an order to get processed by match loop thread
        gateway.submit(buy(1));

        // awaits returns true if matched reaches 0 before 2 seconds
        assertThat(matched.await(2, TimeUnit.SECONDS))
                .as("the matcher should have processed the order")
                .isTrue();
    }

    /**
     * Shutdown must drain what has already been accepted. The engine is deliberately slow
     * so the queue backs up; if shutdown stopped the matcher early, orders would be lost.
     */
    @Test
    void shouldProcessEveryQueuedOrderBeforeShuttingDown() throws Exception {
        AtomicInteger matched = new AtomicInteger(); // counts up to unbounded
        startWith(order -> {
            sleep(2);
            matched.incrementAndGet(); // reassigning a variable is forbidden in a lambda
            return List.of();
        });

        int submitted = 50;
        for (int i = 1; i <= submitted; i++) {
            gateway.submit(buy(i));
        }

        gateway.shutdown();     // blocks until the matcher has finished

        assertThat(matched).hasValue(submitted);
    }

    @Test
    void startTwiceShouldThrow() throws Exception {
        startWith(order -> {
            return List.of();
        });

        assertThatThrownBy(() -> gateway.start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already started");
    }

    @Test
    void shutdownBeforeStartShouldReturn() {
        gateway = new OrderGateway(order -> { return List.of(); });
        assertThatCode(() -> gateway.shutdown()).doesNotThrowAnyException();
    }

    @Test
    void shutdownTwiceShouldReturn() {
        startWith(order -> {
            return List.of();
        });

        gateway.shutdown();
        assertThatCode(() -> gateway.shutdown()).doesNotThrowAnyException();
    }

    @Test
    void rejectedOrderDoesntKillMatcher() throws Exception {
        AtomicInteger matched = new AtomicInteger();

        startWith(order -> {
            if (order.getOrderId() == 2) throw new InvalidOrderException("wrong order");
            matched.incrementAndGet();
            return List.of();
        });

        // the second order should be rejected
        gateway.submit(buy(1));
        gateway.submit(buy(2));
        gateway.submit(buy(3));

        gateway.shutdown();
        assertThat(matched).hasValue(2);
    }

    // --- fixtures ---

    private void startWith(MatchingEngine engine) {
        gateway = new OrderGateway(engine);
        gateway.start();
    }

    private static Order buy(long orderId) {
        return new Order(1, orderId, 10000, 10, Side.BUY, OrderType.LIMIT, orderId);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
