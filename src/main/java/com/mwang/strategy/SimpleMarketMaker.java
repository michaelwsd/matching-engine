package com.mwang.strategy;

import com.mwang.book.BookSnapshot;
import com.mwang.gateway.OrderGateway;
import com.mwang.model.Order;
import com.mwang.model.OrderIdAllocator;
import com.mwang.model.OrderType;
import com.mwang.model.Side;
import com.mwang.model.Trade;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Quotes both sides of the book at a fixed half-spread around the mid, replacing its quotes on
 * every requote tick.
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

    private final Position position = new Position();

    /**
     * Every quote this strategy has posted, with the side it went out on, so {@link #onFill}
     * can pick its own trades out of the whole flow.
     *
     * <p>Holds far more than the two live quotes and is never pruned. Cancelling is
     * asynchronous, so a replaced quote keeps trading until the matcher reaches the cancel,
     * and a {@link Trade} does not say whether an order is finished, so no fill is a safe
     * moment to drop an id. Two entries per tick; a long-lived strategy would evict by age.
     */
    private final Map<Long, Side> myQuotes = new HashMap<>();

    /**
     * The two quotes to cancel on the next tick, or {@link #NO_QUOTE}. Unlike
     * {@link #myQuotes}, replacing a quote ends its life here immediately.
     */
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
     */
    public SimpleMarketMaker(OrderGateway gateway,
                             Supplier<BookSnapshot> book,
                             OrderIdAllocator ids,
                             long accountId,
                             long halfSpread,
                             int quoteSize,
                             long referenceMid) {
        if (halfSpread <= 0) {
            throw new IllegalArgumentException("half spread must be positive: " + halfSpread);
        }
        if (quoteSize <= 0) {
            throw new IllegalArgumentException("quote size must be positive: " + quoteSize);
        }
        if (referenceMid <= 0) {
            throw new IllegalArgumentException("reference mid must be positive: " + referenceMid);
        }

        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.book = Objects.requireNonNull(book, "book");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.accountId = accountId;
        this.halfSpread = halfSpread;
        this.quoteSize = quoteSize;
        this.referenceMid = referenceMid;
    }

    /**
     * Books the trade against {@link #position} if either side of it was one of this
     * strategy's own quotes. Does not quote, so it cannot block.
     */
    @Override
    public void onFill(Trade trade) {
        // bid quote got filled
        if (myQuotes.containsKey(trade.buyOrderId())) {
            position.onFill(Side.BUY, trade.quantity(), trade.price());
        }

        // ask quote got filled
        else if (myQuotes.containsKey(trade.sellOrderId())) {
            position.onFill(Side.SELL, trade.quantity(), trade.price());
        }
    }

    /** Cancels both live quotes and posts a fresh pair around the current mid. */
    @Override
    public void onRequote() throws InterruptedException {
        // cancel old quotes
        cancel(bidId);
        cancel(askId);

        // place a fresh pair around the current mid
        // Task 5.5 adds the risk limit here: skew or withhold a side once the position is
        // large.
        long mid = currentMid();
        bidId = post(Side.BUY, mid - halfSpread);
        askId = post(Side.SELL, mid + halfSpread);
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
     * Submits one quote, records it in {@link #myQuotes} and returns its id. Blocks when the
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
        myQuotes.put(newQuote.getOrderId(), side);
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

    /** @return this strategy's position, for the end-of-run summary */
    public Position position() {
        return position;
    }
}
