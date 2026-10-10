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
 * <p>The producer end of the pipeline; the gateway's matcher thread is the consumer, and the
 * gateway's queue is the only thread boundary. This class holds no queue of its own.
 *
 * <p>Orders are paced by a uniformly random delay below {@code MAX_DELAY_MILLIS}, giving
 * roughly 50 per second. That is a ceiling, not a guarantee: if the matcher falls behind, the
 * gateway's {@code submit} blocks and the producer is paced by the matcher instead.
 *
 * <p>{@link #start()} and {@link #stop()} may be called from different threads, so a JVM
 * shutdown hook can stop this while the main thread is still starting up. The generator,
 * however, is confined to the producer thread - never share one between two drivers.
 *
 * <p><strong>Call {@code stop()} before {@link OrderGateway#shutdown()}.</strong> The reverse
 * order leaves the producer submitting to a stopped gateway, which rejects it.
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

    /**
     * How many orders this driver has handed to the gateway. Written by the producer thread,
     * read by {@link #stop()} and by {@link #submitted()}, so not a plain {@code long}.
     */
    private final AtomicLong submitted = new AtomicLong();

    /**
     * Whether to keep generating. Written by {@link #stop()}, read by the producer thread, so
     * {@code volatile} rather than monitor-guarded - if the producer needed this object's
     * monitor, {@code stop()} holding it across {@link Thread#join(long)} would deadlock.
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

        producer = new Thread(this::producerLoop, "producer");
        producer.start();
        logger.info("Order flow started, up to one order every {}ms", MAX_DELAY_MILLIS);
    }

    /**
     * Generates orders until asked to stop.
     *
     * <p>{@code while (running)} is right here where the same construct was a bug in the
     * gateway's matcher: a consumer has a backlog to drain first, a producer does not. In
     * practice the flag is belt-and-braces, since {@link #stop()} always interrupts and both
     * blocking calls below throw on interruption.
     */
    private void producerLoop() {
        try {
            while (running) {
                gateway.submit(generator.nextOrder());
                submitted.incrementAndGet();
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
     * <p>The flag alone is not enough: the producer may be parked inside {@code put()} waiting
     * for a queue slot that will never free, where it is not running this loop and will never
     * see the flag change. Interruption is the only way to wake it, and unlike the gateway's
     * matcher there is no poison-pill option - nothing feeds a producer.
     *
     * <p>The order being submitted when the interrupt lands is discarded, which is acceptable
     * for synthetic flow. A real gateway would use {@code offer()} and reject explicitly.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        producer.interrupt(); // interrupts the producer thread in case of submitting an order

        try {
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
