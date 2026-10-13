package com.mwang;

import com.mwang.audit.TradeLogger;
import com.mwang.book.BookSnapshot;
import com.mwang.book.DepthLevel;
import com.mwang.book.OrderBook;
import com.mwang.engine.MatchingEngine;
import com.mwang.engine.PriceTimeMatchingEngine;
import com.mwang.gateway.MatchListener;
import com.mwang.gateway.OrderGateway;
import com.mwang.feed.BookFeedServer;
import com.mwang.feed.MarketDataSnapshot;
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
 * Runs a synthetic market for a fixed duration, redrawing the order book as it goes.
 *
 * <p>Wires the pipeline together: a random walk supplies a mid price, a generator turns each
 * mid into an order, a driver pushes those into the gateway from its own thread, and the
 * gateway's single matcher thread feeds them to the engine. Each matched order then fans out
 * to a {@link TradeLogger}, which appends it to a CSV audit trail from its own thread, and to
 * the published snapshot that this class renders and {@link BookFeedServer} serves as JSON.
 *
 * <p>The book is never read from here. It is confined to the matcher thread, so instead a
 * {@link MatchListener} - which the gateway invokes <em>on that thread</em> - builds an
 * immutable {@link BookSnapshot} and publishes it through an {@link AtomicReference}. This
 * class only ever reads that reference, so no lock is needed and no half-updated book can be
 * rendered. The snapshot is built on the matcher thread because only that thread may touch the
 * book, and rendered here because terminal I/O is slow.
 *
 * <p>Output goes to {@code System.out} deliberately: this is the demo's user interface, not
 * diagnostics. Components log through SLF4J.
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

    /** 0 would let the OS choose; a fixed port is friendlier to curl. */
    private static final int FEED_PORT = 8080;

    private static final Duration RUN_DURATION = Duration.ofSeconds(20);
    private static final Duration REFRESH = Duration.ofMillis(200);

    /** Three separate seeds, so changing the pacing does not disturb the price sequence. */
    private static final long WALK_SEED = 42;
    private static final long GENERATOR_SEED = 7;
    private static final long DRIVER_SEED = 99;

    // --- ANSI control sequences, for redrawing in place ---

    private static final String CLEAR_TO_END_OF_LINE = "\u001B[K";
    private static final String HIDE_CURSOR = "\u001B[?25l";
    private static final String SHOW_CURSOR = "\u001B[?25h";

    /**
     * Whether output is a real terminal, which decides how this renders. Redrawing in place
     * needs ANSI cursor codes; piped output, CI logs and IntelliJ's plain run console ignore
     * them, so there we print an occasional snapshot and emit no escapes at all.
     *
     * <p>Detection is never perfectly reliable, so {@code -Dsimulation.ansi=true|false}
     * overrides it, the same escape hatch as {@code --color=always}.
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

    // initial state, 0 orders and trades
    private static final Status EMPTY = new Status(
            new BookSnapshot(List.of(), List.of(), OrderBook.NO_PRICE, OrderBook.NO_PRICE), 0, 0);

    /**
     * Builds the pipeline, runs it for {@link #RUN_DURATION}, then shuts it down and prints a
     * final frame. Blocks for the whole run; everything else happens on the threads the
     * components own.
     */
    public static void main(String[] args) throws InterruptedException, IOException {
        OrderBook book = new OrderBook();
        MatchingEngine engine = new PriceTimeMatchingEngine(book);

        // holds reference to an object, initialize with empty status
        AtomicReference<Status> latest = new AtomicReference<>(EMPTY);

        // Runs on the matcher thread, so it may read the book; it publishes only an immutable
        // snapshot. Deliberately does no I/O - every microsecond here is one the exchange
        // spends not matching.
        // The lambda function will match the abstract method, as there is only one abstract method
        MatchListener publishSnapshot = (order, trades) -> {
            Status previous = latest.get(); // the local variables will be added to the method parameters
            // update the previous state
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

        // Reads the same published reference the renderer does - one get(), so the sequence
        // and the depth it reports come from the same moment.
        BookFeedServer feedServer = new BookFeedServer(FEED_PORT, () -> {
            Status status = latest.get();
            // return a snapshot of the current book
            return MarketDataSnapshot.from(status.book(), status.orders(),
                    System.currentTimeMillis());
        });

        printConfiguration();

        // Start forwards, each consumer before its producer: the trade log must be open
        // before a trade can occur, and the matcher running before an order is submitted.
        tradeLogger.start();
        gateway.start();
        driver.start();
        feedServer.start();

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

        // Each component logs an INFO line as it stops, and those lines land between the last
        // live frame and the final one. Leave a blank line so they do not look like part of
        // the frame above them.
        if (INTERACTIVE) {
            System.out.println();
        }

        // Stop backwards, in the exact reverse of the start order. The producer first, so
        // nothing is still submitting while the gateway drains; the trade log last, so the
        // matcher's final trades still have somewhere to go.
        long shutdownStart = System.nanoTime();
        feedServer.close();
        driver.stop();
        gateway.shutdown();
        tradeLogger.close();
        long shutdownMillis = (System.nanoTime() - shutdownStart) / 1_000_000;

        // The matcher thread has been joined, so reading the book directly is now safe.
        Status finalStatus = latest.get();
        // Fresh, not in place: the shutdown log lines above mean a cursor-up here would count
        // back over them and overwrite them along with half the previous frame.
        draw(frame(new Status(book.snapshot(DEPTH_LEVELS), finalStatus.orders(),
                finalStatus.trades()), RUN_DURATION.toSeconds(), "final"), false);

        System.out.printf("%n  shut down cleanly in %dms%n", shutdownMillis);
        System.out.printf("  %,d trades written to %s%s%n%n", tradeLogger.written(), TRADE_LOG,
                tradeLogger.dropped() > 0 ? "  (" + tradeLogger.dropped() + " DROPPED)" : "");
    }

    private static void runAndRender(AtomicReference<Status> latest) throws InterruptedException {
        Duration refresh = INTERACTIVE ? REFRESH : SCROLLING_REFRESH;
        long frames = RUN_DURATION.toMillis() / refresh.toMillis();

        draw(frame(latest.get(), 0, "live"), false);
        for (long frame = 1; frame <= frames; frame++) {
            Thread.sleep(refresh.toMillis());
            long elapsedSeconds = frame * refresh.toMillis() / 1_000;
            draw(frame(latest.get(), elapsedSeconds, "live"), true);
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
     * Prints one frame, optionally redrawing over the frame above it.
     *
     * <p>In-place redraw works by moving the cursor back up exactly as many lines as the frame
     * is tall. Every frame has the same height - empty depth rows are padded, not omitted - so
     * the cursor lands where the previous frame began. Each line ends with an erase-to-end, so
     * a shorter line cannot leave debris behind.
     *
     * <p>{@code redrawInPlace} must be false whenever anything else has printed since the last
     * frame, because the cursor would otherwise count back over that output and overwrite it.
     * Off a terminal it is ignored and no escapes are emitted at all.
     */
    private static void draw(List<String> lines, boolean redrawInPlace) {
        StringBuilder out = new StringBuilder();
        if (redrawInPlace && INTERACTIVE) {
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

    /** Builds one frame as lines, so the caller can count them and redraw without a fixed height. */
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
        System.out.printf("  book feed        %14s%n", "localhost:" + FEED_PORT + "/book");
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
