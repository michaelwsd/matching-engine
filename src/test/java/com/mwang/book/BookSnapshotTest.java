package com.mwang.book;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the record's own contract rather than going through {@link OrderBook}, whose depth lists
 * are already immutable. The record is public, so its guarantee must hold for any caller,
 * including one passing an {@link ArrayList} it then keeps modifying.
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

    @Test
    void shouldReportTheMidpointOfTheSpread() {
        assertThat(snapshotWith(9_998, 10_002).mid()).isEqualTo(10_000);
    }

    /** Truncation puts the mid on the bid, never on the ask. A quote must not be biased up. */
    @Test
    void shouldTruncateTowardsTheBidOnAnOddSpread() {
        assertThat(snapshotWith(9_999, 10_002).mid()).isEqualTo(10_000);
        assertThat(snapshotWith(10_000, 10_001).mid()).isEqualTo(10_000);
    }

    @Test
    void shouldHaveNoMidWithoutBothSides() {
        assertThat(snapshotWith(9_998, OrderBook.NO_PRICE).mid()).isEqualTo(OrderBook.NO_PRICE);
        assertThat(snapshotWith(OrderBook.NO_PRICE, 10_002).mid()).isEqualTo(OrderBook.NO_PRICE);
        assertThat(snapshotWith(OrderBook.NO_PRICE, OrderBook.NO_PRICE).mid())
                .isEqualTo(OrderBook.NO_PRICE);
    }

    /** The {@code (bid + ask) / 2} form overflows here and returns a negative price. */
    @Test
    void shouldNotOverflowOnPricesNearTheTopOfTheRange() {
        assertThat(snapshotWith(Long.MAX_VALUE - 4, Long.MAX_VALUE).mid())
                .isEqualTo(Long.MAX_VALUE - 2);
    }

    private static BookSnapshot snapshotWith(long bestBid, long bestAsk) {
        return new BookSnapshot(List.of(), List.of(), bestBid, bestAsk);
    }
}
