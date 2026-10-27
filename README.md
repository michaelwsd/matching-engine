# Matching Engine

A limit order book and matching engine in Java, built from scratch.

A self-contained trading simulation: a price-time priority engine that crosses orders into
trades, fed by synthetic market data through a concurrent gateway, with an audit trail on disk
and a live market data feed over HTTP. Built in phases, each adding language and concurrency
concepts on top of a working system - nothing is thrown away between phases.

## Architecture

Five threads today, six once Phase 5 is wired. One queue in, immutable values out.

```
  PRODUCER THREAD
  ┌─────────────────────────────────────────────────────────────────────────────────┐
  │ MidPriceWalk -> MarketDataGenerator                                             │
  │ OrderFlowDriver, about 50 orders/sec                                            │
  └────────────────────────────────────────┬────────────────────────────────────────┘
                                           │ submit()  blocks when full
  ╔════════════════════════════════════════▼════════════════════════════════════════╗
  ║ BlockingQueue<Command>, capacity 1000                                           ║
  ║ Phase 5 quotes rejoin here, as ordinary submits                                 ║
  ╚════════════════════════════════════════╤════════════════════════════════════════╝
  MATCHER THREAD                           │ take()
  ┌────────────────────────────────────────▼────────────────────────────────────────┐
  │ OrderGateway.matchLoop                                                          │
  │   PriceTimeMatchingEngine                                                       │
  │     OrderBook          no locks, confined to this thread                        │
  │   MatchListener.onMatch(order, trades)                                          │
  └───────┬──────────────────────────┬───────────────────────────────┬──────────────┘
          │                          │                               │
  ╔═══════▼════════╗ ╔═══════════════▼═══════════════╗ ╔═════════════▼══════════════╗
  ║ Queue<Trade>   ║ ║ AtomicReference<Status>       ║ ║ Queue<Command>             ║
  ║ offer, drops   ║ ║ set(immutable BookSnapshot)   ║ ║ StrategyRunner, drops      ║
  ╚═══════╤════════╝ ╚══════╤════════════════╤═══════╝ ╚═════════════╤══════════════╝
          │ take() / get()  │                │                       │
  ┌───────▼────────┐ ┌──────▼───────┐ ┌──────▼───────┐ ┌─────────────▼──────────────┐
  │ TRADE-WRITER   │ │ MAIN THREAD  │ │ HTTP, virtual│ │ STRATEGY THREAD  not wired │
  │ TradeLogger    │ │ live redraw  │ │ thread/req   │ │ Strategy.onFill -> Position│
  │ logs/trades.csv│ │ 5 frames/sec │ │ :8080/book   │ │ quotes -> the gateway queue│
  └────────────────┘ └──────────────┘ └──────────────┘ └────────────────────────────┘
```

Four boundaries, each a deliberate choice:

| Boundary | Mechanism | On overload |
|---|---|---|
| Order handoff | `BlockingQueue`, capacity 1000 | **block** - the producer can wait |
| Audit | `BlockingQueue` + `offer` | **drop** and count - the matcher cannot wait |
| Publication | `AtomicReference<Status>` | overwrite - readers only want the latest |
| Strategy fills | `BlockingQueue` + `offer` | **drop** and count - a drop means a wrong position |

The engine and book hold no locks. They are confined to the matcher thread, so the only
things crossing a boundary are orders in and immutable values out.
[`docs/architecture.html`](docs/architecture.html) draws this in full detail.

### The strategy column

The strategy runs on its own thread rather than quoting straight from `onMatch` because the matcher would
otherwise be adding two quotes for every order it removes, filling the queue it alone drains
until `put()` blocks it permanently. A chain, never a cycle. A test reproduces that shape and
times out, so the guard cannot be removed unnoticed.

Its two inputs are deliberately different mechanisms: **fills** arrive on a queue, because
missing one leaves the position permanently wrong, while **market data** is pulled from the
published snapshot, because only the newest value matters. Events must all be received; state
only needs the latest.

## Stack

- **Java 25** (Eclipse Temurin), **Maven** via the committed wrapper
- **JUnit 5** + **AssertJ** for tests
- **Jackson** for JSON, **SLF4J** + **Logback** for logging
- `com.sun.net.httpserver` from the JDK for the feed - no web framework

## Build and run

Needs a JDK 25. Maven is fetched by the wrapper.

```bash
./mvnw clean test      # compile and run the suite
./run.sh               # run the 20-second simulation
```

The book redraws in place as orders arrive:

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
ANSI cursor codes, so there it prints a snapshot every two seconds instead. In IntelliJ, tick
**Emulate terminal in output console**. `-Dsimulation.ansi=true|false` overrides the detection.

