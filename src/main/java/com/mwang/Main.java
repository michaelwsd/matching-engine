package com.mwang;

import com.mwang.book.DepthLevel;
import com.mwang.book.OrderBook;
import com.mwang.model.Order;
import com.mwang.model.OrderType;
import com.mwang.model.Side;

import java.util.List;
import java.util.OptionalLong;

/**
 * Builds an order book from a hardcoded set of orders and prints its depth.
 *
 * <p>This is a demonstration entry point for Phase 1, before any matching exists: every
 * order here rests on the book and nothing crosses. The orders are deliberately arranged
 * so that the best bid sits below the best ask.
 */
public class Main {

    /** How many price levels of depth to display per side. */
    private static final int DEPTH_LEVELS = 5;

    public static void main(String[] args) {
        OrderBook book = new OrderBook();

        long nextOrderId = 1;

        // Bids: several levels, with two orders resting at 9998 to show aggregation.
        book.addOrder(limit(1, nextOrderId++, Side.BUY, 10001, 50));
        book.addOrder(limit(2, nextOrderId++, Side.BUY, 9998, 200));
        book.addOrder(limit(3, nextOrderId++, Side.BUY, 9998, 100));
        book.addOrder(limit(1, nextOrderId++, Side.BUY, 9997, 500));

        // Asks.
        book.addOrder(limit(4, nextOrderId++, Side.SELL, 10002, 300));
        book.addOrder(limit(5, nextOrderId++, Side.SELL, 10005, 150));
        book.addOrder(limit(4, nextOrderId++, Side.SELL, 10006, 220));
        book.addOrder(limit(6, nextOrderId, Side.SELL, 10010, 400));

        printBook(book);
    }

    /**
     * Creates a resting limit order. Timestamps are taken from the system clock here, but
     * the constructor accepts them as a parameter so that tests can control arrival order.
     */
    private static Order limit(long accountId, long orderId, Side side, long price, int quantity) {
        return new Order(accountId, orderId, price, quantity, side, OrderType.LIMIT,
                System.currentTimeMillis());
    }

    /**
     * Column layouts for the two halves of the table. Header and data rows share these
     * format strings so the columns cannot drift apart.
     */
    private static final String BID_CELL = "%8s   %11s";
    private static final String ASK_CELL = "%9s %8s";

    private static final int BID_WIDTH = 22;
    private static final int ASK_WIDTH = 18;

    private static void printBook(OrderBook book) {
        List<DepthLevel> bids = book.bidDepth(DEPTH_LEVELS);
        List<DepthLevel> asks = book.askDepth(DEPTH_LEVELS);

        System.out.println();
        System.out.println(("  " + centre("ORDER BOOK", BID_WIDTH + ASK_WIDTH + 3)).stripTrailing());
        System.out.println("  " + "═".repeat(BID_WIDTH + ASK_WIDTH + 3));
        System.out.println(("  " + centre("BIDS", BID_WIDTH) + "   " + centre("ASKS", ASK_WIDTH))
                .stripTrailing());
        System.out.printf("  " + BID_CELL + " │ " + ASK_CELL + "%n", "qty", "price", "price", "qty");
        System.out.println("  " + "─".repeat(BID_WIDTH + 1) + "┼" + "─".repeat(ASK_WIDTH + 1));

        int rows = Math.max(bids.size(), asks.size());
        for (int i = 0; i < rows; i++) {
            System.out.printf("  %s │ %s%n", bidCell(bids, i), askCell(asks, i));
        }

        System.out.println("  " + "─".repeat(BID_WIDTH + 1) + "┴" + "─".repeat(ASK_WIDTH + 1));
        printSummary(book);
        System.out.println();
    }

    /** Renders one bid row, or blank padding when this side has fewer levels. */
    private static String bidCell(List<DepthLevel> bids, int index) {
        if (index >= bids.size()) {
            return " ".repeat(BID_WIDTH);
        }
        DepthLevel level = bids.get(index);
        return String.format(BID_CELL, level.quantity(), formatPrice(level.price()));
    }

    /** Renders one ask row, or blank padding when this side has fewer levels. */
    private static String askCell(List<DepthLevel> asks, int index) {
        if (index >= asks.size()) {
            return "";
        }
        DepthLevel level = asks.get(index);
        return String.format(ASK_CELL, formatPrice(level.price()), level.quantity());
    }

    /** Centres text within the given width, padding both sides with spaces. */
    private static String centre(String text, int width) {
        int padding = Math.max(0, width - text.length());
        int left = padding / 2;
        return " ".repeat(left) + text + " ".repeat(padding - left);
    }

    private static void printSummary(OrderBook book) {
        OptionalLong bestBid = book.bestBid();
        OptionalLong bestAsk = book.bestAsk();

        if (bestBid.isEmpty() || bestAsk.isEmpty()) {
            System.out.println("  book is one-sided or empty - no spread");
            return;
        }

        long bid = bestBid.getAsLong();
        long ask = bestAsk.getAsLong();

        System.out.printf("  best bid %s    best ask %s    spread %s%n",
                formatPrice(bid), formatPrice(ask), formatPrice(ask - bid));
        System.out.printf("  mid %s%n", formatMid(bid, ask));
    }

    /**
     * Formats a price given in ticks as dollars and cents, where one tick is one cent.
     * Uses integer division rather than floating point, for the same reason prices are
     * stored as scaled longs in the first place.
     */
    private static String formatPrice(long ticks) {
        return String.format("%d.%02d", ticks / 100, ticks % 100);
    }

    /**
     * Formats the midpoint of the spread. The mid of two adjacent ticks falls on a half
     * tick, so this is computed in half-ticks and rendered with a trailing digit rather
     * than by dividing into a double.
     */
    private static String formatMid(long bid, long ask) {
        long total = bid + ask;
        String whole = formatPrice(total / 2);
        return (total % 2 == 0) ? whole : whole + "5";
    }
}
