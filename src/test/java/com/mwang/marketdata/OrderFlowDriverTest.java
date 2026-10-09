package com.mwang.marketdata;

import com.mwang.engine.MatchingEngine;
import com.mwang.gateway.OrderGateway;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderFlowDriverTest {

    /** Generous enough to absorb a scheduling hiccup, far below the 5s join timeout. */
    private static final long PROMPT_MILLIS = 1_000;

    private OrderGateway gateway;
    private OrderFlowDriver driver;
    private Thread filler;

    /** Released in teardown so a deliberately stalled engine can finish and let the queue drain. */
    private final CountDownLatch engineRelease = new CountDownLatch(1);

    /**
     * Order matters, and not only for tidiness.
     *
     * <p>The driver is stopped first because that is the documented shutdown order: a producer
     * submitting to an already-stopped gateway gets an {@link IllegalStateException}. The filler
     * is then stopped so it cannot refill the queue, and only then is the engine released so the
     * matcher can drain - because {@code shutdown()} enqueues its poison pill with a blocking
     * {@code put()}, which would never return while the queue is full and the matcher stalled.
     */
    @AfterEach
    void tearDown() throws InterruptedException {
        if (driver != null) {
            driver.stop();
        }
        if (filler != null) {
            filler.interrupt();
            filler.join(PROMPT_MILLIS);
        }
        engineRelease.countDown();
        if (gateway != null) {
            gateway.shutdown();
        }
    }

    // --- stopping ---

    /**
     * The regression test. A {@code stop()} that only clears a flag passes every other test in
     * this class and fails this one, because a producer parked inside {@code put()} is not
     * executing the loop and will never see the flag change. Only an interrupt reaches it.
     *
     * <p>Measured against the broken version: {@code stop()} returned after 5010ms, having
     * logged its own timeout error, and left the producer thread alive and parked.
     */
    @Test
    void shouldStopPromptlyEvenWhenTheProducerIsBlockedOnAFullQueue() throws Exception {
        startGatewayWith(stalledEngine());
        fillGatewayQueue();

        driver = newDriver();
        driver.start();
        // With every slot taken and the matcher stalled, the producer's next submit parks and
        // can never proceed.
        Thread.sleep(200);

        assertThat(timeOf(driver::stop))
                .as("stop() must interrupt the producer, not wait out the join timeout")
                .isLessThan(PROMPT_MILLIS);
    }

    @Test
    void shouldStopPromptlyWhenTheProducerIsMerelyPacingItself() throws Exception {
        startGatewayWith(countingEngine(new AtomicInteger()));
        driver = newDriver();
        driver.start();
        Thread.sleep(100);

        assertThat(timeOf(driver::stop)).isLessThan(PROMPT_MILLIS);
    }

    /**
     * A thread created with {@code new Thread(...)} is non-daemon, so one that outlives
     * {@code stop()} keeps the whole JVM alive. That is why a leaked producer shows up as a
     * program that will not exit.
     */
    @Test
    void shouldLeaveNoLiveProducerThreadBehind() throws Exception {
        startGatewayWith(stalledEngine());
        fillGatewayQueue();
        driver = newDriver();
        driver.start();
        Thread.sleep(200);

        driver.stop();

        assertThat(liveThreadsNamed("producer")).isEmpty();
    }

    @Test
    void shouldReturnQuietlyWhenStoppedBeforeBeingStarted() {
        startGatewayWith(countingEngine(new AtomicInteger()));
        driver = newDriver();

        assertThatCode(driver::stop).doesNotThrowAnyException();
    }

    @Test
    void shouldTolerateBeingStoppedTwice() throws Exception {
        startGatewayWith(countingEngine(new AtomicInteger()));
        driver = newDriver();
        driver.start();
        Thread.sleep(50);
        driver.stop();

        assertThatCode(driver::stop).doesNotThrowAnyException();
    }

    // --- starting ---

    @Test
    void shouldRejectBeingStartedTwice() {
        startGatewayWith(countingEngine(new AtomicInteger()));
        driver = newDriver();
        driver.start();

        assertThatThrownBy(driver::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already started");
    }

    /** A driver is not restartable: once stopped, the guard still refuses a second start. */
    @Test
    void shouldRefuseToRestartAfterBeingStopped() throws Exception {
        startGatewayWith(countingEngine(new AtomicInteger()));
        driver = newDriver();
        driver.start();
        Thread.sleep(50);
        driver.stop();

        assertThatThrownBy(driver::start).isInstanceOf(IllegalStateException.class);
    }

    // --- the flow itself ---

    @Test
    void shouldDeliverGeneratedOrdersToTheEngine() throws Exception {
        CountDownLatch received = new CountDownLatch(5);
        startGatewayWith(order -> {
            received.countDown();
            return List.of();
        });

        driver = newDriver();
        driver.start();

        assertThat(received.await(5, TimeUnit.SECONDS))
                .as("the engine should have seen five generated orders")
                .isTrue();
    }

    /**
     * The other half of a correct stop: the thread must not merely die, it must stop producing.
     * A {@code stop()} that killed the thread without clearing the flag, or that let one more
     * iteration through, would show up here.
     */
    @Test
    void shouldSubmitNothingFurtherAfterBeingStopped() throws Exception {
        AtomicInteger received = new AtomicInteger();
        startGatewayWith(countingEngine(received));

        driver = newDriver();
        driver.start();
        Thread.sleep(200);
        driver.stop();

        Thread.sleep(200);               // let the matcher drain anything already queued
        int settled = received.get();
        Thread.sleep(300);               // nothing new should arrive in this window

        assertThat(received.get()).isEqualTo(settled);
        assertThat(settled).as("the flow should have reached the engine at all").isPositive();
    }

    // --- construction ---

    @Test
    void shouldRejectANullGenerator() {
        startGatewayWith(countingEngine(new AtomicInteger()));

        assertThatThrownBy(() -> new OrderFlowDriver(null, gateway, 1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("generator");
    }

    @Test
    void shouldRejectANullGateway() {
        assertThatThrownBy(() -> new OrderFlowDriver(generator(), null, 1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("gateway");
    }

    // --- fixtures ---

    private void startGatewayWith(MatchingEngine engine) {
        gateway = new OrderGateway(engine);
        gateway.start();
    }

    /** An engine that accepts one order and then blocks until teardown, stalling the matcher. */
    private MatchingEngine stalledEngine() {
        return order -> {
            try {
                engineRelease.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        };
    }

    private static MatchingEngine countingEngine(AtomicInteger counter) {
        return order -> {
            counter.incrementAndGet();
            return List.of();
        };
    }

    private OrderFlowDriver newDriver() {
        return new OrderFlowDriver(generator(), gateway, 7);
    }

    private static MarketDataGenerator generator() {
        return new MarketDataGenerator(
                new MidPriceWalk(10_000, 5, 500, 42), 20, 10, 100, 8, 7);
    }

    /**
     * Submits from a background thread until it parks inside {@code put()}, which can only
     * happen once every slot is taken. Deliberately does not reference the gateway's capacity:
     * hardcoding 1000 here would silently stop filling the queue the day that constant changed,
     * and this test would then pass without testing anything.
     */
    private void fillGatewayQueue() throws InterruptedException {
        filler = new Thread(() -> {
            try {
                for (long id = 1; ; id++) {
                    gateway.submit(order(id));
                }
            } catch (InterruptedException | IllegalStateException e) {
                // expected: interrupted at teardown, or the gateway stopped underneath us
            }
        }, "filler");
        filler.setDaemon(true);
        filler.start();

        awaitState(filler, Thread.State.WAITING);
    }

    /**
     * Waits for a thread to settle into a state. The second check guards against catching it
     * momentarily waiting on the queue's lock rather than on a free slot.
     */
    private static void awaitState(Thread thread, Thread.State wanted) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (thread.getState() == wanted) {
                Thread.sleep(50);
                if (thread.getState() == wanted) {
                    return;
                }
            }
            Thread.sleep(5);
        }
        throw new AssertionError(thread.getName() + " never reached " + wanted
                + "; it is " + thread.getState());
    }

    private static List<Thread> liveThreadsNamed(String name) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> name.equals(thread.getName()) && thread.isAlive())
                .toList();
    }

    private static long timeOf(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static Order order(long id) {
        return new Order(1, id, 10_000, 10, Side.BUY, OrderType.LIMIT, 0);
    }
}
