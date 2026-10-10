package com.mwang.model;

/**
 * How an order is priced.
 *
 * <p>A {@code LIMIT} order names the worst price it will accept, and rests on the book when it
 * cannot fill immediately. A {@code MARKET} order names no price at all: it takes whatever
 * liquidity is there and has any unfilled remainder cancelled, because there is no price at
 * which it could rest.
 */
public enum OrderType {
    LIMIT,
    MARKET
}
