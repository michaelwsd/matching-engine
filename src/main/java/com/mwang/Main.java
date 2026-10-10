package com.mwang;

import com.mwang.audit.TradeLogger;
import com.mwang.book.BookSnapshot;
import com.mwang.book.DepthLevel;
import com.mwang.book.OrderBook;
import com.mwang.engine.MatchingEngine;
import com.mwang.engine.PriceTimeMatchingEngine;
import com.mwang.gateway.MatchListener;
import com.mwang.gateway.OrderGateway;
import com.mwang.marketdata.MarketDataGenerator;
import com.mwang.marketdata.MidPriceWalk;
import com.mwang.marketdata.OrderFlowDriver;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a synthetic market and redraws the order book live as orders are matched.
 *
 * <p>Wires the whole pipeline together: a bounded random walk supplies a mid price, a generator
 * turns each mid into an order, a driver pushes those orders into the gateway from its own
 * thread, and the gateway's single matcher thread feeds them to the matching engine.
 *
 * <h2>How the live view stays safe</h2>
 *
 * <p>{@link OrderBook} is not thread-safe and is confined to the matcher thread. This class
 * never reads it from {@code main} while the simulation is running. Instead it installs a
 * {@link MatchListener}, which the gateway invokes <em>on the matcher thread</em> after each
 * order. The listener is therefore inside the confinement and may walk the book; it builds an
 * immutable {@link BookSnapshot} and publishes it through an {@link AtomicReference}.
 *
 * <p>{@code main} then only ever reads that reference. It holds a value that cannot change
 * under it, so there is no lock, no copying of live structures, and no possibility of rendering
 * a crossed book that never actually existed.
 *
 * <p>Note what the division of labour buys: the snapshot is <em>built</em> on the matcher thread
 * because only that thread may touch the book, but it is <em>rendered</em> on the main thread
 * because terminal output is slow and the matcher must not wait for it.
 *
 * <p>All output here goes to {@code System.out} deliberately: this is the demo's user interface,
 * not diagnostics. Logging goes through SLF4J from inside the components.
 */
public class Main {

    /** How many price levels of depth to display per side. */
    private static final int DEPTH_LEVELS = 5;

    // --- market configuration, all prices in ticks where one tick is one cent ---

    private static final long REFERENCE_MID = 10_000;
    private static final long TICK_STEP = 5;
    private static final long MAX_DEVIATION = 500;
    private static final long QUOTE_OFFSET = 20;
    private static final int MIN_QUANTITY = 10;
    private static final int MAX_QUANTITY = 100;
    private static final int ACCOUNT_COUNT = 8;

    /** Where the audit trail is appended. Not in src/, and target/ is already gitignored. */
    private static final Path TRADE_LOG = Path.of("logs", "trades.csv");

    private static final Duration RUN_DURATION = Duration.ofSeconds(20);
    private static final Duration REFRESH = Duration.ofMillis(200);

    /**
     * Fixed seeds so that a run can be reproduced. Three separate ones, because the walk, the
     * order generator and the pacing draw independently: changing the pacing must not disturb
     * the price sequence.
     */
    private static final long WALK_SEED = 42;
    private static final long GENERATOR_SEED = 7;
    private static final long DRIVER_SEED = 99;

    // --- ANSI control sequences, for redrawing in place ---

    private static final String CLEAR_TO_END_OF_LINE = "\u001B[K";
    private static final String HIDE_CURSOR = "\u001B[?25l";
    private static final String SHOW_CURSOR = "\u001B[?25h";

    /**
     * Whether output is going to a real terminal, which is what decides how this renders.
     *
     * <p>Redrawing in place means moving the cursor back up over the previous frame with an
     * ANSI escape, and that only works on something that understands ANSI. Piped output, a CI
     * log, and IntelliJ's plain run console do not: there the escapes are ignored or printed
     * literally, and every frame simply scrolls. So when there is no terminal, this falls back
     * to printing an occasional snapshot and emits no escapes at all.
     *
     * <p>{@code System.console()} returns null when stdout is redirected; {@code isTerminal()}
     * is the Java 22 refinement for the case where it does not. Detection is never perfectly
     * reliable, so {@code -Dsimulation.ansi=true|false} overrides it, the same escape hatch
     * that command line tools offer as {@code --color=always}.
     */
    private static final boolean INTERACTIVE = detectInteractive();

    private static boolean detectInteractive() {
        String override = System.getProperty("simulation.ansi");
        if (override != null) {
            return Boolean.parseBoolean(override);
        }
        return System.console() != null && System.console().isTerminal();
    }

    /** Redraw rate on a terminal; off a terminal, each frame is a fresh block of text. */
    private static final Duration SCROLLING_REFRESH = Duration.ofSeconds(2);

    /** Everything the renderer needs, captured together so one read gives a consistent view. */
    private record Status(BookSnapshot book, long orders, long trades) { }

    private static final Status EMPTY = new Status(
            new BookSnapshot(List.of(), List.of(), OrderBook.NO_PRICE, OrderBook.NO_PRICE), 0, 0);

