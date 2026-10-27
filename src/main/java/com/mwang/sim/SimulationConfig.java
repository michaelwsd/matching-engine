package com.mwang.sim;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Every parameter the simulation takes. One object rather than scattered constants, so the
 * runner and the renderer read the same numbers and a test can shorten a run without touching
 * anything else.
 *
 * @param duration         how long to run the market for
 * @param refresh          redraw interval on a terminal
 * @param scrollingRefresh redraw interval off a terminal, where each frame is a fresh block
 * @param depthLevels      price levels of depth to publish and display per side
 * @param tradeLog         where the audit trail is appended
 * @param feedPort         HTTP port for the book feed; 0 lets the OS choose
 * @param referenceMid     the price the synthetic mid walks around, in ticks
 * @param tickStep         how far the mid may move per step
 * @param maxDeviation     how far the mid may stray from {@code referenceMid}
 * @param quoteOffset      how far either side of the mid the synthetic flow quotes
 * @param minQuantity      smallest synthetic order
 * @param maxQuantity      largest synthetic order
 * @param accountCount     how many accounts the synthetic flow trades from
 * @param walkSeed         seeds the mid walk
 * @param generatorSeed    seeds order construction
 * @param driverSeed       seeds submission pacing
 * @param halfSpread       ticks either side of the mid the strategy quotes. Keep this below
 *                         {@code quoteOffset} or the strategy sits level with the flow, behind
 *                         it in the queue, and barely ever trades
 * @param quoteSize        quantity the strategy shows on each side
 * @param maxPosition      largest absolute position the strategy will hold
 * @param maxSkew          ticks the strategy shifts its centre at a full position
 * @param requoteInterval  gap between the end of one requote and the start of the next
 */
public record SimulationConfig(Duration duration,
                               Duration refresh,
                               Duration scrollingRefresh,
                               int depthLevels,
                               Path tradeLog,
                               int feedPort,
                               long referenceMid,
                               long tickStep,
                               long maxDeviation,
                               long quoteOffset,
                               int minQuantity,
                               int maxQuantity,
                               int accountCount,
                               long walkSeed,
                               long generatorSeed,
                               long driverSeed,
                               long halfSpread,
                               int quoteSize,
                               long maxPosition,
                               long maxSkew,
                               Duration requoteInterval) {

    /** 0 would let the OS choose; a fixed port is friendlier to curl. */
    private static final int DEFAULT_FEED_PORT = 8080;

    public SimulationConfig {
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(refresh, "refresh");
        Objects.requireNonNull(scrollingRefresh, "scrollingRefresh");
        Objects.requireNonNull(tradeLog, "tradeLog");
        Objects.requireNonNull(requoteInterval, "requoteInterval");
        if (duration.toMillis() < refresh.toMillis()) {
            throw new IllegalArgumentException("duration " + duration
                    + " is shorter than one refresh of " + refresh);
        }
    }

    /** The configuration {@code Main} runs: a twenty second market on a fixed port. */
    public static SimulationConfig defaults() {
        return of(Duration.ofSeconds(20), DEFAULT_FEED_PORT);
    }

    /**
     * Every other parameter at its default. The only two a caller normally varies are a shorter
     * run and an OS-chosen port, both of which a test wants.
     *
     * @param duration how long to run the market for
     * @param feedPort HTTP port for the book feed; 0 lets the OS choose
     */
    public static SimulationConfig of(Duration duration, int feedPort) {
        return of(duration, feedPort, Path.of("logs", "trades.csv"));
    }

    /**
     * Every other parameter at its default.
     *
     * @param duration how long to run the market for
     * @param feedPort HTTP port for the book feed; 0 lets the OS choose
     * @param tradeLog where to append the audit trail. Not in src/, and target/ is already
     *                 gitignored; a test should pass a temporary directory
     */
    public static SimulationConfig of(Duration duration, int feedPort, Path tradeLog) {
        return new SimulationConfig(
                duration,
                Duration.ofMillis(200),
                Duration.ofSeconds(2),
                5,
                tradeLog,
                feedPort,
                // Prices in ticks, where one tick is one cent.
                10_000,
                5,
                500,
                20,
                10,
                100,
                8,
                // Three separate seeds, so changing the pacing does not disturb the prices.
                42,
                7,
                99,
                // Half the flow's offset, so the strategy quotes inside the market and fills.
                10,
                50,
                300,
                10,
                Duration.ofMillis(100));
    }

    /**
     * @return the account the strategy trades from. Derived from {@link #accountCount()} so it
     *         can never collide: self-trade prevention would silently drop a strategy sharing
     *         an account with the synthetic flow.
     */
    public int strategyAccount() {
        return accountCount + 1;
    }
}
