package com.mwang.strategy;

import com.mwang.book.OrderBook;

/**
 * An immutable view of what a strategy holds and what it is currently quoting, published for
 * another thread to read while the strategy runs.
 *
 * <p>{@link #position} is copied on the way in, so this record never changes once built. The
 * copy handed back by the accessor must not be mutated.
 *
 * @param position      the position as it stood when this was published
 * @param bidPrice      the live bid, or {@link OrderBook#NO_PRICE} when that side is withheld
 * @param askPrice      the live ask, or {@link OrderBook#NO_PRICE} when that side is withheld
 * @param quoteSize     quantity on each side
 * @param sidesWithheld how many times a position limit has kept a side off the book
 * @param quotesPosted  how many quotes the strategy has submitted
 * @param fills         what of the market's trades were the strategy's own
 */
public record StrategyStatus(Position position, long bidPrice, long askPrice, int quoteSize,
                             long sidesWithheld, long quotesPosted, Fills fills) {

    /**
     * The strategy's own executions, counted and in lots. {@link #boughtLots} minus
     * {@link #soldLots} must equal {@link StrategyStatus#position}'s quantity; a lopsided pair
     * is what inventory building up against the strategy looks like.
     *
     * @param count      how many of the market's trades were the strategy's own
     * @param boughtLots quantity its bids took
     * @param soldLots   quantity its asks gave
     */
    public record Fills(long count, long boughtLots, long soldLots) {

        /** @return a strategy that has not traded yet */
        public static Fills none() {
            return new Fills(0, 0, 0);
        }

        /** @return the net of the two sides, which is the position the fills built */
        public long netLots() {
            return boughtLots - soldLots;
        }
    }

    /** Copies the position defensively: a record holding a mutable field is not itself immutable. */
    public StrategyStatus {
        position = position.copy();
    }

    /** @return a flat position quoting nothing, for before the first requote */
    public static StrategyStatus flat(int quoteSize) {
        return new StrategyStatus(new Position(), OrderBook.NO_PRICE, OrderBook.NO_PRICE,
                quoteSize, 0, 0, Fills.none());
    }

    /** @return true when both sides are on the book, so a quoted spread exists */
    public boolean hasBothQuotes() {
        return bidPrice != OrderBook.NO_PRICE && askPrice != OrderBook.NO_PRICE;
    }
}
