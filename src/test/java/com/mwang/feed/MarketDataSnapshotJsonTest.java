package com.mwang.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.book.BookSnapshot;
import com.mwang.book.DepthLevel;
import com.mwang.book.OrderBook;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire format. Separate from {@link MarketDataSnapshotTest}, which covers the
 * conversion rather than the serialisation.
 *
 * <p>One test compares the whole JSON string. That is deliberately brittle: this JSON is a
 * public contract, so a change to it should break the build and force a decision rather than
 * slip out to consumers. The others assert single properties, because a one-line failure is
 * easier to read than a diff of a 200-character string.
 */
class MarketDataSnapshotJsonTest {

    /** Expensive to build and thread-safe once configured, so one instance is reused. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final long SEQUENCE = 1_423;
    private static final long TIMESTAMP = 1_700_000_000_000L;

    /**
     * The canonical shape, start to finish.
     *
     * <p>Two bid levels and one ask, deliberately: a symmetric fixture would serialise
     * identically if bids and asks were ever swapped.
     */
    @Test
    void shouldSerialiseAPopulatedBookToTheAgreedShape() throws Exception {
        BookSnapshot book = new BookSnapshot(
                List.of(new DepthLevel(9998, 300), new DepthLevel(9997, 500)),
                List.of(new DepthLevel(10002, 150)),
                9998, 10002);

        String json = MAPPER.writeValueAsString(
                MarketDataSnapshot.from(book, SEQUENCE, TIMESTAMP));

        assertThat(json).isEqualTo("{"
                + "\"sequence\":1423,"
                + "\"timestamp\":1700000000000,"
                + "\"bestBid\":9998,"
                + "\"bestAsk\":10002,"
                + "\"bids\":[{\"price\":9998,\"quantity\":300},{\"price\":9997,\"quantity\":500}],"
                + "\"asks\":[{\"price\":10002,\"quantity\":150}]"
                + "}");
    }

    @Test
    void shouldSerialiseAnEmptyBookWithNullsAndEmptyArrays() throws Exception {
        String json = MAPPER.writeValueAsString(
                MarketDataSnapshot.from(emptyBook(), 0, TIMESTAMP));

        assertThat(json).isEqualTo("{"
                + "\"sequence\":0,"
                + "\"timestamp\":1700000000000,"
                + "\"bestBid\":null,"
                + "\"bestAsk\":null,"
                + "\"bids\":[],"
                + "\"asks\":[]"
                + "}");
    }

    /**
     * The sentinel is a private convention inside the book. On the wire it would read as a
     * price of minus one cent, which is why the conversion maps it to null.
     */
    @Test
    void shouldNeverEmitTheEmptySideSentinel() throws Exception {
        String json = MAPPER.writeValueAsString(
                MarketDataSnapshot.from(emptyBook(), 0, TIMESTAMP));

        assertThat(json).doesNotContain("-1");
        assertThat(json).contains("\"bestBid\":null", "\"bestAsk\":null");
    }

    /**
     * An absent side must be an empty array, not an absent key. A consumer should be able to
     * iterate without first checking whether the field exists.
     */
    @Test
    void shouldEmitEmptyArraysRatherThanOmittingTheKeys() throws Exception {
        String json = MAPPER.writeValueAsString(
                MarketDataSnapshot.from(emptyBook(), 0, TIMESTAMP));

        assertThat(json).contains("\"bids\":[]", "\"asks\":[]");
    }

    /**
     * Prices are scaled integers, exactly as they are in memory. Emitting {@code 99.98} would
     * hand every consumer a binary float that cannot represent the value exactly - the reason
     * prices are longs in the first place - and a string would make them parse it back.
     */
    @Test
    void shouldEmitPricesAsIntegersRatherThanDecimalsOrStrings() throws Exception {
        BookSnapshot book = new BookSnapshot(
                List.of(new DepthLevel(9998, 300)), List.of(), 9998, OrderBook.NO_PRICE);

        String json = MAPPER.writeValueAsString(
                MarketDataSnapshot.from(book, SEQUENCE, TIMESTAMP));

        assertThat(json).contains("\"bestBid\":9998", "\"price\":9998");
        assertThat(json).doesNotContain("\"9998\"", "99.98");
    }

    /**
     * A negative test, recording a decision. Both are derivable from the two best prices, and a
     * derived field on the wire is one that can contradict the fields it came from. The mid is
     * worse still: it falls on a half tick and could not be an integer.
     */
    @Test
    void shouldNotSendDerivedValues() throws Exception {
        BookSnapshot book = new BookSnapshot(
                List.of(new DepthLevel(9998, 300)), List.of(new DepthLevel(10001, 150)),
                9998, 10001);

        String json = MAPPER.writeValueAsString(
                MarketDataSnapshot.from(book, SEQUENCE, TIMESTAMP));

        assertThat(json).doesNotContain("spread", "mid");
    }

    /**
     * Field order follows the record's component order, so reordering the components is a
     * breaking change to every consumer that reads positionally.
     */
    @Test
    void shouldEmitFieldsInComponentOrder() throws Exception {
        String json = MAPPER.writeValueAsString(
                MarketDataSnapshot.from(emptyBook(), 0, TIMESTAMP));

        assertThat(json.indexOf("sequence"))
                .isLessThan(json.indexOf("timestamp"));
        assertThat(json.indexOf("timestamp"))
                .isLessThan(json.indexOf("bestBid"));
        assertThat(json.indexOf("bestBid"))
                .isLessThan(json.indexOf("bestAsk"));
        assertThat(json.indexOf("bestAsk"))
                .isLessThan(json.indexOf("bids"));
        assertThat(json.indexOf("bids")).isLessThan(json.indexOf("asks"));
    }

    /** What a consumer actually does with it: the JSON must read back into the same value. */
    @Test
    void shouldRoundTripBackToAnEqualValue() throws Exception {
        BookSnapshot book = new BookSnapshot(
                List.of(new DepthLevel(9998, 300), new DepthLevel(9997, 500)),
                List.of(new DepthLevel(10002, 150)),
                9998, 10002);
        MarketDataSnapshot original = MarketDataSnapshot.from(book, SEQUENCE, TIMESTAMP);

        String json = MAPPER.writeValueAsString(original);
        MarketDataSnapshot parsed = MAPPER.readValue(json, MarketDataSnapshot.class);

        assertThat(parsed).isEqualTo(original);
    }

    private static BookSnapshot emptyBook() {
        return new BookSnapshot(List.of(), List.of(), OrderBook.NO_PRICE, OrderBook.NO_PRICE);
    }
}
