package com.mwang.strategy;

import com.mwang.model.Trade;

import java.util.Objects;

/**
 * One instruction travelling through the strategy's queue to its own thread.
 *
 * <p>Sealed, so the runner's {@code switch} is exhaustive and a fourth instruction will not
 * compile until it is handled. A typed {@link Shutdown} matters more here than anywhere: a
 * sentinel {@code Trade} would be booked as a real execution and corrupt the position.
 */
sealed interface Command {

    /** A trade that just executed, which may or may not involve this strategy. */
    record Fill(Trade trade) implements Command {
        // public because a record nested in an interface is implicitly public, and a
        // canonical constructor may not narrow that.
        public Fill {
            Objects.requireNonNull(trade, "trade");
        }
    }

    /** Replace the quotes. Carries no data: the strategy quotes around the current book. */
    record Requote() implements Command { }

    /** Stop the strategy thread, behind every fill already accepted. */
    record Shutdown() implements Command { }
}
