package com.mwang.engine;

import com.mwang.exception.UnknownOrderException;
import com.mwang.model.Order;
import com.mwang.model.Trade;

import java.util.List;

/**
 * Applies one incoming order against the book, producing any trades it causes.
 *
 * <p>An interface so that a second allocation policy - pro-rata, where an order is filled in
 * proportion to size - can be added as a sibling implementation rather than a rewrite.
 */
@FunctionalInterface
public interface MatchingEngine {

    /**
     * Matches an incoming order against resting liquidity and returns the resulting trades
     * in execution order, earliest first.
     *
     * <p>An empty list means nothing traded, which may be because the order rested, because
     * there was no liquidity, or because it was rejected. Callers that need to tell those
     * apart will need a richer return type; nothing does yet.
     *
     * @param incoming the order to match, which may be mutated - its remaining quantity is
     *                 reduced by whatever fills, and it is added to the book if it rests
     * @return the trades produced, possibly empty, never null
     */
    List<Trade> submit(Order incoming);

    /**
     * Removes a resting order from the book. Must run on the thread that owns the book, so
     * reach it by queueing a cancel on the gateway rather than calling this directly.
     *
     * <p>A default rather than abstract, to keep this a {@link FunctionalInterface}. The
     * default throws, which is the truthful answer for a lambda: {@code order -> List.of()}
     * holds no book and has nothing to cancel. An engine that owns one overrides this.
     *
     * @throws UnknownOrderException         if no such order is resting. Routine rather than
     *                                       exceptional when a quote filled a moment earlier.
     * @throws UnsupportedOperationException if this engine has no book
     */
    default void cancel(long orderId) {
        throw new UnsupportedOperationException(
                "this matching engine does not support cancel: " + getClass().getName());
    }
}
