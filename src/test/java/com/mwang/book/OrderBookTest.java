package com.mwang.book;

import com.mwang.exception.InvalidOrderException;
import com.mwang.exception.OrderException;
import com.mwang.exception.UnknownOrderException;
import com.mwang.model.Order;
import com.mwang.model.OrderType;
import com.mwang.model.Side;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.throwable;

class OrderBookTest {

    private final OrderBook book = new OrderBook();

    /**
     * Order ids must be unique within a book. JUnit creates a fresh instance of this class
     * for every test method, so this counter resets each time and tests stay independent.
     */
    private long nextOrderId = 1;

    // --- best bid / best ask ---

    @Test
    void shouldHaveNoBestPricesWhenEmpty() {
        assertThat(book.bestBid()).isEqualTo(OrderBook.NO_PRICE);
        assertThat(book.bestAsk()).isEqualTo(OrderBook.NO_PRICE);
    }

    @Test
    void shouldReportHighestPriceAsBestBid() {
        book.addOrder(buy(9998, 200));
        book.addOrder(buy(10001, 50));
        book.addOrder(buy(9997, 500));

        assertThat(book.bestBid()).isEqualTo(10001);
    }

    @Test
    void shouldReportLowestPriceAsBestAsk() {
        book.addOrder(sell(10005, 150));
        book.addOrder(sell(10002, 300));
        book.addOrder(sell(10010, 400));

        assertThat(book.bestAsk()).isEqualTo(10002);
    }

    @Test
    void shouldKeepBidsAndAsksIndependent() {
        book.addOrder(buy(9998, 100));

        assertThat(book.bestBid()).isEqualTo(9998);
        assertThat(book.bestAsk()).isEqualTo(OrderBook.NO_PRICE);
    }

    // --- depth ---

    @Test
    void shouldAggregateQuantityAcrossOrdersAtTheSamePrice() {
        book.addOrder(buy(9998, 200));
        book.addOrder(buy(9998, 100));

        List<DepthLevel> depth = book.bidDepth(5);

        assertThat(depth).containsExactly(new DepthLevel(9998, 300));
    }

    @Test
    void shouldReturnBidDepthHighestPriceFirst() {
        book.addOrder(buy(9997, 500));
        book.addOrder(buy(10001, 50));
        book.addOrder(buy(9998, 200));

        assertThat(book.bidDepth(5)).containsExactly(
                new DepthLevel(10001, 50),
                new DepthLevel(9998, 200),
                new DepthLevel(9997, 500));
    }

    @Test
    void shouldReturnAskDepthLowestPriceFirst() {
        book.addOrder(sell(10010, 400));
        book.addOrder(sell(10002, 300));
        book.addOrder(sell(10005, 150));

        assertThat(book.askDepth(5)).containsExactly(
                new DepthLevel(10002, 300),
                new DepthLevel(10005, 150),
                new DepthLevel(10010, 400));
    }

    @Test
    void shouldLimitDepthToRequestedNumberOfLevels() {
        book.addOrder(buy(10001, 50));
        book.addOrder(buy(9998, 200));
        book.addOrder(buy(9997, 500));

        assertThat(book.bidDepth(2)).containsExactly(
                new DepthLevel(10001, 50),
                new DepthLevel(9998, 200));
    }

    @Test
    void shouldReturnFewerLevelsThanRequestedWhenBookIsShallow() {
        book.addOrder(buy(10001, 50));

        assertThat(book.bidDepth(10)).hasSize(1);
    }

    @Test
    void shouldReturnEmptyDepthWhenZeroLevelsRequested() {
        book.addOrder(buy(10001, 50));

        assertThat(book.bidDepth(0)).isEmpty();
    }

