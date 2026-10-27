package com.mwang.strategy;

/**
 * A {@link Strategy} that quotes two-sided and publishes what it is holding, so a display or a
 * summary can read its position while it runs.
 */
public interface QuotingStrategy extends Strategy {

    /**
     * @return an immutable view of the position and the live quotes, safe to read from any
     *         thread while the strategy runs
     */
    StrategyStatus status();
}
