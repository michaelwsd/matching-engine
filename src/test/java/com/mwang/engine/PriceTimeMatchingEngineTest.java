package com.mwang.engine;

import com.mwang.book.DepthLevel;
import com.mwang.book.OrderBook;
import com.mwang.model.Order;
import com.mwang.model.OrderType;
import com.mwang.model.Side;
import com.mwang.model.Trade;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Scenarios transcribed from {@code docs/phases/phase-02-scenarios.md}, worked out by hand
 * before the engine existed.
 *
 * <p>Every test finishes with {@link #assertBookNotCrossed()}: a crossed book holds two orders
 * that should have traded and did not, so that one check catches a broad class of bugs.
 */
class PriceTimeMatchingEngineTest {

    private final OrderBook book = new OrderBook();
    private final MatchingEngine engine = new PriceTimeMatchingEngine(book);

    private long nextOrderId = 1;

    /** The resting orders from the spec's starting book, kept so tests can name their ids. */
    private Order b1;
    private Order b2;
    private Order a1;
    private Order a2;

    @BeforeEach
    void standardBook() {
        b1 = order(1, Side.BUY, OrderType.LIMIT, 9998, 200);
        b2 = order(1, Side.BUY, OrderType.LIMIT, 9997, 500);
        a1 = order(2, Side.SELL, OrderType.LIMIT, 10001, 150);
        a2 = order(3, Side.SELL, OrderType.LIMIT, 10002, 300);

        book.addOrder(b1);
        book.addOrder(b2);
        book.addOrder(a1);
        book.addOrder(a2);
    }

    // --- limit order matching ---

    @Test
    void shouldFillExactlyAndDeleteTheEmptiedLevel() {
        Order incoming = buy(10001, 150);

        List<Trade> trades = engine.submit(incoming);

        assertTrades(trades, tuple(incoming.getOrderId(), a1.getOrderId(), 10001L, 150));

        assertThat(book.askDepth(5)).containsExactly(new DepthLevel(10002, 300));
        assertThat(book.bidDepth(5)).containsExactly(
                new DepthLevel(9998, 200),
                new DepthLevel(9997, 500));
        assertBookNotCrossed();
    }

    @Test
    void shouldRestTheUnfilledRemainderOfAnIncomingLimitOrder() {
        Order incoming = buy(10001, 200);

        List<Trade> trades = engine.submit(incoming);

        assertTrades(trades, tuple(incoming.getOrderId(), a1.getOrderId(), 10001L, 150));

        assertThat(book.askDepth(5)).containsExactly(new DepthLevel(10002, 300));
        assertThat(book.bidDepth(5)).containsExactly(
                new DepthLevel(10001, 50),
                new DepthLevel(9998, 200),
                new DepthLevel(9997, 500));
        assertBookNotCrossed();
    }

    @Test
    void shouldReduceTheRestingOrderWhenIncomingIsSmaller() {
        Order incoming = buy(10001, 100);

        List<Trade> trades = engine.submit(incoming);

        assertTrades(trades, tuple(incoming.getOrderId(), a1.getOrderId(), 10001L, 100));

        assertThat(book.askDepth(5)).containsExactly(
                new DepthLevel(10001, 50),
                new DepthLevel(10002, 300));
        assertThat(book.bidDepth(5)).containsExactly(
                new DepthLevel(9998, 200),
                new DepthLevel(9997, 500));
        assertBookNotCrossed();
    }

    @Test
    void shouldWalkMultiplePriceLevelsUntilFilled() {
        Order incoming = buy(10002, 400);

        List<Trade> trades = engine.submit(incoming);

        assertTrades(trades,
                tuple(incoming.getOrderId(), a1.getOrderId(), 10001L, 150),
                tuple(incoming.getOrderId(), a2.getOrderId(), 10002L, 250));

        assertThat(book.askDepth(5)).containsExactly(new DepthLevel(10002, 50));
        assertBookNotCrossed();
    }

    @Test
    void shouldWalkLevelsAndRestWhatIsLeftOver() {
        Order incoming = buy(10002, 500);

        List<Trade> trades = engine.submit(incoming);

        assertTrades(trades,
                tuple(incoming.getOrderId(), a1.getOrderId(), 10001L, 150),
                tuple(incoming.getOrderId(), a2.getOrderId(), 10002L, 300));

        assertThat(book.askDepth(5)).isEmpty();
        assertThat(book.bidDepth(5)).containsExactly(
                new DepthLevel(10002, 50),
                new DepthLevel(9998, 200),
                new DepthLevel(9997, 500));
        assertBookNotCrossed();
    }

    @Test
    void shouldRestWithoutTradingWhenPriceDoesNotCross() {
        Order incoming = buy(9999, 100);

        List<Trade> trades = engine.submit(incoming);

        assertThat(trades).isEmpty();
        assertThat(book.bidDepth(5)).containsExactly(
                new DepthLevel(9999, 100),
                new DepthLevel(9998, 200),
                new DepthLevel(9997, 500));
        assertThat(book.askDepth(5)).containsExactly(
                new DepthLevel(10001, 150),
                new DepthLevel(10002, 300));
        assertBookNotCrossed();
    }

    /** The aggressor pays the resting order's price, not its own - price improvement. */
    @Test
    void shouldExecuteAtTheRestingOrdersPriceNotTheAggressors() {
        Order incoming = buy(10005, 150);

        List<Trade> trades = engine.submit(incoming);

        assertThat(trades).singleElement()
                .extracting(Trade::price)
                .isEqualTo(10001L);
        assertBookNotCrossed();
    }

    // --- market orders ---

    @Test
    void shouldFillMarketOrderAcrossLevelsIgnoringPrice() {
        Order incoming = marketBuy(200);

        List<Trade> trades = engine.submit(incoming);

        assertTrades(trades,
                tuple(incoming.getOrderId(), a1.getOrderId(), 10001L, 150),
                tuple(incoming.getOrderId(), a2.getOrderId(), 10002L, 50));

        assertThat(book.askDepth(5)).containsExactly(new DepthLevel(10002, 250));
        assertBookNotCrossed();
    }

    @Test
    void shouldCancelTheUnfilledRemainderOfAMarketOrder() {
        Order incoming = marketBuy(1000);

        List<Trade> trades = engine.submit(incoming);

        assertTrades(trades,
                tuple(incoming.getOrderId(), a1.getOrderId(), 10001L, 150),
                tuple(incoming.getOrderId(), a2.getOrderId(), 10002L, 300));

        assertThat(book.askDepth(5)).isEmpty();
        assertThat(book.bestAsk()).isEqualTo(OrderBook.NO_PRICE);
        // the unfilled 550 must not have been rested anywhere
        assertThat(book.bidDepth(5)).containsExactly(
                new DepthLevel(9998, 200),
                new DepthLevel(9997, 500));
        assertBookNotCrossed();
    }

    @Test
    void shouldReturnNoTradesForAMarketOrderWithNoLiquidity() {
        book.cancelOrder(a1.getOrderId());
        book.cancelOrder(a2.getOrderId());

        List<Trade> trades = engine.submit(marketBuy(100));

        assertThat(trades).isEmpty();
        assertThat(book.askDepth(5)).isEmpty();
        assertThat(book.bidDepth(5)).containsExactly(
                new DepthLevel(9998, 200),
                new DepthLevel(9997, 500));
        assertBookNotCrossed();
    }

    // --- sell side ---

    @Test
    void shouldWalkMultiplePriceLevelsOnTheSellSide() {
        Order incoming = sell(9997, 600);

        List<Trade> trades = engine.submit(incoming);

        assertTrades(trades,
                tuple(b1.getOrderId(), incoming.getOrderId(), 9998L, 200),
                tuple(b2.getOrderId(), incoming.getOrderId(), 9997L, 400));

        assertThat(book.bidDepth(5)).containsExactly(new DepthLevel(9997, 100));
        assertThat(book.askDepth(5)).containsExactly(
                new DepthLevel(10001, 150),
                new DepthLevel(10002, 300));
        assertBookNotCrossed();
    }

    /** An incoming sell is the seller on every trade it produces; the resting order is the buyer. */
    @Test
    void shouldPutTheIncomingSellOnTheSellSideOfEachTrade() {
        Order incoming = sell(9998, 100);

        List<Trade> trades = engine.submit(incoming);

        assertThat(trades).singleElement()
                .extracting(Trade::buyOrderId, Trade::sellOrderId)
                .containsExactly(b1.getOrderId(), incoming.getOrderId());
        assertBookNotCrossed();
    }

    // --- time priority ---

    @Test
    void shouldFillTheEarliestOrderFirstAtTheSamePrice() {
        Order a3 = order(4, Side.SELL, OrderType.LIMIT, 10001, 100);
        book.addOrder(a3);      // same price as a1, but arrives later

        Order incoming = buy(10001, 200);

        List<Trade> trades = engine.submit(incoming);

        // a1 must fill completely before a3 is touched at all
        assertTrades(trades,
                tuple(incoming.getOrderId(), a1.getOrderId(), 10001L, 150),
                tuple(incoming.getOrderId(), a3.getOrderId(), 10001L, 50));

        assertThat(book.askDepth(5)).containsExactly(
                new DepthLevel(10001, 50),
                new DepthLevel(10002, 300));
        assertBookNotCrossed();
    }

    // --- self-trade prevention ---

    @Test
    void shouldRejectAnIncomingOrderThatWouldMatchItsOwnAccount() {
        Order incoming = order(2, Side.BUY, OrderType.LIMIT, 10001, 200);   // a1's account

        List<Trade> trades = engine.submit(incoming);

        assertThat(trades).isEmpty();
        // rejected outright: no trade, and the incoming order must not rest
        assertThat(book.askDepth(5)).containsExactly(
                new DepthLevel(10001, 150),
                new DepthLevel(10002, 300));
        assertThat(book.bidDepth(5)).containsExactly(
                new DepthLevel(9998, 200),
                new DepthLevel(9997, 500));
        assertBookNotCrossed();
    }

    /** Guards against an inverted account check, which would silently block all trading. */
    @Test
    void shouldStillMatchOrdersFromDifferentAccounts() {
        Order incoming = order(9, Side.BUY, OrderType.LIMIT, 10001, 150);

        List<Trade> trades = engine.submit(incoming);

        assertThat(trades).hasSize(1);
        assertBookNotCrossed();
    }

    @Test
    void shouldRejectAMarketOrderThatWouldMatchItsOwnAccount() {
        Order incoming = order(2, Side.BUY, OrderType.MARKET, 0, 100);

        List<Trade> trades = engine.submit(incoming);

        assertThat(trades).isEmpty();
        assertThat(book.askDepth(5)).containsExactly(
                new DepthLevel(10001, 150),
                new DepthLevel(10002, 300));
        assertBookNotCrossed();
    }

    // --- bookkeeping ---

    /**
     * A fully filled resting order must leave the id index as well as its price level,
     * otherwise its id can never be reused and the index grows without bound.
     */
    @Test
    void shouldRemoveAFullyFilledRestingOrderFromTheIndex() {
        engine.submit(buy(10001, 150));     // consumes a1 entirely

        book.addOrder(new Order(2, a1.getOrderId(), 10003, 75, Side.SELL, OrderType.LIMIT, 99));

        assertThat(book.askDepth(5)).containsExactly(
                new DepthLevel(10002, 300),
                new DepthLevel(10003, 75));
        assertBookNotCrossed();
    }

    // --- helpers ---

    /**
     * Asserts the trades in order, comparing the four predictable components rather than whole
     * {@link Trade} records, since the timestamp comes from the system clock.
     */
    private void assertTrades(List<Trade> trades, Tuple... expected) {
        assertThat(trades)
                .extracting(Trade::buyOrderId, Trade::sellOrderId, Trade::price, Trade::quantity)
                .containsExactly(expected);
    }

    /**
     * The one invariant that must hold after every scenario: a bid at or above the best ask
     * means two orders that should have traded did not.
     */
    private void assertBookNotCrossed() {
        long bid = book.bestBid();
        long ask = book.bestAsk();
        if (bid != OrderBook.NO_PRICE && ask != OrderBook.NO_PRICE) {
            assertThat(bid).isLessThan(ask);
        }
    }

    private Order buy(long price, int quantity) {
        return order(9, Side.BUY, OrderType.LIMIT, price, quantity);
    }

    private Order sell(long price, int quantity) {
        return order(9, Side.SELL, OrderType.LIMIT, price, quantity);
    }

    private Order marketBuy(int quantity) {
        return order(9, Side.BUY, OrderType.MARKET, 0, quantity);
    }

    private Order order(long accountId, Side side, OrderType type, long price, int quantity) {
        long id = nextOrderId++;
        return new Order(accountId, id, price, quantity, side, type, id);
    }
}
