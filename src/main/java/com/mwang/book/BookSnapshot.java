package com.mwang.book;

import java.util.List;

/**
 * An immutable view of the top of the book, safe to hand to another thread, so the book itself
 * never crosses a thread boundary. The matcher builds one between two orders and publishes it.
 *
 * @param bids    aggregated depth, best price first
 * @param asks    aggregated depth, best price first
 * @param bestBid highest bid, or {@link OrderBook#NO_PRICE} when there are none
 * @param bestAsk lowest ask, or {@link OrderBook#NO_PRICE} when there are none
 */
public record BookSnapshot(List<DepthLevel> bids, List<DepthLevel> asks,
                           long bestBid, long bestAsk) {

    /** Copies the lists defensively: a record holding a mutable list is not itself immutable. */
    public BookSnapshot {
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
    }

    /** @return true when both sides hold at least one order */
    public boolean hasBothSides() {
        return bestBid != OrderBook.NO_PRICE && bestAsk != OrderBook.NO_PRICE;
    }

    /**
     * @return the midpoint of the spread, or {@link OrderBook#NO_PRICE} when either side is
     *         empty. Truncates, so an odd spread puts the mid on the bid. Written as an offset
     *         from the bid rather than {@code (bestBid + bestAsk) / 2}, which can overflow.
     */
    public long mid() {
        return hasBothSides() ? bestBid + (bestAsk - bestBid) / 2 : OrderBook.NO_PRICE;
    }
}
