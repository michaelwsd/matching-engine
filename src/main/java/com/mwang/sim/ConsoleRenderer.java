package com.mwang.sim;

import com.mwang.book.BookSnapshot;
import com.mwang.book.DepthLevel;
import com.mwang.book.OrderBook;
import com.mwang.strategy.Position;
import com.mwang.strategy.StrategyStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the simulation to {@code System.out}: a configuration block, a frame per refresh, and a
 * closing summary. Output goes to standard out because it is the demo's user interface, not
 * diagnostics.
 *
 * <p>On a terminal each frame is drawn over the one above it, which only works if every frame is
 * the same height - so empty depth rows and missing figures are padded rather than omitted.
 */
public final class ConsoleRenderer {

    // --- ANSI control sequences, for redrawing in place ---

    private static final String CLEAR_TO_END_OF_LINE = "\u001B[K";
    private static final String HIDE_CURSOR = "\u001B[?25l";
    private static final String SHOW_CURSOR = "\u001B[?25h";

    private static final int BID_WIDTH = 22;
    private static final int ASK_WIDTH = 18;

    /** Width of every section rule, so the config block, the book and the maker line up. */
    private static final int PAGE_WIDTH = BID_WIDTH + ASK_WIDTH + 3;

    /** Shared by header and data rows, so the two halves of the table cannot drift apart. */
    private static final String BID_CELL = "%8s   %11s";
    private static final String ASK_CELL = "%9s %8s";

    /** Two label-value pairs per line, summing to {@link #PAGE_WIDTH}. */
    private static final String PAIR_ROW = "  %-11s%8s   %-11s%8s";

    /** One label-value pair, right-aligned, for the config block and the summary. */
    private static final String FIELD_ROW = "  %-22s%19s";

    /** Cells each side of centre on the position bar. */
    private static final int BAR_HALF_WIDTH = 10;

    /** Shown where a figure does not exist: a flat position has no entry price, and so on. */
    private static final String ABSENT = "-";

    private final SimulationConfig config;

    /**
     * Whether output is a real terminal. Redrawing in place needs ANSI cursor codes, which piped
     * output and CI logs ignore, so off a terminal we print occasional snapshots instead.
     */
    private final boolean interactive;

    public ConsoleRenderer(SimulationConfig config) {
        this.config = config;
        this.interactive = detectInteractive();
    }

    /** Override the detection with {@code -Dsimulation.ansi=true|false}. */
    private static boolean detectInteractive() {
        String override = System.getProperty("simulation.ansi");
        if (override != null) {
            return Boolean.parseBoolean(override);
        }
        return System.console() != null && System.console().isTerminal();
    }

    /** @return true when frames may be redrawn in place rather than printed one after another */
    public boolean interactive() {
        return interactive;
    }

    /** @return how often to redraw: fast in place on a terminal, slower in blocks off one */
    public Duration refresh() {
        return interactive ? config.refresh() : config.scrollingRefresh();
    }

    public void hideCursor() {
        if (interactive) {
            System.out.print(HIDE_CURSOR);
        }
    }

    public void showCursor() {
        if (interactive) {
            System.out.print(SHOW_CURSOR);
        }
    }

    /** Leaves a blank line, so shutdown log lines do not look like part of the frame above. */
    public void separate() {
        if (interactive) {
            System.out.println();
        }
    }

    // --- configuration block ---

    public void printConfiguration() {
        System.out.println();
        System.out.println(("  " + centre("SYNTHETIC MARKET SIMULATION", PAGE_WIDTH))
                .stripTrailing());
        System.out.println("  " + "=".repeat(PAGE_WIDTH));
        field("reference mid", formatPrice(config.referenceMid()));
        field("mid range", formatPrice(config.referenceMid() - config.maxDeviation()) + " - "
                + formatPrice(config.referenceMid() + config.maxDeviation()));
        field("tick step", "+/-" + config.tickStep() + " ticks");
        field("quote offset", "+/-" + config.quoteOffset() + " ticks");
        field("order size", config.minQuantity() + " - " + config.maxQuantity());
        field("accounts", config.accountCount());
        field("duration", config.duration().toSeconds() + "s");
        // 0 means the OS picks one, which is not known until the server starts and logs it.
        field("book feed", config.feedPort() == 0
                ? "port assigned at startup"
                : "localhost:" + config.feedPort() + "/book");
        System.out.println();
        System.out.println(("  " + centre("MARKET MAKER", PAGE_WIDTH)).stripTrailing());
        System.out.println("  " + "-".repeat(PAGE_WIDTH));
        field("account", config.strategyAccount());
        field("half spread", "+/-" + config.halfSpread() + " ticks");
        field("quote size", config.quoteSize());
        field("max position", "+/-" + config.maxPosition());
        field("max skew", config.maxSkew() + " ticks");
        field("requote every", config.requoteInterval().toMillis() + "ms");
        System.out.println();
    }

    private void field(String label, Object value) {
        System.out.printf(FIELD_ROW + "%n", label, value);
    }

    // --- live frames ---

