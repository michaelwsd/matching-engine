package com.mwang.strategy;

import com.mwang.model.Trade;

/**
 * Decides what to do about one of its own fills.
 *
 * <p>Called on the strategy thread, never the matcher thread - see {@link StrategyRunner} for
 * why that distinction is the point. A strategy may therefore block: submitting to a full
 * gateway queue paces it to whatever the exchange can absorb, and slows nothing but itself.
 *
 * <p>It holds its own collaborators - the gateway it quotes into, and a supplier of the latest
 * {@link com.mwang.book.BookSnapshot} to quote around. This interface deliberately passes
 * neither, so that the runner stays pure plumbing with no opinion about trading.
 *
 * <p>Receives <em>every</em> trade on the book, not only its own. Filtering by order id happens
 * here rather than in the runner, so the set of live quote ids stays confined to this thread.
 */
@FunctionalInterface
public interface Strategy {

    /**
     * @param trade a trade that just executed, which may or may not involve this strategy
     * @throws InterruptedException if interrupted while submitting. Propagate it - the runner
     *                              uses interruption to shut this thread down.
     */
    void onFill(Trade trade) throws InterruptedException;
}
