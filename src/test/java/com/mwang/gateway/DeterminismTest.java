package com.mwang.gateway;

import com.mwang.book.OrderBook;
import com.mwang.engine.MatchingEngine;
import com.mwang.engine.PriceTimeMatchingEngine;
import com.mwang.model.Order;
import com.mwang.model.OrderType;
import com.mwang.model.Side;
import com.mwang.model.Trade;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that putting concurrent order intake in front of the engine has not changed what the
 * engine does.
 *
 * <p>Comparing a concurrent run against a sequential one cannot work: arrival order varies
 * between runs, and a different arrival order legitimately produces different trades.
 *
 * <p>So the scenario is built so the outcome cannot vary. Every order is the same price and
 * size and no two share an account, so each one either rests or fully matches exactly one
 * resting order. The trade count, filled quantity and final book are fixed by the scenario.
 */
class DeterminismTest {

    private static final int PAIRS = 500;
    private static final long PRICE = 10_000;
    private static final int QUANTITY = 10;

    @Test
    void shouldMatchTheSameVolumeConcurrentlyAsSequentially() throws Exception {
        Result sequential = runSequentially();
        Result concurrent = runConcurrently();

        assertThat(concurrent.tradeCount)
                .as("concurrent intake must not change how many trades occur")
                .isEqualTo(sequential.tradeCount);

        assertThat(concurrent.tradedQuantity)
                .as("concurrent intake must not change how much is filled")
                .isEqualTo(sequential.tradedQuantity);
    }

    @Test
    void shouldFillEveryOrderAndLeaveTheBookEmpty() throws Exception {
        Result concurrent = runConcurrently();

        assertThat(concurrent.tradeCount).isEqualTo(PAIRS);
        assertThat(concurrent.tradedQuantity).isEqualTo((long) PAIRS * QUANTITY);
        assertThat(concurrent.bestBid).isEqualTo(OrderBook.NO_PRICE);
        assertThat(concurrent.bestAsk).isEqualTo(OrderBook.NO_PRICE);
    }

    /**
     * Conservation holds under any interleaving, so this assertion would survive a messier
     * scenario: the total filled must be twice the traded volume.
     */
    @Test
    void shouldNeitherLoseNorInventQuantity() throws Exception {
        Result concurrent = runConcurrently();

        long filledAcrossOrders = concurrent.orders.stream()
                .mapToLong(o -> o.getOriginalQuantity() - o.getRemainingQuantity())
                .sum();

        assertThat(filledAcrossOrders).isEqualTo(2 * concurrent.tradedQuantity);
    }

    /** Concurrency bugs are probabilistic; one green run proves very little. */
    @RepeatedTest(5)
    void shouldBeStableAcrossRepeatedRuns() throws Exception {
        Result concurrent = runConcurrently();

        assertThat(concurrent.tradeCount).isEqualTo(PAIRS);
        assertThat(concurrent.tradedQuantity).isEqualTo((long) PAIRS * QUANTITY);
    }

    // --- runs ---

    private Result runSequentially() {
        OrderBook book = new OrderBook();
        MatchingEngine engine = new PriceTimeMatchingEngine(book);

        List<Order> orders = buildOrders();
        List<Trade> trades = new ArrayList<>();
        for (Order order : orders) {
            trades.addAll(engine.submit(order));
        }
        return new Result(orders, trades, book);
    }

    private Result runConcurrently() throws Exception {
        OrderBook book = new OrderBook();
        MatchingEngine engine = new PriceTimeMatchingEngine(book);

        // the gateway discards trades, so wrap the engine to capture them
        List<Trade> trades = Collections.synchronizedList(new ArrayList<>());
        MatchingEngine recording = order -> {
            List<Trade> produced = engine.submit(order);
            trades.addAll(produced);
            return produced;
        };

        List<Order> orders = buildOrders();
        OrderGateway gateway = new OrderGateway(recording);
        gateway.start();

        try (var producers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Order order : orders) {
                producers.submit(() -> {
                    gateway.submit(order);
                    return null;
                });
            }
        }   // close() waits for every producer to finish submitting

        gateway.shutdown();     // drains the queue, then joins the matcher

        return new Result(orders, trades, book);
    }

    // --- scenario ---

    /**
     * Equal buys and sells, one price, one size, every account distinct, shuffled with a fixed
     * seed. A fresh list every call, since {@link Order} is mutable and reusing them would start
     * the next run with already-filled quantities.
     */
    private List<Order> buildOrders() {
        List<Order> orders = new ArrayList<>(PAIRS * 2);
        long id = 1;
        for (int i = 0; i < PAIRS; i++) {
            orders.add(order(id, 1_000 + i, Side.BUY));
            id++;
            orders.add(order(id, 2_000 + i, Side.SELL));
            id++;
        }
        Collections.shuffle(orders, new Random(42));
        return orders;
    }

    private Order order(long orderId, long accountId, Side side) {
        return new Order(accountId, orderId, PRICE, QUANTITY, side, OrderType.LIMIT, orderId);
    }

    // --- result ---

    private record Result(List<Order> orders, int tradeCount, long tradedQuantity,
                          long bestBid, long bestAsk) {

        Result(List<Order> orders, List<Trade> trades, OrderBook book) {
            this(orders,
                    trades.size(),
                    trades.stream().mapToLong(Trade::quantity).sum(),
                    book.bestBid(),
                    book.bestAsk());
        }
    }
}
