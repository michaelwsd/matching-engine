package com.mwang.gateway;

import com.mwang.model.Order;

import java.util.Objects;

/**
 * One instruction travelling through the gateway's queue to the matcher thread.
 *
 * <p>Everything a caller wants done is queued as one of these, so the book stays confined to
 * the matcher thread and the queue stays the one place arrival order is decided. Queueing a
 * cancel also removes a race for free: the queue is FIFO, so a {@link Cancel} enqueued after
 * its {@link Submit} is always applied after it.
 *
 * <p>Sealed, so the matcher's {@code switch} is exhaustive and a fourth command will not
 * compile until it is handled. Package-private: callers see only {@link OrderGateway}.
 */
sealed interface Command {

    /** Match this order against the book, resting whatever does not fill. */
    record Submit(Order order) implements Command {
        // public because a record nested in an interface is implicitly public, and a
        // canonical constructor may not narrow that.
        public Submit {
            // Fail here, not later on the matcher thread where nothing says who queued it.
            Objects.requireNonNull(order, "order");
        }
    }

    /**
     * Take this order off the book. An order that already filled is not an error here - see
     * {@link OrderGateway#cancelsMissed()}.
     */
    record Cancel(long orderId) implements Command { }

    /**
     * Stop the matcher. Queued rather than signalled with a flag, so it sits behind every
     * command already accepted and nothing acknowledged is lost. A real case where a sentinel
     * {@code Order} with magic field values used to be - this one the compiler checks.
     */
    record Shutdown() implements Command { }
}
