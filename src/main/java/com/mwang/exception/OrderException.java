package com.mwang.exception;

/**
 * Base type for a bad request, as opposed to a fault in the engine.
 *
 * <p>Lets the gateway catch one type and reply with a rejection while anything else propagates
 * as a genuine fault - and a subclass added later is covered by existing handlers for free.
 *
 * <p>Unchecked: a caller cannot recover from a malformed order, so making it checked would
 * only add a try/catch around every {@code new Order(...)}.
 */
public abstract class OrderException extends RuntimeException {

    protected OrderException(String message) {
        super(message);
    }

    protected OrderException(String message, Throwable cause) {
        super(message, cause);
    }
}
