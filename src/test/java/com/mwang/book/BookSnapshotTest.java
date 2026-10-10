package com.mwang.book;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the record's own contract rather than going through {@link OrderBook}.
 *
 * <p>That distinction matters here. {@code OrderBook.bidDepth} already returns an immutable
 * list, so a snapshot built through the book is safe whether or not the record copies anything.
 * The record is public, so the guarantee has to hold for any caller - including one that passes
 * an {@link ArrayList} it then keeps modifying.
 */
class BookSnapshotTest {

    @Test
    void shouldCopyTheListsItIsGivenRatherThanHoldTheCallersReferences() {
        List<DepthLevel> bids = new ArrayList<>(List.of(new DepthLevel(9998, 200)));
        List<DepthLevel> asks = new ArrayList<>(List.of(new DepthLevel(10002, 300)));

        BookSnapshot snapshot = new BookSnapshot(bids, asks, 9998, 10002);

        bids.clear();
        asks.add(new DepthLevel(10005, 150));

        assertThat(snapshot.bids()).containsExactly(new DepthLevel(9998, 200));
        assertThat(snapshot.asks()).containsExactly(new DepthLevel(10002, 300));
    }

    @Test
    void shouldExposeListsThatCallersCannotModify() {
        BookSnapshot snapshot = new BookSnapshot(
                new ArrayList<>(), new ArrayList<>(), OrderBook.NO_PRICE, OrderBook.NO_PRICE);

        assertThatThrownBy(() -> snapshot.bids().add(new DepthLevel(1, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.asks().add(new DepthLevel(1, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldReportBothSidesPresentOnlyWhenNeitherBestPriceIsTheSentinel() {
        assertThat(snapshotWith(9998, 10002).hasBothSides()).isTrue();
        assertThat(snapshotWith(9998, OrderBook.NO_PRICE).hasBothSides()).isFalse();
        assertThat(snapshotWith(OrderBook.NO_PRICE, 10002).hasBothSides()).isFalse();
        assertThat(snapshotWith(OrderBook.NO_PRICE, OrderBook.NO_PRICE).hasBothSides()).isFalse();
    }

    private static BookSnapshot snapshotWith(long bestBid, long bestAsk) {
        return new BookSnapshot(List.of(), List.of(), bestBid, bestAsk);
    }
}
