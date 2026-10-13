package com.mwang.marketdata;

import com.mwang.gateway.OrderGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates synthetic orders on its own thread and submits them to an {@link OrderGateway}.
 *
 * <p>The producer end of the pipeline. It holds no queue of its own - the gateway's is the only
 * thread boundary. Pacing is a uniform delay below {@code MAX_DELAY_MILLIS}, giving roughly 50
 * orders a second; a ceiling, not a guarantee, since a lagging matcher blocks {@code submit}
 * and paces the producer instead.
 *
 * <p>{@link #start()} and {@link #stop()} may be called from different threads, so a shutdown
 * hook can stop this mid-startup. The generator may not - confine one per driver.
 *
 * <p><strong>Call {@code stop()} before {@link OrderGateway#shutdown()}</strong>, or the
 * producer submits into a stopped gateway and is rejected.
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
     * Whether to keep generating. {@code volatile} rather than monitor-guarded: if the producer
     * needed this object's monitor, {@code stop()} holding it across {@link Thread#join(long)}
     * would deadlock.
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
     * Generates orders until asked to stop.
     *
     * <p>{@code while (running)} is right here where it was a bug in the matcher: a consumer has
     * a backlog to drain, a producer does not. Belt-and-braces in practice, since {@link #stop()}
     * always interrupts and both blocking calls throw on interruption.
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
     * Stops the producer and waits for its thread. Returns immediately if never started, or
     * already stopped.
     *
     * <p>The flag alone is not enough: parked inside {@code put()}, the producer is not running
     * the loop and will never read it. Only an interrupt wakes it, and unlike the matcher there
     * is no queued-sentinel option - nothing feeds a producer.
     *
     * <p>The order in flight when the interrupt lands is discarded, acceptable for synthetic
     * flow. A real gateway would {@code offer()} and reject explicitly.
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
