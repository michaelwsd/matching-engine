package com.mwang.book;

/**
 * One price level with every order at that price added together.
 *
 * <p>This is the shape a market data feed publishes. Consumers care how much is available at a
 * price, not which individual orders make it up - and publishing the orders themselves would
 * leak who is in the market.
 *
 * @param price    the level's price in ticks, one tick being one cent
 * @param quantity total unfilled quantity resting at that price. A {@code long} rather than
 *                 {@code int} like {@link com.mwang.model.Order}, because this is a sum over
 *                 many orders and could overflow one.
 */
public record DepthLevel(long price, long quantity) { }
