package com.mwang.marketdata;

import com.mwang.book.OrderBook;
import com.mwang.engine.MatchingEngine;
import com.mwang.engine.PriceTimeMatchingEngine;
import com.mwang.model.Order;
import com.mwang.model.OrderType;
import com.mwang.model.Side;
import com.mwang.model.Trade;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class MarketDataGeneratorTest {

    private static final long REFERENCE = 10_000;
    private static final long STEP = 5;
    private static final long DEVIATION = 500;

    private static final long WALK_FLOOR = REFERENCE - DEVIATION;
    private static final long WALK_CEILING = REFERENCE + DEVIATION;

    private static final long MAX_OFFSET = 20;
    private static final int MIN_QUANTITY = 10;
    private static final int MAX_QUANTITY = 100;
    private static final int ACCOUNT_COUNT = 8;

    private static final long WALK_SEED = 42;
    private static final long GENERATOR_SEED = 7;

    /** Enough orders to populate a book, cross it repeatedly, and cover every random range. */
    private static final int FLOW_SIZE = 10_000;

    private final OrderBook book = new OrderBook();
    private final MatchingEngine engine = new PriceTimeMatchingEngine(book);

    // --- the generated flow must actually trade ---

    /**
     * The baseline: generated flow must reach the matching engine, not merely pile up on the
     * book. A generator that never crosses would leave the engine's matching loop unexecuted
     * while throwing no exception and breaking no invariant.
     *
     * <p>This is a weak test on its own, because at these parameters the walk drifts +/-500
     * ticks while quotes sit within +/-20 of the mid, so the mid wanders into stale resting
     * orders and trades happen even without deliberate aggression. See
     * {@link #shouldTradeFromItsOwnAggressionRatherThanPriceDrift()} for the test that
     * separates the two.
     */
    @Test
    void shouldProduceFlowThatActuallyTrades() {
        Flow flow = runFlow(FLOW_SIZE);

        assertThat(flow.trades()).isNotEmpty();
    }

    /** A generator that crosses on a handful of orders out of ten thousand is not a market. */
    @Test
    void shouldTradeOnASubstantialFractionOfTheFlow() {
        Flow flow = runFlow(FLOW_SIZE);

        assertThat(flow.trades()).hasSizeGreaterThan(FLOW_SIZE / 10);
    }

    /**
     * Isolates the generator's own aggression from the walk's drift.
     *
     * <p>Two things can make a trade happen here. Either the generator deliberately prices an
     * order through the far side, or the mid wanders far enough that orders resting from an
     * earlier price regime are crossed by accident. The second effect is large at the default
     * parameters and masks the first entirely: pricing buys strictly below the mid and sells
     * strictly above it - the intuitive design, which crosses nothing on purpose - still yields
     * 6,227 trades out of 10,000 against this design's 7,320.
     *
     * <p>So this test pins the walk almost still, leaving the generator's own pricing as the
     * only possible source of crossing. Measured over this flow: 6,444 trades with the signed
     * offset, 174 without. That gap is the property being asserted.
     */
    @Test
    void shouldTradeFromItsOwnAggressionRatherThanPriceDrift() {
        MidPriceWalk almostStatic = new MidPriceWalk(REFERENCE, STEP, 1, WALK_SEED);
        MarketDataGenerator generator = new MarketDataGenerator(
                almostStatic, MAX_OFFSET, MIN_QUANTITY, MAX_QUANTITY, ACCOUNT_COUNT,
                GENERATOR_SEED);

        Flow flow = runFlow(generator, FLOW_SIZE);

        assertThat(flow.trades()).hasSizeGreaterThan(FLOW_SIZE / 4);
    }

    @Test
    void shouldNeverLeaveTheBookCrossed() {
        runFlow(FLOW_SIZE);

        long bid = book.bestBid();
        long ask = book.bestAsk();
        if (bid != OrderBook.NO_PRICE && ask != OrderBook.NO_PRICE) {
            assertThat(bid).isLessThan(ask);
        }
    }

    /**
     * Ties the {@link Trade} records back to the orders they came from: every trade reduces the
     * remaining quantity of exactly two orders, the buyer and the seller, by its own quantity.
     * So the total filled across all orders must be twice the total traded.
     *
     * <p>A far wider net than the hand-built scenarios in the engine tests, because it holds
     * over ten thousand orders nobody chose.
     */
    @Test
    void shouldConserveQuantityBetweenFillsAndTrades() {
        Flow flow = runFlow(FLOW_SIZE);

        long traded = flow.trades().stream().mapToLong(Trade::quantity).sum();
        long filled = flow.submitted().stream()
                .mapToLong(order -> order.getOriginalQuantity() - order.getRemainingQuantity())
                .sum();

        assertThat(filled).isEqualTo(2 * traded);
    }

    @Test
    void shouldNeverOverfillAnOrder() {
        Flow flow = runFlow(FLOW_SIZE);

        assertThat(flow.submitted()).allSatisfy(order ->
                assertThat(order.getRemainingQuantity())
                        .isBetween(0, order.getOriginalQuantity()));
    }

    // --- reproducibility ---

    /**
     * Fixed seeds must give a fixed order stream. Without this, a simulation run that produced
     * a crossed book or a conservation failure could not be replayed, and "it failed once on
     * CI" would be unactionable.
     */
    @Test
    void shouldProduceTheSameSequenceForTheSameSeeds() {
        List<Order> first = generate(seededGenerator(), 1_000);
        List<Order> second = generate(seededGenerator(), 1_000);

        assertThat(fingerprint(first)).isEqualTo(fingerprint(second));
    }

    @Test
    void shouldProduceDifferentSequencesForDifferentGeneratorSeeds() {
        List<Order> first = generate(generatorSeeded(GENERATOR_SEED), 1_000);
        List<Order> second = generate(generatorSeeded(GENERATOR_SEED + 1), 1_000);

        assertThat(fingerprint(first)).isNotEqualTo(fingerprint(second));
    }

    /** The walk's seed must reach the prices: two generators differing only in it must diverge. */
    @Test
    void shouldProduceDifferentSequencesForDifferentWalkSeeds() {
        List<Order> first = generate(walkSeeded(WALK_SEED), 1_000);
        List<Order> second = generate(walkSeeded(WALK_SEED + 1), 1_000);

        assertThat(fingerprint(first)).isNotEqualTo(fingerprint(second));
    }

    // --- order ids ---

    @Test
    void shouldNumberOrdersFromOneWithoutGaps() {
        assertThat(generate(seededGenerator(), 1_000))
                .extracting(Order::getOrderId)
                .containsExactlyElementsOf(LongStream.rangeClosed(1, 1_000).boxed().toList());
    }

    // --- side ---

    @Test
    void shouldProduceBothSides() {
        assertThat(generate(seededGenerator(), 1_000))
                .extracting(Order::getSide)
                .contains(Side.BUY, Side.SELL);
    }

    // --- price ---

    @Test
    void shouldPriceEveryOrderPositively() {
        assertThat(generate(seededGenerator(), FLOW_SIZE))
                .allSatisfy(order -> assertThat(order.getPrice()).isPositive());
    }

    @Test
    void shouldPriceOrdersWithinTheWalkBoundsWidenedByTheOffset() {
        assertThat(generate(seededGenerator(), FLOW_SIZE)).allSatisfy(order ->
                assertThat(order.getPrice())
                        .isBetween(WALK_FLOOR - MAX_OFFSET, WALK_CEILING + MAX_OFFSET));
    }

    /**
     * Recovers the hidden offset. {@code nextOrder()} draws it into a local variable and adds it
     * to the mid, so it is never visible from outside - but the generator calls
     * {@code walk.next()} exactly once per order, so a second walk built on the same seed
     * replays the identical mid sequence and the offset falls out by subtraction.
     *
     * <p>Pins the {@code maxOffset + 1} in the call to {@code nextLong(origin, bound)}. That
     * interval excludes its upper end, so dropping the {@code + 1} makes the largest upward
     * offset one tick smaller than the largest downward one, biasing every buy quote downwards.
     * Every other test in this class still passes when that happens.
     */
    @Test
    void shouldDrawOffsetsSymmetricallyAcrossTheWholeRange() {
        MidPriceWalk mirror = new MidPriceWalk(REFERENCE, STEP, DEVIATION, WALK_SEED);
        MarketDataGenerator generator = seededGenerator();

        Set<Long> observedOffsets = new LinkedHashSet<>();
        for (int i = 0; i < FLOW_SIZE; i++) {
            observedOffsets.add(generator.nextOrder().getPrice() - mirror.next());
        }

        assertThat(observedOffsets).containsExactlyInAnyOrderElementsOf(
                LongStream.rangeClosed(-MAX_OFFSET, MAX_OFFSET).boxed().toList());
    }

    // --- quantity ---

    @Test
    void shouldKeepQuantitiesWithinTheConfiguredRange() {
        assertThat(generate(seededGenerator(), FLOW_SIZE)).allSatisfy(order ->
                assertThat(order.getOriginalQuantity()).isBetween(MIN_QUANTITY, MAX_QUANTITY));
    }

    /** The {@code + 1} again: without it {@code maxQuantity} is documented but unreachable. */
    @Test
    void shouldReachBothEndsOfTheQuantityRange() {
        assertThat(generate(seededGenerator(), FLOW_SIZE))
                .extracting(Order::getOriginalQuantity)
                .contains(MIN_QUANTITY, MAX_QUANTITY);
    }

    // --- account ---

    @Test
    void shouldKeepAccountsWithinTheConfiguredRange() {
        assertThat(generate(seededGenerator(), FLOW_SIZE)).allSatisfy(order ->
                assertThat(order.getAccountId()).isBetween(1L, (long) ACCOUNT_COUNT));
    }

    /**
     * With one account every match is a self-trade, so self-trade prevention rejects it and the
     * flow never trades. The constructor forbids that, but this asserts the account actually
     * varies rather than being drawn once or hardcoded.
     */
    @Test
    void shouldUseEveryConfiguredAccount() {
        assertThat(generate(seededGenerator(), FLOW_SIZE))
                .extracting(Order::getAccountId)
                .containsAll(LongStream.rangeClosed(1, ACCOUNT_COUNT).boxed().toList());
    }

    // --- order type ---

    @Test
    void shouldAlwaysProduceLimitOrders() {
        assertThat(generate(seededGenerator(), 1_000))
                .extracting(Order::getOrderType)
                .containsOnly(OrderType.LIMIT);
    }

    // --- construction ---

    @Test
    void shouldRejectANullWalk() {
        assertThatThrownBy(() -> new MarketDataGenerator(
                null, MAX_OFFSET, MIN_QUANTITY, MAX_QUANTITY, ACCOUNT_COUNT, GENERATOR_SEED))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("walk");
    }

    @Test
    void shouldRejectANonPositiveMaxOffset() {
        assertThatThrownBy(() -> new MarketDataGenerator(
                walk(), 0, MIN_QUANTITY, MAX_QUANTITY, ACCOUNT_COUNT, GENERATOR_SEED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxOffset");
    }

    @Test
    void shouldRejectANonPositiveMinQuantity() {
        assertThatThrownBy(() -> new MarketDataGenerator(
                walk(), MAX_OFFSET, 0, MAX_QUANTITY, ACCOUNT_COUNT, GENERATOR_SEED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minQuantity");
    }

    @Test
    void shouldRejectAMaxQuantityBelowTheMinimum() {
        assertThatThrownBy(() -> new MarketDataGenerator(
                walk(), MAX_OFFSET, 100, 99, ACCOUNT_COUNT, GENERATOR_SEED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxQuantity");
    }

    /** A range of exactly one size is degenerate but legal, and must not be rejected. */
    @Test
    void shouldAcceptAMaxQuantityEqualToTheMinimum() {
        MarketDataGenerator generator = new MarketDataGenerator(
                walk(), MAX_OFFSET, 50, 50, ACCOUNT_COUNT, GENERATOR_SEED);

        assertThat(generate(generator, 100))
                .extracting(Order::getOriginalQuantity)
                .containsOnly(50);
    }

    @Test
    void shouldRejectASingleAccount() {
        assertThatThrownBy(() -> new MarketDataGenerator(
                walk(), MAX_OFFSET, MIN_QUANTITY, MAX_QUANTITY, 1, GENERATOR_SEED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountCount");
    }

    @Test
    void shouldRejectZeroAccounts() {
        assertThatThrownBy(() -> new MarketDataGenerator(
                walk(), MAX_OFFSET, MIN_QUANTITY, MAX_QUANTITY, 0, GENERATOR_SEED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountCount");
    }

    /**
     * An offset wider than the walk's floor could price an order at or below zero, which
     * {@link Order} rejects - so the simulation would die partway through a run rather than
     * refuse to start. Rejecting it here turns a delayed crash into a construction failure.
     */
    @Test
    void shouldRejectAMaxOffsetThatCouldPriceAnOrderAtZero() {
        MidPriceWalk walk = new MidPriceWalk(REFERENCE, STEP, DEVIATION, WALK_SEED);

        assertThatThrownBy(() -> new MarketDataGenerator(
                walk, walk.floor(), MIN_QUANTITY, MAX_QUANTITY, ACCOUNT_COUNT, GENERATOR_SEED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxOffset");
    }

    /** The widest offset that cannot reach zero must still be accepted. */
    @Test
    void shouldAcceptTheWidestOffsetThatKeepsPricesPositive() {
        MidPriceWalk walk = new MidPriceWalk(REFERENCE, STEP, DEVIATION, WALK_SEED);

        MarketDataGenerator generator = new MarketDataGenerator(
                walk, walk.floor() - 1, MIN_QUANTITY, MAX_QUANTITY, ACCOUNT_COUNT,
                GENERATOR_SEED);

        assertThat(generate(generator, 1_000))
                .allSatisfy(order -> assertThat(order.getPrice()).isPositive());
    }

    /** The unseeded constructor must apply the same validation, not bypass it. */
    @Test
    void shouldValidateArgumentsOnTheUnseededConstructorToo() {
        assertThatThrownBy(() -> new MarketDataGenerator(
                walk(), MAX_OFFSET, MIN_QUANTITY, MAX_QUANTITY, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountCount");
    }

    // --- fixtures ---

    /** One simulation run: the orders that went in, and the trades that came out. */
    private record Flow(List<Order> submitted, List<Trade> trades) { }

    private Flow runFlow(int count) {
        return runFlow(seededGenerator(), count);
    }

    private Flow runFlow(MarketDataGenerator generator, int count) {
        List<Order> submitted = new ArrayList<>(count);
        List<Trade> trades = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            Order order = generator.nextOrder();
            submitted.add(order);
            trades.addAll(engine.submit(order));
        }
        return new Flow(submitted, trades);
    }

    private static MidPriceWalk walk() {
        return new MidPriceWalk(REFERENCE, STEP, DEVIATION, WALK_SEED);
    }

    private static MarketDataGenerator seededGenerator() {
        return generatorSeeded(GENERATOR_SEED);
    }

    private static MarketDataGenerator generatorSeeded(long seed) {
        return new MarketDataGenerator(
                walk(), MAX_OFFSET, MIN_QUANTITY, MAX_QUANTITY, ACCOUNT_COUNT, seed);
    }

    private static MarketDataGenerator walkSeeded(long walkSeed) {
        return new MarketDataGenerator(
                new MidPriceWalk(REFERENCE, STEP, DEVIATION, walkSeed),
                MAX_OFFSET, MIN_QUANTITY, MAX_QUANTITY, ACCOUNT_COUNT, GENERATOR_SEED);
    }

    private static List<Order> generate(MarketDataGenerator generator, int count) {
        List<Order> orders = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            orders.add(generator.nextOrder());
        }
        return orders;
    }

    /**
     * Everything about an order that a fixed pair of seeds must reproduce. The timestamp is
     * deliberately excluded: {@code nextOrder()} reads the wall clock, so it is not
     * reproducible, and asserting on it would make these tests flaky.
     */
    private static List<Tuple> fingerprint(List<Order> orders) {
        return orders.stream()
                .map(order -> tuple(order.getOrderId(), order.getAccountId(), order.getPrice(),
                        order.getOriginalQuantity(), order.getSide(), order.getOrderType()))
                .toList();
    }
}
