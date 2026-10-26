package com.mwang.gateway;

import com.mwang.model.Order;

import java.util.Objects;

/**
 * One instruction travelling through the gateway's queue to the matcher thread. Everything a
 * caller wants done is queued, so the book stays confined and the queue decides arrival order.
 *
 * <p>The queue being FIFO removes a race for free: a {@link Cancel} enqueued after its
 * {@link Submit} is always applied after it.
 *
 * <p>Sealed, so the matcher's {@code switch} is exhaustive and a fourth command will not compile
 * until it is handled.
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
     * Take this order off the book. An order that already filled is not an error here; see
     * {@link OrderGateway#cancelsMissed()}.
     */
    record Cancel(long orderId) implements Command { }

    /** Stop the matcher, behind every command already accepted, so nothing accepted is lost. */
    record Shutdown() implements Command { }
}