    @Test
    void shouldReturnDepthSnapshotThatCallersCannotModify() {
        book.addOrder(buy(10001, 50));

        List<DepthLevel> depth = book.bidDepth(5);

        assertThatThrownBy(() -> depth.add(new DepthLevel(1, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // --- cancel ---

    @Test
    void shouldPromoteNextLevelWhenBestBidIsCancelled() {
        Order best = buy(10001, 50);
        book.addOrder(best);
        book.addOrder(buy(9998, 200));

        book.cancelOrder(best.getOrderId());

        assertThat(book.bestBid()).isEqualTo(9998);
    }

    @Test
    void shouldRemovePriceLevelOnceItsLastOrderIsCancelled() {
        Order only = buy(10001, 50);
        book.addOrder(only);

        book.cancelOrder(only.getOrderId());

        assertThat(book.bestBid()).isEqualTo(OrderBook.NO_PRICE);
        assertThat(book.bidDepth(5)).isEmpty();
    }

    @Test
    void shouldKeepPriceLevelWhileOtherOrdersRemainOnIt() {
        Order first = buy(9998, 200);
        book.addOrder(first);
        book.addOrder(buy(9998, 100));

        book.cancelOrder(first.getOrderId());

        assertThat(book.bidDepth(5)).containsExactly(new DepthLevel(9998, 100));
    }

    @Test
    void shouldRejectCancellingAnUnknownOrderId() {
        assertThatThrownBy(() -> book.cancelOrder(999))
                .isInstanceOf(UnknownOrderException.class)
                .hasMessageContaining("999");
    }

    @Test
    void shouldRejectCancellingTheSameOrderTwice() {
        Order order = buy(10001, 50);
        book.addOrder(order);
        book.cancelOrder(order.getOrderId());

        assertThatThrownBy(() -> book.cancelOrder(order.getOrderId()))
                .isInstanceOf(UnknownOrderException.class);
    }

    // --- add ---

    @Test
    void shouldRejectDuplicateOrderId() {
        book.addOrder(new Order(1, 42, 10001, 50, Side.BUY, OrderType.LIMIT, 0));

        assertThatThrownBy(() -> book.addOrder(
                new Order(1, 42, 9998, 100, Side.BUY, OrderType.LIMIT, 0)))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("42");
    }

    /**
     * Proves the cancel index is cleaned up: if cancelling left the id behind, re-adding it
     * would be rejected as a duplicate.
     */
    @Test
    void shouldAllowReusingAnOrderIdAfterItIsCancelled() {
        book.addOrder(new Order(1, 42, 10001, 50, Side.BUY, OrderType.LIMIT, 0));
        book.cancelOrder(42);

        book.addOrder(new Order(1, 42, 9998, 100, Side.BUY, OrderType.LIMIT, 0));

        assertThat(book.bestBid()).isEqualTo(9998);
    }

    @Test
    void shouldCarryTheOffendingIdOnAnUnknownOrderException() {
        assertThatThrownBy(() -> book.cancelOrder(4471))
                .asInstanceOf(throwable(UnknownOrderException.class))
                .extracting(UnknownOrderException::getOrderId)
                .isEqualTo(4471L);
    }

    /** Both rejection types share a parent, so a gateway can catch one thing. */
    @Test
    void shouldReportBothRejectionKindsAsOrderExceptions() {
        assertThatThrownBy(() -> book.cancelOrder(999)).isInstanceOf(OrderException.class);

        book.addOrder(new Order(1, 7, 10001, 50, Side.BUY, OrderType.LIMIT, 0));
        assertThatThrownBy(() -> book.addOrder(new Order(1, 7, 9998, 50, Side.BUY, OrderType.LIMIT, 0)))
                .isInstanceOf(OrderException.class);
    }

    // --- peekBest ---

    @Test
    void shouldPeekNothingOnAnEmptySide() {
        book.addOrder(buy(9998, 200));

        assertThat(book.peekBest(Side.SELL)).isEmpty();
    }

    @Test
    void shouldPeekTheOrderAtTheBestPrice() {
        book.addOrder(buy(9998, 200));
        Order best = buy(10001, 50);
        book.addOrder(best);

        assertThat(book.peekBest(Side.BUY)).containsSame(best);
    }

    @Test
    void shouldPeekTheEarliestOrderWhenSeveralRestAtTheBestPrice() {
        Order first = buy(10001, 50);
        book.addOrder(first);
        book.addOrder(buy(10001, 75));

        assertThat(book.peekBest(Side.BUY)).containsSame(first);
    }

    @Test
    void shouldNotRemoveTheOrderWhenPeeking() {
        book.addOrder(buy(10001, 50));

        book.peekBest(Side.BUY);

        assertThat(book.bidDepth(5)).containsExactly(new DepthLevel(10001, 50));
    }

    // --- removeOrder ---

    @Test
    void shouldRemoveAFilledOrderFromBothTheLevelAndTheIndex() {
        Order order = buy(10001, 50);
        book.addOrder(order);

        book.removeOrder(order.getOrderId());

        assertThat(book.bestBid()).isEqualTo(OrderBook.NO_PRICE);
        // the id is free again, which only holds if the index was cleaned up too
        book.addOrder(new Order(1, order.getOrderId(), 9998, 100, Side.BUY, OrderType.LIMIT, 0));
        assertThat(book.bestBid()).isEqualTo(9998);
    }

    // --- fixtures ---

    private Order buy(long price, int quantity) {
        return order(Side.BUY, price, quantity);
    }

    private Order sell(long price, int quantity) {
        return order(Side.SELL, price, quantity);
    }

    private Order order(Side side, long price, int quantity) {
        long id = nextOrderId++;
        return new Order(1, id, price, quantity, side, OrderType.LIMIT, id);
    }
}
