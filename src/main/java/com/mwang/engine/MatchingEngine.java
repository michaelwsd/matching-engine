package com.mwang.engine;

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
}
