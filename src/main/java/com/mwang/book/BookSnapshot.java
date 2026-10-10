package com.mwang.book;

import java.util.List;

/**
 * An immutable picture of one side-by-side view of the book at a moment in time.
 *
 * <p>Exists so that the book itself never crosses a thread boundary. {@link OrderBook} is not
 * thread-safe and is confined to the matcher thread; a reader on another thread that walked its
 * {@code TreeMap}s could get a {@code ConcurrentModificationException} or, worse, a
 * plausible-looking view stitched from two inconsistent reads - a crossed book that never
 * existed. The matcher builds one of these between two orders, when the book is quiet, and
 * publishes it. Readers then hold a value that cannot change under them.
 *
 * @param bids    aggregated depth, best price first
 * @param asks    aggregated depth, best price first
 * @param bestBid highest bid, or {@link OrderBook#NO_PRICE} when there are none
 * @param bestAsk lowest ask, or {@link OrderBook#NO_PRICE} when there are none
 */
public record BookSnapshot(List<DepthLevel> bids, List<DepthLevel> asks,
                           long bestBid, long bestAsk) {

    /**
     * A record's generated constructor copies references, not contents, so a record holding a
     * mutable list is not itself immutable. {@link List#copyOf} makes defensive, unmodifiable
     * copies, which is what lets this be shared across threads safely.
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
