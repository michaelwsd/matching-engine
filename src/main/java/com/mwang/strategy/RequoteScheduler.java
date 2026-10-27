package com.mwang.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs a task on its own thread at a fixed delay, surviving anything it throws.
 *
 * <p>Takes a {@link Runnable} rather than a {@link StrategyRunner} so the cadence can be tested
 * on its own. {@code Main} passes {@code runner::requote}.
 *
 * <p>Stop this before whatever the task feeds, or the last ticks run against a stopped runner.
 */
public final class RequoteScheduler {

    private static final Logger logger = LoggerFactory.getLogger(RequoteScheduler.class);

    /** How long {@link #stop()} waits for a tick in flight before giving up on it. */
    private static final long SHUTDOWN_TIMEOUT_MILLIS = 5_000;

    // only needs to requote on an interval, no need to know about anything else
    // Runnable only as one method that runs something
    private final Runnable requote;
    private final long intervalMillis;

    /** Written by the requote thread, read by anyone, so not plain {@code long}s. */
    private final AtomicLong ticks = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    private ScheduledExecutorService scheduler;

    /**
     * @param requote  run on every tick
     * @param interval gap between the end of one tick and the start of the next
     * @throws NullPointerException     if either argument is null
     * @throws IllegalArgumentException if {@code interval} rounds to less than a millisecond,
     *                                  which would schedule a spin loop
     */
    public RequoteScheduler(Runnable requote, Duration interval) {
        Objects.requireNonNull(requote, "requote");
        Objects.requireNonNull(interval, "interval");

        // Checked in millis, not as a Duration: a positive Duration under a millisecond
        // truncates to a zero delay, which schedules as fast as the thread will go.
        long millis = interval.toMillis();
        if (millis <= 0) {
            throw new IllegalArgumentException("interval must be at least a millisecond: " + interval);
        }

        this.requote = requote;
        this.intervalMillis = millis;
    }

    /**
     * Starts ticking, first tick after one interval. By then the flow has rested a few orders,
     * so the opening quote is more likely to sit around a real mid than around the fallback.
     *
     * @throws IllegalStateException if already started. A scheduler is not restartable.
     */
    public synchronized void start() {
        if (scheduler != null) {
            throw new IllegalStateException("requote scheduler already started");
        }

        // Named, so the logs read [requote] beside [matcher], [strategy] and [producer].
        scheduler = Executors.newSingleThreadScheduledExecutor(
                runnable -> new Thread(runnable, "requote"));

        // Fixed delay, not fixed rate: after a pause, fixed rate runs every missed tick back to
        // back, and each one cancels and reposts both quotes for the same resulting pair.
        scheduler.scheduleWithFixedDelay(
                this::tick, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        logger.info("Requote scheduler started, every {}ms", intervalMillis);
    }

    /**
     * One tick. Absorbs every {@link RuntimeException}, because a throw escaping here cancels
     * the schedule silently and the strategy would stop quoting with nothing to say why.
     */
    private void tick() {
        long tick = ticks.incrementAndGet();
        try {
            requote.run();
        } catch (RuntimeException e) {
            // Not Throwable: an Error should stop the clock, not leave it ticking in a dying JVM.
            failures.incrementAndGet();
            logger.error("Requote tick {} failed", tick, e);
        }
    }

    /** Stops ticking and waits for a tick in flight. Idempotent, and safe when never started. */
    public synchronized void stop() {
        if (scheduler == null || scheduler.isShutdown()) {
            return;
        }

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                logger.error("Requote tick did not finish within {}ms; interrupting it",
                        SHUTDOWN_TIMEOUT_MILLIS);
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }

        logger.info("Requote scheduler stopped: {} ticks, {} failed", ticks.get(), failures.get());
    }

    /** @return how many ticks have run, failures included */
    public long ticks() {
        return ticks.get();
    }

    /** @return how many ticks threw. Anything but zero means the strategy is failing. */
    public long failures() {
        return failures.get();
    }
}