    /**
     * Prints one frame, optionally redrawing over the frame above it.
     *
     * <p>{@code redrawInPlace} must be false whenever anything else has printed since the last
     * frame, or the cursor counts back over that output. Ignored off a terminal.
     */
    public void drawFrame(MarketStatus market, StrategyStatus strategy, long elapsedSeconds,
                          String label, boolean redrawInPlace) {
        draw(frame(market, strategy, elapsedSeconds, label), redrawInPlace);
    }

    private void draw(List<String> lines, boolean redrawInPlace) {
        StringBuilder out = new StringBuilder();
        if (redrawInPlace && interactive) {
            out.append("\u001B[").append(lines.size()).append('A');
        }
        for (String line : lines) {
            out.append(line);
            if (interactive) {
                out.append(CLEAR_TO_END_OF_LINE);
            }
            out.append('\n');
        }
        if (!interactive) {
            out.append('\n');
        }
        System.out.print(out);
        System.out.flush();
    }

    /** Builds one frame as lines, so the caller can count them and redraw without a fixed height. */
    private List<String> frame(MarketStatus market, StrategyStatus strategy, long elapsedSeconds,
                               String label) {
        BookSnapshot book = market.book();
        List<String> lines = new ArrayList<>();

        lines.add(("  " + centre(label.toUpperCase() + " ORDER BOOK", PAGE_WIDTH)).stripTrailing());
        lines.add("  " + "=".repeat(PAGE_WIDTH));
        lines.add(("  " + centre("BIDS", BID_WIDTH) + "   " + centre("ASKS", ASK_WIDTH))
                .stripTrailing());
        lines.add(String.format("  " + BID_CELL + " | " + ASK_CELL, "qty", "price", "price", "qty"));
        lines.add("  " + "-".repeat(BID_WIDTH + 1) + "+" + "-".repeat(ASK_WIDTH + 1));

        for (int i = 0; i < config.depthLevels(); i++) {
            lines.add(("  " + bidCell(book.bids(), i) + " | " + askCell(book.asks(), i))
                    .stripTrailing());
        }

        lines.add("  " + "-".repeat(BID_WIDTH + 1) + "+" + "-".repeat(ASK_WIDTH + 1));
        lines.addAll(marketSummary(book));
        lines.addAll(makerPanel(strategy, book.mid()));
        lines.add(String.format("  elapsed %2ds     orders %,6d     trades %,6d",
                elapsedSeconds, market.orders(), market.trades()));
        return lines;
    }

