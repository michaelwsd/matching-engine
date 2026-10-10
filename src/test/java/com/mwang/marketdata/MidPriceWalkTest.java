package com.mwang.marketdata;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MidPriceWalkTest {

    private static final long REFERENCE = 10_000;
    private static final long STEP = 5;
    private static final long DEVIATION = 500;
    private static final long SEED = 42;

    private static final long FLOOR = REFERENCE - DEVIATION;
    private static final long CEILING = REFERENCE + DEVIATION;

    /**
     * Long enough that the walk reaches its bounds and gets clamped many times over. A short
     * run would never leave the middle of the range and so would never exercise the clamp.
     */
    private static final int LONG_RUN = 100_000;

    // --- starting state ---

    @Test
    void shouldStartAtTheReferenceMid() {
        MidPriceWalk walk = seededWalk();

        assertThat(walk.currentMid()).isEqualTo(REFERENCE);
    }

    @Test
    void shouldNotAdvanceWhenOnlyReadingTheCurrentMid() {
        MidPriceWalk walk = seededWalk();

        long first = walk.currentMid();
        long second = walk.currentMid();
        long third = walk.currentMid();

        assertThat(first).isEqualTo(second).isEqualTo(third);
    }

    @Test
    void shouldReportTheLastGeneratedValueAsTheCurrentMid() {
        MidPriceWalk walk = seededWalk();

        long generated = walk.next();

        assertThat(walk.currentMid()).isEqualTo(generated);
    }

    // --- reproducibility ---

    /**
     * The property the whole test suite rests on: a seeded walk is deterministic. Without
     * this, every assertion below could only ever be about ranges, never about values, and a
     * failing simulation run could not be replayed.
     */
    @Test
    void shouldProduceTheSameSequenceForTheSameSeed() {
        List<Long> first = walk(seededWalk(), 1_000);
        List<Long> second = walk(seededWalk(), 1_000);

        assertThat(first).containsExactlyElementsOf(second);
    }

    @Test
    void shouldProduceDifferentSequencesForDifferentSeeds() {
        List<Long> first = walk(new MidPriceWalk(REFERENCE, STEP, DEVIATION, 1), 1_000);
        List<Long> second = walk(new MidPriceWalk(REFERENCE, STEP, DEVIATION, 2), 1_000);

        assertThat(first).isNotEqualTo(second);
    }

    // --- bounds ---

    @Test
    void shouldNeverLeaveTheConfiguredBounds() {
        assertThat(walk(seededWalk(), LONG_RUN)).allSatisfy(
                mid -> assertThat(mid).isBetween(FLOOR, CEILING));
    }

    /**
     * Stated separately from the bounds check even though it follows from it, because this is
     * the consequence that matters downstream: {@code Order} rejects a limit order priced at
     * or below zero, and that failure would surface far away from this class.
     */
    @Test
    void shouldNeverProduceANonPositivePrice() {
        assertThat(walk(seededWalk(), LONG_RUN)).allSatisfy(
                mid -> assertThat(mid).isPositive());
    }

    /**
     * A walk that returns the reference price forever satisfies every bounds assertion above
     * perfectly. This is the test that fails if {@code next()} computes a step and then
     * forgets to apply it.
     */
    @Test
    void shouldActuallyMove() {
        assertThat(Set.copyOf(walk(seededWalk(), LONG_RUN))).hasSizeGreaterThan(1);
    }

    @Test
    void shouldMoveNoFurtherThanOneStepPerCall() {
        MidPriceWalk walk = seededWalk();
        long previous = walk.currentMid();

        for (int i = 0; i < LONG_RUN; i++) {
            long current = walk.next();
            assertThat(Math.abs(current - previous))
                    .describedAs("tick %d moved from %d to %d", i, previous, current)
                    .isLessThanOrEqualTo(STEP);
            previous = current;
        }
    }

    /**
     * Pins the {@code stepSize + 1} in the call to {@code nextLong(origin, bound)}. Because
     * that interval excludes its upper end, dropping the {@code + 1} makes the largest upward
     * step one tick smaller than the largest downward step, so the walk drifts quietly
     * downwards forever. Every other test in this class still passes when that happens.
     *
     * <p>The bounds here are deliberately far wider than the walk can travel in this many
     * ticks, so nothing is ever clamped and every observed move is a raw draw.
     */
    @Test
    void shouldDrawStepsSymmetricallyAcrossTheWholeRange() {
        MidPriceWalk walk = new MidPriceWalk(1_000_000, STEP, 500_000, SEED);

        Set<Long> observedSteps = new LinkedHashSet<>();
        long previous = walk.currentMid();
        for (int i = 0; i < 10_000; i++) {
            long current = walk.next();
            observedSteps.add(current - previous);
            previous = current;
        }

        assertThat(observedSteps).containsExactlyInAnyOrder(-5L, -4L, -3L, -2L, -1L, 0L, 1L, 2L, 3L, 4L, 5L);
    }

    // --- construction ---

    @Test
    void shouldRejectANonPositiveReferenceMid() {
        assertThatThrownBy(() -> new MidPriceWalk(0, STEP, DEVIATION, SEED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("referenceMid");
    }

    @Test
    void shouldRejectANonPositiveStepSize() {
        assertThatThrownBy(() -> new MidPriceWalk(REFERENCE, 0, DEVIATION, SEED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stepSize");
    }

    @Test
    void shouldRejectANonPositiveMaxDeviation() {
        assertThatThrownBy(() -> new MidPriceWalk(REFERENCE, STEP, 0, SEED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDeviation");
    }

    /** A deviation equal to the reference puts the floor exactly on zero, which is not a price. */
    @Test
    void shouldRejectADeviationThatPutsTheFloorOnZero() {
        assertThatThrownBy(() -> new MidPriceWalk(REFERENCE, STEP, REFERENCE, SEED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDeviation");
    }

    @Test
    void shouldRejectADeviationThatPutsTheFloorBelowZero() {
        assertThatThrownBy(() -> new MidPriceWalk(REFERENCE, STEP, REFERENCE + 1, SEED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDeviation");
    }

    /** The unseeded constructor must apply the same validation, not bypass it. */
    @Test
    void shouldValidateArgumentsOnTheUnseededConstructorToo() {
        assertThatThrownBy(() -> new MidPriceWalk(REFERENCE, -1, DEVIATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stepSize");
    }

    // --- fixtures ---

    // --- the published range ---

    /**
     * {@code floor()} is not just a convenience: {@link MarketDataGenerator} validates its own
     * offset against it, so this is the contract that keeps every generated price positive.
     */
    @Test
    void shouldReportTheRangeItWillNeverLeave() {
        MidPriceWalk walk = seededWalk();

        assertThat(walk.floor()).isEqualTo(FLOOR);
        assertThat(walk.ceiling()).isEqualTo(CEILING);
    }

    @Test
    void shouldKeepTheFloorPositiveSoEveryMidCanPriceAnOrder() {
        assertThat(seededWalk().floor()).isPositive();
    }

    @Test
    void shouldNeverStepOutsideItsOwnPublishedRange() {
        MidPriceWalk walk = seededWalk();

        assertThat(walk(walk, 10_000))
                .allSatisfy(mid -> assertThat(mid).isBetween(walk.floor(), walk.ceiling()));
    }

    private static MidPriceWalk seededWalk() {
        return new MidPriceWalk(REFERENCE, STEP, DEVIATION, SEED);
    }

    private static List<Long> walk(MidPriceWalk walk, int ticks) {
        List<Long> mids = new ArrayList<>(ticks);
        for (int i = 0; i < ticks; i++) {
            mids.add(walk.next());
        }
        return mids;
    }
}
