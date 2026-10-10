package com.mwang.model;

/**
 * Which way an order trades.
 *
 * <p>A {@code BUY} is a bid, a {@code SELL} is an ask (also called an offer). The two
 * constants are compared with {@code ==} throughout: an enum has exactly one instance per
 * constant, so identity and equality are the same thing and {@code ==} cannot be fooled.
 */
public enum Side {
    BUY,
    SELL
}
