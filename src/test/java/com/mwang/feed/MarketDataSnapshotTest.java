package com.mwang.feed;

import com.mwang.book.BookSnapshot;
import com.mwang.book.DepthLevel;
import com.mwang.book.OrderBook;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketDataSnapshotTest {

    private static final long SEQUENCE = 1_423;
    private static final long TIMESTAMP = 1_700_000_000_000L;

    // --- conversion from a book snapshot ---

    @Test
    void shouldCarryPricesAndDepthThroughUnchanged() {
        BookSnapshot book = new BookSnapshot(
                List.of(new DepthLevel(9998, 300), new DepthLevel(9997, 500)),
                List.of(new DepthLevel(10002, 150)),
                9998, 10002);

        MarketDataSnapshot message = MarketDataSnapshot.from(book, SEQUENCE, TIMESTAMP);

        assertThat(message.sequence()).isEqualTo(SEQUENCE);
        assertThat(message.timestamp()).isEqualTo(TIMESTAMP);
        assertThat(message.bestBid()).isEqualTo(9998L);
        assertThat(message.bestAsk()).isEqualTo(10002L);
        assertThat(message.bids()).containsExactly(
                new DepthLevel(9998, 300), new DepthLevel(9997, 500));
        assertThat(message.asks()).containsExactly(new DepthLevel(10002, 150));
    }

    /**
     * The sentinel must not reach the wire. On the book, -1 means "this side is empty"; to a
     * consumer it would read as a price of minus one cent.
     */
    @Test
    void shouldTranslateAnEmptyBidSideToNull() {
        BookSnapshot book = new BookSnapshot(
                List.of(), List.of(new DepthLevel(10002, 150)), OrderBook.NO_PRICE, 10002);

        MarketDataSnapshot message = MarketDataSnapshot.from(book, SEQUENCE, TIMESTAMP);

        assertThat(message.bestBid()).isNull();
        assertThat(message.bestAsk()).isEqualTo(10002L);
    }

    @Test
    void shouldTranslateAnEmptyAskSideToNull() {
        BookSnapshot book = new BookSnapshot(
                List.of(new DepthLevel(9998, 300)), List.of(), 9998, OrderBook.NO_PRICE);

        MarketDataSnapshot message = MarketDataSnapshot.from(book, SEQUENCE, TIMESTAMP);

        assertThat(message.bestBid()).isEqualTo(9998L);
        assertThat(message.bestAsk()).isNull();
    }

    /** An absent side is an empty array, never a missing one - consumers should not branch. */
    @Test
    void shouldGiveEmptyListsRatherThanNullForAnEmptyBook() {
        MarketDataSnapshot message = MarketDataSnapshot.from(emptyBook(), 0, TIMESTAMP);

        assertThat(message.bids()).isEmpty();
        assertThat(message.asks()).isEmpty();
        assertThat(message.bestBid()).isNull();
        assertThat(message.bestAsk()).isNull();
    }

    @Test
    void shouldRejectANullBookSnapshot() {
        assertThatThrownBy(() -> MarketDataSnapshot.from(null, SEQUENCE, TIMESTAMP))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("book");
    }

    // --- the record's own contract ---

    /**
     * Tests the canonical constructor directly. {@code from} only ever passes unmodifiable
     * lists, so routing through it would prove nothing about the record's own guarantee.
     */
    @Test
    void shouldCopyTheListsItIsGivenRatherThanHoldTheCallersReferences() {
        List<DepthLevel> bids = new ArrayList<>(List.of(new DepthLevel(9998, 300)));
        List<DepthLevel> asks = new ArrayList<>(List.of(new DepthLevel(10002, 150)));

        MarketDataSnapshot message =
                new MarketDataSnapshot(SEQUENCE, TIMESTAMP, 9998L, 10002L, bids, asks);

        bids.clear();
        asks.add(new DepthLevel(10005, 99));

        assertThat(message.bids()).containsExactly(new DepthLevel(9998, 300));
        assertThat(message.asks()).containsExactly(new DepthLevel(10002, 150));
    }

    @Test
    void shouldExposeListsThatCallersCannotModify() {
        MarketDataSnapshot message = MarketDataSnapshot.from(emptyBook(), 0, TIMESTAMP);

        assertThatThrownBy(() -> message.bids().add(new DepthLevel(1, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> message.asks().add(new DepthLevel(1, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectANegativeSequence() {
        assertThatThrownBy(() -> MarketDataSnapshot.from(emptyBook(), -1, TIMESTAMP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequence");
    }

    @Test
    void shouldRejectNullLists() {
        assertThatThrownBy(() ->
                new MarketDataSnapshot(SEQUENCE, TIMESTAMP, null, null, null, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("bids");
        assertThatThrownBy(() ->
                new MarketDataSnapshot(SEQUENCE, TIMESTAMP, null, null, List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("asks");
    }

    /** A sequence of zero is a real value - the very first publication, before any order. */
    @Test
    void shouldAcceptASequenceOfZero() {
        assertThat(MarketDataSnapshot.from(emptyBook(), 0, TIMESTAMP).sequence()).isZero();
    }

    private static BookSnapshot emptyBook() {
        return new BookSnapshot(List.of(), List.of(), OrderBook.NO_PRICE, OrderBook.NO_PRICE);
    }
}
