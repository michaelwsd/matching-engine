package com.mwang.strategy;

import com.mwang.model.Trade;

/**
 * Decides what to quote, given what the market has done and what the clock says.
 *
 * <p>Every method runs on the strategy thread, one call at a time, so a strategy's own state
 * needs no synchronisation and may block freely. It holds its own gateway and book snapshot
 * supplier; this interface passes neither, keeping {@link StrategyRunner} free of trading logic.
 */
@FunctionalInterface
public interface Strategy {

    /**
     * Reports a trade that just executed. Receives <em>every</em> trade on the book, so a
     * strategy that cares only about its own must filter by order id.
     *
     * @throws InterruptedException if interrupted while submitting. Propagate it: the runner
     *                              interrupts this thread to shut it down.
     */
    void onFill(Trade trade) throws InterruptedException;

    /**
     * Invites the strategy to replace its quotes around the book as it is now. Clock-driven, so
     * it carries nothing, and ticks are skipped when the strategy is behind - never assume one
     * call per interval.
     *
     * <p>A quoting strategy <strong>must</strong> override this: it is the only call that
     * arrives before the first trade, so the default means never posting an opening quote.
     *
     * @throws InterruptedException as for {@link #onFill}
     */
    default void onRequote() throws InterruptedException { }
}
