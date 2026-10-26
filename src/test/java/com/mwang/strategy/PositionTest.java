package com.mwang.strategy;

import com.mwang.book.OrderBook;
import com.mwang.model.Side;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every expected number here was worked by hand before the class was written; figures derived
 * from the implementation would only prove it agrees with itself. The short cases are the ones
 * that matter, since a sign error there reports a profit while losing money.
 */
class PositionTest {

    private final Position position = new Position();

    @Test
    void shouldStartFlat() {
        assertThat(position.quantity()).isZero();
        assertThat(position.costBasis()).isZero();
        assertThat(position.realised()).isZero();
        assertThat(position.averagePrice()).isEqualTo(OrderBook.NO_PRICE);
    }

    // --- the hand-worked sequence, long ---

    /** Buy 100 at 9998 then 100 at 9996: 200 long at an average of 9997, nothing realised. */
    @Test
    void shouldAverageTwoBuysOfTheSameSide() {
        position.onFill(Side.BUY, 100, 9_998);
        position.onFill(Side.BUY, 100, 9_996);

        assertThat(position.quantity()).isEqualTo(200);
        assertThat(position.averagePrice()).isEqualTo(9_997);
        assertThat(position.costBasis()).isEqualTo(1_999_400);
        assertThat(position.realised()).as("growing a position realises nothing").isZero();
    }

    /** Then sell 150 at 10002: 5 ticks above the 9997 average, 150 times over. */
    @Test
    void shouldRealiseOnAPartialSell() {
        position.onFill(Side.BUY, 100, 9_998);
        position.onFill(Side.BUY, 100, 9_996);
        position.onFill(Side.SELL, 150, 10_002);

        assertThat(position.realised()).isEqualTo(750);
        assertThat(position.quantity()).isEqualTo(50);
        assertThat(position.averagePrice())
                .as("a reduction must not disturb the average of what is left")
                .isEqualTo(9_997);
    }

    /** Then the mid falls to 9990: 50 long at 9997 is 7 ticks underwater. */
    @Test
    void shouldMarkTheRemainderAgainstTheMid() {
        position.onFill(Side.BUY, 100, 9_998);
        position.onFill(Side.BUY, 100, 9_996);
        position.onFill(Side.SELL, 150, 10_002);

        assertThat(position.unrealised(9_990)).isEqualTo(-350);
        assertThat(position.total(9_990)).isEqualTo(400);
    }

    // --- the same sequence, mirrored into a short ---

    /** Sell 100 at 9998 then 100 at 9996: 200 short, average still 9997. */
    @Test
    void shouldAverageTwoSellsOfTheSameSide() {
        position.onFill(Side.SELL, 100, 9_998);
        position.onFill(Side.SELL, 100, 9_996);

        assertThat(position.quantity()).isEqualTo(-200);
        assertThat(position.averagePrice())
                .as("an average price is positive whichever way the position points")
                .isEqualTo(9_997);
        assertThat(position.costBasis()).isEqualTo(-1_999_400);
        assertThat(position.realised()).isZero();
    }

    /**
     * The case that matters. Buying back above the average is a loss on a short, so every figure
     * is the exact negation of the long sequence above.
     */
    @Test
    void shouldMirrorEveryFigureWhenStartingFromAShort() {
        position.onFill(Side.SELL, 100, 9_998);
        position.onFill(Side.SELL, 100, 9_996);
        position.onFill(Side.BUY, 150, 10_002);

        assertThat(position.realised()).as("bought back 5 ticks above the average").isEqualTo(-750);
        assertThat(position.quantity()).isEqualTo(-50);
        assertThat(position.averagePrice()).isEqualTo(9_997);

        assertThat(position.unrealised(9_990))
                .as("a short profits when the price falls")
                .isEqualTo(350);
        assertThat(position.total(9_990)).isEqualTo(-400);
    }

    // --- flipping through zero ---

    /** Long 100, sell 250: realises on the 100 and opens 150 short at the fill price. */
    @Test
    void shouldFlipFromLongToShortInOneFill() {
        position.onFill(Side.BUY, 100, 9_998);
        position.onFill(Side.SELL, 250, 10_002);

        assertThat(position.realised()).as("4 ticks on the 100 that closed").isEqualTo(400);
        assertThat(position.quantity()).isEqualTo(-150);
        assertThat(position.averagePrice())
                .as("the reopened side is priced at the fill, not blended with the old long")
                .isEqualTo(10_002);
    }

    @Test
    void shouldFlipFromShortToLongInOneFill() {
        position.onFill(Side.SELL, 100, 9_998);
        position.onFill(Side.BUY, 250, 9_990);

        assertThat(position.realised()).as("8 ticks on the 100 that closed").isEqualTo(800);
        assertThat(position.quantity()).isEqualTo(150);
        assertThat(position.averagePrice()).isEqualTo(9_990);
    }

    // --- invariants ---

    @Test
    void shouldReturnToFlatAfterARoundTrip() {
        position.onFill(Side.BUY, 100, 9_998);
        position.onFill(Side.SELL, 100, 10_002);

        assertThat(position.quantity()).isZero();
        assertThat(position.costBasis()).as("nothing held means nothing paid for").isZero();
        assertThat(position.realised()).isEqualTo(400);
        assertThat(position.averagePrice()).isEqualTo(OrderBook.NO_PRICE);
        assertThat(position.unrealised(1)).as("flat is flat whatever the mid").isZero();
        assertThat(position.total(9_990)).isEqualTo(400);
    }

    @Test
    void shouldReportZeroUnrealisedWhenFlatEvenWithAnUnusableMid() {
        assertThat(position.unrealised(OrderBook.NO_PRICE)).isZero();
        assertThat(position.total(OrderBook.NO_PRICE)).isZero();
    }

    @Test
    void shouldKeepTotalEqualToTheSumOfItsParts() {
        position.onFill(Side.BUY, 70, 10_001);
        position.onFill(Side.SELL, 30, 10_004);
        position.onFill(Side.BUY, 15, 9_999);

        assertThat(position.total(10_000))
                .isEqualTo(position.realised() + position.unrealised(10_000));
    }

    /** Average cost, not FIFO: the cheaper lot is not matched off first. */
    @Test
    void shouldMatchAReductionAgainstTheAverageNotTheOldestLot() {
        position.onFill(Side.BUY, 100, 9_998);
        position.onFill(Side.BUY, 100, 9_996);
        position.onFill(Side.SELL, 50, 10_002);

        assertThat(position.realised())
                .as("50 at 5 ticks over the 9997 average, not 4 ticks over the first lot")
                .isEqualTo(250);
        assertThat(position.quantity()).isEqualTo(150);
        assertThat(position.averagePrice()).isEqualTo(9_997);
    }

    // --- rejections ---

    @Test
    void shouldRefuseToMarkAHeldPositionAgainstAnUnusablePrice() {
        position.onFill(Side.BUY, 10, 10_000);

        assertThatThrownBy(() -> position.unrealised(OrderBook.NO_PRICE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot mark");
        assertThatThrownBy(() -> position.total(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectAFillThatCannotHaveHappened() {
        assertThatThrownBy(() -> position.onFill(Side.BUY, 0, 10_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity");
        assertThatThrownBy(() -> position.onFill(Side.BUY, -5, 10_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> position.onFill(Side.BUY, 10, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price");
        assertThatThrownBy(() -> position.onFill(null, 10, 10_000))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("side");
    }
}
