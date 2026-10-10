# Matching Engine

A limit order book and matching engine in Java, built from scratch.

A self-contained trading simulation: a price-time priority matching engine that crosses
orders and produces trades, fed by synthetic market data over a concurrent order gateway,
with an audit trail on disk and a live market data feed over HTTP.

Built in phases, each layering new language and concurrency concepts on top of a working
system - nothing is thrown away between phases.

## Status

**Phase 4 complete** - a running synthetic market with persistence and a published feed.
187 tests, green.

| Phase | Scope | Status |
|---|---|---|
| 0 | Environment & tooling | done |
| 1 | Core order book | done |
| 2 | Matching logic | done |
| 3 | Concurrency | done |
| 3b | Network order entry (TCP/FIX) | skipped - documented, not built |
| 4 | Market data & persistence | done |
| 5 | Market-making strategy | not started |

What runs today, in one sentence per thread:

- A **producer** thread walks a mid price, prices a random order around it, and submits.
- A **matcher** thread drains the queue and is the only thread that ever touches the book.
- A **trade-writer** thread appends every execution to `logs/trades.csv`.
- The **main** thread redraws the book in place, five frames a second.
- A **virtual thread per request** serves the book as JSON on `localhost:8080/book`.

The engine and book contain no locks. They are confined to the matcher thread, and the only
things that cross a thread boundary are a queue of orders in and immutable snapshots out.
[`docs/architecture.html`](docs/architecture.html) draws the whole shape.

## Stack

- **Java 25** (Eclipse Temurin)
- **Maven** via the wrapper - no local Maven install required
- **JUnit 5** + **AssertJ** for testing
- **Jackson** for JSON, **SLF4J** + **Logback** for logging
- `com.sun.net.httpserver` from the JDK for the feed - no web framework

## Building

Requires a JDK 25 installation. Maven itself is fetched by the wrapper.

```bash
./mvnw clean test      # compile and run the test suite
./mvnw clean compile   # compile only
```

## Running the simulation

```bash
./run.sh
```

It runs for 20 seconds, redrawing the book in place as orders arrive:

```
               LIVE ORDER BOOK
  ===========================================
           BIDS                   ASKS
       qty         price |     price      qty
  -----------------------+-------------------
       217         98.54 |     98.61       19
       207         98.53 |     98.67       95
       149         98.52 |     98.73       75
       200         98.50 |     98.94       26
       235         98.49 |     98.95       21
  -----------------------+-------------------
  best bid 98.54    best ask 98.61    spread 0.07
  mid 98.575
  elapsed 20s     orders    939     trades    690
```

In-place redraw needs a real terminal. Piped output and IntelliJ's plain run console ignore
ANSI cursor codes, so there the simulation prints a snapshot every two seconds instead. In
IntelliJ, tick **Emulate terminal in output console** in the run configuration to get the
live view. `-Dsimulation.ansi=true|false` overrides the detection either way.

While it runs, the book is also live over HTTP:

```bash
curl -s localhost:8080/book | python3 -m json.tool
```

```json
{
    "sequence": 224,
    "timestamp": 1791614327093,
    "bestBid": 9934,
    "bestAsk": 9945,
    "bids": [{"price": 9934, "quantity": 34}],
    "asks": [{"price": 9945, "quantity": 61}]
}
```

Abbreviated above: the real response carries five levels per side.

Prices on the wire are in **ticks**, one tick being one cent. `null` means that side is
empty. `sequence` is the number of orders processed when the snapshot was taken, so a
consumer can detect that it missed something. `GET` on anything other than exactly `/book`
returns 404, and any other method returns 405.

Afterwards:

- `logs/trades.csv` holds every execution: `timestamp,buyOrderId,sellOrderId,price,quantity`
- `logs/engine.log` holds the run's lifecycle, and rolls daily

A healthy run logs eight lines whose totals reconcile with each other and with the CSV:

```
INFO [main] OrderFlowDriver - Order flow stopped after 920 orders
INFO [main] OrderGateway    - Gateway stopped: 920 orders processed, 672 trades, 0 rejected
INFO [main] TradeLogger     - Trade log closed, 672 trades written to logs/trades.csv
```

Add `-DLOG_LEVEL=DEBUG` to see every individual trade. See `logback.xml` for why that is not
the default.

## Layout

```
src/main/java/com/mwang/    production code
src/test/java/com/mwang/    tests, mirroring the main package
docs/roadmap.html           the five-phase plan
docs/architecture.html      how the pieces fit together, drawn
docs/phases/                per-phase build documents
logs/                       trade log and engine log, gitignored
run.sh                      compile if needed, then run the simulation
```

Packages, outermost in:

| Package | Holds |
|---|---|
| `model` | `Order`, `Trade`, `Side`, `OrderType` |
| `book` | `OrderBook`, `PriceLevel`, `DepthLevel`, `BookSnapshot` |
| `engine` | `MatchingEngine` and its price-time implementation |
| `exception` | `OrderException` and its two subtypes |
| `gateway` | `OrderGateway` (the thread boundary), `MatchListener` |
| `marketdata` | `MidPriceWalk`, `MarketDataGenerator`, `OrderFlowDriver` |
| `audit` | `TradeLogger` |
| `feed` | `MarketDataSnapshot`, `BookFeedServer` |

## Design notes

Decisions worth recording as they are made.

### Build and types

- **`maven.compiler.release`** rather than `source`/`target`, so the compiler validates
  against the target JDK's API and not just its syntax and bytecode level.
