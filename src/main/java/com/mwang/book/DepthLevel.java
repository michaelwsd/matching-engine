package com.mwang.book;

/**
 * One price level with every order at that price added together.
 *
 * <p>The shape a market data feed publishes: consumers care how much is available at a price,
 * not which orders make it up, and publishing those would leak who is in the market.
 *
 * @param price    the level's price in ticks, one tick being one cent
 * @param quantity total unfilled quantity resting there. A {@code long} rather than
 *                 {@link com.mwang.model.Order}'s {@code int}, being a sum that could overflow.
 */
public record DepthLevel(long price, long quantity) { }
