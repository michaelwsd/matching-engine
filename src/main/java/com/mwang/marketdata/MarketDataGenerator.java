package com.mwang.marketdata;

import com.mwang.model.Order;
import com.mwang.model.OrderType;
import com.mwang.model.Side;

import java.util.Objects;
import java.util.Random;

/**
 * Turns a moving mid price into a stream of plausible limit orders.
 *
 * <p>Each order takes a random side, a random size, a random account, and a price offset a
 * random distance either side of the current mid. That offset is <em>signed</em> on purpose:
 * pricing buys only below the mid and sells only above it would produce a book that never
 * crosses and therefore never trades. Letting a buy sit above the mid is what makes some of
 * this flow aggressive.
 *
 * <p>Order ids are issued in sequence from 1, so a generated stream is unique within one
 * generator and reproducible given the same seed.
 *
 * <p>Not thread-safe: {@link #nextOrder()} advances both the walk and this class's own random
 * sequence. Confine one generator to one thread.
 */
public final class MarketDataGenerator {

    private final MidPriceWalk walk;
    private final Random random;
    private final long maxOffset;
    private final int minQuantity;
    private final int maxQuantity;
    private final int accountCount; // every order picks a random account from this range

    private long nextOrderId = 1;

    /**
     * @param walk         supplies the mid price that each order is priced around
     * @param maxOffset    largest distance from the mid, in ticks, at which an order may be
     *                     priced. Must be positive - an offset of zero would price every order
     *                     at exactly the mid, which is not a market - and must be less than
     *                     the walk's {@link MidPriceWalk#floor()}, so that no generated price
     *                     can reach zero and be rejected by {@link Order}.
     * @param minQuantity  smallest order size; must be positive
     * @param maxQuantity  largest order size; must be at least {@code minQuantity}
     * @param accountCount how many distinct accounts the generated flow comes from. Must be at
     *                     least two: with a single account every match is a self-trade, so
     *                     self-trade prevention rejects it and the simulation runs forever
     *                     without producing a single trade.
     * @param seed         seed for this generator's own draws - side, offset, quantity and
     *                     account. Independent of the walk's seed, so that changing the
     *                     quantity logic does not disturb the price sequence.
     * @throws IllegalArgumentException if any bound is out of range
     * @throws NullPointerException     if {@code walk} is null
     */
    public MarketDataGenerator(MidPriceWalk walk,
                               long maxOffset,
                               int minQuantity,
                               int maxQuantity,
                               int accountCount,
                               long seed) {
        Objects.requireNonNull(walk, "walk");
        if (maxOffset <= 0) {
            throw new IllegalArgumentException("maxOffset must be positive: " + maxOffset);
        }
        if (maxOffset >= walk.floor()) {
            throw new IllegalArgumentException(
                    "maxOffset must be less than the walk's floor, otherwise a generated price "
                            + "can reach zero: " + maxOffset + " >= " + walk.floor());
        }
        if (minQuantity <= 0) {
            throw new IllegalArgumentException("minQuantity must be positive: " + minQuantity);
        }
        if (maxQuantity < minQuantity) {
            throw new IllegalArgumentException("maxQuantity must be at least minQuantity: "
                    + maxQuantity + " < " + minQuantity);
        }
        if (accountCount < 2) {
            throw new IllegalArgumentException(
                    "accountCount must be at least 2, otherwise every match is a self-trade: "
                            + accountCount);
        }

        this.walk = walk;
        this.random = new Random(seed);
        this.maxOffset = maxOffset;
        this.minQuantity = minQuantity;
        this.maxQuantity = maxQuantity;
        this.accountCount = accountCount;
    }

    /** Creates a generator with an arbitrary seed, for demonstrations rather than tests. */
    public MarketDataGenerator(MidPriceWalk walk, long maxOffset, int minQuantity,
                               int maxQuantity, int accountCount) {
        this(walk, maxOffset, minQuantity, maxQuantity, accountCount, new Random().nextLong());
    }

    /**
     * Advances the mid by one tick and prices one order around it.
     *
     * @return a limit order, always with a positive price and quantity
     */
    public Order nextOrder() {
        long mid = walk.next();
        // nextBoolean() rather than Side.values()[nextInt(2)]: values() allocates a fresh array
        // on every call, and indexing by ordinal would silently change meaning if the enum were
        // ever reordered or extended.
        Side side = random.nextBoolean() ? Side.SELL : Side.BUY;
        long price = mid + random.nextLong(-maxOffset, maxOffset + 1);
        int quantity = random.nextInt(minQuantity, maxQuantity + 1);
        int account = random.nextInt(1, accountCount + 1);
        return new Order(account, nextOrderId++, price, quantity, side, OrderType.LIMIT,
                System.currentTimeMillis());
    }
}
