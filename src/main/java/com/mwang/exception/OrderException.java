package com.mwang.exception;

/**
 * Base type for a bad request, as opposed to a fault in the engine, so the gateway can catch one
 * type and reject while anything else propagates.
 *
 * <p>Unchecked: a caller cannot recover from a malformed order.
 */
public abstract class OrderException extends RuntimeException {

    protected OrderException(String message) {
        super(message);
    }

    protected OrderException(String message, Throwable cause) {
        super(message, cause);
    }
}
