package com.mwang.feed;

import com.mwang.book.BookSnapshot;
import com.mwang.book.DepthLevel;
import com.mwang.book.OrderBook;

import java.util.List;
import java.util.Objects;

/**
 * The order book as published to the outside world, serialised to JSON.
 *
 * <p>Kept separate from {@link BookSnapshot} so that the book package stays free of
 * serialisation concerns, and so the {@code -1} empty-side sentinel never reaches a consumer
 * as a price of minus one cent.
 *
 * <p>Prices are in <strong>ticks</strong>, one tick being one cent. Component order is the
 * JSON field order, so reordering is a breaking change. {@code spread} and {@code mid} are
 * deliberately absent: a consumer can derive them.
 *
 * @param sequence  orders processed when this was taken, so a consumer can detect a gap
 * @param timestamp publication time in milliseconds since the Unix epoch
 * @param bestBid   highest bid in ticks, or {@code null} when there are no bids
 * @param bestAsk   lowest ask in ticks, or {@code null} when there are no asks
 * @param bids      aggregated depth, highest price first
 * @param asks      aggregated depth, lowest price first
 */
public record MarketDataSnapshot(long sequence,
                                 long timestamp,
                                 Long bestBid,
                                 Long bestAsk,
                                 List<DepthLevel> bids,
                                 List<DepthLevel> asks) {

    /**
     * Copies the lists defensively: a record copies references, not contents, so one holding a
     * mutable list is not itself immutable. Deliberately does not check that the bid is below
     * the ask - that invariant belongs to the book, not to a message about it.
     */
    public MarketDataSnapshot {
        Objects.requireNonNull(bids, "bids");
        Objects.requireNonNull(asks, "asks");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative: " + sequence);
        }
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
    }

    /**
     * Converts an internal snapshot into a publishable message.
     *
     * @throws NullPointerException     if {@code book} is null
     * @throws IllegalArgumentException if {@code sequence} is negative
     */
    public static MarketDataSnapshot from(BookSnapshot book, long sequence, long timestamp) {
        Objects.requireNonNull(book, "book");
        return new MarketDataSnapshot(sequence, timestamp,
                price(book.bestBid()), price(book.bestAsk()),
                book.bids(), book.asks());
    }

    /** Turns the book's empty-side sentinel into the wire's way of saying the same thing. */
    private static Long price(long ticks) {
        return ticks == OrderBook.NO_PRICE ? null : ticks;
    }
}
