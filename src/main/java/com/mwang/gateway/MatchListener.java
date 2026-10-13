package com.mwang.gateway;

import com.mwang.model.Order;
import com.mwang.model.Trade;

import java.util.List;
import java.util.Objects;

/**
 * Notified after the engine has processed each order.
 *
 * <p>Runs on the matcher thread, so it may read matcher-owned state such as the book - but it
 * must be fast, since every millisecond here is one the exchange is not matching, and it must
 * hand other threads immutable values only, never the book itself.
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

    /**
     * Returns a listener that runs this one and then {@code next}. The gateway holds a single
     * listener, so this is how several are attached.
     *
     * <p>If the first throws, the second is skipped for that order and the gateway logs it, so
     * put the one whose record matters most - the audit log - first.
     */
    default MatchListener andThen(MatchListener next) {
        Objects.requireNonNull(next, "next");
        return (order, trades) -> {
            // calls onMatch on both the trade logger and public snapshot
            this.onMatch(order, trades);
            next.onMatch(order, trades);
        };
    }
}
