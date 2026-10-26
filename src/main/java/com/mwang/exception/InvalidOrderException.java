package com.mwang.exception;

/**
 * Thrown when an order cannot exist as described: a non-positive quantity, a limit order without
 * a usable price, or an id already in use on the book.
 */
public class InvalidOrderException extends OrderException {

    public InvalidOrderException(String message) {
        super(message);
    }

    public InvalidOrderException(String message, Throwable cause) {
        super(message, cause);
    }
}
