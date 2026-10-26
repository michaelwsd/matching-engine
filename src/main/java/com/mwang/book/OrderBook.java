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
 * Every resting order for one instrument, organised by price and then by arrival time. Decides
 * where orders sit, not which ones match - that is {@link com.mwang.engine.MatchingEngine}.
 *
 * <p>A {@link TreeMap} per side maps price to {@link PriceLevel}, bids in reverse order so
 * {@code firstKey()} is the best price on both sides and the matching loop need not be mirrored.
 * {@code ordersById} indexes orders flat, making a cancel a lookup rather than a search.
 *
 * <p><strong>Invariant:</strong> an order is in {@code ordersById} if and only if it is in a
 * price level. Every mutator updates both.
 *
 * <p>Confined to the matcher thread and deliberately lock-free. {@link #snapshot(int)} is how
 * its state reaches any other thread.
 */
public final class OrderBook {

    /**
     * Returned by {@link #bestBid()} and {@link #bestAsk()} for an empty side. Safe as a sentinel
     * because {@link com.mwang.model.Order} rejects any price at or below zero.
     */
    public static final long NO_PRICE = -1;

    private final Map<Long, Order> ordersById = new HashMap<>();
    private final TreeMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, PriceLevel> asks = new TreeMap<>();

    /**
     * Rests an order on the book, at the back of the queue for its price.
     *
     * @throws InvalidOrderException if this id is already resting here, which would leave one of
     *                               the two orders uncancellable
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
     * {@link #removeOrder(long)}; the separate name records why it left the book.
     *
     * @throws UnknownOrderException if no such order is resting here
     */
    public void cancelOrder(long orderId) {
        removeOrder(orderId);
    }

    /**
     * Takes an order off the book entirely: out of its level, out of the id index, and deleting
     * the level if it was the last order there, so {@code firstKey()} always names a price with
     * liquidity behind it.
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
     * The order at the front of the best level on this side, the next one that would fill. Empty
     * when the side holds nothing.
     *
     * <p>Returns the order, not the level: a caller holding a level could break the invariant.
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
     * An immutable view of the top of the book, safe to hand to another thread. Must be called
     * from the thread that owns this book; the result may be published anywhere.
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
