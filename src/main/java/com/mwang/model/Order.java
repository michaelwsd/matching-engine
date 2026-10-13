package com.mwang.model;

import com.mwang.exception.InvalidOrderException;

import java.util.Objects;

/**
 * A single order submitted to the exchange.
 *
 * <p>An entity rather than a value - its id outlives changes to its state and its remaining
 * quantity shrinks as it fills - which is why it is a class and not a record.
 *
 * <p>Not thread-safe; confined to the matcher thread.
 */
public class Order {

    private final long accountId;
    private final long orderId;
    private final long price;
    private final int originalQuantity;
    private final Side side;
    private final OrderType orderType;
    private final long timestamp;

    /** Quantity still resting and available to fill. The only mutable state on this class. */
    private int remainingQuantity;

    /**
     * @param accountId        the submitting account, used for self-trade prevention
     * @param orderId          unique identifier for this order
     * @param price            limit price in ticks of one cent, so {@code 10001} is $100.01.
     *                         Never floating point, since binary floats cannot represent
     *                         decimal fractions exactly. Ignored for {@link OrderType#MARKET}.
     * @param originalQuantity the quantity submitted, before any fills; must be positive
     * @param side             whether this order buys or sells
     * @param orderType        limit or market
     * @param timestamp        arrival time in milliseconds since the Unix epoch, supplied by
     *                         the caller so tests can control arrival order
     * @throws InvalidOrderException if the quantity is not positive, or a limit order has a
     *                               non-positive price
     * @throws NullPointerException  if {@code side} or {@code orderType} is null
     */
    public Order(long accountId,
                 long orderId,
                 long price,
                 int originalQuantity,
                 Side side,
                 OrderType orderType,
                 long timestamp) {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(orderType, "orderType");
        if (originalQuantity <= 0) {
            throw new InvalidOrderException("order quantity must be positive: " + originalQuantity);
        }
        if (orderType == OrderType.LIMIT && price <= 0) {
            throw new InvalidOrderException("price must be positive for a limit order: " + price);
        }

        this.accountId = accountId;
        this.orderId = orderId;
        this.price = price;
        this.originalQuantity = originalQuantity;
        this.side = side;
        this.orderType = orderType;
        this.timestamp = timestamp;
        this.remainingQuantity = originalQuantity;
    }

    /**
     * Reduces the remaining quantity by the amount filled.
     *
     * <p>Overfilling throws rather than clamping: it means the matching logic has lost track of
     * this order, and filling less would leave the book quietly inconsistent.
     *
     * @throws IllegalArgumentException if the quantity is not positive or exceeds what remains
     */
    public void fill(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("fill quantity must be positive: " + quantity);
        }
        if (quantity > remainingQuantity) {
            throw new IllegalArgumentException(
                    "cannot fill " + quantity + " on order " + orderId
                            + ": only " + remainingQuantity + " remaining");
        }
        remainingQuantity -= quantity;
    }

    /** @return true once this order has no quantity left to fill */
    public boolean isFullyFilled() {
        return remainingQuantity == 0;
    }

    public long getAccountId() {
        return accountId;
    }

    public long getOrderId() {
        return orderId;
    }

    /** @return the limit price in ticks. Meaningless for a {@link OrderType#MARKET} order. */
    public long getPrice() {
        return price;
    }

    /** @return the quantity submitted, which never changes. Contrast
     *  {@link #getRemainingQuantity()}. */
    public int getOriginalQuantity() {
        return originalQuantity;
    }

    /** @return the quantity still available to fill, which shrinks as this order trades */
    public int getRemainingQuantity() {
        return remainingQuantity;
    }

    public Side getSide() {
        return side;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "Order[" + orderId + " " + side + " " + orderType
                + " " + remainingQuantity + "/" + originalQuantity
                + " @ " + price + "]";
    }
}
