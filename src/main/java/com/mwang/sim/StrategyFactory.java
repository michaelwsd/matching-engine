package com.mwang.sim;

import com.mwang.book.BookSnapshot;
import com.mwang.gateway.OrderGateway;
import com.mwang.model.OrderIdAllocator;
import com.mwang.strategy.AdaptiveMarketMaker;
import com.mwang.strategy.QuotingStrategy;
import com.mwang.strategy.SimpleMarketMaker;

import java.util.function.Supplier;

/**
 * Builds the strategy a run quotes with. A factory rather than another {@link SimulationConfig}
 * field because each strategy takes different parameters, and the runner only needs the three
 * things they share.
 */
@FunctionalInterface
public interface StrategyFactory {

    /**
     * @param gateway where the strategy submits and cancels
     * @param book    supplies the latest published snapshot
     * @param ids     shared with the synthetic flow, so quotes cannot collide with it
     * @param config  for the account, quote size and limits every strategy needs
     */
    QuotingStrategy create(OrderGateway gateway,
                           Supplier<BookSnapshot> book,
                           OrderIdAllocator ids,
                           SimulationConfig config);

    /**
     * The fixed-half-spread maker of task 5.3. Profitable in a calm market and a coin flip in a
     * fast one, because no single width suits both.
     */
    static StrategyFactory simple() {
        return (gateway, book, ids, config) -> new SimpleMarketMaker(gateway, book, ids,
                config.strategyAccount(), config.halfSpread(), config.quoteSize(),
                config.referenceMid(), config.maxPosition(), config.maxSkew());
    }

    /**
     * The volatility-scaled maker, at the parameters the sweep in {@code README.md} settled on.
     *
     * @see AdaptiveMarketMaker
     */
    static StrategyFactory adaptive() {
        return adaptive(4, 60, 40, 10);
    }

    /** @see AdaptiveMarketMaker#AdaptiveMarketMaker for what each parameter means */
    static StrategyFactory adaptive(long minHalfSpread, long maxHalfSpread,
                                    long spreadMultipleTenths, long skewFractionTenths) {
        return (gateway, book, ids, config) -> new AdaptiveMarketMaker(gateway, book, ids,
                config.strategyAccount(), config.quoteSize(), config.referenceMid(),
                config.maxPosition(), minHalfSpread, maxHalfSpread, spreadMultipleTenths,
                skewFractionTenths);
    }
}