### The HTTP feed

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

Abbreviated - the real response carries five levels per side. Prices are **ticks** of one
cent. `null` means that side is empty. `sequence` is the order count when the snapshot was
taken, so a consumer can tell it missed something. Anything other than `GET /book` returns
404; any other method, 405.

### Afterwards

- `logs/trades.csv` - every execution: `timestamp,buyOrderId,sellOrderId,price,quantity`
- `logs/engine.log` - lifecycle, rolls daily

A healthy run's totals reconcile with each other and with the CSV:

```
INFO [main] OrderFlowDriver - Order flow stopped after 920 orders
INFO [main] OrderGateway    - Gateway stopped: 920 orders processed, 672 trades, 0 rejected, 0 cancelled, 0 cancels missed
INFO [main] TradeLogger     - Trade log closed, 672 trades written to logs/trades.csv
```

`-DLOG_LEVEL=DEBUG` logs every individual trade. See `logback.xml` for why that is not the
default.

## Layout

```
src/main/java/com/mwang/    production code
src/test/java/com/mwang/    tests, mirroring the main package
docs/roadmap.html           the five-phase plan
docs/architecture.html      how the pieces fit together, drawn
docs/phases/                per-phase build documents
logs/                       trade log and engine log, gitignored
run.sh                      compile if needed, then run
```

| Package | Holds |
|---|---|
| `model` | `Order`, `Trade`, `Side`, `OrderType`, `OrderIdAllocator` |
| `book` | `OrderBook`, `PriceLevel`, `DepthLevel`, `BookSnapshot` |
| `engine` | `MatchingEngine` and its price-time implementation |
| `exception` | `OrderException` and its two subtypes |
| `gateway` | `OrderGateway` (the thread boundary), `MatchListener` |
| `marketdata` | `MidPriceWalk`, `MarketDataGenerator`, `OrderFlowDriver` |
| `audit` | `TradeLogger` |
| `feed` | `MarketDataSnapshot`, `BookFeedServer` |
| `strategy` | `Strategy`, `StrategyRunner` - the Phase 5 extension point |

## Design notes

Decisions worth recording, with the reason rather than just the rule.

### Build and types

- **`maven.compiler.release`**, not `source`/`target`, so the compiler validates against the
  target JDK's API and not merely its bytecode level.
- **The Maven Wrapper is committed**, so the build pins its own Maven version and runs on a
  machine with nothing but a JDK.
- **`.mvn/jvm.config` passes `--sun-misc-unsafe-memory-access=allow`.** Maven's own Guice
  dependency calls a terminally deprecated `Unsafe` method on JDK 25, and four warnings per
  build train you to skim build output. Nothing here uses `Unsafe`. (That file takes no
  comments, hence this note.)
- **Prices are `long` ticks of one cent, never `double`.** Binary floats cannot represent
  decimal fractions exactly, and prices sit on a fixed tick grid anyway. The decimal point is
  inserted at the display boundary and nowhere else.
- **`Order` is a class, `Trade` is a record.** A trade is a historical fact; an order is an
  entity whose remaining quantity shrinks as it fills. That one field is the only mutable
  state in the project.
- **Exceptions split by cause.** What a client can provoke gets a domain type under
  `OrderException`, so the gateway catches one type and replies with a rejection. Violations
  of the engine's own invariants stay plain runtime exceptions and are meant to crash.

### Book and matching

- **Bids sort with `Comparator.reverseOrder()`**, so both sides answer "best price" with
  `firstKey()` and the matching loop is written once rather than mirrored.
- **`bestBid()`/`bestAsk()` return a `long` with a `NO_PRICE` sentinel**, not an
  `OptionalLong`. An empty book is normal, and `-1` is unambiguous because every real price is
  positive. The sentinel becomes JSON `null` at the feed boundary, so it never escapes as a
  price.
- **A `HashMap` from order id to order** makes cancel O(1). The invariant - an order is in the
  index if and only if it is in a price level - is maintained by `addOrder` and `removeOrder`
  together, and no caller can reach a level to break it.
- **Trades execute at the resting order's price.** It arrived first and set the terms, so a
  buyer willing to pay more receives price improvement.
- **An unfilled market remainder is cancelled.** A market order carries no price, so there is
  none at which it could rest; inventing one would impose terms the client never asked for.
- **Self-trade prevention cancels the newest.** The incoming order is rejected outright and
  the resting order keeps its queue position, which is what a market maker with a working
  quote wants. Trades already done against other accounts stand.
- **`OrderBook` exposes `peekBest(Side)`, not its price levels.** A caller holding a level
  could remove a filled order without updating the id index.
