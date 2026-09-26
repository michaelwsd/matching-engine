# Matching Engine

A limit order book and matching engine in Java, built from scratch.

The goal is a self-contained trading simulation: a price-time priority matching engine
that crosses orders and produces trades, fed by concurrent order intake over a TCP
order-entry protocol and by synthetic market data, with a market-making strategy quoting against it and reporting its own P&L.

Built in phases, each layering new language and concurrency concepts on top of a working
system - nothing is thrown away between phases.

## Status

**Phase 2 complete** - a matching engine that crosses orders and produces trades.

| Phase | Scope | Status |
|---|---|---|
| 0 | Environment & tooling | done |
| 1 | Core order book | done |
| 2 | Matching logic | done |
| 3 | Concurrency | not started |
| 3b | Network order entry (TCP) | not started |
| 4 | Market data & persistence | not started |
| 5 | Market-making strategy | not started |

Orders cross, produce trades and rest their remainders, under price-time priority with
self-trade prevention. Order intake is still single-threaded - concurrency arrives in
Phase 3.

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
- **Trades execute at the resting order's price**, not the aggressor's. The resting order
  arrived first and set the terms, so a buyer willing to pay more receives price
  improvement.
- **An unfilled market-order remainder is cancelled.** A market order has no price, so
  there is no price at which it could rest; inventing one would impose terms the client
  never asked for.
- **Self-trade prevention cancels the newest.** The incoming order is rejected outright and
  the resting order keeps its queue position - which is what a market maker with a working
  quote wants. Trades already executed against other accounts stand.
- **`OrderBook` exposes `peekBest(Side)` rather than its price levels.** Handing out levels
  would let a caller remove a filled order without updating the id index, so the engine
  works in terms of orders and cannot break the book's invariants.
- **Exceptions split by cause.** Conditions a client can provoke get domain types under a
  shared `OrderException`, so a gateway can catch one thing and reply with a rejection.
  Violations of the engine's own invariants stay as plain runtime exceptions and are meant
  to crash.
- **No separate `MatchingStrategy` interface.** `MatchingEngine` already provides the swap
  point a second allocation policy would need; a second interface would be ceremony.
