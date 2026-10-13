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
 * Matches incoming orders against resting liquidity using price-time priority: better
 * prices fill first, and at equal price the order that arrived first fills first.
 *
 * <p>The book is supplied rather than created here, so that the same book can be read by
 * the demo, the snapshot endpoint and any strategy without going through the engine. The
 * engine is expected to be the only writer; from Phase 3 that is enforced by confining it
 * to a single matcher thread.
 *
 * <p>Not thread-safe.
 */
public final class PriceTimeMatchingEngine implements MatchingEngine {

    private final OrderBook book;

    /**
     * @param book the book to match against and rest unfilled remainders on. This engine
     *             expects to be its only writer.
     * @throws NullPointerException if {@code book} is null
     */
    public PriceTimeMatchingEngine(OrderBook book) {
        this.book = Objects.requireNonNull(book, "book");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Policies applied here, each a deliberate choice rather than an accident of the
     * implementation:
     *
     * <ul>
     *   <li><b>Execution price is the resting order's price.</b> The resting order arrived
     *       first and established the terms, so an aggressor willing to pay more receives
     *       price improvement.
     *   <li><b>An unfilled limit remainder rests on the book</b> at its own price.
     *   <li><b>An unfilled market remainder is cancelled.</b> A market order carries no
     *       price, so there is no price at which it could rest; inventing one would impose
     *       terms the client never asked for. This is immediate-or-cancel behaviour, and it
     *       is what most exchanges do.
     *   <li><b>Self-trade prevention cancels the newest.</b> On reaching a resting order
     *       from the same account, the incoming order is rejected outright - it neither
     *       matches further nor rests, and the resting order keeps its queue position. Any
     *       trades already executed against other accounts stand, because an execution
     *       cannot be unwound.
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
     * <p>Delegates straight to the book: cancelling needs no matching, only removal from a
     * price level and from the id index, which {@link OrderBook#cancelOrder} keeps in step.
     */
    @Override
    public void cancel(long orderId) {
        book.cancelOrder(orderId);
    }

    /**
     * Whether these two orders can trade - the incoming order's price is at least as good as
     * the resting order's, from the incoming order's point of view.
     *
     * <p>A market order always crosses: it accepts any price, which is exactly what makes it a
     * market order.
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
