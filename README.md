# Matching Engine

A limit order book and matching engine in Java, built from scratch.

The goal is a self-contained trading simulation: a price-time priority matching engine
that crosses orders and produces trades, fed by concurrent order intake over a TCP
order-entry protocol and by synthetic market data, with a market-making strategy quoting against it and reporting its own P&L.

Built in phases, each layering new language and concurrency concepts on top of a working
system - nothing is thrown away between phases.

## Status

**Phase 1 complete** - a working limit order book with price-time priority.

| Phase | Scope | Status |
|---|---|---|
| 0 | Environment & tooling | done |
| 1 | Core order book | done |
| 2 | Matching logic | not started |
| 3 | Concurrency | not started |
| 3b | Network order entry (TCP) | not started |
| 4 | Market data & persistence | not started |
| 5 | Market-making strategy | not started |

Orders rest, cancel and display with correct price-time priority. Nothing crosses yet -
matching arrives in Phase 2.

## Stack

- **Java 25** (Eclipse Temurin)
- **Maven** via the wrapper - no local Maven install required
- **JUnit 5** + **AssertJ** for testing

## Building

Requires a JDK 25 installation. Maven itself is fetched by the wrapper.

```bash
./mvnw clean test      # compile and run the test suite
./mvnw clean compile   # compile only
```

## Running the demo

Builds a book from a hardcoded set of orders and prints its depth:

```bash
./mvnw -q compile && java -cp target/classes com.mwang.Main
```

```
                  ORDER BOOK
  ═══════════════════════════════════════════
           BIDS                   ASKS
       qty         price │     price      qty
  ───────────────────────┼───────────────────
        50        100.01 │    100.02      300
       300         99.98 │    100.05      150
       500         99.97 │    100.06      220
                         │    100.10      400
  ───────────────────────┴───────────────────
  best bid 100.01    best ask 100.02    spread 0.01
  mid 100.015
```

## Layout

```
src/main/java/com/mwang/    production code
src/test/java/com/mwang/    tests, mirroring the main package
docs/roadmap.html           the five-phase plan
docs/phases/                per-phase build documents
```

## Design notes

Decisions worth recording as they are made:

- **`maven.compiler.release`** rather than `source`/`target`, so the compiler validates
  against the target JDK's API and not just its syntax and bytecode level.
- **Maven Wrapper committed** so the build pins its own Maven version and runs on a
  machine with nothing installed but a JDK.
- **Prices are scaled `long`s**, in ticks of one cent, never `double`. Binary floating
  point cannot represent decimal fractions exactly, and prices live on a fixed tick grid
  anyway, so integers are both faster and more truthful. Formatting inserts the decimal
  point at the display boundary and nowhere else.
- **`Order` is a class, `Trade` is a record.** A trade is a historical fact and can never
  change; an order is an entity whose remaining quantity shrinks as it fills. Only that
  one field is mutable - everything else is `final`.
- **Bids use `Comparator.reverseOrder()`** so both sides answer "best price" with
  `firstKey()`, letting Phase 2's matching loop be written once rather than mirrored.
- **`bestBid()`/`bestAsk()` return `OptionalLong`**, making the empty book impossible for
  a caller to ignore. An empty book is a normal state, not an error.
- **A `HashMap` index from order id to order** gives O(1) cancel. Both it and the price
  maps are mutated only inside `addOrder`/`cancelOrder`, so they cannot drift apart.