    public static void main(String[] args) throws InterruptedException, IOException {
        OrderBook book = new OrderBook();
        MatchingEngine engine = new PriceTimeMatchingEngine(book);

        AtomicReference<Status> latest = new AtomicReference<>(EMPTY);

        // Runs on the matcher thread, so it may read the book; it publishes only an immutable
        // snapshot. Deliberately does no I/O - every microsecond here is one the exchange
        // spends not matching.
        // The lambda function will match the abstract method, as there is only one abstract method
        MatchListener publishSnapshot = (order, trades) -> {
            Status previous = latest.get();
            latest.set(new Status(book.snapshot(DEPTH_LEVELS),
                    previous.orders() + 1, previous.trades() + trades.size()));
        };

        // Both listeners run on the matcher thread, in this order. The audit log goes first:
        // if the display code ever throws, the trade has already been recorded.
        TradeLogger tradeLogger = new TradeLogger(TRADE_LOG);
        // allows onMatch to be called on both logger and snapshot
        OrderGateway gateway = new OrderGateway(engine, tradeLogger.andThen(publishSnapshot));

        MidPriceWalk walk = new MidPriceWalk(REFERENCE_MID, TICK_STEP, MAX_DEVIATION, WALK_SEED);
        MarketDataGenerator generator = new MarketDataGenerator(
                walk, QUOTE_OFFSET, MIN_QUANTITY, MAX_QUANTITY, ACCOUNT_COUNT, GENERATOR_SEED);
        OrderFlowDriver driver = new OrderFlowDriver(generator, gateway, DRIVER_SEED);

        printConfiguration();

        // Start forwards, each consumer before its producer: the trade log must be open
        // before a trade can occur, and the matcher running before an order is submitted.
        tradeLogger.start();
        gateway.start();
        driver.start();

        try {
            if (INTERACTIVE) {
                System.out.print(HIDE_CURSOR);
            } else {
                System.out.println("  (output is not a terminal - printing a snapshot every "
                        + SCROLLING_REFRESH.toSeconds() + "s instead of redrawing in place)");
                System.out.println();
            }
            runAndRender(latest);
        } finally {
            if (INTERACTIVE) {
                System.out.print(SHOW_CURSOR);
            }
        }

        // Stop backwards, in the exact reverse of the start order. The producer first, so
        // nothing is still submitting while the gateway drains; the trade log last, so the
        // matcher's final trades still have somewhere to go.
        long shutdownStart = System.nanoTime();
        driver.stop();
        gateway.shutdown();
        tradeLogger.close();
        long shutdownMillis = (System.nanoTime() - shutdownStart) / 1_000_000;

        // The matcher thread has been joined, so reading the book directly is now safe.
        Status finalStatus = latest.get();
        draw(frame(new Status(book.snapshot(DEPTH_LEVELS), finalStatus.orders(),
                finalStatus.trades()), RUN_DURATION.toSeconds(), "final"), false);

        System.out.printf("%n  shut down cleanly in %dms%n", shutdownMillis);
        System.out.printf("  %,d trades written to %s%s%n%n", tradeLogger.written(), TRADE_LOG,
                tradeLogger.dropped() > 0 ? "  (" + tradeLogger.dropped() + " DROPPED)" : "");
    }

    private static void runAndRender(AtomicReference<Status> latest) throws InterruptedException {
        Duration refresh = INTERACTIVE ? REFRESH : SCROLLING_REFRESH;
        long frames = RUN_DURATION.toMillis() / refresh.toMillis();

        draw(frame(latest.get(), 0, "live"), true);
        for (long frame = 1; frame <= frames; frame++) {
            Thread.sleep(refresh.toMillis());
            long elapsedSeconds = frame * refresh.toMillis() / 1_000;
            draw(frame(latest.get(), elapsedSeconds, "live"), false);
        }
    }

    // --- rendering ---

    private static final int BID_WIDTH = 22;
    private static final int ASK_WIDTH = 18;

    /** Width of every section rule, so the config block and the book line up. */
    private static final int PAGE_WIDTH = BID_WIDTH + ASK_WIDTH + 3;

    /**
     * Column layouts for the two halves of the table. Header and data rows share these format
     * strings so the columns cannot drift apart.
     */
    private static final String BID_CELL = "%8s   %11s";
    private static final String ASK_CELL = "%9s %8s";

    /**
     * Redraws a frame in place by moving the cursor back up over the previous one.
     *
     * <p>Every frame has the same number of lines - empty depth rows are padded rather than
     * omitted - so the cursor always lands where the frame began. Each line is followed by an
     * erase-to-end-of-line, so a short line cannot leave debris from a longer predecessor. The
     * whole frame is assembled into one string and written once, which avoids tearing.
     *
     * <p>Off a terminal ({@link #INTERACTIVE}) no escapes are emitted at all and frames simply
     * follow one another, so piped output and CI logs stay readable.
     */
    private static void draw(List<String> lines, boolean firstFrame) {
        StringBuilder out = new StringBuilder();
        if (!firstFrame && INTERACTIVE) {
            out.append("\u001B[").append(lines.size()).append('A');
        }
        for (String line : lines) {
            out.append(line);
            if (INTERACTIVE) {
                out.append(CLEAR_TO_END_OF_LINE);
            }
            out.append('\n');
        }
        if (!INTERACTIVE) {
            out.append('\n');
        }
        System.out.print(out);
        System.out.flush();
    }

