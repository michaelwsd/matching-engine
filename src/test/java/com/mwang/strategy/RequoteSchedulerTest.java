package com.mwang.strategy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A 10ms interval keeps these quick without making them racy: every test waits on a latch or on
 * termination rather than on a sleep long enough to "probably" be enough.
 */
class RequoteSchedulerTest {

    private static final Duration INTERVAL = Duration.ofMillis(10);

    private RequoteScheduler scheduler;

    /** Every test must stop its scheduler, or its thread outlives the test. */
    @AfterEach
    void stopScheduler() {
        if (scheduler != null) {
            scheduler.stop();
        }
    }

    @Test
    @Timeout(5)
    void shouldRunTheTaskRepeatedly() throws Exception {
        CountDownLatch ran = new CountDownLatch(3);
        scheduler = new RequoteScheduler(ran::countDown, INTERVAL);

        scheduler.start();

        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        scheduler.stop();
        assertThat(scheduler.ticks()).isGreaterThanOrEqualTo(3);
        assertThat(scheduler.failures()).isZero();
    }

    /**
     * The whole reason this class exists. A throw that escapes the scheduled body cancels the
     * schedule with no stack trace and no further executions, so without the catch in
     * {@code tick} this test never counts past one and hangs until its timeout.
     */
    @Test
    @Timeout(5)
    void shouldKeepTickingAfterATaskThrows() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch ranAfterTheThrow = new CountDownLatch(2);
        scheduler = new RequoteScheduler(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("requote is broken");
            }
            ranAfterTheThrow.countDown();
        }, INTERVAL);

        scheduler.start();

        assertThat(ranAfterTheThrow.await(5, TimeUnit.SECONDS)).isTrue();
        scheduler.stop();
        assertThat(scheduler.failures()).as("the throw is counted, not hidden").isEqualTo(1);
        assertThat(scheduler.ticks()).as("the failed tick still counts as a tick")
                .isGreaterThanOrEqualTo(3);
    }

    /** An Error is deliberately not caught: it stops the clock rather than ticking on. */
    @Test
    @Timeout(5)
    void shouldStopTickingOnAnError() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        scheduler = new RequoteScheduler(() -> {
            ran.countDown();
            throw new AssertionError("the JVM is in trouble");
        }, INTERVAL);

        scheduler.start();
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();

        long after = ticksAfterSettling();
        assertThat(after).as("an Error cancels the schedule").isEqualTo(1);
        assertThat(scheduler.failures()).as("an Error is not a counted failure").isZero();
    }

    @Test
    @Timeout(5)
    void shouldStopTickingOnceStopped() throws Exception {
        CountDownLatch ran = new CountDownLatch(2);
        scheduler = new RequoteScheduler(ran::countDown, INTERVAL);

        scheduler.start();
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        scheduler.stop();

        long atStop = scheduler.ticks();
        TimeUnit.MILLISECONDS.sleep(10 * INTERVAL.toMillis());
        assertThat(scheduler.ticks()).isEqualTo(atStop);
    }

    @Test
    @Timeout(5)
    void shouldRejectASecondStart() {
        scheduler = new RequoteScheduler(() -> { }, INTERVAL);
        scheduler.start();

        assertThatThrownBy(() -> scheduler.start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already started");
    }

    @Test
    @Timeout(5)
    void shouldBeSafeToStopWhenNeverStarted() {
        scheduler = new RequoteScheduler(() -> { }, INTERVAL);

        assertThatCode(() -> scheduler.stop()).doesNotThrowAnyException();
        assertThat(scheduler.ticks()).isZero();
    }

    @Test
    @Timeout(5)
    void shouldBeIdempotentOnStop() {
        scheduler = new RequoteScheduler(() -> { }, INTERVAL);
        scheduler.start();

        scheduler.stop();
        assertThatCode(() -> scheduler.stop()).doesNotThrowAnyException();
    }

    // --- construction ---

    /** A positive duration under a millisecond truncates to a zero delay, which spins a core. */
    @Test
    void shouldRejectAnIntervalBelowAMillisecond() {
        assertThatThrownBy(() -> new RequoteScheduler(() -> { }, Duration.ofNanos(500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least a millisecond");
    }

    @Test
    void shouldRejectANonPositiveInterval() {
        assertThatThrownBy(() -> new RequoteScheduler(() -> { }, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequoteScheduler(() -> { }, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectMissingArguments() {
        assertThatThrownBy(() -> new RequoteScheduler(null, INTERVAL))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RequoteScheduler(() -> { }, null))
                .isInstanceOf(NullPointerException.class);
    }

    /** Waits out several intervals, so "it stopped ticking" is not just "it had not ticked yet". */
    private long ticksAfterSettling() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(10 * INTERVAL.toMillis());
        return scheduler.ticks();
    }
}
