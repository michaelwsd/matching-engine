package com.mwang.book;

import com.mwang.exception.InvalidOrderException;
import com.mwang.exception.UnknownOrderException;
import com.mwang.model.Order;
import com.mwang.model.Side;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Every resting order for one instrument, organised by price and then by arrival time.
 *
 * <p>Three structures, kept in step. A {@link TreeMap} per side maps price to
 * {@link PriceLevel}, sorted because "what is the best price" is what the engine asks most
 * often and {@code firstKey()} answers it; bids sort in reverse so <em>first</em> means best on
 * both sides, letting the matching loop be written once instead of mirrored. {@code ordersById}
 * indexes orders flat, so cancelling is a lookup rather than a search.
 *
 * <p><strong>The invariant everything relies on:</strong> an order is in {@code ordersById} if
 * and only if it is in a price level. Every mutator updates both, and no caller can reach a
 * level to break it.
 *
 * <p>Decides <em>where</em> orders sit, not which ones match - that is
 * {@link com.mwang.engine.MatchingEngine}'s job.
 *
 * <p>Not thread-safe, and deliberately lock-free: confined to the gateway's matcher thread.
 * {@link #snapshot(int)} is how its state reaches any other thread.
 */
public final class OrderBook {

    /**
     * Returned by {@link #bestBid()} and {@link #bestAsk()} for an empty side. Safe as a
     * sentinel because every real price is positive - {@link com.mwang.model.Order} rejects a
     * limit priced at zero or below.
     */
    public static final long NO_PRICE = -1;

    private final Map<Long, Order> ordersById = new HashMap<>();
    private final TreeMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, PriceLevel> asks = new TreeMap<>();

    /**
     * Rests an order on the book, at the back of the queue for its price.
     *
     * @throws InvalidOrderException if this id is already resting here. A duplicate would make
     *                               the id index ambiguous and leave one order uncancellable.
     */
    public void addOrder(Order order) {
        long orderId = order.getOrderId();
        if (ordersById.containsKey(orderId)) {
            throw new InvalidOrderException("Order id already in use on this book: " + orderId);
        }
        ordersById.put(orderId, order);
        bookFor(order.getSide())
                .computeIfAbsent(order.getPrice(), price -> new PriceLevel())
                .add(order);
    }

    /**
     * Removes an order at the client's request. Identical in effect to
     * {@link #removeOrder(long)}; the separate name records <em>why</em> it left the book,
     * which a gateway needs in order to report back differently.
     *
     * @throws UnknownOrderException if no such order is resting here
     */
    public void cancelOrder(long orderId) {
        removeOrder(orderId);
    }

    /**
     * Takes an order off the book entirely - out of its level, out of the id index, and deleting
     * the level if that was its last order, so {@code firstKey()} always names a price with
     * liquidity behind it. Used for client cancels and for fully filled resting orders alike.
     *
     * @throws UnknownOrderException if no such order is resting here
     */
    public void removeOrder(long orderId) {
        Order order = ordersById.remove(orderId);
        if (order == null) {
            throw new UnknownOrderException(orderId);
        }

        TreeMap<Long, PriceLevel> book = bookFor(order.getSide());
        // Non-null by the class invariant: the order was in the id index, so it is in a level.
        PriceLevel level = book.get(order.getPrice());
        level.remove(order);

        if (level.isEmpty()) {
            book.remove(order.getPrice());
        }
    }

    /**
     * The order at the front of the best level on this side - the next one that would fill.
     * Empty when the side holds nothing.
     *
     * <p>Returns an {@link Order} rather than the level on purpose: a caller holding a level
     * could remove a filled order without updating the id index, breaking the invariant.
     */
    public Optional<Order> peekBest(Side side) {
        TreeMap<Long, PriceLevel> book = bookFor(side);
        if (book.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(book.firstEntry().getValue().peek());
    }

    /** @return the highest price anyone is bidding, or {@link #NO_PRICE} if nobody is */
    public long bestBid() {
        return bids.isEmpty() ? NO_PRICE : bids.firstKey();
    }

    /** @return the lowest price anyone is offering, or {@link #NO_PRICE} if nobody is */
    public long bestAsk() {
        return asks.isEmpty() ? NO_PRICE : asks.firstKey();
    }

    /** @param levels how many price levels to return, best price first */
    public List<DepthLevel> bidDepth(int levels) {
        return depth(Side.BUY, levels);
    }

    /** @param levels how many price levels to return, best price first */
    public List<DepthLevel> askDepth(int levels) {
        return depth(Side.SELL, levels);
    }

    /**
     * An immutable view of the top of the book, safe to hand to another thread.
     *
     * <p>Must be called from the thread that owns this book, since it walks the structures
     * {@link #addOrder} and {@link #removeOrder} mutate. The <em>result</em> is safe to publish
     * anywhere, so a reader never needs the book itself.
     *
     * @param levels how many price levels to include per side
     */
    public BookSnapshot snapshot(int levels) {
        return new BookSnapshot(bidDepth(levels), askDepth(levels), bestBid(), bestAsk());
    }

    /** The side's map, with no side effects - so a read path cannot insert an empty level. */
    private TreeMap<Long, PriceLevel> bookFor(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    /** Aggregates the top {@code levels} price levels of one side into published depth. */
    private List<DepthLevel> depth(Side side, int levels) {
        List<DepthLevel> depth = new ArrayList<>();
        for (Map.Entry<Long, PriceLevel> entry : bookFor(side).entrySet()) {
            if (depth.size() >= levels) {
                break;
            }
            depth.add(new DepthLevel(entry.getKey(), entry.getValue().totalQuantity()));
        }
        return List.copyOf(depth);
    }
}