    /**
     * Builds one frame. Returns lines rather than printing them so the caller can count them,
     * which is what makes redrawing in place possible without a hardcoded height.
     */
    private static List<String> frame(Status status, long elapsedSeconds, String label) {
        BookSnapshot book = status.book();
        List<String> lines = new ArrayList<>();

        lines.add(("  " + centre(label.toUpperCase() + " ORDER BOOK", PAGE_WIDTH)).stripTrailing());
        lines.add("  " + "=".repeat(PAGE_WIDTH));
        lines.add(("  " + centre("BIDS", BID_WIDTH) + "   " + centre("ASKS", ASK_WIDTH))
                .stripTrailing());
        lines.add(String.format("  " + BID_CELL + " | " + ASK_CELL, "qty", "price", "price", "qty"));
        lines.add("  " + "-".repeat(BID_WIDTH + 1) + "+" + "-".repeat(ASK_WIDTH + 1));

        for (int i = 0; i < DEPTH_LEVELS; i++) {
            lines.add(("  " + bidCell(book.bids(), i) + " | " + askCell(book.asks(), i))
                    .stripTrailing());
        }

        lines.add("  " + "-".repeat(BID_WIDTH + 1) + "+" + "-".repeat(ASK_WIDTH + 1));
        lines.addAll(summary(book));
        lines.add(String.format("  elapsed %2ds     orders %,6d     trades %,6d",
                elapsedSeconds, status.orders(), status.trades()));
        return lines;
    }

    /** Always two lines, so every frame is the same height even on a one-sided book. */
    private static List<String> summary(BookSnapshot book) {
        if (!book.hasBothSides()) {
            return List.of("  book is one-sided or empty - no spread", "  mid       -");
        }
        long bid = book.bestBid();
        long ask = book.bestAsk();
        return List.of(
                String.format("  best bid %s    best ask %s    spread %s",
                        formatPrice(bid), formatPrice(ask), formatPrice(ask - bid)),
                String.format("  mid %s", formatMid(bid, ask)));
    }

    /** Renders one bid row, or blank padding when this side has fewer levels. */
    private static String bidCell(List<DepthLevel> bids, int index) {
        if (index >= bids.size()) {
            return " ".repeat(BID_WIDTH);
        }
        DepthLevel level = bids.get(index);
        return String.format(BID_CELL, level.quantity(), formatPrice(level.price()));
    }

    /** Renders one ask row, or blank padding when this side has fewer levels. */
    private static String askCell(List<DepthLevel> asks, int index) {
        if (index >= asks.size()) {
            return "";
        }
        DepthLevel level = asks.get(index);
        return String.format(ASK_CELL, formatPrice(level.price()), level.quantity());
    }

    /** Centres text within the given width, padding both sides with spaces. */
    private static String centre(String text, int width) {
        int padding = Math.max(0, width - text.length());
        int left = padding / 2;
        return " ".repeat(left) + text + " ".repeat(padding - left);
    }

    private static void printConfiguration() {
        System.out.println();
        System.out.println(("  " + centre("SYNTHETIC MARKET SIMULATION", PAGE_WIDTH))
                .stripTrailing());
        System.out.println("  " + "=".repeat(PAGE_WIDTH));
        System.out.printf("  reference mid    %14s%n", formatPrice(REFERENCE_MID));
        System.out.printf("  mid range        %14s%n",
                formatPrice(REFERENCE_MID - MAX_DEVIATION) + " - "
                        + formatPrice(REFERENCE_MID + MAX_DEVIATION));
        System.out.printf("  tick step        %14s%n", "+/-" + TICK_STEP + " ticks");
        System.out.printf("  quote offset     %14s%n", "+/-" + QUOTE_OFFSET + " ticks");
        System.out.printf("  order size       %14s%n", MIN_QUANTITY + " - " + MAX_QUANTITY);
        System.out.printf("  accounts         %14d%n", ACCOUNT_COUNT);
        System.out.printf("  duration         %14s%n", RUN_DURATION.toSeconds() + "s");
        System.out.println();
    }

    /**
     * Formats a price given in ticks as dollars and cents, where one tick is one cent.
     * Uses integer division rather than floating point, for the same reason prices are
     * stored as scaled longs in the first place.
     */
    private static String formatPrice(long ticks) {
        return String.format("%d.%02d", ticks / 100, ticks % 100);
    }

    /**
     * Formats the midpoint of the spread. The mid of two adjacent ticks falls on a half
     * tick, so this is computed in half-ticks and rendered with a trailing digit rather
     * than by dividing into a double.
     */
    private static String formatMid(long bid, long ask) {
        long total = bid + ask;
        String whole = formatPrice(total / 2);
        return (total % 2 == 0) ? whole : whole + "5";
    }
}
