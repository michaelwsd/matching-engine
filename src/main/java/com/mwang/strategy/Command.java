package com.mwang.strategy;

import com.mwang.model.Trade;

import java.util.Objects;

/**
 * One instruction travelling through the strategy's queue to its own thread.
 *
 * <p>Sealed, so the runner's {@code switch} is exhaustive and a third instruction will not
 * compile until it is handled. {@link Shutdown} being a real case matters more here than
 * anywhere: a sentinel {@code Trade} reaching the strategy would be booked as a genuine
 * execution and corrupt its position, silently and permanently.
 *
 * <p>Package-private: callers use {@link StrategyRunner#onMatch} and {@link StrategyRunner#stop}.
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

    /**
     * Stop the strategy thread. Queued rather than signalled with a flag, so the fills already
     * accepted are delivered first - they are owed to the strategy's position.
     */
    record Shutdown() implements Command { }
}
