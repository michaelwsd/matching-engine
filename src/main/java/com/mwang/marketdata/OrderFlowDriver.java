package com.mwang.marketdata;

import com.mwang.gateway.OrderGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Random;

/**
 * Drives synthetic order flow into an {@link OrderGateway} from a dedicated producer thread.
 *
 * <p>Sits at the opposite end of the gateway's queue from its matcher thread: this class is the
 * producer, the matcher is the consumer, and the gateway's {@code BlockingQueue} is the only
 * thread boundary in the pipeline. This class holds no queue of its own.
 *
 * <p>Orders are paced by a uniformly random delay in {@code [0, MAX_DELAY_MILLIS)}, giving a
 * mean of roughly 20ms and so about 50 orders per second. That is a ceiling rather than a
 * guarantee: {@link OrderGateway#submit(com.mwang.model.Order)} blocks when the queue is full,
 * so if the matcher falls behind, the producer is paced by the matcher instead. Real order flow
 * is bursty rather than evenly spaced, which an exponential delay would model; a uniform draw
 * is used here because it keeps the demo legible.
 *
 * <p>{@link #start()} and {@link #stop()} are safe to call from different threads, so a JVM
 * shutdown hook may call {@code stop()} while the main thread is still starting up. The
 * generator, however, is confined to the producer thread - never share one generator between
 * two drivers, because {@code nextOrderId} is incremented without synchronisation.
 *
 * <p><strong>Shutdown order matters.</strong> Call {@code stop()} on this driver before
 * {@link OrderGateway#shutdown()}. The reverse order leaves the producer submitting to a
 * stopped gateway, which rejects it with an {@link IllegalStateException}.
 */
public final class OrderFlowDriver {

    /**
     * Upper bound, exclusive, on the pause taken after each order. A uniform draw over
     * {@code [0, 40)} has a mean of 20ms, so the flow runs at roughly 50 orders per second -
     * slow enough to watch a book build up and trade.
     */
    private static final long MAX_DELAY_MILLIS = 40;

    /** How long {@link #stop()} waits for the producer to finish before giving up on it. */
    private static final long SHUTDOWN_TIMEOUT_MILLIS = 5_000;

    private static final Logger logger = LoggerFactory.getLogger(OrderFlowDriver.class);

    private final MarketDataGenerator generator;
    private final OrderGateway gateway;
    private final Random random;

    private Thread producer;

    /**
     * Whether the producer should keep generating. Written by {@link #stop()} and read by the
     * producer thread, so it is {@code volatile} rather than guarded by this object's monitor -
     * if the producer needed the monitor, {@code stop()} holding it across
     * {@link Thread#join(long)} would deadlock.
     */
    private volatile boolean running;

    /**
     * @param generator supplies the orders to submit; must not be shared with another driver
     * @param gateway   receives the orders; must already be started before {@link #start()}
     * @param seed      seed for the pacing draws only. Deliberately separate from the
     *                  generator's and the walk's seeds, so that changing the pacing does not
     *                  disturb the order sequence.
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
     * @throws IllegalStateException if this driver has already been started. A driver is not
     *                               restartable: once stopped, build a new one.
     */
    public synchronized void start() {
        if (producer != null) {
            throw new IllegalStateException("producer already started");
        }
        running = true;

        producer = new Thread(this::producerLoop, "producer");
        producer.start();
    }

    /**
     * Generates orders until asked to stop.
     *
     * <p>{@code while (running)} is the right shape here, where the same construct was a bug in
     * the gateway's matcher. A consumer can have a backlog, so it must keep draining until it
     * reads a sentinel positioned after the last real order. A producer is the source: when it
     * stops producing there is nothing left over, so a flag is appropriate.
     *
     * <p>In practice the flag is belt-and-braces: {@link #stop()} always interrupts, and both
     * blocking calls in this loop throw on interruption, so the exception is the exit path that
     * actually fires. Removing this condition breaks no test. It is kept because a loop with no
     * stop condition is harder to reason about, and because it would become the only exit if a
     * non-interruptible call were ever added here.
     */
    private void producerLoop() {
        try {
            while (running) {
                gateway.submit(generator.nextOrder());
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
     * Stops the producer and waits for its thread to finish. Returns immediately if the driver
     * was never started, or has already been stopped.
     *
     * <p>Clearing the flag is not sufficient on its own. The producer may be parked inside
     * {@code put()} waiting for a queue slot that will never free, in which case it is not
     * executing this loop and will never see the flag change. Interruption is the only way to
     * wake a thread out of a blocking call, and unlike the gateway's matcher there is no
     * poison-pill option here: nothing feeds a producer, so nothing can be sent to it.
     *
     * <p>The order being submitted when the interrupt lands is discarded, because
     * {@code put()} throws instead of enqueuing it. That is acceptable for synthetic flow. A
     * real gateway taking client orders would use {@code offer()} and reject explicitly rather
     * than blocking in the first place.
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
                logger.error("producer did not stop within {}ms", SHUTDOWN_TIMEOUT_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
