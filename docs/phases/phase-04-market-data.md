# Phase 4 - Market Data & Persistence

**Estimate:** 5-7 days
**Goal:** feed the engine from something resembling real market data, and give it a way to
report what it's doing.

Phases 1-3 built a correct, concurrent engine that nobody can see into. This phase gives it
input worth processing and output worth reading.

---

## Objectives

A runnable simulation that generates or replays market data, drives orders through the
Phase 3 gateway, logs every trade to a file, and serves live book snapshots as JSON over
HTTP.

---

## Trading concepts

### Ticks

A **tick** is one observation of market state at a point in time - typically a timestamp
plus a price, sometimes with quantity and bid/ask. A day of tick data for a liquid
instrument runs to millions of rows. It's the raw material of every backtest.

Beware the word: "tick" also means the minimum price increment (Phase 1's tick size).
Context distinguishes them, but be precise when you speak.

### Random walk

Real price series look like a **random walk**: the next price is the current price plus a
small random change. Not random *around* a fixed level - random *from wherever it is now*.
That's why prices trend and drift rather than oscillating around a mean.

```
price[t+1] = price[t] + random_step
```

The roadmap asks for a **bounded** random walk, which adds a pull back toward a reference
level so the price doesn't wander to zero or infinity over a long run. That's an artificial
constraint - real prices do go to zero - but it keeps a simulation useful.

You're generating a **mid-price** and quoting orders around it. That mirrors reality: there
is a fair value, and participants post bids below and asks above it.

### Replay and backtesting

The alternative to synthetic data is replaying history: read recorded ticks, feed them
through your system at accelerated speed, and see how a strategy would have behaved. This
is **backtesting**, and it's most of what quantitative trading research actually is.

Its central danger is **lookahead bias** - accidentally letting the simulation use
information that wasn't available at that moment. It creeps in subtly: sorting the whole
file before replaying, letting a strategy see a tick's close before deciding, computing an
average over the full dataset. A backtest with lookahead bias shows spectacular returns and
loses money live. If you build replay, be able to explain how you avoided it.

### Snapshots vs incremental updates

Two ways to publish book state:

- **Snapshot** - the full current state. Simple, self-correcting, larger.
- **Incremental** (deltas) - only what changed. Much smaller, but the consumer must track
  state, handle sequence gaps, and resynchronise after a drop.

Real feeds do both: periodic snapshots for recovery, deltas in between. You're building
snapshots only - but know the distinction and why exchanges bother.

### Audit trails

Every executed trade is written to durable storage, always, in real venues. Not for
debugging - for **compliance**. Regulators require exchanges to reconstruct any trading day
and justify every fill. That's why your `TradeLogger` isn't optional decoration; it's the
component with the strictest correctness requirement in the whole system, because a lost
trade record is a regulatory incident rather than an inconvenience.

---

## Java concepts

### Lambdas and functional interfaces

A **functional interface** has exactly one abstract method, so a lambda can implement it:

```java
Runnable r = () -> System.out.println("hi");
Comparator<String> byLength = (a, b) -> Integer.compare(a.length(), b.length());
```

`@FunctionalInterface` is optional but use it - it makes the compiler enforce the
single-method rule, so nobody accidentally breaks every lambda by adding a method.

Know the core ones in `java.util.function`: `Function<T,R>`, `Supplier<T>`, `Consumer<T>`,
`Predicate<T>`, `BiFunction<T,U,R>`. Prefer them to inventing your own unless a domain name
genuinely adds meaning - `Strategy` in Phase 5 will, `StringTransformer` would not.

### Streams, and when not to use them

```java
List<String> names = people.stream()
        .filter(p -> p.age() >= 18)
        .map(Person::name)
        .sorted()
        .toList();
```

Streams are declarative: you say what, not how. They're excellent for filter/map/reduce
pipelines over collections.

**Where they hurt:**

- Debugging is harder - you can't easily step through or set a breakpoint mid-pipeline
- Stack traces are deep and unhelpful
- They allocate, and in a hot path that matters
- A pipeline with side effects, or one longer than about five operations, is usually less
  readable than the loop it replaced

**Never do this:**

```java
list.stream().forEach(x -> otherList.add(x));    // side-effecting stream
```

That's a loop wearing a costume. Write the loop.

The interview-worthy version of this opinion: streams are for *transforming data*, loops
are for *doing things*. In Phase 5's hot path, prefer loops. In Phase 4's CSV parsing,
streams are exactly right.

### `java.nio.file` (NIO.2)

The modern file API, replacing `java.io.File`:

```java
Path path = Path.of("data", "ticks.csv");
try (Stream<String> lines = Files.lines(path)) {
    long count = lines.filter(l -> !l.isBlank()).count();
}
```

`Files.lines()` is lazy - it streams the file rather than loading it into memory, so it
handles a file larger than your heap. But it holds an open file handle, which is why it
**must** be closed, which is why it's in a try-with-resources above. This is one of the few
streams that is `AutoCloseable`, and forgetting it leaks file descriptors.

### try-with-resources and `AutoCloseable`

```java
try (BufferedWriter writer = Files.newBufferedWriter(path)) {
    writer.write("line");
}   // close() called automatically, even if an exception is thrown
```

Any `AutoCloseable` works. Multiple resources are closed in reverse order. If both the body
and `close()` throw, the body's exception propagates and the close exception is attached as
**suppressed** - retrievable via `getSuppressed()`. That mechanism exists precisely because
the old manual pattern silently lost the original exception.

Never write a manual `finally { stream.close(); }` in new code.

### Buffering and flushing

`BufferedWriter` accumulates in memory and writes in blocks - far fewer syscalls, much
faster. The cost: **data sitting in the buffer isn't on disk yet**. Crash before flush and
it's gone.

For an audit log, that's the whole design tension:

- Flush every record - durable, slow
- Flush periodically - fast, small loss window
- Flush on close only - fastest, loses everything on a crash

And `flush()` only pushes to the OS. `FileChannel.force()` / `FileDescriptor.sync()` push
to the physical device. Real trading systems care about this distinction. Pick a policy and
justify it.

### Thread safety of your logger

Trades arrive from the matcher thread. If you later log from anywhere else, or the HTTP
handler touches shared state, you have concurrency again.

Options, in increasing order of sophistication: `synchronized` on the write method (simple,
serialises writers); confine logging to the matcher thread (consistent with Phase 3's
philosophy); or hand records to a dedicated writer thread through another queue (best
throughput, keeps I/O off the matcher's critical path).

Notice the third is the same pattern as Phase 3. Recognising that is the point.

### SLF4J and Logback: facade vs implementation

**SLF4J** is an API. **Logback** is an implementation. Your code imports only SLF4J; Logback
sits on the classpath and gets picked up at runtime.

Why bother? Because a library that hard-codes a logging implementation forces its choice on
every application that uses it, and applications end up with three logging frameworks
fighting. Coding to the facade means the implementation is swappable. This is the same
"program to an interface" principle as Phase 2, at the dependency level.

```java
private static final Logger log = LoggerFactory.getLogger(OrderBook.class);

log.info("Trade executed: {} @ {}", quantity, price);
```

**Use the `{}` placeholders, never string concatenation.** With placeholders, if `info` is
disabled the arguments are never formatted and nothing is allocated. With
`log.info("Trade: " + quantity)` the string is built regardless - wasted work on every
call, on the hot path.

Levels, and what they mean in practice:

- `ERROR` - something is broken and needs a human
- `WARN` - suspicious but handled; a rejected order belongs here
- `INFO` - lifecycle and run totals; components starting and stopping, and the counts they
  report when they do
- `DEBUG` - detail for diagnosing a problem
- `TRACE` - firehose

The discipline: if `INFO` is too noisy to read, it's wrong. Someone should be able to watch
`INFO` during a run and understand what happened.

**Where individual trades go, and why it is not `INFO`.** A trade is important, so `INFO`
looks obvious. Measure before you decide. This simulation executes around 34 trades a second
against a live view that redraws 5 times a second, so per-trade `INFO` lines scroll away the
thing they are annotating and bury the lifecycle lines that say whether the run was healthy.
Worse, Logback's appenders are synchronous: the thread that calls `logger.info` performs the
write, and trades are logged from the matcher thread. An `INFO` line there puts file and
console I/O on the exchange's hot path - the exact cost `TradeLogger` exists to move onto its
own thread.

So level is about **audience and rate**, not importance. Trades go to `DEBUG`, reachable with
`-DLOG_LEVEL=DEBUG` when diagnosing a specific run. The complete, permanent record of every
trade is the CSV audit trail, which is unconditional. `Logback`'s `AsyncAppender` is the
answer if per-trade logging ever has to be permanent - it buffers and can drop, and both are
decisions worth making deliberately.

Configure `logback.xml` in `src/main/resources` with a console appender and a rolling file
appender. Rolling matters - an unbounded log file fills a disk, and a full disk takes the
whole system down.

### Jackson

The default JSON library in production Java:

```java
ObjectMapper mapper = new ObjectMapper();
String json = mapper.writeValueAsString(myObject);
```

It serialises records natively. Two things worth knowing: `ObjectMapper` is expensive to
create and **thread-safe once configured**, so create one and reuse it - don't construct
one per request. And control your output deliberately (`@JsonProperty`, `@JsonIgnore`)
rather than letting your internal field names become a public API contract you can't change.

### `com.sun.net.httpserver.HttpServer`

A minimal HTTP server in the JDK - no framework, no dependency:

```java
HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
server.createContext("/hello", exchange -> { /* handle */ });
server.start();
```

It is not a production web server, and using it here is a deliberate scope choice: you want
an endpoint to poll, not a web framework. Note that the handler runs on the server's thread
pool, so whatever it touches is shared state - which means your snapshot must be safe to
read from another thread. Think about that before you write the handler.

---

## Prerequisites

- Phase 3 gate closed, determinism test green

---

## Build tasks

### 4.1 - `MarketDataGenerator`

Synthetic bounded random walk producing mid-prices. Submit orders around the mid into the
Phase 3 gateway.

Make the seed configurable. A fixed seed gives reproducible runs, which you need for
testing; a random seed gives variety, which you want for demos. Being able to reproduce a
simulation exactly is worth a lot when something goes wrong.

Think about the distribution of orders you generate - all at the mid produces no book at
all; spread too wide and nothing ever trades.

### 4.2 - Optional: CSV replay

Parse a tick CSV with `Files.lines()` and streams into `List<MarketTick>`, then replay with
a `ScheduledExecutorService` at accelerated speed.

Do this **in addition to** the generator if you want the streams and NIO.2 practice - it's
the better vehicle for those concepts. Handle a malformed row deliberately: skip it, fail
fast, or log and continue? Each is defensible; silently producing wrong data is not.

### 4.3 - `TradeLogger`

Append every executed `Trade` to a file. try-with-resources, `BufferedWriter`, and an
explicit flush policy you can defend.

Choose a format. CSV is greppable and loads into anything; JSON Lines is
self-describing. Either is fine - "whatever I typed first" is not.

### 4.4 - `BookSnapshot` and JSON

A snapshot type carrying best bid, best ask, and depth to N levels. Serialise with Jackson.

Design questions: is it a record? Is it immutable? How is it produced without either
blocking the matcher thread or reading a book that's mid-update? That second one is the
real problem - **think it through before you write the endpoint.**

### 4.5 - HTTP endpoint

Expose the snapshot as JSON on a context path. Confirm with `curl`:

```bash
curl -s localhost:8080/book | python3 -m json.tool
```

Set the content type correctly. Handle the case where the book is empty.

### 4.6 - Migrate to SLF4J

Replace every `System.out.println` in library code. Rejected orders at `WARN`, lifecycle and
run totals at `INFO`, individual trades at `DEBUG` - see the levels section above for why that
last one is not `INFO`. Configure console and rolling file appenders in `logback.xml`.

`Main` and `SimulationRunner` may legitimately print to stdout - that's their user
interface, not logging. Everything else logs.

---

## Design decisions you make yourself

1. **Generator, replay, or both?** What does each give you that the other doesn't?
2. **Flush policy for the trade log** - per record, periodic, or on close? Frame it as
   durability vs throughput and give your reasoning.
3. **How does the snapshot get produced safely** while the matcher thread is mutating the
   book? Copy under the matcher thread? Publish an immutable snapshot after each match?
   Something else? This is the hardest question in the phase.
4. **CSV or JSON Lines** for the trade log?
5. **Malformed input row** - skip, fail, or log and continue?
6. **What's in a `BookSnapshot`?** Enough for Phase 5's strategy, and no more.

---

## Definition of done

- [x] `MarketDataGenerator` producing a bounded random walk, seedable
- [x] Orders driven into the Phase 3 gateway from generated (or replayed) data
- [x] `TradeLogger` appending trades with try-with-resources and a documented flush policy
- [x] `BookSnapshot` serialised to JSON by Jackson
- [x] HTTP endpoint returning live snapshots, verified with `curl`
- [x] No `System.out.println` outside `Main`/`SimulationRunner`
- [x] `logback.xml` with console and rolling file appenders
- [x] Rejections at `WARN`, lifecycle at `INFO`, trades at `DEBUG` - **amended** from "trades
      at `INFO`" after measuring the rate and noticing the appenders are synchronous. The
      reasoning is in the levels section above; the measurement is what changed the decision.
- [x] `./mvnw clean test` green

**Skipped deliberately:** 4.2's CSV replay. The generator covers the same ground for this
project's purposes, and replay's real value - lookahead bias, historical backtests - belongs
to a backtester rather than to a matching engine. Worth building if this ever grows one.

### What a healthy run logs

Eight lines, start to finish, with totals that reconcile against each other and against the
CSV:

```
INFO  [main] TradeLogger     - Trade log open at .../logs/trades.csv
INFO  [main] OrderGateway    - Gateway started, queue capacity 1000
INFO  [main] OrderFlowDriver - Order flow started, up to one order every 40ms
INFO  [main] BookFeedServer  - Book feed listening on http://localhost:8080/book
INFO  [main] BookFeedServer  - Book feed stopped
INFO  [main] OrderFlowDriver - Order flow stopped after 920 orders
INFO  [main] OrderGateway    - Gateway stopped: 920 orders processed, 672 trades, 0 rejected
INFO  [main] TradeLogger     - Trade log closed, 672 trades written to logs/trades.csv
```

Read it as a reconciliation, not as chatter. The driver submitted 920 and the gateway
processed 920, so nothing was dropped at the queue. The gateway counted 672 trades and the
logger wrote 672, so nothing was dropped at the audit trail. A mismatch in either pair is a
bug that no single component would report on its own.

---

## Review checklist

- Every `AutoCloseable` in a try-with-resources - especially `Files.lines()`
- Logging uses `{}` placeholders, never concatenation
- One `ObjectMapper`, reused, not constructed per request
- Snapshot generation cannot see a half-updated book
- Log file rolls; it cannot grow unbounded
- Streams used for transformation, not for side effects
- No credentials, absolute paths, or personal data in logs or committed files
- Trade log path is configurable, not hardcoded to someone's home directory

---

## Interview questions

1. Why does `Files.lines()` need closing when most streams don't?
2. What does try-with-resources do that a `finally` block doesn't? What's a suppressed
   exception?
3. Why `log.info("x {}", y)` instead of `log.info("x " + y)`?
4. What's the difference between SLF4J and Logback, and why does the split exist?
5. Your `BufferedWriter` has trades in its buffer and the process is killed. What's lost,
   and what would you change if that were unacceptable?
6. How do you produce a book snapshot without blocking the matcher or reading torn state?
7. When are streams the wrong choice?
8. What's lookahead bias, and how does it get into a backtest?
9. Snapshots vs incremental updates - why do real feeds publish both?

---

## Common mistakes

- **`Files.lines()` without try-with-resources** - leaks file descriptors until the process
  dies.
- **String concatenation in log calls** on the hot path.
- **A new `ObjectMapper` per request** - it's expensive and it's thread-safe; make it a field.
- **Reading the book from the HTTP thread** while the matcher mutates it. Torn state, and
  it will be intermittent and horrible to debug.
- **Logging per-event at `INFO`** - every order, or every trade - drowning the lifecycle lines
  that tell you whether the run was healthy, and putting synchronous I/O on the matcher thread
  while you do it.
- **No log rotation**, filling the disk during a long run.
- **Hardcoded absolute file paths** that only work on your machine.
- **Committing a large tick CSV** to git. Keep it small, or keep it out.
- **Side-effecting streams** - `forEach` with `add` inside.
