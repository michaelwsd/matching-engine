package com.mwang.model;

/**
 * A completed execution between two orders.
 *
 * <p>A trade is a historical fact: once it has happened, nothing about it can change. That
 * makes it a value rather than an entity, which is why it is a record - two trades with
 * identical components are the same trade.
 *
 * @param buyOrderId  the resting or incoming order on the buy side
 * @param sellOrderId the resting or incoming order on the sell side
 * @param price       execution price in ticks, where one tick is one cent, so {@code 10001}
 *                    means $100.01. This is the <em>resting</em> order's price, not the
 *                    aggressor's: the resting order was there first and established the terms,
 *                    so an aggressive buyer willing to pay more receives price improvement.
 * @param quantity    the quantity exchanged, always positive
 * @param timestamp   execution time in milliseconds since the Unix epoch
 */
public record Trade(long buyOrderId, long sellOrderId, long price, int quantity, long timestamp) {

    public Trade {
        if (price <= 0) {
            throw new IllegalArgumentException("trade price must be positive: " + price);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("trade quantity must be positive: " + quantity);
        }
    }
}
