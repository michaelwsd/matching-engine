package com.mwang.engine;

import com.mwang.book.OrderBook;
import com.mwang.model.Order;
import com.mwang.model.OrderType;
import com.mwang.model.Side;
import com.mwang.model.Trade;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Matches incoming orders against resting liquidity using price-time priority: better prices
 * fill first, and at equal price the order that arrived first fills first.
 *
 * <p>The book is supplied rather than created here, so the demo, the snapshot endpoint and any
 * strategy can read it without going through the engine. The engine expects to be its only
 * writer, enforced by confining both to the gateway's matcher thread.
 *
 * <p>Not thread-safe.
 */
public final class PriceTimeMatchingEngine implements MatchingEngine {

    private final OrderBook book;

    /**
     * @param book the book to match against and rest remainders on; this engine expects to be
     *             its only writer
     * @throws NullPointerException if {@code book} is null
     */
    public PriceTimeMatchingEngine(OrderBook book) {
        this.book = Objects.requireNonNull(book, "book");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Four deliberate policies:
     *
     * <ul>
     *   <li><b>Execution price is the resting order's.</b> It arrived first and set the terms,
     *       so an aggressor willing to pay more receives price improvement.
     *   <li><b>An unfilled limit remainder rests</b> at its own price.
     *   <li><b>An unfilled market remainder is cancelled.</b> A market order carries no price,
     *       so there is none at which it could rest; inventing one would impose terms the
     *       client never asked for. Immediate-or-cancel, as most exchanges do.
     *   <li><b>Self-trade prevention cancels the newest.</b> On reaching a resting order from
     *       the same account the incoming order is rejected outright - it neither matches
     *       further nor rests - and the resting order keeps its queue position. Trades already
     *       done against other accounts stand, because an execution cannot be unwound.
     * </ul>
     */
    @Override
    public List<Trade> submit(Order incoming) {
        List<Trade> trades = new ArrayList<>();
        long timestamp = System.currentTimeMillis();

        while (!incoming.isFullyFilled()) {
            Optional<Order> maybe = book.peekBest(incoming.getSide() == Side.SELL ? Side.BUY : Side.SELL);
            if (maybe.isEmpty()) break;

            // the resting order we want to match against
            Order resting = maybe.get();

            // check if there is a match
            if (!ordersCrossed(incoming, resting)) break;

            // check if account id is the same
            if (incoming.getAccountId() == resting.getAccountId()) return trades;
            int filledQuantity = Integer.min(incoming.getRemainingQuantity(), resting.getRemainingQuantity());
            incoming.fill(filledQuantity);
            resting.fill(filledQuantity);

            long buyId = incoming.getSide() == Side.BUY ? incoming.getOrderId() : resting.getOrderId();
            long sellId = incoming.getSide() == Side.SELL ? incoming.getOrderId() : resting.getOrderId();
            trades.add(new Trade(buyId, sellId, resting.getPrice(), filledQuantity, timestamp));

            // clean up
            if (resting.isFullyFilled()) {
                book.removeOrder(resting.getOrderId());
            }
        }

        // check if incoming has any remaining
        if (incoming.getOrderType() == OrderType.LIMIT && !incoming.isFullyFilled()) {
            book.addOrder(incoming);
        }

        return trades;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Straight to the book: cancelling needs no matching, only the removal that
     * {@link OrderBook#cancelOrder} keeps consistent across both structures.
     */
    @Override
    public void cancel(long orderId) {
        book.cancelOrder(orderId);
    }

    /**
     * Whether these two can trade - the incoming price is at least as good as the resting one,
     * from the incoming order's point of view. A market order always crosses: accepting any
     * price is what makes it a market order.
     */
    private boolean ordersCrossed(Order incoming, Order resting) {
        if (incoming.getOrderType() == OrderType.MARKET) {
            return true;
        }
        return incoming.getSide() == Side.BUY
                ? incoming.getPrice() >= resting.getPrice()
                : incoming.getPrice() <= resting.getPrice();
    }
}
