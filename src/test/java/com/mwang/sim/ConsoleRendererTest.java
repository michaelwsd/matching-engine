package com.mwang.sim;

import com.mwang.book.BookSnapshot;
import com.mwang.book.DepthLevel;
import com.mwang.book.OrderBook;
import com.mwang.model.Side;
import com.mwang.strategy.Position;
import com.mwang.strategy.StrategyStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Captures what the renderer prints and asserts on its shape. Two things here are not cosmetic:
 * drawing over the previous frame only works while every frame is the same height, and the
 * position bar is the one figure in the display derived by arithmetic rather than read off.
 *
 * <p>Pins {@code simulation.ansi} off, so a terminal running the suite cannot turn the escape
 * codes on and change what is captured.
 */
class ConsoleRendererTest {

    private static final String ANSI_PROPERTY = "simulation.ansi";
    private static final long MAX_POSITION = 300;

    private final SimulationConfig config = SimulationConfig.of(Duration.ofSeconds(1), 0);

    private PrintStream realOut;
    private String previousAnsi;
    private ByteArrayOutputStream captured;
    private ConsoleRenderer renderer;

    @BeforeEach
    void captureOutput() {
        previousAnsi = System.setProperty(ANSI_PROPERTY, "false");
        realOut = System.out;
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        renderer = new ConsoleRenderer(config);
    }

    @AfterEach
    void restoreOutput() {
        System.setOut(realOut);
        if (previousAnsi == null) {
            System.clearProperty(ANSI_PROPERTY);
        } else {
            System.setProperty(ANSI_PROPERTY, previousAnsi);
        }
    }

    // --- frame height ---

    /**
     * The frame is redrawn by counting back over its own lines, so a frame that shrinks when the
     * position goes flat or the book goes one-sided would leave the previous one half on screen.
     */
    @Test
    void shouldDrawEveryFrameAtTheSameHeight() {
        int flatOnAFullBook = heightOf(twoSidedBook(), flat());
        int longOnAFullBook = heightOf(twoSidedBook(), holding(Side.BUY, 150));
        int shortOnAFullBook = heightOf(twoSidedBook(), holding(Side.SELL, 150));
        int atTheLimit = heightOf(twoSidedBook(), holding(Side.BUY, MAX_POSITION));
        int onAnEmptyBook = heightOf(emptyBook(), flat());
        int onAOneSidedBook = heightOf(oneSidedBook(), holding(Side.BUY, 150));

        assertThat(List.of(longOnAFullBook, shortOnAFullBook, atTheLimit, onAnEmptyBook,
                        onAOneSidedBook))
                .as("every frame must match the first, or the redraw tears")
                .containsOnly(flatOnAFullBook);
    }

    // --- the position bar ---

    @Test
    void shouldLeaveTheBarEmptyAndCentredWhenFlat() {
        String bar = barFrom(frame(twoSidedBook(), flat()));

        assertThat(bar).contains("|").doesNotContain("#");
    }

    /** Fills from the centre rightwards: a long is inventory to be sold. */
    @Test
    void shouldFillTheBarTowardsLongWhenLong() {
        String bar = barFrom(frame(twoSidedBook(), holding(Side.BUY, MAX_POSITION / 2)));

        assertThat(bar).contains("+");
        assertThat(bar.indexOf('#')).as("fills to the right of centre")
                .isGreaterThan(bar.indexOf('+'));
    }

    @Test
    void shouldFillTheBarTowardsShortWhenShort() {
        String bar = barFrom(frame(twoSidedBook(), holding(Side.SELL, MAX_POSITION / 2)));

        assertThat(bar.indexOf('#')).as("fills to the left of centre")
                .isLessThan(bar.indexOf('+'));
    }

