package com.mwang.model;

import com.mwang.exception.InvalidOrderException;

import java.util.Objects;

/**
 * A single order submitted to the exchange.
 *
 * <p>An order is an entity rather than a value: it has an identity ({@code orderId}) that
 * outlives changes to its state. Its remaining quantity shrinks as the order partially
 * fills, which is why this is a class and not a record.
 *
 * <p>Instances are not thread-safe. All access is expected to be confined to the single
 * matcher thread.
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
     * @param accountId        the account that submitted the order, used for self-trade prevention
     * @param orderId          unique identifier for this order
     * @param price            limit price in ticks, where one tick is one cent, so {@code 10001}
     *                         means $100.01. Never a floating-point type: binary floating point
     *                         cannot represent decimal fractions exactly, so prices are scaled to
     *                         whole ticks and compared as exact integers. Ignored for
     *                         {@link OrderType#MARKET} orders, which have no price protection.
     * @param originalQuantity the quantity submitted, before any fills. Must be positive.
     * @param side             whether this order buys or sells
     * @param orderType        limit or market
     * @param timestamp        arrival time in milliseconds since the Unix epoch. Supplied by the
     *                         caller rather than read from the system clock, so that tests can
     *                         construct orders with controlled arrival times.
     * @throws InvalidOrderException if the quantity is not positive, or a limit order has a
     *                                  non-positive price
     * @throws NullPointerException     if {@code side} or {@code orderType} is null
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
     * <p>Asking to fill more than remains is treated as a programming error rather than
     * clamped, because it means the matching logic has lost track of this order's state.
     * Silently filling less than requested would leave the book quietly inconsistent.
     *
     * @param quantity the amount filled, which must be positive and no greater than
     *                 {@link #getRemainingQuantity()}
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

    public long getPrice() {
        return price;
    }

    public int getOriginalQuantity() {
        return originalQuantity;
    }

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
