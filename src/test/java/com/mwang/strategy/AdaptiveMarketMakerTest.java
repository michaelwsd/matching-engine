package com.mwang.strategy;

import com.mwang.book.BookSnapshot;
import com.mwang.book.DepthLevel;
import com.mwang.book.OrderBook;
import com.mwang.engine.MatchingEngine;
import com.mwang.engine.PriceTimeMatchingEngine;
import com.mwang.gateway.OrderGateway;
import com.mwang.model.OrderIdAllocator;
import com.mwang.model.Trade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the strategy against a real gateway, engine and book, and asserts on where the quotes
 * ended up. Built like {@link SimpleMarketMakerTest}: the strategy reads its mid from
 * {@link #published} rather than the live book, so the test thread never touches the book.
 *
 * <p>The volatility average is seeded from the first move it sees, so every spread assertion
 * below needs at least two requotes with a known mid between them.
 */
class AdaptiveMarketMakerTest {

    private static final long STRATEGY_ACCOUNT = 99;
    private static final int QUOTE_SIZE = 50;
    private static final long REFERENCE_MID = 10_000;
    private static final long MAX_POSITION = 100;
    private static final long MIN_HALF_SPREAD = 4;
    private static final long MAX_HALF_SPREAD = 60;

    /** 4.0x: the half-spread is four times the average mid move. */
    private static final long SPREAD_MULTIPLE_TENTHS = 40;

    /** 1.0x: a full position skews the centre by one whole half-spread. */
    private static final long SKEW_FRACTION_TENTHS = 10;

    private static final long FIRST_ID = 5_001;
    private static final long FOREIGN_ID = 90_001;
    private static final int DEPTH = 5;

    private static final BookSnapshot EMPTY_BOOK = new BookSnapshot(
            List.of(), List.of(), OrderBook.NO_PRICE, OrderBook.NO_PRICE);

    private final OrderBook book = new OrderBook();
    private final MatchingEngine engine = new PriceTimeMatchingEngine(book);
    private final OrderGateway gateway = new OrderGateway(engine);
    private final OrderIdAllocator ids = new OrderIdAllocator(FIRST_ID);
    private final AtomicReference<BookSnapshot> published = new AtomicReference<>(EMPTY_BOOK);

    private final AdaptiveMarketMaker maker = new AdaptiveMarketMaker(
            gateway, published::get, ids, STRATEGY_ACCOUNT, QUOTE_SIZE, REFERENCE_MID,
            MAX_POSITION, MIN_HALF_SPREAD, MAX_HALF_SPREAD, SPREAD_MULTIPLE_TENTHS,
            SKEW_FRACTION_TENTHS);

    @BeforeEach
    void startGateway() {
        gateway.start();
    }

    @AfterEach
    void stopGateway() {
        gateway.shutdown();
    }

    // --- sizing the spread from the movement ---

    /** Nothing measured yet, so it quotes as wide as it is allowed to rather than guessing. */
    @Test
    void shouldQuoteAtItsWidestBeforeMeasuringAnyMovement() throws Exception {
        publish(9_990, 10_010);

        maker.onRequote();

        assertThat(maker.currentHalfSpread()).isEqualTo(MAX_HALF_SPREAD);
        assertThat(maker.status().bidPrice()).isEqualTo(10_000 - MAX_HALF_SPREAD);
        assertThat(maker.status().askPrice()).isEqualTo(10_000 + MAX_HALF_SPREAD);
    }

    /** A mid that has not moved is the quietest market there is, so it quotes as tight as allowed. */
    @Test
    @Timeout(5)
    void shouldQuoteAtItsNarrowestWhenTheMidIsStill() throws Exception {
        publish(9_990, 10_010);
        maker.onRequote();
        maker.onRequote();

        assertThat(maker.currentHalfSpread()).isEqualTo(MIN_HALF_SPREAD);
        assertThat(maker.status().bidPrice()).isEqualTo(10_000 - MIN_HALF_SPREAD);
        assertThat(maker.status().askPrice()).isEqualTo(10_000 + MIN_HALF_SPREAD);
    }

    /** A 10 tick move at 4.0x is a 40 tick half-spread. */
    @Test
    @Timeout(5)
    void shouldScaleTheSpreadToTheSizeOfTheMove() throws Exception {
        publish(9_990, 10_010);
        maker.onRequote();

        publish(10_000, 10_020);
        maker.onRequote();

        assertThat(maker.currentHalfSpread()).isEqualTo(40);
        assertThat(maker.status().bidPrice()).as("10010 - 40").isEqualTo(9_970);
        assertThat(maker.status().askPrice()).as("10010 + 40").isEqualTo(10_050);
    }

    /**
     * A move that is not a round multiple of the multiple's denominator. Dividing before
     * multiplying truncates this to nothing, and every spread collapses onto the floor.
     */
    @Test
    @Timeout(5)
    void shouldScaleAMoveSmallerThanTheMultiplesDenominator() throws Exception {
        publish(9_990, 10_010);
        maker.onRequote();

        publish(9_993, 10_013);
        maker.onRequote();

        assertThat(maker.currentHalfSpread()).as("a 3 tick move at 4.0x").isEqualTo(12);
        assertThat(maker.status().bidPrice()).isEqualTo(10_003 - 12);
        assertThat(maker.status().askPrice()).isEqualTo(10_003 + 12);
    }

    @Test
    @Timeout(5)
    void shouldNotQuoteWiderThanItsCeilingHoweverFarTheMidJumps() throws Exception {
        publish(9_990, 10_010);
        maker.onRequote();

        publish(10_490, 10_510);
        maker.onRequote();

        assertThat(maker.currentHalfSpread()).isEqualTo(MAX_HALF_SPREAD);
    }

    /** The average decays towards the recent moves, so a jump does not widen it forever. */
    @Test
    @Timeout(5)
    void shouldNarrowAgainOnceTheMarketSettles() throws Exception {
        publish(9_990, 10_010);
        maker.onRequote();
        publish(10_090, 10_110);
        maker.onRequote();
        long afterTheJump = maker.currentHalfSpread();

        for (int tick = 0; tick < 40; tick++) {
            maker.onRequote();
        }

        assertThat(afterTheJump).isEqualTo(MAX_HALF_SPREAD);
        assertThat(maker.currentHalfSpread())
                .as("a still mid for forty ticks must bring it back to the floor")
                .isEqualTo(MIN_HALF_SPREAD);
    }

    // --- skew, which scales with the spread ---

    /** Long half the limit at a 4 tick half-spread: the centre moves 4 * 1.0 * 50/100 = 2 down. */
    @Test
    @Timeout(5)
    void shouldSkewInProportionToBothThePositionAndTheSpread() throws Exception {
        publish(9_990, 10_010);
        maker.onRequote();
        maker.onRequote();
        buy(MAX_POSITION / 2);

        maker.onRequote();

        assertThat(maker.currentHalfSpread()).isEqualTo(MIN_HALF_SPREAD);
        assertThat(maker.status().bidPrice()).as("centre 9998, less a 4 tick half-spread")
                .isEqualTo(9_994);
        assertThat(maker.status().askPrice()).isEqualTo(10_002);
    }

    /** At a full position the flattening quote sits exactly on the market mid. */
    @Test
    @Timeout(5)
    void shouldOfferAtTheMarketMidWhenLongAtTheLimit() throws Exception {
        publish(9_990, 10_010);
        maker.onRequote();
        maker.onRequote();
        buy(MAX_POSITION);

        maker.onRequote();

        assertThat(maker.status().askPrice()).isEqualTo(10_000);
        assertThat(maker.status().bidPrice()).as("no bid at the limit")
                .isEqualTo(OrderBook.NO_PRICE);
    }

    // --- the position limit ---

    @Test
    @Timeout(5)
    void shouldWithholdTheBidWhenLongAtTheLimit() throws Exception {
        maker.onRequote();
        buy(MAX_POSITION);

        maker.onRequote();
        gateway.shutdown();

        assertThat(book.snapshot(DEPTH).bids()).isEmpty();
        assertThat(book.snapshot(DEPTH).asks()).hasSize(1);
        assertThat(maker.status().sidesWithheld()).isEqualTo(1);
    }

    @Test
    @Timeout(5)
    void shouldWithholdTheAskWhenShortAtTheLimit() throws Exception {
        maker.onRequote();
        sell(MAX_POSITION);

        maker.onRequote();
        gateway.shutdown();

        assertThat(book.snapshot(DEPTH).asks()).isEmpty();
        assertThat(book.snapshot(DEPTH).bids()).hasSize(1);
        assertThat(maker.status().sidesWithheld()).isEqualTo(1);
    }

    @Test
    @Timeout(5)
    void shouldResumeQuotingBothSidesWhenThePositionRetreats() throws Exception {
        maker.onRequote();
        buy(MAX_POSITION);
        maker.onRequote();

        sell(1);
        maker.onRequote();
        gateway.shutdown();

        assertThat(book.snapshot(DEPTH).bids()).hasSize(1);
        assertThat(book.snapshot(DEPTH).asks()).isNotEmpty();
    }

    // --- the mid it quotes around ---

    @Test
    @Timeout(5)
    void shouldFallBackToTheReferenceMidWhenTheBookHasOneSide() throws Exception {
        publish(9_990, OrderBook.NO_PRICE);

        maker.onRequote();

        assertThat(maker.status().bidPrice()).isEqualTo(REFERENCE_MID - MAX_HALF_SPREAD);
        assertThat(maker.status().askPrice()).isEqualTo(REFERENCE_MID + MAX_HALF_SPREAD);
    }

    // --- fills ---

    @Test
    @Timeout(5)
    void shouldBookAndCountOnlyItsOwnFills() throws Exception {
        maker.onRequote();

        maker.onFill(new Trade(FIRST_ID, FOREIGN_ID, 9_940, 30, 1L));
        maker.onFill(new Trade(FOREIGN_ID, FOREIGN_ID + 1, 10_000, 70, 2L));

        StrategyStatus.Fills fills = maker.status().fills();
        assertThat(fills.count()).isEqualTo(1);
        assertThat(fills.boughtLots()).isEqualTo(30);
        assertThat(fills.netLots()).isEqualTo(maker.status().position().quantity());
    }

    // --- construction ---

    @Test
    void shouldRejectASpreadCeilingBelowItsFloor() {
        assertThatThrownBy(() -> makerWith(MIN_HALF_SPREAD, MIN_HALF_SPREAD - 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("below the min");
    }

    @Test
    void shouldRejectANonPositiveSpreadFloor() {
        assertThatThrownBy(() -> makerWith(0, MAX_HALF_SPREAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min half spread");
    }

    /** A fully skewed bid at the widest spread must still price above zero. */
    @Test
    void shouldRejectAReferenceMidThatCannotCarryTheWidestQuote() {
        assertThatThrownBy(() -> new AdaptiveMarketMaker(gateway, published::get, ids,
                STRATEGY_ACCOUNT, QUOTE_SIZE, 100, MAX_POSITION, MIN_HALF_SPREAD, MAX_HALF_SPREAD,
                SPREAD_MULTIPLE_TENTHS, SKEW_FRACTION_TENTHS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quote offset");
    }

    @Test
    void shouldRejectMissingCollaborators() {
        assertThatThrownBy(() -> new AdaptiveMarketMaker(null, published::get, ids,
                STRATEGY_ACCOUNT, QUOTE_SIZE, REFERENCE_MID, MAX_POSITION, MIN_HALF_SPREAD,
                MAX_HALF_SPREAD, SPREAD_MULTIPLE_TENTHS, SKEW_FRACTION_TENTHS))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AdaptiveMarketMaker(gateway, null, ids,
                STRATEGY_ACCOUNT, QUOTE_SIZE, REFERENCE_MID, MAX_POSITION, MIN_HALF_SPREAD,
                MAX_HALF_SPREAD, SPREAD_MULTIPLE_TENTHS, SKEW_FRACTION_TENTHS))
                .isInstanceOf(NullPointerException.class);
    }

    // --- helpers ---

    private void publish(long bestBid, long bestAsk) {
        published.set(new BookSnapshot(
                bestBid == OrderBook.NO_PRICE ? List.of() : List.of(new DepthLevel(bestBid, 100)),
                bestAsk == OrderBook.NO_PRICE ? List.of() : List.of(new DepthLevel(bestAsk, 100)),
                bestBid, bestAsk));
    }

    private void buy(long lots) {
        maker.onFill(new Trade(FIRST_ID, FOREIGN_ID, 9_940, (int) lots, 1L));
    }

    private void sell(long lots) {
        maker.onFill(new Trade(FOREIGN_ID, FIRST_ID + 1, 10_060, (int) lots, 1L));
    }

    private AdaptiveMarketMaker makerWith(long minHalfSpread, long maxHalfSpread) {
        return new AdaptiveMarketMaker(gateway, published::get, ids, STRATEGY_ACCOUNT, QUOTE_SIZE,
                REFERENCE_MID, MAX_POSITION, minHalfSpread, maxHalfSpread,
                SPREAD_MULTIPLE_TENTHS, SKEW_FRACTION_TENTHS);
    }
}
