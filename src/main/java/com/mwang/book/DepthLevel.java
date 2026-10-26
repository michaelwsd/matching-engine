package com.mwang.book;

/**
 * One price level with every order at that price added together. The shape a market data feed
 * publishes: how much is available at a price, not which orders make it up.
 *
 * @param price    the level's price in ticks, one tick being one cent
 * @param quantity total unfilled quantity resting there. A {@code long} rather than
 *                 {@link com.mwang.model.Order}'s {@code int}, being a sum that could overflow.
 */
public record DepthLevel(long price, long quantity) { }
