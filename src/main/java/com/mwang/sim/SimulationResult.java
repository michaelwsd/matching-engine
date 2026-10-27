package com.mwang.sim;

import com.mwang.book.BookSnapshot;
import com.mwang.strategy.StrategyStatus;

import java.time.Duration;

/**
 * What one run produced. Every figure is read after the threads have been joined, so this is a
 * complete and final account rather than a snapshot.
 *
 * @param duration          how long the market ran for
 * @param shutdownMillis    how long the orderly shutdown took
 * @param ordersSubmitted   orders the synthetic flow sent
 * @param ordersProcessed   orders the matcher accepted
 * @param tradesExecuted    trades the matcher produced
 * @param ordersRejected    orders the matcher refused
 * @param ordersCancelled   cancels the matcher applied
 * @param cancelsMissed     cancels naming an order that had already gone. Expected, not a fault
 * @param tradesWritten     trades the audit log recorded
 * @param tradesDropped     trades the audit log could not keep up with. Any drop is a fault
 * @param fillsDelivered    trades that reached the strategy
 * @param fillsDropped      trades the strategy queue lost. Any drop is a fault
 * @param strategyFailures  calls the strategy threw out of. Any failure is a fault
 * @param requoteTicks      requote ticks that ran
 * @param requoteFailures   requote ticks that threw. Any failure is a fault
 * @param requotesSkipped   ticks dropped because the strategy was behind. Not a fault
 * @param strategy          the strategy's final position and last quotes
 * @param markMid           the price the open position is marked against
 * @param markedAgainstBook whether {@code markMid} came from the closing book or the fallback
 * @param finalBook         the book as the matcher left it
 */
public record SimulationResult(Duration duration,
                               long shutdownMillis,
                               long ordersSubmitted,
                               long ordersProcessed,
                               long tradesExecuted,
                               long ordersRejected,
                               long ordersCancelled,
                               long cancelsMissed,
                               long tradesWritten,
                               long tradesDropped,
                               long fillsDelivered,
                               long fillsDropped,
                               long strategyFailures,
                               long requoteTicks,
                               long requoteFailures,
                               long requotesSkipped,
                               StrategyStatus strategy,
                               long markMid,
                               boolean markedAgainstBook,
                               BookSnapshot finalBook) {

    /** @return the strategy's signed net position: positive long, negative short, zero flat */
    public long position() {
        return strategy.position().quantity();
    }

    /** @return P&amp;L locked in by fills that closed earlier ones, in ticks */
    public long realised() {
        return strategy.position().realised();
    }

    /** @return paper P&amp;L on the open position at {@link #markMid()}, in ticks */
    public long unrealised() {
        return strategy.position().unrealised(markMid);
    }

    /** @return {@link #realised()} plus {@link #unrealised()}, in ticks */
    public long total() {
        return strategy.position().total(markMid);
    }
}
