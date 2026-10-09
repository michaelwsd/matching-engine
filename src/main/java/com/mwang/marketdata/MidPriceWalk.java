package com.mwang.marketdata;

import java.util.Random;

/**
 * A bounded random walk over a mid price, in ticks.
 *
 * <p>Each call to {@link #next()} moves the mid by a uniformly random step and then clamps
 * the result into {@code [referenceMid - maxDeviation, referenceMid + maxDeviation]}. The
 * clamp is what stops the walk drifting away over a long run: an unbounded random walk has
 * no tendency to return anywhere, so a simulation left running would eventually produce a
 * mid no order could be built from.
 *
 * <p>Instances are not thread-safe. {@link #next()} mutates {@code mid}, so confine one
 * walk to one thread.
 */
public class MidPriceWalk {
    private final long referenceMid; // level it is pulled back towards
    private final long stepSize;
    private final long maxDeviation;
    private final Random random;
    // current mid in ticks
    private long mid;

    /**
     * @param referenceMid the level the walk is bounded around, in ticks; must be positive
     * @param stepSize     the largest single move in either direction, in ticks; must be positive
     * @param maxDeviation how far the mid may stray from the reference in either direction.
     *                     Must be positive and strictly less than {@code referenceMid}, so that
     *                     the lower bound stays above zero and every mid can price a limit order.
     * @param seed         seed for the underlying generator, so that a run can be reproduced
     * @throws IllegalArgumentException if any bound is non-positive, or the deviation would
     *                                  allow the mid to reach zero
     */
    public MidPriceWalk(long referenceMid, long stepSize, long maxDeviation, long seed) {
        if (referenceMid <= 0) {
            throw new IllegalArgumentException("referenceMid must be positive: " + referenceMid);
        }
        if (stepSize <= 0) {
            throw new IllegalArgumentException("stepSize must be positive: " + stepSize);
        }
        if (maxDeviation <= 0) {
            throw new IllegalArgumentException("maxDeviation must be positive: " + maxDeviation);
        }
        if (maxDeviation >= referenceMid) {
            throw new IllegalArgumentException(
                    "maxDeviation must be less than referenceMid, otherwise the mid can reach zero: "
                            + maxDeviation + " >= " + referenceMid);
        }

        this.referenceMid = referenceMid;
        this.stepSize = stepSize;
        this.maxDeviation = maxDeviation;
        this.random = new Random(seed);
        this.mid = referenceMid;
    }

    /** Creates a walk with an arbitrary seed, for demonstrations rather than tests. */
    public MidPriceWalk(long referenceMid, long stepSize, long maxDeviation) {
        this(referenceMid, stepSize, maxDeviation, new Random().nextLong());
    }

    /**
     * Advances the walk by one tick.
     *
     * @return the new mid, always within the configured bounds and therefore always positive
     */
    public long next() {
        // step the current mid then clamp it to fit within the bound
        long step = random.nextLong(-stepSize, stepSize + 1);
        mid = clamp(mid + step);
        return mid;
    }

    /** @return the current mid, without advancing the walk */
    public long currentMid() {
        return mid;
    }

    private long clamp(long candidate) {
        long floor = referenceMid - maxDeviation;
        long ceiling = referenceMid + maxDeviation;
        return Math.clamp(candidate, floor, ceiling); // ensure it's always within the bound
    }

    @Override
    public String toString() {
        return "MidPriceWalk[mid=" + mid + " ref=" + referenceMid
                + " step=+/-" + stepSize + " dev=+/-" + maxDeviation + "]";
    }
}
