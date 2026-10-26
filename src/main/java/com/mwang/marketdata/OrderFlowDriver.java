package com.mwang.marketdata;

import com.mwang.gateway.OrderGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates synthetic orders on its own thread and submits them to an {@link OrderGateway}. It
 * holds no queue of its own; the gateway's is the only thread boundary.
 *
 * <p>Pacing is a uniform delay below {@code MAX_DELAY_MILLIS}, roughly 50 orders a second. A
 * ceiling, not a guarantee: a lagging matcher blocks {@code submit} and paces the producer.
 *
 * <p>{@link #start()} and {@link #stop()} may be called from different threads; the generator
 * may not, so confine one per driver. Call {@code stop()} before
 * {@link OrderGateway#shutdown()}, or the producer submits into a stopped gateway.
 */
public final class OrderFlowDriver {

    /** Exclusive upper bound on the pause after each order; a uniform draw averages half it. */
    private static final long MAX_DELAY_MILLIS = 40;

    /** How long {@link #stop()} waits for the producer to finish before giving up on it. */
    private static final long SHUTDOWN_TIMEOUT_MILLIS = 5_000;

    private static final Logger logger = LoggerFactory.getLogger(OrderFlowDriver.class);

    private final MarketDataGenerator generator;
    private final OrderGateway gateway;
    private final Random random;

    private Thread producer;

    /** Written by the producer, read by {@link #submitted()} at any time - so not a plain long. */
    private final AtomicLong submitted = new AtomicLong();

    /**
     * Whether to keep generating. {@code volatile} rather than monitor-guarded: a producer
     * needing this object's monitor would deadlock against {@code stop()}'s {@code join}.
     */
    private volatile boolean running;

    /**
     * @param generator supplies the orders; must not be shared with another driver
     * @param gateway   receives them; must already be started before {@link #start()}
     * @param seed      seeds the pacing draws only, separately from the generator and the walk
     * @throws NullPointerException if {@code generator} or {@code gateway} is null
     */
    public OrderFlowDriver(MarketDataGenerator generator, OrderGateway gateway, long seed) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.random = new Random(seed);
    }

    /**
     * Starts the producer thread.
     *
     * @throws IllegalStateException if already started. A driver is not restartable.
     */
    public synchronized void start() {
        if (producer != null) {
            throw new IllegalStateException("producer already started");
        }
        running = true;

        // start a new thread to submit orders
        producer = new Thread(this::producerLoop, "producer");
        producer.start();
        logger.info("Order flow started, up to one order every {}ms", MAX_DELAY_MILLIS);
    }

    /**
     * Generates orders until asked to stop. {@code while (running)} is right here, unlike in the
     * matcher: a producer has no accepted backlog to drain.
     */
    private void producerLoop() {
        try {
            while (running) {
                gateway.submit(generator.nextOrder());
                submitted.incrementAndGet(); // atomic operation
                Thread.sleep(Duration.ofMillis(random.nextLong(0, MAX_DELAY_MILLIS)));
            }
        } catch (InterruptedException e) {
            // Interruption is the normal way out of this loop, not an error: stop() uses it to
            // break the thread out of a blocking submit(). Restore the flag so that anything
            // further up the stack can still see it, and fall out.
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stops the producer and waits for its thread. Returns immediately if never started or
     * already stopped.
     *
     * <p>Interrupts as well as clearing the flag: parked in {@code put()}, the producer never
     * reaches the loop condition. Nothing feeds a producer, so there is no sentinel to queue.
     *
     * <p>The order in flight is discarded, acceptable for synthetic flow.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        // the producer may submit an order and the order will be queued for a long time
        // as the order queues, producer will not reach the next iteration, and hence
        // won't see the running flag, hence we must terminate it manually
        producer.interrupt();

        try {
            // wait for producer to terminate
            producer.join(SHUTDOWN_TIMEOUT_MILLIS);
            if (producer.isAlive()) {
                logger.error("Producer did not stop within {}ms", SHUTDOWN_TIMEOUT_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logger.info("Order flow stopped after {} orders", submitted.get());
    }

    /** @return how many orders this driver has submitted so far */
    public long submitted() {
        return submitted.get();
    }
}
