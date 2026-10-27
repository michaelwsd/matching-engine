package com.mwang.sim;

import com.mwang.book.OrderBook;
import com.mwang.strategy.Position;
import com.mwang.strategy.StrategyStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the whole system - book, matcher, gateway, audit log, feed, synthetic flow and market
 * maker - and asserts that what comes out is internally consistent.
 *
 * <p>The seeds fix the price sequence and the orders built from it, but not the interleaving:
 * the flow and the requote clock are both paced by wall time, so two runs produce different
 * trade counts. That is why nothing here asserts a figure. It asserts <em>invariants</em> -
 * orders and trades are conserved end to end, the position equals the lots that built it, and
 * the P&amp;L adds up. A specific profit would only be testing the seed.
 *
 * <p>One run in {@link #runTheSimulation}, shared by every test: the run takes seconds, and
 * every assertion below is on the result rather than on the running system.
 *
 * <p>The audit trail goes to a temporary directory, so the suite cannot overwrite the log left
 * by a real run.
 */
@Timeout(60)
class SimulationRunnerTest {

    /** Long enough for the maker to be hit several times, short enough to sit in a test suite. */
    private static final Duration RUN = Duration.ofSeconds(4);

    @TempDir
    static Path tradeLogDirectory;

    private static SimulationConfig config;
    private static SimulationResult result;

    @BeforeAll
    static void runTheSimulation() throws IOException, InterruptedException {
        // Port 0: the OS picks a free one, so a stale 8080 cannot fail the build.
        config = SimulationConfig.of(RUN, 0, tradeLogDirectory.resolve("trades.csv"));
        result = new SimulationRunner(config).run();
    }

    // --- it ran at all ---

    @Test
    void shouldCompleteAndReportTheRunItWasAskedFor() {
        assertThat(result).isNotNull();
        assertThat(result.duration()).isEqualTo(RUN);
        assertThat(result.shutdownMillis()).as("an orderly shutdown, not a hang").isNotNegative();
    }

    @Test
    void shouldProduceOrdersAndTrades() {
        assertThat(result.ordersSubmitted()).as("the synthetic flow ran").isPositive();
        assertThat(result.ordersProcessed()).isPositive();
        assertThat(result.tradesExecuted()).as("the orders actually crossed").isPositive();
    }

    @Test
    void shouldHaveQuotedAndBeenFilled() {
        assertThat(result.requoteTicks()).as("the clock drove the strategy").isPositive();
        assertThat(result.strategy().quotesPosted()).isPositive();
        assertThat(result.strategy().fills().count())
                .as("quoting inside the flow's spread must win fills, or the maker is not "
                        + "competitive and half this simulation is untested")
                .isPositive();
    }

    // --- nothing was lost on the way through ---

    /** The maker quotes into the same book, so the two sources must account for every order. */
    @Test
    void shouldConserveOrdersFromBothSubmitters() {
        assertThat(result.ordersSubmitted() + result.strategy().quotesPosted())
                .isEqualTo(result.ordersProcessed());
    }

    /** One trade, three consumers: the audit log, the display counter and the strategy. */
    @Test
    void shouldDeliverEveryTradeToEveryConsumer() {
        assertThat(result.tradesWritten()).as("audit log").isEqualTo(result.tradesExecuted());
        assertThat(result.fillsDelivered()).as("strategy").isEqualTo(result.tradesExecuted());
    }

    @Test
    void shouldWriteOneAuditRowPerTrade() throws IOException {
        List<String> rows = Files.readAllLines(config.tradeLog());

        assertThat(rows.getFirst()).isEqualTo("timestamp,buyOrderId,sellOrderId,price,quantity");
        assertThat(rows).hasSize((int) result.tradesWritten() + 1);
    }

    /** Every one of these is a fault, not a tolerance. A green run must report none. */
    @Test
    void shouldRunWithoutFaults() {
        assertThat(result.ordersRejected()).as("rejected orders").isZero();
        assertThat(result.tradesDropped()).as("trades missing from the audit log").isZero();
        assertThat(result.fillsDropped()).as("fills the strategy never saw").isZero();
        assertThat(result.requoteFailures()).as("requote ticks that threw").isZero();
        assertThat(result.strategyFailures())
                .as("calls the strategy threw out of, which would mean its last quotes and "
                        + "cancels never reached a still-open gateway")
                .isZero();
    }

    // --- the position is the sum of the fills that built it ---

    @Test
    void shouldHoldExactlyTheLotsItsFillsNetTo() {
        StrategyStatus.Fills fills = result.strategy().fills();

        assertThat(fills.boughtLots() + fills.soldLots())
                .as("a counted fill moved some quantity")
                .isPositive();
        assertThat(result.position())
                .as("Position's averaging and closing arithmetic must not disturb the net")
                .isEqualTo(fills.netLots());
    }

    /**
     * The limit is checked before posting, so a fill can carry the position up to one whole
     * quote past it - but no further, or the limit is not being enforced.
     */
    @Test
    void shouldKeepThePositionWithinOneQuoteOfTheLimit() {
        assertThat(Math.abs(result.position()))
                .isLessThanOrEqualTo(config.maxPosition() + config.quoteSize() - 1);
    }

    // --- the P&L adds up ---

    @Test
    void shouldReportTotalAsTheSumOfItsParts() {
        assertThat(result.total()).isEqualTo(result.realised() + result.unrealised());
    }

    /** The open position is marked at this price, so a nonsense mark would misstate the P&L. */
    @Test
    void shouldMarkAgainstAPriceTheWalkCouldHaveReached() {
        long reach = config.maxDeviation() + config.quoteOffset();

        assertThat(result.markMid())
                .isBetween(config.referenceMid() - reach, config.referenceMid() + reach);
    }

    @Test
    void shouldKeepTheCostBasisConsistentWithThePosition() {
        Position position = result.strategy().position();

        if (position.quantity() == 0) {
            assertThat(position.costBasis()).as("nothing held means nothing paid for").isZero();
            assertThat(position.unrealised(result.markMid())).isZero();
            assertThat(position.averagePrice()).isEqualTo(OrderBook.NO_PRICE);
        } else {
            assertThat(position.costBasis() > 0).as("a long paid out, a short took in")
                    .isEqualTo(position.quantity() > 0);
            assertThat(position.averagePrice()).as("an average price is positive either way")
                    .isPositive();
            assertThat(position.unrealised(result.markMid()))
                    .isEqualTo(position.quantity() * result.markMid() - position.costBasis());
        }
    }

    // --- construction ---

    @Test
    void shouldRejectAMissingConfiguration() {
        assertThatThrownBy(() -> new SimulationRunner(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("config");
    }

    @Test
    void shouldRejectARunTooShortToDrawOneFrame() {
        assertThatThrownBy(() -> SimulationConfig.of(Duration.ofMillis(1), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shorter than one refresh");
    }
}
