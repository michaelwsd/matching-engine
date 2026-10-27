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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the strategy against a real gateway, engine and book, and asserts on where the quotes
 * ended up rather than on which methods were called.
 *
 * <p>The strategy reads its mid from {@link #published}, not from the live book, which is what
 * {@code Main} does too and what keeps this test free of races: the test thread never touches
 * the book. {@code gateway.shutdown()} drains the queue and joins the matcher, so it doubles as
 * "wait until the quotes have landed" before any assertion on the book.
 *
 * <p>The id allocator starts at a known {@link #FIRST_ID}, so the ids the strategy draws are
 * predictable and fills can be handed to it directly.
 */
class SimpleMarketMakerTest {

    private static final long STRATEGY_ACCOUNT = 99;
    private static final long HALF_SPREAD = 20;
    private static final int QUOTE_SIZE = 50;
    private static final long REFERENCE_MID = 10_000;
    private static final long MAX_POSITION = 100;
    private static final long MAX_SKEW = 20;
    private static final long FIRST_ID = 5_001;
    private static final int DEPTH = 5;

    /** An order id belonging to someone else, for building trades the strategy is not party to. */
    private static final long FOREIGN_ID = 90_001;

    private static final BookSnapshot EMPTY_BOOK = new BookSnapshot(
            List.of(), List.of(), OrderBook.NO_PRICE, OrderBook.NO_PRICE);

    private final OrderBook book = new OrderBook();
    private final MatchingEngine engine = new PriceTimeMatchingEngine(book);
    private final OrderGateway gateway = new OrderGateway(engine);
    private final OrderIdAllocator ids = new OrderIdAllocator(FIRST_ID);
    private final AtomicReference<BookSnapshot> published = new AtomicReference<>(EMPTY_BOOK);

    private final SimpleMarketMaker maker = new SimpleMarketMaker(
            gateway, published::get, ids, STRATEGY_ACCOUNT, HALF_SPREAD, QUOTE_SIZE,
            REFERENCE_MID, MAX_POSITION, MAX_SKEW);

    @BeforeEach
    void startGateway() {
        gateway.start();
    }

    /** Shutdown is idempotent, so this is safe whether or not the test already drained. */
    @AfterEach
    void stopGateway() {
        gateway.shutdown();
    }

    // --- which mid the quotes are built around ---

    /** Nothing to read a mid from, so the reference stands in: 10000 +/- 20. */
    @Test
    @Timeout(5)
    void shouldQuoteAroundTheReferenceMidWhenTheBookIsEmpty() throws Exception {
        maker.onRequote();
        gateway.shutdown();

        BookSnapshot resting = book.snapshot(DEPTH);
        assertThat(resting.bids()).containsExactly(new DepthLevel(9_980, QUOTE_SIZE));
        assertThat(resting.asks()).containsExactly(new DepthLevel(10_020, QUOTE_SIZE));
    }

    /**
     * One side is not a mid. Quoting around the lone bid would let a single stray order drag the
     * strategy to it, so the reference is used instead.
     */
    @Test
    @Timeout(5)
    void shouldQuoteAroundTheReferenceMidWhenOnlyOneSideExists() throws Exception {
        publish(9_100, OrderBook.NO_PRICE);

        maker.onRequote();
        gateway.shutdown();

        assertThat(book.bestBid()).as("the lone bid must not pull the quotes down").isEqualTo(9_980);
        assertThat(book.bestAsk()).isEqualTo(10_020);
    }

    /** Bid 9100, ask 9104, so mid 9102 and quotes at 9082 and 9122, nowhere near the reference. */
    @Test
    @Timeout(5)
    void shouldQuoteAroundTheBookMidWhenBothSidesExist() throws Exception {
        publish(9_100, 9_104);

        maker.onRequote();
        gateway.shutdown();

        assertThat(book.bestBid()).isEqualTo(9_082);
        assertThat(book.bestAsk()).isEqualTo(9_122);
    }

    /**
     * Bid 9100, ask 9103: the true mid is 9101.5, which is not a price the book can express.
     * Integer division truncates towards the bid, giving 9101 and quotes at 9081 and 9121.
     */
    @Test
    @Timeout(5)
    void shouldTruncateTheMidTowardsTheBidOnAnOddSpread() throws Exception {
        publish(9_100, 9_103);

        maker.onRequote();
        gateway.shutdown();

        assertThat(book.bestBid()).isEqualTo(9_081);
        assertThat(book.bestAsk()).isEqualTo(9_121);
    }

    // --- replacing quotes rather than stacking them ---

    /**
     * With nothing live yet there is nothing to cancel. A missing {@code NO_QUOTE} guard would
     * send two cancels for order 0 and show up as two missed cancels.
     */
    @Test
    @Timeout(5)
    void shouldNotCancelAnythingOnTheFirstRequote() throws Exception {
        maker.onRequote();
        gateway.shutdown();

        assertThat(gateway.ordersCancelled()).isZero();
        assertThat(gateway.cancelsMissed()).as("no cancel should have been sent at all").isZero();
    }

    /**
     * Two ticks at the same mid put both pairs at the same prices, so the level quantity says
     * whether the first pair was really withdrawn: {@code QUOTE_SIZE} if it was, double if not.
     */
    @Test
    @Timeout(5)
    void shouldReplaceItsQuotesRatherThanAccumulateThem() throws Exception {
        maker.onRequote();
        maker.onRequote();
        gateway.shutdown();

        BookSnapshot resting = book.snapshot(DEPTH);
        assertThat(resting.bids()).containsExactly(new DepthLevel(9_980, QUOTE_SIZE));
        assertThat(resting.asks()).containsExactly(new DepthLevel(10_020, QUOTE_SIZE));
        assertThat(gateway.ordersCancelled()).isEqualTo(2);
    }

    // --- the soft layer: proportional skew ---

    /**
     * Quote centre moves by {@code -held * MAX_SKEW / MAX_POSITION}, so at a tenth of the limit
     * it moves a tenth of the skew. The 7-lot row lands on 1.4 ticks and truncates to 1.
     */
    @ParameterizedTest(name = "position {0} quotes {1} / {2}")
    @CsvSource({
            "   0, 9980, 10020",
            "   7, 9979, 10019",
            "  25, 9975, 10015",
            "  50, 9970, 10010",
            " -25, 9985, 10025",
            " -50, 9990, 10030",
    })
    @Timeout(5)
    void shouldSkewBothQuotesAgainstTheInventory(int held, long bid, long ask) throws Exception {
        maker.onRequote();
        if (held > 0) {
            buy(held);
        } else if (held < 0) {
            sell(-held);
        }

        maker.onRequote();
        gateway.shutdown();

        assertThat(book.bestBid()).isEqualTo(bid);
        assertThat(book.bestAsk()).isEqualTo(ask);
        assertThat(book.bestAsk() - book.bestBid())
                .as("skew moves the centre, it never changes the width")
                .isEqualTo(2 * HALF_SPREAD);
    }

    /**
     * At the limit the full skew applies, and because MAX_SKEW equals HALF_SPREAD the surviving
     * quote sits exactly on the market mid: the whole edge surrendered to get flat.
     */
    @Test
    @Timeout(5)
    void shouldOfferAtTheMarketMidWhenLongAtTheLimit() throws Exception {
        maker.onRequote();
        buy(QUOTE_SIZE);
        buy(QUOTE_SIZE);

        maker.onRequote();
        gateway.shutdown();

        assertThat(book.bestAsk()).isEqualTo(REFERENCE_MID);
        assertThat(book.bestBid()).as("the bid is withheld, not merely skewed")
                .isEqualTo(OrderBook.NO_PRICE);
    }

    @Test
    @Timeout(5)
    void shouldBidAtTheMarketMidWhenShortAtTheLimit() throws Exception {
        maker.onRequote();
        sell(QUOTE_SIZE);
        sell(QUOTE_SIZE);

        maker.onRequote();
        gateway.shutdown();

        assertThat(book.bestBid()).isEqualTo(REFERENCE_MID);
        assertThat(book.bestAsk()).isEqualTo(OrderBook.NO_PRICE);
    }

    // --- the hard position limit ---

    /** Half way to the limit is not at it, so both sides still go out. */
    @Test
    @Timeout(5)
    void shouldQuoteBothSidesWhileUnderTheLimit() throws Exception {
        maker.onRequote();
        buy(QUOTE_SIZE);

        maker.onRequote();
        gateway.shutdown();

        BookSnapshot resting = book.snapshot(DEPTH);
        assertThat(resting.bids()).hasSize(1);
        assertThat(resting.asks()).hasSize(1);
    }

    /**
     * Long at the limit, so bidding could only make it worse. The ask stays: a fill there is
     * the thing that brings the position back, so withholding both sides would be strictly
     * worse than withholding one.
     */
    @Test
    @Timeout(5)
    void shouldWithholdTheBidWhenLongAtTheLimit() throws Exception {
        maker.onRequote();
        buy(QUOTE_SIZE);
        buy(QUOTE_SIZE);

        maker.onRequote();
        gateway.shutdown();

        BookSnapshot resting = book.snapshot(DEPTH);
        assertThat(maker.position().quantity()).isEqualTo(MAX_POSITION);
        assertThat(resting.bids()).as("nothing that could add to the position").isEmpty();
        assertThat(resting.asks()).as("the side that reduces it keeps quoting").hasSize(1);
    }

    /** The mirror image, where a sign error in the comparison would hide. */
    @Test
    @Timeout(5)
    void shouldWithholdTheAskWhenShortAtTheLimit() throws Exception {
        maker.onRequote();
        sell(QUOTE_SIZE);
        sell(QUOTE_SIZE);

        maker.onRequote();
        gateway.shutdown();

        BookSnapshot resting = book.snapshot(DEPTH);
        assertThat(maker.position().quantity()).isEqualTo(-MAX_POSITION);
        assertThat(resting.asks()).isEmpty();
        assertThat(resting.bids()).hasSize(1);
    }

    /** A limit, not a latch: once the position retreats, the withheld side comes back. */
    @Test
    @Timeout(5)
    void shouldResumeQuotingBothSidesWhenThePositionRetreats() throws Exception {
        maker.onRequote();
        buy(QUOTE_SIZE);
        buy(QUOTE_SIZE);
        maker.onRequote(); // at the limit, bid withheld

        sell(QUOTE_SIZE);
        maker.onRequote();
        gateway.shutdown();

        BookSnapshot resting = book.snapshot(DEPTH);
        assertThat(resting.bids()).hasSize(1);
        assertThat(resting.asks()).hasSize(1);
    }

    /**
     * A withheld side leaves no live quote, so the next tick must not try to cancel the one it
     * withheld. Keeping the stale id instead of clearing it shows up as a missed cancel.
     */
    @Test
    @Timeout(5)
    void shouldNotCancelASideItWithheld() throws Exception {
        maker.onRequote();
        buy(QUOTE_SIZE);
        buy(QUOTE_SIZE);
        maker.onRequote(); // bid withheld
        maker.onRequote(); // must not cancel the bid it never posted

        gateway.shutdown();

        assertThat(gateway.cancelsMissed()).isZero();
    }

    /** Counted for the summary, so the limit shows up as a number and not only as behaviour. */
    @Test
    @Timeout(5)
    void shouldCountEverySideTheLimitWithholds() throws Exception {
        maker.onRequote();
        assertThat(maker.sidesWithheld()).as("nothing withheld while flat").isZero();

        buy(QUOTE_SIZE);
        buy(QUOTE_SIZE);
        maker.onRequote();
        maker.onRequote();
        gateway.shutdown();

        assertThat(maker.sidesWithheld()).as("one side, on each of two ticks").isEqualTo(2);
    }

    // --- telling its own fills from everyone else's ---

    /** The first id drawn is the bid, the second the ask. */
    @Test
    @Timeout(5)
    void shouldBookABuyFillOnItsOwnBid() throws Exception {
        maker.onRequote();

        maker.onFill(new Trade(FIRST_ID, FOREIGN_ID, 9_980, QUOTE_SIZE, 1L));

        assertThat(maker.position().quantity()).isEqualTo(QUOTE_SIZE);
        assertThat(maker.position().averagePrice()).isEqualTo(9_980);
    }

    @Test
    @Timeout(5)
    void shouldBookASellFillOnItsOwnAsk() throws Exception {
        maker.onRequote();

        maker.onFill(new Trade(FOREIGN_ID, FIRST_ID + 1, 10_020, QUOTE_SIZE, 1L));

        assertThat(maker.position().quantity()).isEqualTo(-QUOTE_SIZE);
        assertThat(maker.position().averagePrice()).isEqualTo(10_020);
    }

    /** Every trade on the book arrives here, so a strategy that fails to filter books the market. */
    @Test
    @Timeout(5)
    void shouldIgnoreATradeBetweenOtherParticipants() throws Exception {
        maker.onRequote();

        maker.onFill(new Trade(FOREIGN_ID, FOREIGN_ID + 1, 10_000, 30, 1L));

        assertThat(maker.position().quantity()).isZero();
        assertThat(maker.position().realised()).isZero();
    }

    /**
     * A quote larger than the order crossing it stays on the book for the remainder, so one
     * quote can fill over several trades. Dropping an id on the first fill loses the rest.
     */
    @Test
    @Timeout(5)
    void shouldBookEveryPartialFillOfOneQuote() throws Exception {
        maker.onRequote();

        maker.onFill(new Trade(FIRST_ID, FOREIGN_ID, 9_980, 20, 1L));
        maker.onFill(new Trade(FIRST_ID, FOREIGN_ID + 1, 9_980, 30, 2L));

        assertThat(maker.position().quantity()).isEqualTo(50);
        assertThat(maker.position().costBasis()).isEqualTo(50 * 9_980);
    }

    /**
     * Cancelling is asynchronous, so a replaced quote keeps trading until the matcher reaches
     * the cancel. Attributing fills by the current ids alone would silently discard this one and
     * leave the position permanently wrong.
     */
    @Test
    @Timeout(5)
    void shouldBookAFillOnAQuoteItHasAlreadyReplaced() throws Exception {
        maker.onRequote(); // draws 5001 and 5002
        maker.onRequote(); // draws 5003 and 5004, so 5001 is no longer the live bid

        maker.onFill(new Trade(FIRST_ID, FOREIGN_ID, 9_980, QUOTE_SIZE, 1L));

        assertThat(maker.position().quantity())
                .as("the replaced bid was still on the book when it traded")
                .isEqualTo(QUOTE_SIZE);
    }

    // --- the published status ---

    @Test
    void shouldPublishAFlatStatusBeforeTheFirstRequote() {
        StrategyStatus status = maker.status();

        assertThat(status.bidPrice()).isEqualTo(OrderBook.NO_PRICE);
        assertThat(status.askPrice()).isEqualTo(OrderBook.NO_PRICE);
        assertThat(status.hasBothQuotes()).isFalse();
        assertThat(status.position().quantity()).isZero();
        assertThat(status.quoteSize()).isEqualTo(QUOTE_SIZE);
        assertThat(status.quotesPosted()).isZero();
        assertThat(status.fills().count()).isZero();
    }

    @Test
    @Timeout(5)
    void shouldPublishTheQuotesItJustPosted() throws Exception {
        publish(9_990, 10_010);

        maker.onRequote();

        StrategyStatus status = maker.status();
        assertThat(status.bidPrice()).isEqualTo(9_980);
        assertThat(status.askPrice()).isEqualTo(10_020);
        assertThat(status.hasBothQuotes()).isTrue();
        assertThat(status.quotesPosted()).isEqualTo(2);
    }

    /** A withheld side has no price to report, or the display would show a quote that is gone. */
    @Test
    @Timeout(5)
    void shouldPublishNoPriceForASideItWithheld() throws Exception {
        maker.onRequote(); // posts the quotes the fills below land on
        buy(QUOTE_SIZE);
        buy(QUOTE_SIZE);

        maker.onRequote();

        StrategyStatus status = maker.status();
        assertThat(status.bidPrice())
                .as("long at the limit, so no bid")
                .isEqualTo(OrderBook.NO_PRICE);
        assertThat(status.askPrice()).as("fully skewed down onto the reference mid")
                .isEqualTo(10_000);
        assertThat(status.hasBothQuotes()).isFalse();
        assertThat(status.sidesWithheld()).isEqualTo(1);
        assertThat(status.quotesPosted()).as("two on the first tick, the ask alone on the second")
                .isEqualTo(3);
    }

    @Test
    @Timeout(5)
    void shouldRepublishOnItsOwnFillWithoutWaitingForARequote() throws Exception {
        maker.onRequote();

        buy(30);

        assertThat(maker.status().position().quantity()).isEqualTo(30);
        assertThat(maker.status().fills().count()).isEqualTo(1);
        assertThat(maker.status().fills().boughtLots()).isEqualTo(30);
        assertThat(maker.status().fills().soldLots()).isZero();
    }

    /** The tally is what the end-to-end test reconciles the position against. */
    @Test
    @Timeout(5)
    void shouldTallyFilledLotsOnEachSideSeparately() throws Exception {
        maker.onRequote();

        buy(40);
        sell(15);

        StrategyStatus.Fills fills = maker.status().fills();
        assertThat(fills.count()).isEqualTo(2);
        assertThat(fills.boughtLots()).isEqualTo(40);
        assertThat(fills.soldLots()).isEqualTo(15);
        assertThat(fills.netLots()).as("and the net must be the position Position worked out")
                .isEqualTo(maker.status().position().quantity());
    }

    @Test
    @Timeout(5)
    void shouldNotCountOtherParticipantsTradesAsItsOwnFills() throws Exception {
        maker.onRequote();

        maker.onFill(new Trade(FOREIGN_ID, FOREIGN_ID + 1, 10_000, 30, 1L));

        assertThat(maker.status().fills().count()).isZero();
        assertThat(maker.status().position().quantity()).isZero();
    }

    /**
     * The whole point of publishing a copy. A reader on another thread holds this record while
     * the strategy keeps trading, and must not see the position change underneath it.
     */
    @Test
    @Timeout(5)
    void shouldPublishAStatusThatLaterFillsDoNotDisturb() throws Exception {
        maker.onRequote();
        buy(30);

        StrategyStatus held = maker.status();
        buy(20);

        assertThat(held.position().quantity()).isEqualTo(30);
        assertThat(maker.status().position().quantity()).isEqualTo(50);
    }

    // --- construction ---

    @Test
    void shouldRejectANonPositiveHalfSpread() {
        assertThatThrownBy(() -> makerWith(0, QUOTE_SIZE, REFERENCE_MID, MAX_POSITION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("half spread");
    }

    @Test
    void shouldRejectANonPositiveQuoteSize() {
        assertThatThrownBy(() -> makerWith(HALF_SPREAD, 0, REFERENCE_MID, MAX_POSITION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quote size");
    }

    @Test
    void shouldRejectANonPositiveReferenceMid() {
        assertThatThrownBy(() -> makerWith(HALF_SPREAD, QUOTE_SIZE, 0, MAX_POSITION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reference mid");
    }

    @Test
    void shouldRejectANonPositiveMaxPosition() {
        assertThatThrownBy(() -> makerWith(HALF_SPREAD, QUOTE_SIZE, REFERENCE_MID, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max position");
    }

    @Test
    void shouldRejectANonPositiveMaxSkew() {
        assertThatThrownBy(() -> new SimpleMarketMaker(gateway, published::get, ids,
                STRATEGY_ACCOUNT, HALF_SPREAD, QUOTE_SIZE, REFERENCE_MID, MAX_POSITION, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max skew");
    }

    /** A reference mid this low would price the bid at or below zero once fully skewed. */
    @Test
    void shouldRejectAReferenceMidTooLowToCarryTheSpreadAndSkew() {
        assertThatThrownBy(() -> makerWith(HALF_SPREAD, QUOTE_SIZE,
                HALF_SPREAD + MAX_SKEW, MAX_POSITION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot carry");
    }

    @Test
    void shouldRejectMissingCollaborators() {
        Supplier<BookSnapshot> supplier = published::get;
        assertThatThrownBy(() -> new SimpleMarketMaker(
                null, supplier, ids, STRATEGY_ACCOUNT, HALF_SPREAD, QUOTE_SIZE, REFERENCE_MID, MAX_POSITION, MAX_SKEW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SimpleMarketMaker(
                gateway, null, ids, STRATEGY_ACCOUNT, HALF_SPREAD, QUOTE_SIZE, REFERENCE_MID, MAX_POSITION, MAX_SKEW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SimpleMarketMaker(
                gateway, supplier, null, STRATEGY_ACCOUNT, HALF_SPREAD, QUOTE_SIZE, REFERENCE_MID, MAX_POSITION, MAX_SKEW))
                .isInstanceOf(NullPointerException.class);
    }

    // --- helpers ---

    /** Replaces what the strategy sees as the top of the book. */
    private void publish(long bestBid, long bestAsk) {
        published.set(new BookSnapshot(
                bestBid == OrderBook.NO_PRICE ? List.of() : List.of(new DepthLevel(bestBid, 100)),
                bestAsk == OrderBook.NO_PRICE ? List.of() : List.of(new DepthLevel(bestAsk, 100)),
                bestBid, bestAsk));
    }

    /**
     * Moves the position by handing the strategy a fill on a quote it posted. The same id is
     * reused across calls, which {@link Position} neither sees nor cares about: it reads only
     * the side, the quantity and the price.
     */
    private void buy(int lots) {
        maker.onFill(new Trade(FIRST_ID, FOREIGN_ID, 9_980, lots, 1L));
    }

    private void sell(int lots) {
        maker.onFill(new Trade(FOREIGN_ID, FIRST_ID + 1, 10_020, lots, 1L));
    }

    private SimpleMarketMaker makerWith(long halfSpread, int quoteSize, long referenceMid,
                                        long maxPosition) {
        return new SimpleMarketMaker(gateway, published::get, ids, STRATEGY_ACCOUNT,
                halfSpread, quoteSize, referenceMid, maxPosition, MAX_SKEW);
    }
}