- **Maven Wrapper committed** so the build pins its own Maven version and runs on a
  machine with nothing installed but a JDK.
- **`.mvn/jvm.config` passes `--sun-misc-unsafe-memory-access=allow`** so the build is quiet.
  Maven's own Guice dependency calls a terminally deprecated `sun.misc.Unsafe` method on JDK
  25, and four warnings on every build train you to skim past build output. Nothing in this
  project uses `Unsafe`; the flag only silences the tooling. (The file holds JVM arguments and
  does not support comments, which is why this note lives here.)
- **Prices are scaled `long`s**, in ticks of one cent, never `double`. Binary floating
  point cannot represent decimal fractions exactly, and prices live on a fixed tick grid
  anyway, so integers are both faster and more truthful. Formatting inserts the decimal
  point at the display boundary and nowhere else.
- **`Order` is a class, `Trade` is a record.** A trade is a historical fact and can never
  change; an order is an entity whose remaining quantity shrinks as it fills. Only that
  one field is mutable - everything else is `final`.
- **Exceptions split by cause.** Conditions a client can provoke get domain types under a
  shared `OrderException`, so a gateway can catch one thing and reply with a rejection.
  Violations of the engine's own invariants stay as plain runtime exceptions and are meant
  to crash.

### Book and matching

- **Bids use `Comparator.reverseOrder()`** so both sides answer "best price" with
  `firstKey()`, letting the matching loop be written once rather than mirrored.
- **`bestBid()`/`bestAsk()` return a `long` with a `NO_PRICE` sentinel**, not an
  `OptionalLong`. An empty book is a normal state, and `-1` is unambiguous because every
  real price is positive - `Order` rejects a limit priced at zero or below. The sentinel
  is converted to JSON `null` at the feed boundary, so it never escapes as a price.
- **A `HashMap` index from order id to order** gives O(1) cancel. The invariant that an
  order is in the index if and only if it is in a price level is maintained by `addOrder`
  and `removeOrder` together, and no caller can reach a level to break it.
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
- **No separate `MatchingStrategy` interface.** `MatchingEngine` already provides the swap
  point a second allocation policy would need; a second interface would be ceremony.

### Concurrency

- **The engine is not thread-safe, by design.** Concurrency lives at the boundary: many
  producers put orders on a `BlockingQueue`, and one matcher thread drains it. The engine
  and book are reachable from that thread only, so they need no locks - which also makes
  deadlock structurally impossible and keeps price-time priority meaningful.
- **Consumers stop with a poison pill, producers with an interrupt.** A consumer has
  already accepted work it owes, so a sentinel queued behind the last real item lets the
  backlog drain. A producer owes nothing and nothing feeds it, so no sentinel could ever
  reach it - only an interrupt can wake a thread parked in `put()`.
- **The poison pill is offered with a timeout, never `put()`.** A blocking `put` waits for
  a slot that only the stalled consumer could free, and the `join` that is meant to bound
  shutdown sits after it. Shutdown would hang with no log line saying why. There is a test
  that wedges the matcher and fills the queue to prove it comes back.
- **A bad order cannot kill the matcher.** `OrderException` is caught per order inside the
  loop; anything else is a genuine fault and is allowed to propagate. Without that, one
  malformed order would silently stop all matching while `submit` kept succeeding.
- **Block at the order boundary, drop at the audit boundary.** Both are bounded queues, and
  the decision goes opposite ways because the caller differs. A client submitting an order
  can be made to wait. The matching engine cannot, so `TradeLogger.onMatch` uses `offer`
  and counts the drops it would rather not have. "Block or drop" has no answer in the
  abstract; it depends who is calling.
- **The determinism test constrains its scenario rather than weakening its assertions.**
  Concurrent arrival order varies between runs, so comparing trade sequences would be
  flaky. Instead every order shares a price and size with a distinct account, making the
  outcome ordering-independent and exactly assertable.

### Market data, persistence and the feed

- **Snapshots are built on the matcher thread and published, not read across threads.** The
  `MatchListener` runs on the matcher thread, so it may legally walk the book; it builds an
  immutable `BookSnapshot` and `set`s it on an `AtomicReference`. Readers hold a value that
  cannot change under them. Three independent readers - the renderer, the HTTP handler and
  anything added later - need no locks and no changes to the matcher.
- **The HTTP server takes a `Supplier`, not the book.** It therefore cannot reach
  matcher-owned state even by accident, and it is trivially testable with a fixed value.
- **`MarketDataSnapshot` is separate from `BookSnapshot`.** The book package stays free of
  serialisation concerns, and the wire format can be pinned by a test that asserts the exact
  JSON string - because component order in a record *is* the field order in the output.
- **The trade log flushes when its queue empties.** Under a burst, writes batch in the
  buffer; the moment the burst ends everything is pushed out. `flush()` reaches the
  operating system, not the platter, so this survives a process crash and not a power cut -
  `FileChannel.force()` would be the next step if that mattered.
- **Log level is about audience and rate, not importance.** Rejections at `WARN`, lifecycle
  and run totals at `INFO`, individual trades at `DEBUG`. Logback's appenders are
  synchronous, so a per-trade `INFO` line would put file and console I/O back on the
  matcher thread, undoing the arrangement above. The complete record of every trade is the
  CSV, unconditionally.
- **Three independent seeds** - walk, generator, pacing - so changing how orders are paced
  cannot disturb the price sequence a test is asserting on.
