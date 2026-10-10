package com.mwang.feed;

import com.mwang.book.BookSnapshot;
import com.mwang.book.DepthLevel;
import com.mwang.book.OrderBook;

import java.util.List;
import java.util.Objects;

/**
 * The outbound market data message: what the book looks like, as published to the world.
 *
 * <h2>Why this exists separately from {@link BookSnapshot}</h2>
 *
 * <p>{@code BookSnapshot} is a domain type, internal to the exchange. This is a public
 * contract. Keeping them apart buys three things.
 *
 * <p>It keeps a serialisation library out of {@code com.mwang.book}. The model, book and
 * engine packages currently import nothing outside the JDK, which is a property worth
 * defending - the rules of the order book should not depend on how anybody happens to
 * transmit them.
 *
 * <p>It gives the sentinel somewhere to die. {@link OrderBook#NO_PRICE} is {@code -1}, a
 * private convention inside the book; on the wire it would read as a price of minus one cent
 * to every consumer. Here it becomes {@code null}, which is how JSON says "there isn't one".
 *
 * <p>And it holds the two things a book does not know about itself: a sequence number and a
 * publication time. A snapshot of a book has no inherent position in a feed.
 *
 * <h2>Reading the fields</h2>
 *
 * <p>All prices are in <strong>ticks</strong>, where one tick is one cent, so {@code 10001}
 * means $100.01. JSON cannot carry the unit, so it is documented here instead. Prices are
 * never sent as decimals or strings, for the same reason they are never stored as
 * {@code double} internally.
 *
 * <p>{@code bestBid} or {@code bestAsk} being {@code null} means that side of the book is
 * <em>empty</em> - not that the price is unknown or withheld.
 *
 * <p>{@code sequence} increases by one per order processed, so a consumer can tell that it
 * missed an update rather than silently acting on stale data.
 *
 * <p>Deliberately absent: {@code spread} and {@code mid}. A consumer can subtract two numbers,
 * and a derived field on the wire is a field that can contradict the ones it came from. The
 * mid is worse still, since it falls on a half tick and could not be an integer.
 *
 * <p>{@link DepthLevel} is reused from the book package rather than redeclared. It is already
 * a two-component record of exactly the right shape and carries no behaviour to leak; a
 * parallel copy would be duplication for its own sake.
 *
 * <p><strong>The component order below is the JSON field order</strong>, because Jackson
 * serialises record components as declared. Reordering them changes the wire format.
 *
 * @param sequence  how many orders the engine had processed when this was taken
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
     * Defensive copies, because a record's generated constructor copies references rather than
     * contents - a record holding a mutable list is not itself immutable. {@code from} happens
     * to pass lists that are already unmodifiable, but this constructor is public and the
     * guarantee has to hold for every caller.
     *
     * <p>Note what is <em>not</em> validated: that the best bid is below the best ask. That
     * invariant belongs to the order book. A message type re-checking its source's invariants
     * adds noise and a second place to get it wrong.
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
     * @param book      the snapshot to publish; its lists are already immutable
     * @param sequence  how many orders had been processed when it was taken
     * @param timestamp publication time in milliseconds since the Unix epoch
     * @throws NullPointerException     if {@code book} is null
     * @throws IllegalArgumentException if {@code sequence} is negative
     */
    public static MarketDataSnapshot from(BookSnapshot book, long sequence, long timestamp) {
        Objects.requireNonNull(book, "book");
        return new MarketDataSnapshot(sequence, timestamp,
                price(book.bestBid()), price(book.bestAsk()),
                book.bids(), book.asks());
    }

    /** Translates the book's empty-side sentinel into the wire's way of saying the same thing. */
    private static Long price(long ticks) {
        return ticks == OrderBook.NO_PRICE ? null : ticks;
    }
}
