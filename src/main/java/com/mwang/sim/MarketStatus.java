package com.mwang.sim;

import com.mwang.book.BookSnapshot;
import com.mwang.book.OrderBook;

import java.util.List;

/**
 * Everything the renderer needs about the market, captured together so one read gives a
 * consistent view. Published by a listener on the matcher thread.
 *
 * @param book   the top of the book as it stood
 * @param orders how many orders the matcher has processed
 * @param trades how many trades have executed
 */
public record MarketStatus(BookSnapshot book, long orders, long trades) {

    /** @return an empty book with nothing traded, for before the first order */
    public static MarketStatus empty() {
        return new MarketStatus(
                new BookSnapshot(List.of(), List.of(), OrderBook.NO_PRICE, OrderBook.NO_PRICE),
                0, 0);
    }
}
