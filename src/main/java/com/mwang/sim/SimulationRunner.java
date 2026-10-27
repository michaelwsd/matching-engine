package com.mwang.sim;

import com.mwang.audit.TradeLogger;
import com.mwang.book.BookSnapshot;
import com.mwang.book.OrderBook;
import com.mwang.engine.MatchingEngine;
import com.mwang.engine.PriceTimeMatchingEngine;
import com.mwang.feed.BookFeedServer;
import com.mwang.feed.MarketDataSnapshot;
import com.mwang.gateway.MatchListener;
import com.mwang.gateway.OrderGateway;
import com.mwang.marketdata.MarketDataGenerator;
import com.mwang.marketdata.MidPriceWalk;
import com.mwang.marketdata.OrderFlowDriver;
import com.mwang.model.OrderIdAllocator;
import com.mwang.strategy.QuotingStrategy;
import com.mwang.strategy.RequoteScheduler;
import com.mwang.strategy.StrategyRunner;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wires Phases 1-5 together, runs a synthetic market for a fixed duration with a market maker
 * quoting into it, and reports what happened.
 *
 * <p>The book is never read from here, being confined to the matcher thread. A
 * {@link MatchListener} running on that thread publishes an immutable {@link BookSnapshot}
 * through an {@link AtomicReference} that this class only reads, so nothing here needs a lock.
 * The strategy publishes its position the same way.
 *
 * <p>One run per instance. {@code README.md} draws the shape of the pipeline.
 */
public final class SimulationRunner {

    private final SimulationConfig config;
    private final StrategyFactory strategyFactory;
    private final ConsoleRenderer renderer;

    /**
     * Runs with {@link StrategyFactory#simple()}, the task 5.3 maker.
     *
     * @throws NullPointerException if {@code config} is null
     */
    public SimulationRunner(SimulationConfig config) {
        this(config, StrategyFactory.simple());
    }

    /** @throws NullPointerException if either argument is null */
    public SimulationRunner(SimulationConfig config, StrategyFactory strategyFactory) {
        this.config = Objects.requireNonNull(config, "config");
        this.strategyFactory = Objects.requireNonNull(strategyFactory, "strategyFactory");
        this.renderer = new ConsoleRenderer(config);
    }

