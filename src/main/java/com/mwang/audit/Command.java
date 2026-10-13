package com.mwang.audit;

import com.mwang.model.Trade;

import java.util.Objects;

/**
 * One instruction travelling through the trade log's queue to the writer thread.
 *
 * <p>Sealed, so the writer's {@code switch} is exhaustive and a third instruction will not
 * compile until it is handled. {@link Shutdown} being a real case is the point: a sentinel
 * {@code Trade} with values chosen only to satisfy validation was checked by nothing, and would
 * have been written to the CSV as a genuine execution had it ever reached {@code write}.
 *
 * <p>Package-private: callers use {@link TradeLogger#onMatch} and {@link TradeLogger#close}.
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

    /**
     * Stop the writer. Queued rather than signalled with a flag, so it sits behind every trade
     * already accepted - this is a consumer with a backlog it has promised to write.
     */
    record Shutdown() implements Command { }
}
