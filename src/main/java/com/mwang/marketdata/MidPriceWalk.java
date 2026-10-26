package com.mwang.marketdata;

import java.util.Random;

/**
 * A bounded random walk over a mid price, in ticks. {@link #next()} moves the mid by a uniformly
 * random step, then clamps it into {@code [referenceMid - maxDeviation, referenceMid +
 * maxDeviation]} so a long run cannot drift somewhere no order can be priced.
 *
 * <p>Not thread-safe: {@link #next()} mutates the mid. Confine one walk to one thread.
 */
public final class MidPriceWalk {

    /** The centre of the allowed range. Nothing pulls the mid back to it; the clamp only
     *  stops it leaving. */
    private final long referenceMid;
    private final long stepSize;
    private final long maxDeviation;
    private final Random random;
    // current mid in ticks
    private long mid;

    /**
     * @param referenceMid the level the walk is bounded around, in ticks; must be positive
     * @param stepSize     the largest single move in either direction, in ticks; must be positive
     * @param maxDeviation how far the mid may stray either way. Positive and less than
     *                     {@code referenceMid}, so the lower bound stays above zero.
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
        // step the current mid
        long step = random.nextLong(-stepSize, stepSize + 1);
        // clamp the new value to between the right bounds
        mid = Math.clamp(mid + step, floor(), ceiling());
        return mid;
    }

    /** @return the current mid, without advancing the walk */
    public long currentMid() {
        return mid;
    }

    /** @return the lowest mid this walk can ever produce */
    public long floor() {
        return referenceMid - maxDeviation;
    }

    /** @return the highest mid this walk can ever produce */
    public long ceiling() {
        return referenceMid + maxDeviation;
    }

    @Override
    public String toString() {
        return "MidPriceWalk[mid=" + mid + " ref=" + referenceMid
                + " step=+/-" + stepSize + " dev=+/-" + maxDeviation + "]";
    }
}
