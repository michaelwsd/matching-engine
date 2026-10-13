package com.mwang.book;

import java.util.List;

/**
 * An immutable view of the top of the book, safe to hand to another thread.
 *
 * <p>Exists so the book itself never crosses a thread boundary. {@link OrderBook} is confined
 * to the matcher thread, which builds one of these between two orders and publishes it; readers
 * then hold a value that cannot change under them.
 *
 * @param bids    aggregated depth, best price first
 * @param asks    aggregated depth, best price first
 * @param bestBid highest bid, or {@link OrderBook#NO_PRICE} when there are none
 * @param bestAsk lowest ask, or {@link OrderBook#NO_PRICE} when there are none
 */
public record BookSnapshot(List<DepthLevel> bids, List<DepthLevel> asks,
                           long bestBid, long bestAsk) {

    /**
     * Copies the lists defensively: a record holding a mutable list is not itself immutable,
     * and that immutability is what makes this safe to share across threads.
     */
    public BookSnapshot {
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
    }

    /** @return true when both sides hold at least one order */
    public boolean hasBothSides() {
        return bestBid != OrderBook.NO_PRICE && bestAsk != OrderBook.NO_PRICE;
    }
}