    /**
     * Builds the pipeline, runs it for the configured duration, shuts it down in order and
     * prints the summary. Blocks for the whole run; everything else happens on the threads the
     * components own.
     *
     * @return the final account of the run, every figure read after the threads were joined
     * @throws IOException          if the audit log or the feed port cannot be opened
     * @throws InterruptedException if interrupted while waiting out the run
     */
    public SimulationResult run() throws IOException, InterruptedException {
        OrderBook book = new OrderBook();
        MatchingEngine engine = new PriceTimeMatchingEngine(book);

        AtomicReference<MarketStatus> latest = new AtomicReference<>(MarketStatus.empty());

        // Runs on the matcher thread, so it may read the book; it publishes only an immutable
        // snapshot. Deliberately does no I/O - every microsecond here is one the exchange
        // spends not matching.
        MatchListener publishSnapshot = (order, trades) -> {
            MarketStatus previous = latest.get();
            latest.set(new MarketStatus(book.snapshot(config.depthLevels()),
                    previous.orders() + 1, previous.trades() + trades.size()));
        };

        // The construction order is circular: the maker needs the gateway, the gateway needs its
        // listeners, and one of those listeners is the runner, which needs the maker. Broken
        // here, because a lambda body does not run when the lambda is built - by the time the
        // matcher calls this one, all three objects exist. An AtomicReference rather than a
        // bare field because a lambda may only capture something effectively final, and because
        // the matcher thread is the one that reads it.
        AtomicReference<StrategyRunner> strategyHook = new AtomicReference<>();
        MatchListener notifyStrategy = (order, trades) -> strategyHook.get().onMatch(order, trades);

        // All three listeners run on the matcher thread, in this order. The audit log goes
        // first: if anything downstream throws, the trade has already been recorded. The
        // strategy goes last, and only enqueues, so it cannot hold up the matcher.
        TradeLogger tradeLogger = new TradeLogger(config.tradeLog());
        OrderGateway gateway = new OrderGateway(engine,
                tradeLogger.andThen(publishSnapshot).andThen(notifyStrategy));

        MidPriceWalk walk = new MidPriceWalk(config.referenceMid(), config.tickStep(),
                config.maxDeviation(), config.walkSeed());
        // One allocator, shared by everything that submits to this book, so the strategy's
        // quotes cannot collide with the synthetic flow.
        OrderIdAllocator orderIds = new OrderIdAllocator();
        MarketDataGenerator generator = new MarketDataGenerator(walk, orderIds,
                config.quoteOffset(), config.minQuantity(), config.maxQuantity(),
                config.accountCount(), config.generatorSeed());
        OrderFlowDriver driver = new OrderFlowDriver(generator, gateway, config.driverSeed());

        BookFeedServer feedServer = new BookFeedServer(config.feedPort(), () -> {
            MarketStatus status = latest.get();
            return MarketDataSnapshot.from(status.book(), status.orders(),
                    System.currentTimeMillis());
        });

        QuotingStrategy maker =
                strategyFactory.create(gateway, () -> latest.get().book(), orderIds, config);
        StrategyRunner runner = new StrategyRunner(maker);
        // Before gateway.start(), so the matcher thread cannot reach notifyStrategy first.
        // Thread.start() publishes everything written before it, so no further ceremony.
        strategyHook.set(runner);
        RequoteScheduler requoteScheduler =
                new RequoteScheduler(runner::requote, config.requoteInterval());

        renderer.printConfiguration();

        // Start forwards, each consumer before its producer: the trade log must be open before a
        // trade can occur, the matcher running before an order is submitted, and the strategy
        // listening before it is asked to quote.
        tradeLogger.start();
        gateway.start();
        runner.start();
        driver.start();
        feedServer.start();
        requoteScheduler.start();

        try {
            renderer.hideCursor();
            if (!renderer.interactive()) {
                System.out.println("  (output is not a terminal - printing a snapshot every "
                        + renderer.refresh().toSeconds() + "s instead of redrawing in place)");
                System.out.println();
            }
            render(latest, maker);
        } finally {
            renderer.showCursor();
        }

        // Each component logs an INFO line as it stops, and those lines land between the last
        // live frame and the final one.
        renderer.separate();

        // Stop backwards, in the exact reverse of the start order. The clock first, so no new
        // tick arrives mid-shutdown; then the strategy, which drains its backlog and whose last
        // requote must still find the gateway open; the trade log last, so the matcher's final
        // trades still have somewhere to go.
        long shutdownStart = System.nanoTime();
        requoteScheduler.stop();
        feedServer.close();
        driver.stop();
        runner.stop();
        gateway.shutdown();
        tradeLogger.close();
        long shutdownMillis = (System.nanoTime() - shutdownStart) / 1_000_000;

        // The matcher thread has been joined, so reading the book directly is now safe.
        BookSnapshot finalBook = book.snapshot(config.depthLevels());
        boolean markedAgainstBook = finalBook.mid() != OrderBook.NO_PRICE;

        SimulationResult result = new SimulationResult(
                config.duration(),
                shutdownMillis,
                driver.submitted(),
                gateway.ordersProcessed(),
                gateway.tradesExecuted(),
                gateway.ordersRejected(),
                gateway.ordersCancelled(),
                gateway.cancelsMissed(),
                tradeLogger.written(),
                tradeLogger.dropped(),
                runner.delivered(),
                runner.dropped(),
                runner.failures(),
                requoteScheduler.ticks(),
                requoteScheduler.failures(),
                runner.requotesSkipped(),
                maker.status(),
                markedAgainstBook ? finalBook.mid() : config.referenceMid(),
                markedAgainstBook,
                finalBook);

        renderer.printSummary(result);
        return result;
    }

    /**
     * Draws a frame per refresh until the run duration is up.
     *
     * <p>Runs to a deadline rather than a frame count, so how long the market runs does not
     * depend on how often the display redraws. Counting frames made a run that was not a whole
     * number of refreshes end early while still reporting the duration it was asked for.
     */
    private void render(AtomicReference<MarketStatus> latest, QuotingStrategy maker)
            throws InterruptedException {
        long refreshMillis = renderer.refresh().toMillis();
        long start = System.nanoTime();
        long deadline = start + config.duration().toNanos();

        renderer.drawFrame(latest.get(), maker.status(), 0, "live", false);
        for (long remaining = deadline - System.nanoTime();
                remaining > 0;
                remaining = deadline - System.nanoTime()) {
            // Never sleep past the deadline, or a slow refresh would stretch the run instead.
            Thread.sleep(Math.min(refreshMillis, TimeUnit.NANOSECONDS.toMillis(remaining) + 1));
            long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
            renderer.drawFrame(latest.get(), maker.status(), elapsedSeconds, "live", true);
        }
    }
}