    /**
     * The trap: dividing before multiplying leaves the bar empty at every position below the
     * limit, which is exactly the range that matters.
     */
    @Test
    void shouldFillTheBarInProportionToThePosition() {
        int quarter = countCells(barFrom(frame(twoSidedBook(), holding(Side.BUY, 75))));
        int half = countCells(barFrom(frame(twoSidedBook(), holding(Side.BUY, 150))));
        int full = countCells(barFrom(frame(twoSidedBook(), holding(Side.BUY, MAX_POSITION))));

        assertThat(quarter).isPositive().isLessThan(half);
        assertThat(half).isLessThan(full);
    }

    /** A fill can overshoot the limit by up to one quote, and the bar must not run off its end. */
    @Test
    void shouldNotOverfillTheBarPastTheLimit() {
        int full = countCells(barFrom(frame(twoSidedBook(), holding(Side.BUY, MAX_POSITION))));
        int over = countCells(barFrom(frame(twoSidedBook(), holding(Side.BUY, MAX_POSITION * 3))));

        assertThat(over).isEqualTo(full);
    }

    // --- the figures beside it ---

    @Test
    void shouldLeaveZeroUnsignedAndSignEverythingElse() {
        assertThat(frame(twoSidedBook(), flat()))
                .anySatisfy(line -> assertThat(line).contains("position", "0"))
                .noneSatisfy(line -> assertThat(line).contains("+0"));
        assertThat(frame(twoSidedBook(), holding(Side.SELL, 150)))
                .anySatisfy(line -> assertThat(line).contains("position", "-150"));
    }

    /** A flat position has no entry price and a withheld side has no quote, so neither invents one. */
    @Test
    void shouldShowADashWhereAFigureDoesNotExist() {
        List<String> lines = frame(twoSidedBook(),
                new StrategyStatus(new Position(), OrderBook.NO_PRICE, OrderBook.NO_PRICE,
                        50, 0, 0, StrategyStatus.Fills.none()));

        assertThat(lines).anySatisfy(line -> assertThat(line).contains("quoting bid", "-"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("entry", "-"));
    }

    /** Marking against a book with no mid would throw; the renderer must reach for the dash. */
    @Test
    void shouldNotTryToMarkAPositionAgainstABookWithNoMid() {
        List<String> lines = frame(oneSidedBook(), holding(Side.BUY, 150));

        assertThat(lines).anySatisfy(line -> assertThat(line).contains("unrealised", "-"));
    }

    // --- helpers ---

    private List<String> frame(BookSnapshot book, StrategyStatus strategy) {
        captured.reset();
        renderer.drawFrame(new MarketStatus(book, 100, 20), strategy, 5, "live", false);
        return captured.toString(StandardCharsets.UTF_8).lines().toList();
    }

    private int heightOf(BookSnapshot book, StrategyStatus strategy) {
        return frame(book, strategy).size();
    }

    private static String barFrom(List<String> lines) {
        return lines.stream()
                .filter(line -> line.contains("["))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no position bar in the frame"));
    }

    private static int countCells(String bar) {
        return (int) bar.chars().filter(c -> c == '#').count();
    }

    private static StrategyStatus flat() {
        return StrategyStatus.flat(50);
    }

    /** @param lots always positive; {@code side} decides which way the position points */
    private static StrategyStatus holding(Side side, long lots) {
        Position position = new Position();
        position.onFill(side, (int) lots, 10_000);
        StrategyStatus.Fills fills = side == Side.BUY
                ? new StrategyStatus.Fills(1, lots, 0)
                : new StrategyStatus.Fills(1, 0, lots);
        return new StrategyStatus(position, 9_990, 10_010, 50, 0, 2, fills);
    }

    private static BookSnapshot twoSidedBook() {
        return new BookSnapshot(
                List.of(new DepthLevel(9_990, 100)), List.of(new DepthLevel(10_010, 100)),
                9_990, 10_010);
    }

    private static BookSnapshot oneSidedBook() {
        return new BookSnapshot(
                List.of(new DepthLevel(9_990, 100)), List.of(), 9_990, OrderBook.NO_PRICE);
    }

    private static BookSnapshot emptyBook() {
        return new BookSnapshot(List.of(), List.of(), OrderBook.NO_PRICE, OrderBook.NO_PRICE);
    }
}
