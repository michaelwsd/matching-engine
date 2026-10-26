package com.mwang.marketdata;

import com.mwang.model.Order;
import com.mwang.model.OrderIdAllocator;
import com.mwang.model.OrderType;
import com.mwang.model.Side;

import java.util.Objects;
import java.util.Random;

/**
 * Turns a moving mid price into a stream of plausible limit orders, each with a random side,
 * size, account and price offset from the current mid.
 *
 * <p>The offset is signed deliberately: pricing buys only below the mid would give a book that
 * never crosses and so never trades.
 *
 * <p>Order ids come from a shared {@link OrderIdAllocator}, so a strategy quoting into the same
 * book cannot collide with this flow. Accounts run {@code 1..accountCount}; see
 * {@link #accountCount()} for what anything else sharing the book must do.
 *
 * <p>Not thread-safe: {@link #nextOrder()} advances the walk and this class's own sequence.
 */
public final class MarketDataGenerator {

    private final MidPriceWalk walk;
    private final Random random;
    private final long maxOffset;
    private final int minQuantity;
    private final int maxQuantity;
    private final int accountCount; // every order picks a random account from this range
    private final OrderIdAllocator ids;

    /**
     * @param walk         supplies the mid price that each order is priced around
     * @param maxOffset    largest distance from the mid, in ticks. Positive, and less than the
     *                     walk's {@link MidPriceWalk#floor()} so no price can reach zero.
     * @param minQuantity  smallest order size; must be positive
     * @param maxQuantity  largest order size; must be at least {@code minQuantity}
     * @param accountCount how many distinct accounts the flow comes from. At least two: with
     *                     one, every match is a self-trade and nothing ever trades.
     * @param seed         seeds this generator's own draws only, independently of the walk
     * @throws IllegalArgumentException if any bound is out of range
     * @throws NullPointerException     if {@code walk} is null
     */
    public MarketDataGenerator(MidPriceWalk walk,
                               OrderIdAllocator ids,
                               long maxOffset,
                               int minQuantity,
                               int maxQuantity,
                               int accountCount,
                               long seed) {
        Objects.requireNonNull(walk, "walk");
        Objects.requireNonNull(ids, "ids");
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
        this.ids = ids;
        this.random = new Random(seed);
        this.maxOffset = maxOffset;
        this.minQuantity = minQuantity;
        this.maxQuantity = maxQuantity;
        this.accountCount = accountCount;
    }

    /**
     * Creates a generator with its <strong>own</strong> id sequence. Correct only when nothing
     * else submits to the same book - otherwise pass a shared {@link OrderIdAllocator}.
     */
    public MarketDataGenerator(MidPriceWalk walk, long maxOffset, int minQuantity,
                               int maxQuantity, int accountCount, long seed) {
        this(walk, new OrderIdAllocator(), maxOffset, minQuantity, maxQuantity, accountCount, seed);
    }

    /** Creates a generator with an arbitrary seed, for demonstrations rather than tests. */
    public MarketDataGenerator(MidPriceWalk walk, long maxOffset, int minQuantity,
                               int maxQuantity, int accountCount) {
        this(walk, maxOffset, minQuantity, maxQuantity, accountCount, new Random().nextLong());
    }

    /**
     * @return the highest account id this generator issues. Anything else submitting to the same
     *         book must use a higher one, or self-trade prevention silently drops its orders.
     */
    public int accountCount() {
        return accountCount;
    }

    /**
     * Advances the mid by one tick and prices one order around it.
     *
     * @return a limit order, always with a positive price and quantity
     */
    public Order nextOrder() {
        // advance the walk to get a new mid price
        long mid = walk.next();
        
        // choose a random side
        Side side = random.nextBoolean() ? Side.SELL : Side.BUY;

        // create random values for the order
        long price = mid + random.nextLong(-maxOffset, maxOffset + 1);
        int quantity = random.nextInt(minQuantity, maxQuantity + 1);
        int account = random.nextInt(1, accountCount + 1);
        return new Order(account, ids.next(), price, quantity, side, OrderType.LIMIT,
                System.currentTimeMillis());
    }
}
