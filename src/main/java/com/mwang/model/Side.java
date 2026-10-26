package com.mwang.model;

/**
 * Which way an order trades. A {@code BUY} is a bid, a {@code SELL} is an ask. Compared with
 * {@code ==} throughout, which is safe because an enum has one instance per constant.
 */
public enum Side {
    BUY,
    SELL
}