- **No separate `MatchingStrategy` interface.** `MatchingEngine` is already the swap point a
  second allocation policy would need.

### Concurrency

- **The engine is not thread-safe, by design.** Concurrency lives at the boundary: producers
  queue orders, one matcher drains. The engine and book are reachable from that thread only,
  so they need no locks, deadlock is structurally impossible, and queue position *is* arrival
  order - which is what makes time priority a deterministic, auditable fact.
- **The gateway carries commands, not orders.** A `sealed interface Command permits Submit,
  Cancel, Shutdown` over one `BlockingQueue`. Cancel has to travel the same queue as submit,
  or it could only reach orders still queued rather than the resting ones that actually need
  pulling - and a caller mutating a resting order directly would break the confinement every
  other decision here depends on. Being sealed makes the matcher's `switch` exhaustive, so a
  fifth command is a compile error until it is handled. `Shutdown` as a real case also
  replaces a sentinel `Order` with magic field values. **All three queues** - order handoff,
  audit, strategy - use this same shape, each with its own package-private `Command`, so no
  fake domain object stands in for a control signal anywhere.
- **One shared `OrderIdAllocator` for everything that submits to a book.** A book rejects a
  duplicate id while the first order still rests, so separate counters per participant make a
  collision unlikely rather than impossible - and the failure arrives mid-run as
  `InvalidOrderException`. This is also the one place where an `AtomicLong`'s atomicity is
  load-bearing rather than defensive: the producer and a strategy really do draw concurrently,
  where a `volatile long` would hand both the same id.
- **The strategy's account id is reserved, not chosen.** `STRATEGY_ACCOUNT = ACCOUNT_COUNT + 1`,
  derived so the two cannot drift. Self-trade prevention rejects an incoming order that meets a
  resting order from the same account and does so *silently* - no exception, no rejection count
  - so a strategy sharing an account with the synthetic flow would appear to quote and have no
  quote.
- **Strategy code never runs on the matcher thread.** `StrategyRunner` is the `MatchListener`;
  its `onMatch` only `offer`s fills onto its own queue and returns. The strategy itself runs on
  a separate thread and submits from there. Quoting from `onMatch` would have the matcher adding
  two quotes for every order it removes, filling the queue it alone drains until `put()` blocks
  it permanently - a cycle rather than a chain. There is a test that reproduces that shape and
  times out, to prove the guard works.
- **Shut down producer, then strategy, then gateway.** The runner produces orders as well as
  consuming fills, and the producer role decides: stopping it while the gateway still runs lets
  its backlog drain and its last quotes land, where the reverse order throws
  `IllegalStateException` once per pending fill.
- **A cancel for an order that already filled is normal flow.** Counted in `cancelsMissed`
  and logged at `DEBUG`, not `WARN`. A quoting strategy loses that race constantly, and
  warning on it would bury the rejections that mean something.
- **Consumers stop with a queued `Shutdown`, producers with an interrupt.** A consumer owes
  work it has already accepted, so a sentinel queued behind the last real item drains the
  backlog. A producer owes nothing and nothing feeds it, so no sentinel could reach it - only an
  interrupt wakes a thread parked in `put()`.
- **The sentinel is offered with a timeout, never `put()`.** A blocking `put` waits for a
  slot only the stalled consumer could free, and the `join` meant to bound shutdown sits
  after it. Shutdown would hang with no log line saying why. A test wedges the matcher and
  fills the queue to prove it comes back.
- **A bad order cannot kill the matcher.** `OrderException` is caught per order inside the
  loop; anything else is a genuine fault and propagates. Without that, one malformed order
  would silently stop all matching while `submit` kept succeeding.
- **Block at the order boundary, drop at the audit boundary.** Both queues are bounded and the
  decision goes opposite ways because the caller differs: a client submitting an order can be
  made to wait, the matching engine cannot. "Block or drop" has no answer in the abstract.
- **The determinism test constrains its scenario rather than weakening its assertions.**
  Arrival order varies between runs, so comparing trade sequences would be flaky. Instead
  every order shares a price and size with a distinct account, making the outcome
  ordering-independent and exactly assertable.

### Market data, persistence and the feed

- **Snapshots are built on the matcher thread and published, never read across threads.** The
  `MatchListener` runs on the matcher thread, so it may legally walk the book; it builds an
  immutable `BookSnapshot` and `set`s it on an `AtomicReference`. Three independent readers -
  renderer, HTTP handler, and anything added later - need no locks and no change to the
  matcher.
- **The HTTP server takes a `Supplier`, not the book**, so it cannot reach matcher-owned state
  even by accident, and it tests against a fixed value.