    /** Always two lines, so every frame is the same height even on a one-sided book. */
    private List<String> marketSummary(BookSnapshot book) {
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

    /**
     * The strategy's own panel: where it is quoting, what it is holding, and what that is worth.
     * Always seven lines, with {@link #ABSENT} standing in for a figure that does not exist yet,
     * so the frame height never changes as the position opens and closes.
     *
     * <p>P&amp;L is in ticks and prices are in dollars, matching the book above.
     *
     * @param mid the market mid to mark the open position against, or
     *            {@link OrderBook#NO_PRICE} when the book has no mid to offer
     */
    private List<String> makerPanel(StrategyStatus strategy, long mid) {
        Position position = strategy.position();
        boolean markable = position.quantity() == 0 || mid != OrderBook.NO_PRICE;

        List<String> lines = new ArrayList<>();
        lines.add("  " + "-".repeat(PAGE_WIDTH));
        lines.add(("  " + centre("MARKET MAKER", PAGE_WIDTH)).stripTrailing());
        lines.add(String.format(PAIR_ROW,
                "quoting bid", priceOrAbsent(strategy.bidPrice()),
                "ask", priceOrAbsent(strategy.askPrice())));
        lines.add(String.format(PAIR_ROW,
                "position", signed(position.quantity()),
                "entry", priceOrAbsent(position.averagePrice())));
        lines.add(String.format(PAIR_ROW,
                "realised", signed(position.realised()),
                "unrealised", markable ? signed(position.unrealised(mid)) : ABSENT));
        lines.add(String.format(PAIR_ROW,
                "total", markable ? signed(position.total(mid)) : ABSENT,
                "withheld", String.format("%,d", strategy.sidesWithheld())));
        lines.add(("  " + centre(positionBar(position.quantity()), PAGE_WIDTH)).stripTrailing());
        return lines;
    }

    /**
     * A bar showing how much of the position limit is used, filling out from centre towards the
     * side being held. Inventory risk is the thing to watch in this simulation, and a number
     * alone does not show how close to the edge it is.
     */
    private String positionBar(long quantity) {
        // Multiply before dividing: the other order truncates to zero below a full limit. Not
        // clamped, because a fill may overshoot the limit and the loop below stops at its own
        // bounds anyway.
        long filled = Math.abs(quantity) * BAR_HALF_WIDTH / config.maxPosition();

        StringBuilder bar = new StringBuilder("short [");
        for (int cell = -BAR_HALF_WIDTH; cell <= BAR_HALF_WIDTH; cell++) {
            if (cell == 0) {
                bar.append(quantity == 0 ? '|' : '+');
            } else if (quantity > 0 && cell > 0 && cell <= filled) {
                bar.append('#');
            } else if (quantity < 0 && cell < 0 && -cell <= filled) {
                bar.append('#');
            } else {
                bar.append('-');
            }
        }
        return bar.append("] long").toString();
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

    // --- closing summary ---

    /**
     * The final account of the run. Printed fresh rather than redrawn: the shutdown log lines
     * above it mean a cursor-up here would count back over them.
     */
    public void printSummary(SimulationResult result) {
        drawFrame(new MarketStatus(result.finalBook(), result.ordersProcessed(),
                result.tradesExecuted()), result.strategy(), result.duration().toSeconds(),
                "final", false);

        System.out.println();
        System.out.println(("  " + centre("SIMULATION COMPLETE", PAGE_WIDTH)).stripTrailing());
        System.out.println("  " + "=".repeat(PAGE_WIDTH));
        field("duration", result.duration().toSeconds() + "s");
        // Separated because the maker quotes into the same book: the two add up to the third.
        field("flow orders", String.format("%,d", result.ordersSubmitted()));
        field("maker quotes", String.format("%,d", result.strategy().quotesPosted()));
        field("orders matched", String.format("%,d", result.ordersProcessed()));
        field("trades executed", String.format("%,d", result.tradesExecuted()));
        field("orders rejected", String.format("%,d", result.ordersRejected()));
        field("orders cancelled", String.format("%,d", result.ordersCancelled()));
        field("cancels too late", String.format("%,d", result.cancelsMissed()));
        System.out.println();
        System.out.println(("  " + centre("MARKET MAKER", PAGE_WIDTH)).stripTrailing());
        System.out.println("  " + "-".repeat(PAGE_WIDTH));
        field("requotes", String.format("%,d", result.requoteTicks()));
        field("requotes skipped", String.format("%,d", result.requotesSkipped()));
        field("sides withheld", String.format("%,d", result.strategy().sidesWithheld()));
        field("trades seen", String.format("%,d", result.fillsDelivered()));
        field("own fills", String.format("%,d", result.strategy().fills().count()));
        // Lopsided in a trending market: the losing side keeps getting hit, the other does not.
        field("bought / sold", String.format("%,d / %,d lots",
                result.strategy().fills().boughtLots(), result.strategy().fills().soldLots()));
        field("final position", signed(result.position()));
        field("average entry", priceOrAbsent(result.strategy().position().averagePrice()));
        field("marked against", formatPrice(result.markMid())
                + (result.markedAgainstBook() ? " (book mid)" : " (reference)"));
        field("realised P&L", signed(result.realised()) + " ticks");
        field("unrealised P&L", signed(result.unrealised()) + " ticks");
        field("total P&L", signed(result.total()) + " ticks");
        System.out.println();

        System.out.printf("  shut down cleanly in %dms%n", result.shutdownMillis());
        System.out.printf("  %,d trades written to %s%n", result.tradesWritten(), config.tradeLog());
        printFaults(result);
        System.out.println();
    }

    /** Anything that should have been zero. Silent when the run was clean. */
    private void printFaults(SimulationResult result) {
        if (result.tradesDropped() > 0) {
            System.out.printf("  WARNING: %,d trades were not written to the audit log%n",
                    result.tradesDropped());
        }
        if (result.fillsDropped() > 0) {
            System.out.printf("  WARNING: %,d fills never reached the strategy; its position is "
                    + "not trustworthy%n", result.fillsDropped());
        }
        if (result.requoteFailures() > 0) {
            System.out.printf("  WARNING: %,d requotes failed; see the log for the cause%n",
                    result.requoteFailures());
        }
        if (result.strategyFailures() > 0) {
            System.out.printf("  WARNING: the strategy threw on %,d calls; see the log for the "
                    + "cause%n", result.strategyFailures());
        }
    }

    // --- formatting ---

    /** Centres text within the given width, padding both sides with spaces. */
    private static String centre(String text, int width) {
        int padding = Math.max(0, width - text.length());
        int left = padding / 2;
        return " ".repeat(left) + text + " ".repeat(padding - left);
    }

    /** Formats a tick price as dollars and cents, by integer division rather than floating point. */
    private static String formatPrice(long ticks) {
        return String.format("%d.%02d", ticks / 100, ticks % 100);
    }

    /** Formats a price that may not exist, such as the entry price of a flat position. */
    private static String priceOrAbsent(long ticks) {
        return ticks == OrderBook.NO_PRICE ? ABSENT : formatPrice(ticks);
    }

    /**
     * Signs a quantity or a P&amp;L figure, so a short and a loss read differently from a long.
     * Zero is left unsigned: flat is neither.
     */
    private static String signed(long value) {
        return value == 0 ? "0" : String.format("%+,d", value);
    }

    /**
     * Formats the midpoint of the spread. The mid of two adjacent ticks falls on a half tick, so
     * this works in half-ticks rather than dividing into a double.
     */
    private static String formatMid(long bid, long ask) {
        long total = bid + ask;
        String whole = formatPrice(total / 2);
        return (total % 2 == 0) ? whole : whole + "5";
    }
}
