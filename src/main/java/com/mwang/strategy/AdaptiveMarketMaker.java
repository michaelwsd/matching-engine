package com.mwang.strategy;

import com.mwang.book.BookSnapshot;
import com.mwang.book.OrderBook;
import com.mwang.gateway.OrderGateway;
import com.mwang.model.Order;
import com.mwang.model.OrderIdAllocator;
import com.mwang.model.OrderType;
import com.mwang.model.Side;
import com.mwang.model.Trade;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Quotes both sides of the book at a half-spread proportional to how much the mid has been
 * moving, skews the centre against its position, and withholds a side at
 * {@link #maxPosition}.
 *
 * <p>The fixed half-spread in {@link SimpleMarketMaker} is the thing that loses money. A quote
 * narrower than the market's uncertainty is picked off on every move; a quote wider than it
 * never trades. Neither width is right for both a quiet market and a fast one, so this measures
 * the movement and sizes the spread from it.
 *
 * <p>Confined to the strategy thread, like everything {@link StrategyRunner} calls, so none of
 * its state is synchronised. {@link #status()} is the one exception.
 */
public final class AdaptiveMarketMaker implements QuotingStrategy {

    /** No live quote on that side. Zero is safe: {@link OrderIdAllocator} starts at 1. */
    private static final long NO_QUOTE = 0;

    /** Volatility is held scaled, so an average below one tick does not truncate to zero. */
    private static final long SIGMA_SCALE = 1_000;

    /** Samples the volatility average effectively spans. Larger is steadier but slower. */
    private static final long SIGMA_WINDOW = 8;

    /** Denominator for the two parameters expressed in tenths. */
    private static final long TENTHS = 10;

    private final OrderGateway gateway;
    private final Supplier<BookSnapshot> book;
    private final OrderIdAllocator ids;

    private final long accountId;
    private final int quoteSize;

    /** Where to quote before the book has two sides to take a mid from. */
    private final long referenceMid;

    private final long maxPosition;
    private final long minHalfSpread;
    private final long maxHalfSpread;

    /** Half-spread per tick of measured volatility, in tenths. */
    private final long spreadMultipleTenths;

    /** Full-position skew as a fraction of the current half-spread, in tenths. */
    private final long skewFractionTenths;

    private final Position position = new Position();

    /**
     * Every order id this strategy has posted, so {@link #onFill} can pick out its own trades.
     * Never pruned: a cancelled quote keeps trading until the matcher reaches the cancel, and a
     * {@link Trade} does not say whether an order is finished.
     */
    private final Set<Long> myQuotes = new HashSet<>();

    /** Mean absolute mid move between requotes, scaled by {@link #SIGMA_SCALE}. */
    private long sigmaScaled;
    private boolean volatilitySeeded;
    private long previousMid = OrderBook.NO_PRICE;

    private long bidId = NO_QUOTE;
    private long askId = NO_QUOTE;
    private long bidPrice = OrderBook.NO_PRICE;
    private long askPrice = OrderBook.NO_PRICE;

    private long sidesWithheld;
    private long quotesPosted;
    private long ownFills;
    private long boughtLots;
    private long soldLots;

    private final AtomicReference<StrategyStatus> status;

    /**
     * @param gateway              where quotes are submitted and cancelled
     * @param book                 supplies the latest published snapshot
     * @param ids                  shared with every other submitter to this book
     * @param accountId            must differ from every account in the flow, or self-trade
     *                             prevention silently drops these quotes
     * @param quoteSize            quantity on each side; must be positive
     * @param referenceMid         fallback mid for an empty or one-sided book; must be positive
     * @param maxPosition          largest absolute position to hold; must be positive
     * @param minHalfSpread        narrowest half-spread to quote, however quiet; must be positive
     * @param maxHalfSpread        widest half-spread to quote, and the width used before any
     *                             volatility has been measured; must be at least
     *                             {@code minHalfSpread}
     * @param spreadMultipleTenths half-spread per tick of measured volatility, in tenths; must
     *                             be positive
     * @param skewFractionTenths   skew at a full position as a fraction of the current
     *                             half-spread, in tenths; must be positive
     * @throws IllegalArgumentException if any limit is out of range, or if {@code referenceMid}
     *                                  cannot carry the widest quote and a full skew
     */
    public AdaptiveMarketMaker(OrderGateway gateway,
                               Supplier<BookSnapshot> book,
                               OrderIdAllocator ids,
                               long accountId,
                               int quoteSize,
                               long referenceMid,
                               long maxPosition,
                               long minHalfSpread,
                               long maxHalfSpread,
                               long spreadMultipleTenths,
                               long skewFractionTenths) {
        if (quoteSize <= 0) {
            throw new IllegalArgumentException("quote size must be positive: " + quoteSize);
        }
        if (referenceMid <= 0) {
            throw new IllegalArgumentException("reference mid must be positive: " + referenceMid);
        }
        if (maxPosition <= 0) {
            throw new IllegalArgumentException("max position must be positive: " + maxPosition);
        }
        if (minHalfSpread <= 0) {
            throw new IllegalArgumentException(
                    "min half spread must be positive: " + minHalfSpread);
        }
        if (maxHalfSpread < minHalfSpread) {
            throw new IllegalArgumentException("max half spread " + maxHalfSpread
                    + " is below the min of " + minHalfSpread);
        }
        if (spreadMultipleTenths <= 0) {
            throw new IllegalArgumentException(
                    "spread multiple must be positive: " + spreadMultipleTenths);
        }
        if (skewFractionTenths <= 0) {
            throw new IllegalArgumentException(
                    "skew fraction must be positive: " + skewFractionTenths);
        }
        // Otherwise the widest fully skewed bid prices at or below zero and Order rejects it.
        long widestOffset = maxHalfSpread + (maxHalfSpread * skewFractionTenths) / TENTHS;
        if (referenceMid <= widestOffset) {
            throw new IllegalArgumentException("reference mid " + referenceMid
                    + " cannot carry a quote offset of " + widestOffset);
        }

        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.book = Objects.requireNonNull(book, "book");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.accountId = accountId;
        this.quoteSize = quoteSize;
        this.referenceMid = referenceMid;
        this.maxPosition = maxPosition;
        this.minHalfSpread = minHalfSpread;
        this.maxHalfSpread = maxHalfSpread;
        this.spreadMultipleTenths = spreadMultipleTenths;
        this.skewFractionTenths = skewFractionTenths;
        this.status = new AtomicReference<>(StrategyStatus.flat(quoteSize));
    }

    /** Books the trade against {@link #position} if either side of it was one of these quotes. */
    @Override
    public void onFill(Trade trade) {
        if (myQuotes.contains(trade.buyOrderId())) {
            position.onFill(Side.BUY, trade.quantity(), trade.price());
            ownFills++;
            boughtLots += trade.quantity();
            publish();
        } else if (myQuotes.contains(trade.sellOrderId())) {
            position.onFill(Side.SELL, trade.quantity(), trade.price());
            ownFills++;
            soldLots += trade.quantity();
            publish();
        }
    }

    /**
     * Cancels both live quotes, measures how far the mid has moved since the last tick, and
     * posts a fresh pair whose width comes from that movement.
     */
    @Override
    public void onRequote() throws InterruptedException {
        // Cancel before posting, or both pairs are live until the matcher reaches the cancels.
        cancel(bidId);
        cancel(askId);
        bidId = NO_QUOTE;
        askId = NO_QUOTE;
        bidPrice = OrderBook.NO_PRICE;
        askPrice = OrderBook.NO_PRICE;

        long mid = currentMid();
        updateVolatility(mid);

        long halfSpread = halfSpread();
        long held = position.quantity();
        long quoteMid = mid + skew(held, halfSpread);

        // Signed, so one comparison a side covers long and short without branching on which.
        if (held < maxPosition) {
            bidPrice = quoteMid - halfSpread;
            bidId = post(Side.BUY, bidPrice);
        } else {
            sidesWithheld++;
        }
        if (held > -maxPosition) {
            askPrice = quoteMid + halfSpread;
            askId = post(Side.SELL, askPrice);
        } else {
            sidesWithheld++;
        }

        publish();
    }

    /**
     * Folds this tick's mid move into the volatility average. Measured per requote, not per
     * second, so the spread this produces depends on the requote interval.
     */
    private void updateVolatility(long mid) {
        if (previousMid != OrderBook.NO_PRICE) {
            long move = Math.abs(mid - previousMid) * SIGMA_SCALE;
            // Seeded from the first move rather than from zero, which would start it tight.
            sigmaScaled = volatilitySeeded
                    ? sigmaScaled + (move - sigmaScaled) / SIGMA_WINDOW
                    : move;
            volatilitySeeded = true;
        }
        previousMid = mid;
    }

    /**
     * @return the half-spread to quote, from the measured volatility and bounded either side.
     *         Widest until a move has been measured: an unfilled quote costs nothing, while a
     *         quote narrower than an unknown volatility is picked off.
     */
    private long halfSpread() {
        if (!volatilitySeeded) {
            return maxHalfSpread;
        }
        long fromVolatility = (sigmaScaled * spreadMultipleTenths) / (SIGMA_SCALE * TENTHS);
        return Math.clamp(fromVolatility, minHalfSpread, maxHalfSpread);
    }

    /**
     * @return ticks to shift the quote centre, negative when long so the ask gets cheap and the
     *         bid expensive. Scaled to {@code halfSpread}, so widening the spread widens the
     *         skew with it and a full position always moves the quotes by the same proportion.
     */
    private long skew(long held, long halfSpread) {
        long maxSkew = (halfSpread * skewFractionTenths) / TENTHS;
        return -(held * maxSkew) / maxPosition;
    }

    /**
     * The price to quote around. The book starts empty and often has one side only, so there is
     * frequently no real mid; {@link #referenceMid} stands in until there is.
     */
    private long currentMid() {
        long mid = book.get().mid();
        return mid == OrderBook.NO_PRICE ? referenceMid : mid;
    }

    private long post(Side side, long price) throws InterruptedException {
        Order newQuote = new Order(accountId, ids.next(), price, quoteSize, side,
                OrderType.LIMIT, System.currentTimeMillis());

        // Registered before submitting, so an order is never on the book unrecorded.
        myQuotes.add(newQuote.getOrderId());
        gateway.submit(newQuote);
        quotesPosted++;
        return newQuote.getOrderId();
    }

    private void cancel(long orderId) throws InterruptedException {
        if (orderId != NO_QUOTE) {
            gateway.cancel(orderId);
        }
    }

    private void publish() {
        status.set(new StrategyStatus(position, bidPrice, askPrice, quoteSize, sidesWithheld,
                quotesPosted, new StrategyStatus.Fills(ownFills, boughtLots, soldLots)));
    }

    /** Republished on every fill and every requote. */
    @Override
    public StrategyStatus status() {
        return status.get();
    }

    /** @return the half-spread the next tick would quote, in ticks. For tests and diagnostics. */
    public long currentHalfSpread() {
        return halfSpread();
    }
}