- **`MarketDataSnapshot` is separate from `BookSnapshot`**, keeping serialisation out of the
  `book` package and letting a test pin the exact JSON string - because component order in a
  record *is* field order in the output.
- **The trade log flushes when its queue empties.** Writes batch under a burst and are pushed
  out the moment it ends. `flush()` reaches the OS, not the platter, so this survives a
  process crash but not a power cut; `FileChannel.force()` is the next step if that matters.
- **Log level is about audience and rate, not importance.** Rejections `WARN`, lifecycle and
  totals `INFO`, individual trades `DEBUG`. Logback's appenders are synchronous, so a
  per-trade `INFO` would put file and console I/O back on the matcher thread. The complete
  record of every trade is the CSV, unconditionally.
- **Three independent seeds** - walk, generator, pacing - so changing how orders are paced
  cannot disturb a price sequence a test asserts on.

### Strategy and market making

Two strategies ship. `SimpleMarketMaker` quotes a fixed half-spread either side of the book
mid. `AdaptiveMarketMaker` sizes its half-spread from how much the mid has been moving.
`StrategyFactory` chooses; `Main` runs the adaptive one.

The fixed-spread version is the more instructive of the two, because measuring *why* it loses
money found a defect in the market rather than in the strategy.

- **A market maker earns the spread and pays for inventory.** Both are visible in the summary:
  `realised P&L` is spread captured on round trips, `unrealised P&L` is what the open position
  is worth. A maker can be good at the first and still lose on the second.
- **Adverse selection is not a tendency here, it is the only fill mechanism.** Measured over
  five price paths, `SimpleMarketMaker` ended long every time the market fell and short every
  time it rose. Its quotes rested *behind* the touch 89% of the time (measured: 137 of 154
  orders), and a quote behind the touch can only be filled by the price travelling through it
  - which is to say, by the market moving against it. `bought / sold` in the summary shows it
  directly: 281 lots bought against 89 sold, in a falling market.
- **The flow does demand liquidity, which is what makes market making possible at all.**
  `MarketDataGenerator` prices each order at `mid + U` with `U` uniform over `±quoteOffset`
  and **independent of the side**, so roughly half of all orders cross the spread for no
  informational reason. Without that, no liquidity provider could ever profit; a book of
  purely passive orders only trades when someone is picked off.
- **The flow's aggregate spread is far tighter than its own `quoteOffset`.** Orders rest from
  many different mids, so an offset of ±20 produces a book spread of 4-8 ticks, not 40. Any
  half-spread above about 4 is therefore *outside* the market. This is the trap the
  `halfSpread` comment in `SimulationConfig` used to get wrong.
- **The binding constraint is fair-value uncertainty, not spread width.** Compared against the
  walk's true mid, the book mid is unbiased but carries a standard deviation of ~14 ticks.
  Removing the maker's own quotes from the estimate, weighting by depth, smoothing with an
  EWMA, and estimating from trade prices instead were all measured: none beat ~15 ticks. Each
  observation of the mid carries ±11.5 ticks of `U` noise, and the mid moves faster than there
  is time to average it away. **You cannot quote a 7-tick spread around a 15-tick estimate.**
- **So the original market was unplayable.** With `tickStep` at 5, the mid moved about half the
  book's own spread *per order*. Over ten price paths the best strategy netted +302 ticks,
  which is zero. The default is now 1 tick a step; raise it to watch the pathology return.
  Real markets move a small fraction of a spread per trade, which is the whole reason a
  resting quote is worth anything.
- **`AdaptiveMarketMaker` sizes the spread from measured volatility**, which is what makes one
  strategy work in both a quiet market and a fast one. It quotes at its widest until it has
  measured a move - an unfilled quote costs nothing, while a quote narrower than an unknown
  volatility is picked off - and its inventory skew scales with the current spread, so a full
  position always shifts the quotes by the same proportion of their width.
- **Its volatility proxy has a known limit.** It measures the movement of the *book mid*, which
  is a smoothed series, so it understates the true uncertainty when the book is very stale. In
  the pathological `tickStep 5` regime it is therefore worse than a fixed spread. Measured over
  ten paths at `tickStep 1`: adaptive wins 9 and totals +11,381 ticks, against +4,746 for the
  fixed spread - 2.4x, by trading four times the volume at a thinner but still positive edge.
- **Both strategies estimate fair value from a book that contains their own quotes.** It does
  not matter at these spreads, because the quotes sit behind the touch and symmetric quotes
  leave the mid unchanged anyway. It would matter for a strategy quoting at the touch, which is
  the natural next step.
