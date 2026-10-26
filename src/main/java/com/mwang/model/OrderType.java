package com.mwang.model;

/**
 * How an order is priced. A {@code LIMIT} order names the worst price it will accept and rests
 * when it cannot fill. A {@code MARKET} order names no price, so any unfilled remainder is
 * cancelled rather than rested.
 */
public enum OrderType {
    LIMIT,
    MARKET
}
