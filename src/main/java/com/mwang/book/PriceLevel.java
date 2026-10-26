package com.mwang.book;

import com.mwang.model.Order;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Every order resting at one price, held in arrival order. This queue is what gives the book its
 * time priority: {@link #add} appends to the back, {@link #poll} takes from the front.
 *
 * <p>Package-private, because {@link OrderBook} also indexes orders by id and an outside caller
 * holding a level could update one structure and not the other. Not thread-safe.
 */
final class PriceLevel {

    private final Deque<Order> orders = new ArrayDeque<>();

    /** Appends to the back, behind everything already queued at this price. */
    public void add(Order order) {
        orders.addLast(order);
    }

    /** @return the next order that would fill, or null when this level is empty */
    public Order peek() {
        return orders.peekFirst();
    }

    /** @return the next order that would fill, removing it, or null when this level is empty */
    public Order poll() {
        return orders.pollFirst();
    }

    /**
     * Removes one specific order, for a cancel or a completed fill. Linear in the size of the
     * level, since {@link Order} does not override {@code equals} and this is an identity scan.
     *
     * @return true if the order was present and removed
     */
    public boolean remove(Order order) {
        return orders.remove(order);
    }

    /** @return the total still unfilled at this price. Walks the queue, so O(n). */
    public long totalQuantity() {
        long quantity = 0;
        for (Order order : orders) {
            quantity += order.getRemainingQuantity();
        }
        return quantity;
    }

    /** @return true when no orders remain, at which point the book deletes this level */
    public boolean isEmpty() {
        return orders.isEmpty();
    }
}
