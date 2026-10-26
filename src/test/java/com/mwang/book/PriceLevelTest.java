package com.mwang.book;

import com.mwang.model.Order;
import com.mwang.model.OrderType;
import com.mwang.model.Side;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Time priority belongs to {@link PriceLevel}, and {@link OrderBook} exposes no way to observe
 * queue order. These tests check it directly, sharing the package to reach it.
 */
class PriceLevelTest {

    private final PriceLevel level = new PriceLevel();

    private long nextOrderId = 1;

    @Test
    void shouldBeEmptyWhenCreated() {
        assertThat(level.isEmpty()).isTrue();
        assertThat(level.totalQuantity()).isZero();
    }

    @Test
    void shouldFillOrdersInArrivalOrder() {
        Order first = order(100);
        Order second = order(200);
        Order third = order(300);

        level.add(first);
        level.add(second);
        level.add(third);

        assertThat(level.poll()).isSameAs(first);
        assertThat(level.poll()).isSameAs(second);
        assertThat(level.poll()).isSameAs(third);
    }

    @Test
    void shouldPeekAtTheFrontWithoutRemovingIt() {
        Order first = order(100);
        level.add(first);
        level.add(order(200));

        assertThat(level.peek()).isSameAs(first);
        assertThat(level.peek()).isSameAs(first);
        assertThat(level.totalQuantity()).isEqualTo(300);
    }

    @Test
    void shouldPreserveArrivalOrderWhenAnOrderIsRemovedFromTheMiddle() {
        Order first = order(100);
        Order second = order(200);
        Order third = order(300);
        level.add(first);
        level.add(second);
        level.add(third);

        assertThat(level.remove(second)).isTrue();

        assertThat(level.poll()).isSameAs(first);
        assertThat(level.poll()).isSameAs(third);
    }

    @Test
    void shouldReportWhetherRemovalFoundTheOrder() {
        Order resting = order(100);
        level.add(resting);

        assertThat(level.remove(order(200))).isFalse();
        assertThat(level.remove(resting)).isTrue();
        assertThat(level.isEmpty()).isTrue();
    }

    @Test
    void shouldSumRemainingQuantityAcrossOrders() {
        level.add(order(100));
        level.add(order(250));

        assertThat(level.totalQuantity()).isEqualTo(350);
    }

    /** Depth must reflect what is still available, not what was originally submitted. */
    @Test
    void shouldCountRemainingQuantityRatherThanOriginalAfterAPartialFill() {
        Order partiallyFilled = order(100);
        level.add(partiallyFilled);
        level.add(order(50));

        partiallyFilled.fill(60);

        assertThat(level.totalQuantity()).isEqualTo(90);
    }

    @Test
    void shouldReturnNullWhenPollingAnEmptyLevel() {
        assertThat(level.poll()).isNull();
        assertThat(level.peek()).isNull();
    }

    private Order order(int quantity) {
        long id = nextOrderId++;
        return new Order(1, id, 10001, quantity, Side.BUY, OrderType.LIMIT, id);
    }
}
