package com.mwang.strategy;

import com.mwang.engine.MatchingEngine;
import com.mwang.gateway.OrderGateway;
import com.mwang.model.Order;
import com.mwang.model.OrderType;
import com.mwang.model.Side;
import com.mwang.model.Trade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrategyRunnerTest {

    /** Distinguishes the strategy's own quotes from external flow in the test engine. */
    private static final long STRATEGY_ACCOUNT = 9;

    private StrategyRunner runner;
    private OrderGateway gateway;

    @AfterEach
    void stopEverything() {
        if (runner != null) {
            runner.stop();          // producer first, as the class documents
        }
        if (gateway != null) {
            gateway.shutdown();
        }
    }

    // --- the point of the class ---

    /**
     * The assertion that defines this class. If the strategy ran on the matcher thread, a
     * strategy that submits would be feeding the queue it drains.
     */
    @Test
    void shouldRunTheStrategyOnItsOwnThreadNotTheMatcherThread() throws Exception {
        AtomicReference<String> strategyThread = new AtomicReference<>();
        AtomicReference<String> listenerThread = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);

        runner = new StrategyRunner(fill -> {
            strategyThread.set(Thread.currentThread().getName());
            delivered.countDown();
        });
        runner.start();

        // onMatch is what the gateway calls, so record the thread it runs on too.
        Thread.currentThread().setName("matcher");
        listenerThread.set(Thread.currentThread().getName());
        runner.onMatch(buy(1), List.of(trade(1, 2)));

        assertThat(delivered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(strategyThread.get()).isEqualTo("strategy");
        assertThat(strategyThread.get()).isNotEqualTo(listenerThread.get());
    }

    /**
     * The deadlock regression guard. Every order trades and the strategy answers each fill with
     * two quotes, filling the gateway's 1000-deep queue. Wired directly as a
     * {@code MatchListener} this wedges the matcher; through the runner it must keep draining.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void shouldNotWedgeTheMatcherWhenTheStrategyQuotesOnEveryFill() throws Exception {
        AtomicLong nextQuoteId = new AtomicLong(1_000_000);
        AtomicLong quotesPosted = new AtomicLong();

        // Declared first so the strategy lambda can close over the array slot.
        OrderGateway[] box = new OrderGateway[1];
        runner = new StrategyRunner(fill -> {
            box[0].submit(quote(nextQuoteId.getAndIncrement(), Side.BUY));
            box[0].submit(quote(nextQuoteId.getAndIncrement(), Side.SELL));
            quotesPosted.addAndGet(2);
        });

        // External orders fill; the strategy's own quotes rest. Amplification is therefore
        // exactly two commands per external order, and bounded - a fixture that also traded
        // the quotes would double the fills every generation and blow up for its own reasons.
        MatchingEngine engine = order -> order.getAccountId() == STRATEGY_ACCOUNT
                ? List.of()
                : List.of(trade(order.getOrderId(), order.getOrderId()));
        gateway = new OrderGateway(engine, runner);
        box[0] = gateway;

        gateway.start();
        runner.start();

        // 1200 orders: more than the queue's capacity even before the quotes they generate.
        for (long id = 1; id <= 1_200; id++) {
            gateway.submit(buy(id));
        }

        // The matcher must still be making progress, not parked in put() on its own queue.
        long seen = gateway.ordersProcessed();
        Thread.sleep(200);
        assertThat(gateway.ordersProcessed())
                .as("a wedged matcher would stop counting entirely")
                .isGreaterThan(seen);

        runner.stop();          // producer first
        gateway.shutdown();

        assertThat(quotesPosted.get())
                .as("the strategy should have quoted throughout")
                .isPositive();
        assertThat(gateway.ordersProcessed())
                .as("the matcher drained past the queue's capacity, so it never blocked on itself")
                .isGreaterThan(1_000);
        assertThat(runner.dropped()).isZero();
    }

    // --- the listener side ---

    /** onMatch runs on the matcher thread, so it must never block, even with nowhere to put. */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void shouldNeverBlockInOnMatchEvenWhenTheQueueIsFull() {
        runner = new StrategyRunner(fill -> { });   // never started: nothing drains the queue

        // Far more than CAPACITY, offered with no consumer at all.
        assertThatCode(() -> {
            for (int i = 0; i < 150_000; i++) {
                runner.onMatch(buy(i), List.of(trade(i, i)));
            }
        }).doesNotThrowAnyException();

        assertThat(runner.dropped())
                .as("the overflow must be dropped and counted, not blocked on")
                .isPositive();
    }

    @Test
    void shouldDeliverEveryQueuedFillBeforeStopping() throws Exception {
        List<Trade> seen = java.util.Collections.synchronizedList(new ArrayList<>());
        runner = new StrategyRunner(fill -> {
            Thread.sleep(1);        // slow enough that the queue backs up
            seen.add(fill);
        });
        runner.start();

        for (int i = 1; i <= 50; i++) {
            runner.onMatch(buy(i), List.of(trade(i, i)));
        }
        runner.stop();              // must drain, not abandon

        assertThat(seen).hasSize(50);
        assertThat(runner.delivered()).isEqualTo(50);
    }

    /** A throwing strategy must not kill the thread, or the run silently stops trading. */
    @Test
    void shouldSurviveAStrategyThatThrows() throws Exception {
        CountDownLatch bothSeen = new CountDownLatch(2);
        runner = new StrategyRunner(fill -> {
            bothSeen.countDown();
            throw new IllegalStateException("strategy is broken");
        });
        runner.start();

        runner.onMatch(buy(1), List.of(trade(1, 1)));
        runner.onMatch(buy(2), List.of(trade(2, 2)));

        assertThat(bothSeen.await(2, TimeUnit.SECONDS))
                .as("the second fill proves the thread survived the first throw")
                .isTrue();
        runner.stop();
        assertThat(runner.failures())
                .as("a strategy failing every call is invisible to a caller unless counted")
                .isEqualTo(2);
        assertThat(runner.delivered())
                .as("a call that threw did not deliver anything")
                .isZero();
    }

    @Test
    void shouldCountNoFailuresWhenTheStrategyNeverThrows() {
        RecordingStrategy strategy = new RecordingStrategy();
        runner = new StrategyRunner(strategy);
        runner.start();

        runner.requote();
        runner.onMatch(buy(1), List.of(trade(1, 1)));
        runner.stop();

        assertThat(runner.failures()).isZero();
    }

    // --- the requote side ---

    /** Requote ticks must reach the strategy, and on the strategy's thread like everything else. */
    @Test
    void shouldDeliverRequoteTicksOnTheStrategyThread() {
        RecordingStrategy strategy = new RecordingStrategy();
        runner = new StrategyRunner(strategy);
        runner.start();

        runner.requote();
        runner.requote();
        runner.stop();          // queues behind both ticks, so they are delivered first

        assertThat(strategy.requotes.get()).isEqualTo(2);
        assertThat(strategy.requoteThread.get()).isEqualTo("strategy");
        assertThat(runner.requotesSkipped()).isZero();
    }

    /** As for fills: a throwing requote must not kill the thread and stop the strategy trading. */
    @Test
    void shouldSurviveAStrategyThatThrowsOnRequote() {
        RecordingStrategy strategy = new RecordingStrategy();
        strategy.throwOnRequote = true;
        runner = new StrategyRunner(strategy);
        runner.start();

        runner.requote();
        runner.requote();
        runner.onMatch(buy(1), List.of(trade(1, 1)));
        runner.stop();

        assertThat(strategy.requotes.get()).isEqualTo(2);
        assertThat(strategy.fills)
                .as("a fill delivered after two throwing requotes proves the thread survived")
                .hasSize(1);
        assertThat(runner.failures()).as("both requotes threw").isEqualTo(2);
    }

    /**
     * A stale tick is worthless, so a full queue drops it rather than blocking the scheduler -
     * and that drop must not be counted against the fills, where any drop is a fault.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void shouldDropRequoteTicksRatherThanBlockWhenTheQueueIsFull() {
        runner = new StrategyRunner(new RecordingStrategy());    // never started: nothing drains

        assertThatCode(() -> {
            for (int i = 0; i < 150_000; i++) {
                runner.requote();
            }
        }).doesNotThrowAnyException();

        assertThat(runner.requotesSkipped()).isPositive();
        assertThat(runner.dropped())
                .as("a skipped tick is not a dropped fill")
                .isZero();
    }

    // --- lifecycle ---

    @Test
    void startTwiceShouldThrow() {
        runner = new StrategyRunner(fill -> { });
        runner.start();

        assertThatThrownBy(() -> runner.start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already started");
    }

    @Test
    void stopBeforeStartShouldReturn() {
        runner = new StrategyRunner(fill -> { });
        assertThatCode(() -> runner.stop()).doesNotThrowAnyException();
    }

    @Test
    void stopTwiceShouldReturn() {
        runner = new StrategyRunner(fill -> { });
        runner.start();
        runner.stop();
        assertThatCode(() -> runner.stop()).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectANullStrategy() {
        assertThatThrownBy(() -> new StrategyRunner(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("strategy");
    }

    // --- fixtures ---

    /**
     * Records both callbacks. A lambda cannot be used for the requote tests, since a lambda
     * takes the {@link Strategy#onRequote()} default and there is nothing to observe.
     */
    private static final class RecordingStrategy implements Strategy {

        private final List<Trade> fills = java.util.Collections.synchronizedList(new ArrayList<>());
        private final AtomicLong requotes = new AtomicLong();
        private final AtomicReference<String> requoteThread = new AtomicReference<>();
        private volatile boolean throwOnRequote;

        @Override
        public void onFill(Trade trade) {
            fills.add(trade);
        }

        @Override
        public void onRequote() {
            requoteThread.set(Thread.currentThread().getName());
            requotes.incrementAndGet();
            if (throwOnRequote) {
                throw new IllegalStateException("requote is broken");
            }
        }
    }

    private static Order buy(long orderId) {
        return new Order(1, orderId, 10_000, 10, Side.BUY, OrderType.LIMIT, orderId);
    }

    private static Order quote(long orderId, Side side) {
        return new Order(STRATEGY_ACCOUNT, orderId, side == Side.BUY ? 9_980 : 10_020, 20, side,
                OrderType.LIMIT, orderId);
    }

    private static Trade trade(long buyId, long sellId) {
        return new Trade(buyId, sellId, 10_000, 10, 1);
    }
}
