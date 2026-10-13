package com.mwang.model;

/**
 * A completed execution between two orders. A historical fact that can never change, which is
 * why it is a record rather than a class.
 *
 * @param buyOrderId  the order on the buy side
 * @param sellOrderId the order on the sell side
 * @param price       execution price in ticks of one cent. The <em>resting</em> order's price:
 *                    it was there first and set the terms, so an aggressor willing to pay more
 *                    receives price improvement.
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
