package com.mwang.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Issues unique order ids to everything that submits to one book.
 *
 * <p>A book rejects a duplicate id while the first order still rests, so every participant
 * sharing a book must draw from the same sequence. Handing them one allocator makes a collision
 * impossible; giving each its own base and hoping the ranges never meet makes it merely
 * unlikely, and the failure arrives mid-run as an
 * {@link com.mwang.exception.InvalidOrderException}.
 *
 * <p>Thread-safe, and genuinely needs to be: a market-data generator on the producer thread and
 * a strategy on its own thread call {@link #next()} concurrently. This is the case an
 * {@link AtomicLong} exists for - two writers doing read-modify-write, where {@code volatile}
 * would lose ids to interleaving.
 */
public final class OrderIdAllocator {

    private final AtomicLong next;

    /** Starts at 1. Zero is avoided so an unset id is distinguishable from a real one. */
    public OrderIdAllocator() {
        this(1);
    }

    /**
     * @param first the first id to issue
     * @throws IllegalArgumentException if {@code first} is not positive
     */
    public OrderIdAllocator(long first) {
        if (first <= 0) {
            throw new IllegalArgumentException("first id must be positive: " + first);
        }
        this.next = new AtomicLong(first);
    }

    /** @return an id no other caller of this allocator will receive */
    public long next() {
        return next.getAndIncrement();
    }

    /** @return how many ids have been issued, for reporting rather than for allocating */
    public long issued() {
        return next.get() - 1;
    }

    @Override
    public String toString() {
        return "OrderIdAllocator[next=" + next.get() + "]";
    }
}
