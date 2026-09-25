package com.mwang.book;

import com.mwang.model.Order;
import com.mwang.model.Side;

import java.util.*;

public class OrderBook {
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

    public void addOrder(Order order) {
        long orderId = order.getOrderId();
        if (ordersById.containsKey(orderId)) {
            throw new IllegalStateException("Order already exists: " + orderId);
        }
        ordersById.put(orderId, order);

        TreeMap<Long, PriceLevel> book = getBook(order);
        PriceLevel level = book.get(order.getPrice());
        level.add(order);
    }

    public void cancelOrder(long orderId) {
        Order order = ordersById.remove(orderId);
        if (order == null) {
            throw new IllegalStateException("Order doesn't exist: " + orderId);
        }

        TreeMap<Long, PriceLevel> book = getBook(order);
        PriceLevel level = book.get(order.getPrice());
        level.remove(order);

        if (level.isEmpty()) book.remove(order.getPrice());
    }

    public OptionalLong bestBid() {
        return bids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(bids.firstKey());
    }

    public OptionalLong bestAsk() {
        return asks.isEmpty() ? OptionalLong.empty() : OptionalLong.of(asks.firstKey());
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
