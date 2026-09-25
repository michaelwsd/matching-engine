package com.mwang.book;

import com.mwang.model.Order;

import java.util.ArrayDeque;
import java.util.Deque;

public class PriceLevel {
    private final Deque<Order> orders = new ArrayDeque<>();

    public void add(Order order) {
        orders.addLast(order);
    }

    public Order peek() {
        return orders.peekFirst();
    }

    public Order poll() {
        return orders.pollFirst();
    }

    public boolean remove(Order order) {
        return orders.remove(order);
    }

    public long totalQuantity() {
        long quantity = 0;
        for (Order order: orders) {
            quantity += order.getRemainingQuantity();
        }
        return quantity;
    }

    public boolean isEmpty() {
        return orders.isEmpty();
    }

}
