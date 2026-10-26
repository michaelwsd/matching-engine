package com.mwang.exception;

/**
 * Thrown when an operation names an order that is not resting on the book, typically a cancel
 * arriving after the order filled. Carries the id as a field so a handler need not parse
 * {@link #getMessage()}.
 */
public class UnknownOrderException extends OrderException {

    private final long orderId;

    public UnknownOrderException(long orderId) {
        super("Unknown order id: " + orderId);
        this.orderId = orderId;
    }

    /** @return the id that was not found */
    public long getOrderId() {
        return orderId;
    }
}
