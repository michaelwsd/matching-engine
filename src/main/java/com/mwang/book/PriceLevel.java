package com.mwang.book;

import com.mwang.model.Order;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Every order resting at one price, held in arrival order.
 *
 * <p>The queue is what gives the book its <em>time</em> priority: {@link #add} appends to the
 * back, {@link #poll} takes from the front, so at equal price the first to arrive is the first
 * to fill.
 *
 * <p>Package-private deliberately: {@link OrderBook} also indexes orders by id, and an outside
 * caller holding a level could remove from one structure and not the other.
 *
 * <p>Not thread-safe.
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
     * Removes one specific order, for a cancel or a completed fill.
     *
     * <p>Linear in the size of the level: {@link Order} does not override {@code equals}, so
     * this is an identity scan. Acceptable because levels are shallow in practice. Making it
     * O(1) would mean storing a node handle per order, which is the next optimisation if
     * profiling ever points here.
     *
     * @return true if the order was present and removed
     */
    public boolean remove(Order order) {
        return orders.remove(order);
    }

    /**
     * @return the total still unfilled at this price. Walks the queue, so O(n) - which is why
     *         depth is read in snapshots rather than per order.
     */
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
