package com.mwang.strategy;

import com.mwang.book.OrderBook;
import com.mwang.model.Side;

import java.util.Objects;

/**
 * What a strategy holds and what it has made, in ticks. Starts flat; {@link #onFill} is the only
 * mutator.
 *
 * <p>Quantity is signed, positive long and negative short, so the arithmetic needs no separate
 * case for shorts. The stored field is the cost basis - the total paid for what is still held -
 * rather than an average price, which would truncate on every fill; {@link #averagePrice()} is
 * derived from it. A reduction is matched at that average rather than against the oldest lots.
 *
 * <p>Confined to the strategy thread, so nothing here is synchronised. Do not share it.
 */
public final class Position {

    /** Signed: positive long, negative short, zero flat. */
    private long quantity;

    /** Total paid for the open position, signed like {@link #quantity}. Zero when flat. */
    private long costBasis;

    private long realised;

    /** Starts flat. */
    public Position() { }

    private Position(long quantity, long costBasis, long realised) {
        this.quantity = quantity;
        this.costBasis = costBasis;
        this.realised = realised;
    }

    /**
     * @return an independent copy holding the same figures. Lets this position be published to
     *         another thread without exposing the one the strategy keeps mutating.
     */
    public Position copy() {
        return new Position(quantity, costBasis, realised);
    }

    /**
     * Folds one of the strategy's own fills in, realising P&amp;L on whatever it closes.
     *
     * @param side   which way the strategy traded
     * @param filled how much executed, always positive
     * @param price  the execution price in ticks
     * @throws NullPointerException     if {@code side} is null
     * @throws IllegalArgumentException if {@code filled} or {@code price} is not positive
     * @throws ArithmeticException      on overflow
     */
    public void onFill(Side side, int filled, long price) {
        Objects.requireNonNull(side, "side");
        if (filled <= 0) {
            throw new IllegalArgumentException("fill quantity must be positive: " + filled);
        }
        if (price <= 0) {
            throw new IllegalArgumentException("fill price must be positive: " + price);
        }

        long signed = side == Side.BUY ? filled : -filled; // how much quantity got filled
        // if direction doesn't change (long/short), simply update position
        if (quantity == 0 || (quantity > 0) == (signed > 0)) {
            updatePos(signed, price);
            return;
        }

        // close out the position by going flat
        long closed = Math.min(filled, Math.abs(quantity)); // capped at the position
        closePos(closed, price);

        // update position with remaining
        long remaining = filled - closed;
        if (remaining > 0) {
            updatePos(signed > 0 ? remaining : -remaining, price);
        }
    }

    /** Adds {@code signed} units at {@code price}. Nothing closes, so realised P&amp;L is untouched. */
    private void updatePos(long signed, long price) {
        // updates position quantity and cost basis
        quantity += signed;
        costBasis = Math.addExact(costBasis, Math.multiplyExact(signed, price));
    }

    /**
     * Closes {@code closed} units at {@code price} and realises them against what they cost.
     * Never moves past flat; {@link #onFill} reopens any remainder.
     *
     * <p>{@code basisOut} is what those units cost, positive for a short as well as a long. Must
     * read {@code quantity} before writing it.
     */
    private void closePos(long closed, long price) {
        long basisOut = Math.multiplyExact(costBasis, closed) / quantity; // what they cost
        long sign = quantity > 0 ? 1 : -1;

        // calculate how much is made/lost by closing them
        realised = Math.addExact(realised,
                sign * Math.subtractExact(Math.multiplyExact(closed, price), basisOut));
        costBasis -= sign * basisOut;
        quantity -= sign * closed;
    }

    /** @return signed net position: positive long, negative short, zero flat */
    public long quantity() {
        return quantity;
    }

    /** @return total paid for what is still held, in ticks */
    public long costBasis() {
        return costBasis;
    }

    /** @return P&amp;L locked in by fills that closed earlier ones, in ticks */
    public long realised() {
        return realised;
    }

    /**
     * @param mid the price to mark against
     * @return paper P&amp;L on what is still held, in ticks. Zero when flat.
     * @throws IllegalArgumentException if holding a position and {@code mid} is not positive.
     *                                  An empty or one-sided book has no mid to mark against.
     */
    public long unrealised(long mid) {
        if (quantity == 0) {
            return 0;
        }
        if (mid <= 0) {
            throw new IllegalArgumentException("cannot mark a position against mid " + mid);
        }
        return Math.subtractExact(Math.multiplyExact(quantity, mid), costBasis);
    }

    /**
     * @return {@link #realised()} plus {@link #unrealised(long)}, in ticks
     * @throws IllegalArgumentException as for {@link #unrealised(long)}
     */
    public long total(long mid) {
        return Math.addExact(realised, unrealised(mid));
    }

    /**
     * @return what the open position cost per unit, in ticks, or {@link OrderBook#NO_PRICE} when
     *         flat. Truncated, so use {@link #costBasis()} for arithmetic.
     */
    public long averagePrice() {
        return quantity == 0 ? OrderBook.NO_PRICE : costBasis / quantity;
    }

    @Override
    public String toString() {
        return "Position[quantity=" + quantity + ", averagePrice=" + averagePrice()
                + ", costBasis=" + costBasis + ", realised=" + realised + "]";
    }
}
