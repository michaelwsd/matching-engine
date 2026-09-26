package com.mwang.book;

import com.mwang.exception.InvalidOrderException;
import com.mwang.exception.UnknownOrderException;
import com.mwang.model.Order;
import com.mwang.model.Side;

import java.util.*;

public class OrderBook {

    /**
     * Returned by {@link #bestBid()} and {@link #bestAsk()} when that side of the book is
     * empty. Safe as a sentinel because every real price is positive - {@link
     * com.mwang.model.Order} rejects a limit order priced at zero or below.
     */
    public static final long NO_PRICE = -1;

    private final Map<Long, Order> ordersById = new HashMap<>();
    private final TreeMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, PriceLevel> asks = new TreeMap<>();

    private TreeMap<Long, PriceLevel> getBook(Order order) {
        Side side = order.getSide();
        long price = order.getPrice();
        TreeMap<Long, PriceLevel> book = side == Side.BUY ? bids : asks; // equals throws exception

        if (!book.containsKey(price)) book.put(price, new PriceLevel());
        return book;
    }

    /**
     * The order at the front of the best price level on the given side - the next one that
     * would fill. Empty when that side of the book holds nothing.
     *
     * <p>Deliberately returns an {@link Order} rather than a price level: which level an
     * order sits in is this class's business, and callers that could reach into a level
     * directly could remove a filled order without updating the id index, leaving the two
     * structures disagreeing.
     */
    public Optional<Order> peekBest(Side side) {
        TreeMap<Long, PriceLevel> book = side == Side.BUY ? bids : asks;
        if (book.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(book.firstEntry().getValue().peek());
    }

    public void addOrder(Order order) {
        long orderId = order.getOrderId();
        if (ordersById.containsKey(orderId)) {
            throw new InvalidOrderException("Order id already in use on this book: " + orderId);
        }
        ordersById.put(orderId, order);

        TreeMap<Long, PriceLevel> book = getBook(order);
        PriceLevel level = book.get(order.getPrice());
        level.add(order);
    }

    /**
     * Removes an order at the client's request. Identical in effect to
     * {@link #removeOrder(long)}; the separate name records <em>why</em> the order left the
     * book, which Phase 3b's gateway needs to report back differently.
     */
    public void cancelOrder(long orderId) {
        removeOrder(orderId);
    }

    /**
     * Takes an order off the book entirely - out of its price level, out of the id index,
     * and deleting the level if that was its last order. Used both for client cancels and
     * for resting orders the matching engine has filled completely.
     *
     * @throws UnknownOrderException if no such order is on the book
     */
    public void removeOrder(long orderId) {
        Order order = ordersById.remove(orderId);
        if (order == null) {
            throw new UnknownOrderException(orderId);
        }

        TreeMap<Long, PriceLevel> book = getBook(order);
        PriceLevel level = book.get(order.getPrice());
        level.remove(order);

        if (level.isEmpty()) book.remove(order.getPrice());
    }

    public long bestBid() {
        return bids.isEmpty() ? NO_PRICE : bids.firstKey();
    }

    public long bestAsk() {
        return asks.isEmpty() ? NO_PRICE : asks.firstKey();
    }

    private List<DepthLevel> getDepth(Side side, int level) {
        TreeMap<Long, PriceLevel> book = side == Side.BUY ? bids : asks;
        List<DepthLevel> depth = new ArrayList<>();
        int counter = 0;
        for (Map.Entry<Long, PriceLevel> entry: book.entrySet()) {
            if (counter >= level) break;
            Long key = entry.getKey();
            PriceLevel value = entry.getValue();
            long quantity = value.totalQuantity();
            depth.add(new DepthLevel(key, quantity));
            counter++;
        }

        return List.copyOf(depth);
    }

    public List<DepthLevel> bidDepth(int level) {
        return getDepth(Side.BUY, level);
    }

    public List<DepthLevel> askDepth(int level) {
        return getDepth(Side.SELL, level);
    }
}
