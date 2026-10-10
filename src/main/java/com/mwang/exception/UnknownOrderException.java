package com.mwang.exception;

/**
 * Thrown when an operation names an order that is not resting on the book - typically a
 * cancel arriving after the order has already filled or been cancelled.
 *
 * <p>Carries the id as a field rather than only inside the message, so a handler can act on
 * it without parsing text back out of {@link #getMessage()}.
 */
public class UnknownOrderException extends OrderException {

    private final long orderId;

    public UnknownOrderException(long orderId) {
        super("Unknown order id: " + orderId);
        this.orderId = orderId;
    }

    /** @return the id that was not found, for a handler that needs to report it back */
    public long getOrderId() {
        return orderId;
    }
}
