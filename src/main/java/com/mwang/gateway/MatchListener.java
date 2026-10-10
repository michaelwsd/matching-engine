package com.mwang.gateway;

import com.mwang.model.Order;
import com.mwang.model.Trade;

import java.util.List;

/**
 * Notified after the engine has processed each order.
 *
 * <p><strong>Runs on the matcher thread.</strong> That has two consequences a listener must
 * respect. It may safely touch state owned by the matcher - the order book included - because
 * it is executing inside that confinement. And it must be fast: every millisecond it spends is
 * a millisecond the exchange is not matching. Anything slow, such as writing to a file or a
 * socket, belongs on another thread fed by a queue.
 *
 * <p>A listener must also not hand matcher-owned objects to other threads. Publish an immutable
 * value, such as a {@link com.mwang.book.BookSnapshot}, rather than the book itself.
 */
@FunctionalInterface
public interface MatchListener {

    /** A listener that does nothing, so the gateway never has to null-check. */
    MatchListener NONE = (order, trades) -> { };

    /**
     * @param order  the order that was just processed
     * @param trades the trades it produced, empty when it rested or was rejected
     */
    void onMatch(Order order, List<Trade> trades);
}
