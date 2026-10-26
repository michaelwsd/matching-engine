package com.mwang.audit;

import com.mwang.model.Trade;

import java.util.Objects;

/**
 * One instruction travelling through the trade log's queue to the writer thread.
 *
 * <p>Sealed, so the writer's {@code switch} is exhaustive and a third instruction will not
 * compile until it is handled. A typed {@link Shutdown} is the point: a sentinel {@code Trade}
 * would have been written to the CSV as a genuine execution.
 */
sealed interface Command {

    /** Append this trade to the audit trail. */
    record Append(Trade trade) implements Command {
        // public because a record nested in an interface is implicitly public, and a
        // canonical constructor may not narrow that.
        public Append {
            Objects.requireNonNull(trade, "trade");
        }
    }

    /** Stop the writer, behind every trade already accepted and promised to the file. */
    record Shutdown() implements Command { }
}
