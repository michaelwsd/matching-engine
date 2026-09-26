package com.mwang.exception;

/**
 * Base type for conditions caused by a bad request rather than by a fault in the engine.
 *
 * <p>The distinction matters at a network boundary: a gateway can catch this one type and
 * reply with a rejection, while letting anything else propagate as a genuine fault. Adding
 * a new subclass later is then automatically covered by existing handlers.
 *
 * <p>Unchecked, because there is no recovery a caller can perform - a malformed order is a
 * bug in whatever produced it. Making it checked would force a try/catch around every
 * {@code new Order(...)} in the codebase for no benefit.
 */
public abstract class OrderException extends RuntimeException {

    protected OrderException(String message) {
        super(message);
    }

    protected OrderException(String message, Throwable cause) {
        super(message, cause);
    }
}
