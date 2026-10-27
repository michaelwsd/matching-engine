package com.mwang.strategy;

import com.mwang.book.BookSnapshot;
import com.mwang.gateway.OrderGateway;
import com.mwang.model.Order;
import com.mwang.model.OrderIdAllocator;
import com.mwang.model.OrderType;
import com.mwang.model.Side;
import com.mwang.model.Trade;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Quotes both sides of the book at a fixed half-spread, replacing its quotes on every requote
 * tick. Skews the centre away from its position, and withholds a side altogether once the
 * position reaches {@link #maxPosition}.
 *
 * <p>Confined to the strategy thread, like everything {@link StrategyRunner} calls, so none
 * of its state is synchronised.
 */
public final class SimpleMarketMaker implements Strategy {

    /** No live quote on that side. Zero is safe: {@link OrderIdAllocator} starts at 1. */
    private static final long NO_QUOTE = 0;

    private final OrderGateway gateway;
    /** Reads the snapshot the matcher publishes, keeping the book itself off this thread. */
    private final Supplier<BookSnapshot> book;
    private final OrderIdAllocator ids;

    private final long accountId;
    private final long halfSpread;
    private final int quoteSize;

    /** Where to quote before the book has two sides to take a mid from. */
    private final long referenceMid;

    /**
     * Hard ceiling on the absolute position: past this the side that would worsen it is not
     * quoted. Checked before posting, so a fill can overshoot it by up to {@link #quoteSize}.
     */
    private final long maxPosition;

    /** Ticks the quote centre moves at a full {@link #maxPosition}, scaled down below that. */
    private final long maxSkew;

    private final Position position = new Position();

    /**
     * Every order id this strategy has posted, so {@link #onFill} can pick out its own trades.
     * Never pruned: a cancelled quote keeps trading until the matcher reaches the cancel, and
     * a {@link Trade} does not say whether an order is finished.
     */
    private final Set<Long> myQuotes = new HashSet<>();

    /**
     * Ticks where {@link #maxPosition} kept a side off the book, for the end-of-run summary.
     * Plain, like {@link Position}: only safe to read once the strategy thread has stopped.
     */
    private long sidesWithheld;

    /** The two quotes to cancel on the next tick, or {@link #NO_QUOTE}. */
    private long bidId = NO_QUOTE;
    private long askId = NO_QUOTE;

    /**
     * @param gateway      where quotes are submitted and cancelled
     * @param book         supplies the latest published snapshot
     * @param ids          shared with every other submitter to this book
     * @param accountId    must differ from every account in the flow, or self-trade prevention
     *                     silently drops these quotes
     * @param halfSpread   ticks either side of the mid; must be positive
     * @param quoteSize    quantity on each side; must be positive
     * @param referenceMid fallback mid for an empty or one-sided book; must be positive
     * @param maxPosition  largest absolute position to hold; must be positive
     * @param maxSkew      ticks to shift the quote centre at a full position; must be positive
     * @throws IllegalArgumentException if any limit is not positive, or if {@code referenceMid}
     *                                  cannot carry the half-spread and a full skew
     */
    public SimpleMarketMaker(OrderGateway gateway,
                             Supplier<BookSnapshot> book,
                             OrderIdAllocator ids,
                             long accountId,
                             long halfSpread,
                             int quoteSize,
                             long referenceMid,
                             long maxPosition,
                             long maxSkew) {
        if (halfSpread <= 0) {
            throw new IllegalArgumentException("half spread must be positive: " + halfSpread);
        }
        if (quoteSize <= 0) {
            throw new IllegalArgumentException("quote size must be positive: " + quoteSize);
        }
        if (referenceMid <= 0) {
            throw new IllegalArgumentException("reference mid must be positive: " + referenceMid);
        }
        if (maxPosition <= 0) {
            throw new IllegalArgumentException("max position must be positive: " + maxPosition);
        }
        if (maxSkew <= 0) {
            throw new IllegalArgumentException("max skew must be positive: " + maxSkew);
        }
        // Otherwise a fully skewed bid prices at or below zero and Order rejects it.
        if (referenceMid <= halfSpread + maxSkew) {
            throw new IllegalArgumentException("reference mid " + referenceMid
                    + " cannot carry a half-spread of " + halfSpread
                    + " and a skew of " + maxSkew);
        }

        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.book = Objects.requireNonNull(book, "book");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.accountId = accountId;
        this.halfSpread = halfSpread;
        this.quoteSize = quoteSize;
        this.referenceMid = referenceMid;
        this.maxPosition = maxPosition;
        this.maxSkew = maxSkew;
    }

    /**
     * Books the trade against {@link #position} if either side of it was one of this
     * strategy's own quotes. Does not quote, so it cannot block.
     */
    @Override
    public void onFill(Trade trade) {
        // bid quote got filled
        if (myQuotes.contains(trade.buyOrderId())) {
            position.onFill(Side.BUY, trade.quantity(), trade.price());
        }

        // ask quote got filled
        else if (myQuotes.contains(trade.sellOrderId())) {
            position.onFill(Side.SELL, trade.quantity(), trade.price());
        }
    }

    /**
     * Cancels both live quotes and posts a fresh pair around the skewed mid, leaving out
     * whichever side would push the position further past {@link #maxPosition}.
     */
    @Override
    public void onRequote() throws InterruptedException {
        // Cancel before posting, or both pairs are live until the matcher reaches the cancels.
        // Cleared here so a withheld side keeps no id for a quote it no longer has.
        cancel(bidId);
        cancel(askId);
        bidId = NO_QUOTE;
        askId = NO_QUOTE;

        long held = position.quantity();
        // skew the quotes based on our current position (less flat = more skew)
        long quoteMid = currentMid() + skew(held);

        // Signed, so one comparison a side covers long and short without branching on which.
        if (held < maxPosition) {
            bidId = post(Side.BUY, quoteMid - halfSpread);
        } else {
            sidesWithheld++;
        }
        if (held > -maxPosition) {
            askId = post(Side.SELL, quoteMid + halfSpread);
        } else {
            sidesWithheld++;
        }
    }

    /**
     * The price to quote around. The book starts empty and often has one side only, so there is
     * frequently no real mid; {@link #referenceMid} stands in until there is.
     */
    private long currentMid() {
        BookSnapshot snapshot = book.get();
        if (snapshot.hasBothSides()) {
            // Truncates, so an odd spread puts the mid on the bid. Half a tick, documented
            // rather than corrected: a quoting bias this small is dwarfed by the spread.
            return snapshot.bestBid() + (snapshot.bestAsk() - snapshot.bestBid()) / 2;
        }
        return referenceMid;
    }

    /**
     * @param held the signed position
     * @return ticks to shift the quote centre, negative when long so the ask gets cheap and
     *         the bid expensive. Proportional to {@code held}, truncated towards zero.
     */
    private long skew(long held) {
        return -(held * maxSkew) / maxPosition;
    }

    /**
     * Submits one quote, records its id in {@link #myQuotes} and returns it. Blocks when the
     * gateway queue is full, which paces this strategy rather than the matcher.
     */
    private long post(Side side, long price) throws InterruptedException {
        Order newQuote = new Order(
                accountId,
                ids.next(),
                price,
                quoteSize,
                side,
                OrderType.LIMIT,
                System.currentTimeMillis()
        );

        // Registered before submitting, so an order is never on the book unrecorded.
        myQuotes.add(newQuote.getOrderId());
        gateway.submit(newQuote);
        return newQuote.getOrderId();
    }

    /**
     * Cancels a live quote, if there is one. A quote that already filled is not an error: the
     * gateway counts it in {@code cancelsMissed()}.
     */
    private void cancel(long orderId) throws InterruptedException {
        if (orderId != NO_QUOTE) {
            gateway.cancel(orderId);
        }
    }

    /**
     * @return how many times the position limit withheld a side. At most one a tick, since
     *         both sides cannot be at the limit at once.
     */
    public long sidesWithheld() {
        return sidesWithheld;
    }

    /** @return this strategy's position, for the end-of-run summary */
    public Position position() {
        return position;
    }
}
