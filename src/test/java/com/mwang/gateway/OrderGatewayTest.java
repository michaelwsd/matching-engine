package com.mwang.gateway;

import com.mwang.book.OrderBook;
import com.mwang.engine.MatchingEngine;
import com.mwang.engine.PriceTimeMatchingEngine;
import com.mwang.exception.InvalidOrderException;
import com.mwang.model.Order;
import com.mwang.model.OrderType;
import com.mwang.model.Side;
import com.mwang.model.Trade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
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

    @Test
    void closedGatewayShouldNotSubmit() {
        gateway = new OrderGateway(order -> {
            return List.of();
        });

        assertThatThrownBy(() -> gateway.submit(buy(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running");
    }

    // --- listener ---

    @Test
    void shouldNotifyTheListenerOfEachOrderAndItsTrades() throws Exception {
        Trade trade = new Trade(1, 2, 10000, 10, 0);
        List<Order> seenOrders = Collections.synchronizedList(new ArrayList<>());
        List<Trade> seenTrades = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch notified = new CountDownLatch(1);

        gateway = new OrderGateway(order -> List.of(trade), (order, trades) -> {
            seenOrders.add(order);
            seenTrades.addAll(trades);
            notified.countDown();
        });
        gateway.start();

        Order submitted = buy(1);
        gateway.submit(submitted);

        assertThat(notified.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(seenOrders).containsExactly(submitted);
        assertThat(seenTrades).containsExactly(trade);
    }

    /** A rejected order still happened, so the listener hears about it with no trades. */
    @Test
    void shouldNotifyTheListenerWithNoTradesWhenTheEngineRejectsTheOrder() throws Exception {
        CountDownLatch notified = new CountDownLatch(1);
        List<Trade> seenTrades = Collections.synchronizedList(new ArrayList<>());

        gateway = new OrderGateway(
                order -> { throw new InvalidOrderException("rejected"); },
                (order, trades) -> {
                    seenTrades.addAll(trades);
                    notified.countDown();
                });
        gateway.start();

        gateway.submit(buy(1));

        assertThat(notified.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(seenTrades).isEmpty();
    }

    /**
     * The listener is caller-supplied and runs on the matcher thread, so an exception from it
     * would otherwise propagate out of the match loop and kill the matcher - silently, since
     * nothing joins that thread until shutdown. Every subsequent order would queue up forever.
     */
    @Test
    void shouldSurviveAListenerThatThrows() throws Exception {
        AtomicInteger processed = new AtomicInteger();
        CountDownLatch allSeen = new CountDownLatch(3);

        gateway = new OrderGateway(
                order -> {
                    processed.incrementAndGet();
                    allSeen.countDown();
                    return List.of();
                },
                (order, trades) -> { throw new IllegalStateException("listener is broken"); });
        gateway.start();

        gateway.submit(buy(1));
        gateway.submit(buy(2));
        gateway.submit(buy(3));

        assertThat(allSeen.await(2, TimeUnit.SECONDS))
                .as("the matcher must keep processing after a listener blows up")
                .isTrue();
        assertThat(processed).hasValue(3);
    }

    @Test
    void shouldRejectANullListener() {
        assertThatThrownBy(() -> new OrderGateway(order -> List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("listener");
    }

    // --- run counters ---

    /**
     * The counters are the only record of a run's size once the process is gone, since they
     * feed the summary line shutdown logs. Asserting after {@code shutdown()} needs no latch:
     * shutdown drains the queue before returning, so the totals are final by then.
     */
    @Test
    void shouldCountEveryOrderItProcesses() throws Exception {
        startWith(order -> List.of());

        gateway.submit(buy(1));
        gateway.submit(buy(2));
        gateway.submit(buy(3));
        gateway.shutdown();

        assertThat(gateway.ordersProcessed()).isEqualTo(3);
        assertThat(gateway.tradesExecuted()).isZero();
        assertThat(gateway.ordersRejected()).isZero();
    }

    @Test
    void shouldCountTheTradesTheEngineProduces() throws Exception {
        startWith(order -> List.of(new Trade(1, 2, 10_000, 10, 0),
                new Trade(3, 4, 10_000, 10, 0)));

        gateway.submit(buy(1));
        gateway.submit(buy(2));
        gateway.shutdown();

        assertThat(gateway.ordersProcessed()).isEqualTo(2);
        assertThat(gateway.tradesExecuted())
                .as("two orders, two trades each")
                .isEqualTo(4);
    }

    /**
     * A rejected order still reached the engine, so it counts as processed as well as
     * rejected. Counting it only as rejected would make the two totals fail to reconcile.
     */
    @Test
    void shouldCountARejectedOrderAsBothProcessedAndRejected() throws Exception {
        startWith(order -> {
            throw new InvalidOrderException("rejected");
        });

        gateway.submit(buy(1));
        gateway.shutdown();

        assertThat(gateway.ordersProcessed()).isEqualTo(1);
        assertThat(gateway.ordersRejected()).isEqualTo(1);
        assertThat(gateway.tradesExecuted()).isZero();
    }

    @Test
    void shouldReportZeroCountsBeforeAnythingIsSubmitted() {
        startWith(order -> List.of());

        assertThat(gateway.ordersProcessed()).isZero();
        assertThat(gateway.tradesExecuted()).isZero();
        assertThat(gateway.ordersRejected()).isZero();
    }

    // --- shutdown under the worst case ---

    /**
     * Shutdown must return even when the matcher will never come back <em>and</em> the queue
     * has no room for the shutdown command.
     *
     * <p>A blocking {@code put} of the pill would wait for a slot only the wedged matcher
     * could free, so it would wait forever - and the {@code join} that is meant to bound the
     * whole shutdown sits after it and would never be reached. The failure mode is a hang with
     * no log line explaining it.
     *
     * <p>Deliberately the slowest test in the suite: it spends the real {@code offer} timeout,
     * because the timeout elapsing is the thing being asserted. The annotation runs the body on
     * its own thread so that a regression fails the test rather than hanging the build.
     *
     * <p>Emits an expected ERROR about the pill and an {@code IllegalStateException} from the
     * wedged engine. Both are the test working.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void shouldStopWaitingForTheSentinelWhenTheQueueIsFullAndTheMatcherIsWedged()
            throws Exception {
        CountDownLatch reachedEngine = new CountDownLatch(1);
        gateway = new OrderGateway(order -> {
            reachedEngine.countDown();
            try {
                // Never released. Only the interrupt from shutdown ends this wait, and the
                // exception it becomes kills the matcher - which is what lets the JVM exit.
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("engine interrupted", e);
            }
            return List.of();
        });
        gateway.start();

        // One order to drive the matcher into the engine, where it wedges. Only then is the
        // queue filled, so every slot stays taken for the rest of the test.
        gateway.submit(buy(1));
        assertThat(reachedEngine.await(2, TimeUnit.SECONDS))
                .as("the matcher should be inside the engine before the queue is filled")
                .isTrue();
        Thread filler = fillQueue();

        gateway.shutdown();

        filler.interrupt();
        assertThat(gateway.ordersProcessed())
                .as("the wedged order never completed, so nothing was counted")
                .isZero();
    }

    // --- cancel ---

    /**
     * The point of the whole Command refactor: a cancel reaches a <em>resting</em> order. The
     * flag-on-a-queued-order approach could only ever have reached orders still in the queue,
     * which is the fraction of their life when cancelling does not matter.
     */
    @Test
    void shouldCancelAnOrderThatIsRestingOnTheBook() throws Exception {
        OrderBook book = new OrderBook();
        startWith(new PriceTimeMatchingEngine(book));

        gateway.submit(buy(1));
        gateway.cancel(1);
        gateway.shutdown();     // blocks until both commands have been applied

        assertThat(book.bestBid())
                .as("the resting bid should be gone")
                .isEqualTo(OrderBook.NO_PRICE);
        assertThat(gateway.ordersCancelled()).isOne();
        assertThat(gateway.cancelsMissed()).isZero();
    }

    /**
     * Queueing the cancel is what removes the race. The queue is FIFO, so a cancel enqueued
     * after its submit is always applied after it - there is no window where the order is
     * neither queued nor resting, and therefore no cancel that silently does nothing.
     */
    @Test
    void shouldApplyACancelAfterTheSubmitItFollowsEvenWhenBothAreQueuedAtOnce() throws Exception {
        OrderBook book = new OrderBook();
        startWith(new PriceTimeMatchingEngine(book));

        for (long id = 1; id <= 50; id++) {
            gateway.submit(buy(id));
            gateway.cancel(id);
        }
        gateway.shutdown();

        assertThat(book.bestBid())
                .as("every order was cancelled, so no bid should remain")
                .isEqualTo(OrderBook.NO_PRICE);
        assertThat(gateway.ordersCancelled()).isEqualTo(50);
        assertThat(gateway.cancelsMissed())
                .as("none of these cancels should have lost a race")
                .isZero();
    }

    /**
     * A strategy pulling a quote at the moment it fills is routine, not a fault. It must be
     * counted, must not be logged as a warning, and above all must not kill the matcher.
     */
    @Test
    void shouldCountACancelForAnOrderThatIsNoLongerOnTheBook() throws Exception {
        OrderBook book = new OrderBook();
        startWith(new PriceTimeMatchingEngine(book));

        gateway.cancel(999);            // never submitted
        gateway.submit(buy(1));         // must still be matched afterwards
        gateway.shutdown();

        assertThat(gateway.cancelsMissed()).isOne();
        assertThat(gateway.ordersCancelled()).isZero();
        assertThat(gateway.ordersProcessed())
                .as("a missed cancel must not stop the matcher")
                .isOne();
        assertThat(book.bestBid()).isEqualTo(10000);
    }

    /**
     * {@code MatchingEngine} stays a functional interface, so {@code cancel} is a default that
     * throws. A lambda engine holds no book and genuinely cannot cancel; failing loudly is
     * better than a cancel that quietly does nothing.
     */
    @Test
    void shouldFailLoudlyWhenTheEngineCannotCancel() {
        MatchingEngine lambdaEngine = order -> List.of();

        assertThatThrownBy(() -> lambdaEngine.cancel(1))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("does not support cancel");
    }

    @Test
    void cancelBeforeStartShouldThrow() {
        gateway = new OrderGateway(order -> List.of());

        assertThatThrownBy(() -> gateway.cancel(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running");
    }

    @Test
    void cancelAfterShutdownShouldThrow() throws Exception {
        startWith(order -> List.of());
        gateway.shutdown();

        assertThatThrownBy(() -> gateway.cancel(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running");
    }

    /**
     * Wrapping the order in a Command moved where a null fails. Without the check in
     * {@code Command.Submit}, a null would travel the queue and blow up on the matcher thread,
     * with nothing left in the stack trace to say who queued it.
     */
    @Test
    void shouldRejectANullOrderAtTheCallSite() {
        startWith(order -> List.of());

        assertThatThrownBy(() -> gateway.submit(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("order");
    }

    // --- fixtures ---

    /**
     * Submits until the submitting thread parks inside {@code put()}, whichever capacity the
     * gateway happens to use. Hardcoding 1000 here would silently stop filling the queue the
     * day that constant changed.
     */
    private Thread fillQueue() throws InterruptedException {
        Thread filler = new Thread(() -> {
            try {
                for (long id = 1; ; id++) {
                    gateway.submit(buy(id));
                }
            } catch (InterruptedException | IllegalStateException e) {
                // Either the queue filled and we were interrupted, or the gateway stopped.
            }
        }, "queue-filler");
        filler.setDaemon(true); // must never outlive the test and hold the JVM open
        filler.start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (filler.getState() == Thread.State.WAITING) {
                return filler;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("the queue never filled");
    }

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
