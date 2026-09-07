# Phase 3 - Concurrency

**Estimate:** 6-8 days
**Goal:** add concurrent order intake without letting it corrupt the correctness you just
proved in Phase 2.

This is the phase interviewers will probe hardest, and it's the one where the most
tempting design is the wrong one. Read the whole document before writing anything.

---

## Objectives

Multi-threaded order intake feeding a single-threaded matcher, with a test proving that
concurrent and sequential execution produce identical results.

---

## The central idea: do NOT make the engine thread-safe

The instinctive move is to put `synchronized` on the engine, or swap `TreeMap` for
`ConcurrentSkipListMap`, and call it concurrent. **Resist this.** It is wrong here, and
explaining *why* is the most valuable thing you'll take from this phase.

### The single-writer principle

Real matching engines - LSE, NASDAQ, CME, and the systems firms like IMC trade against -
run their matching core on **one thread**. Deliberately.

Reasons:

**Determinism.** Given the same input sequence, the output must be identical, every time.
That's a correctness property, a regulatory requirement (exchanges must be able to
reconstruct and justify any trading day), and the thing that makes the system testable at
all. Multiple threads mutating the book means interleaving decides who fills first -
you've made fairness a race condition.

**Fairness.** Price-time priority is a *promise* to participants. Under a lock, "time"
becomes "whichever thread happened to win the lock", which isn't the order things arrived.

**Speed.** Counter-intuitively, single-threaded is often *faster*. No lock acquisition, no
cache-line contention between cores, no memory barriers, and the hot data stays in one
core's cache. The LMAX Disruptor work famously demonstrated a single thread handling
millions of orders per second - far more than any realistic order flow.

**Simplicity.** A single-threaded engine has no data races by construction. You cannot
have a concurrency bug in code only one thread ever touches. Given how hard concurrency
bugs are to reproduce, this is worth a great deal.

So: **concurrency belongs at the boundary**, not the core. Many threads submit; a queue
serialises them; one thread matches. The queue is the only concurrent data structure in
the system, and it's a well-tested one you didn't write.

```
  client thread ─┐
  client thread ─┼─> BlockingQueue ─> matcher thread ─> MatchingEngine ─> OrderBook
  client thread ─┘   (thread-safe)    (single writer)   (single-threaded)
```

Everything left of the queue is concurrent. Everything right of it is not, and never needs
to be.

---

## Java concepts

### Threads vs tasks

A `Thread` is an OS-level resource - roughly 1MB of stack, expensive to create, and you
can have maybe a few thousand. Creating one per unit of work doesn't scale, which is why
we pool them.

An `ExecutorService` decouples *what* runs from *where*:

```java
ExecutorService pool = Executors.newFixedThreadPool(4);
pool.submit(() -> doWork());
pool.shutdown();
pool.awaitTermination(5, TimeUnit.SECONDS);
```

You submit tasks; the pool decides which thread runs them. You almost never call
`new Thread()` in modern Java.

### Virtual threads (Java 21+)

A **virtual thread** is scheduled by the JVM, not the OS. Its stack lives on the heap and
grows as needed, so creating a million is reasonable. When a virtual thread blocks on I/O
or a queue, the JVM parks it and runs another on the same carrier (platform) thread.

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int i = 0; i < 10_000; i++) {
        executor.submit(() -> doSomethingBlocking());
    }
}   // close() blocks until all tasks finish
```

The win is for **blocking, I/O-bound** work - simulated clients waiting on a queue is
exactly the shape. You get the simple blocking programming model with the scalability that
previously required async/reactive code.

They are *not* faster for CPU-bound work. You still only have as many cores as you have,
and virtual threads add scheduling overhead. Knowing when they don't help matters as much
as knowing when they do.

Note `ExecutorService` implements `AutoCloseable` since Java 19, so try-with-resources
handles shutdown - that's what the `var executor` above relies on.

**Your matcher stays a platform thread.** It's a long-running, CPU-bound loop that should
own a core. Making it virtual gains nothing and adds scheduling indirection. Be able to
explain that split.

### `BlockingQueue` and producer-consumer

```java
BlockingQueue<String> queue = new LinkedBlockingQueue<>();

// producer
queue.put(item);          // blocks if the queue is full

// consumer
String item = queue.take();   // blocks until something is available
```

The blocking is the point: no polling, no sleeping, no busy-waiting. The consumer sleeps
until work arrives and the OS wakes it.

Implementations differ in ways that matter:

| | Bounded? | Backing store | Notes |
|---|---|---|---|
| `LinkedBlockingQueue` | optionally | linked nodes | two locks (head/tail), good throughput |
| `ArrayBlockingQueue` | always | array | single lock, no allocation per element |
| `ConcurrentLinkedQueue` | no | linked nodes | **not blocking** - `poll()` returns null |
| `SynchronousQueue` | zero capacity | none | a handoff; producer waits for a consumer |

`ConcurrentLinkedQueue` is in that list because the roadmap's stretch goal asks you to
benchmark it - and because using it forces you to write your own waiting strategy, which
is a good way to appreciate what `BlockingQueue` does for you.

### Bounded queues and backpressure

An **unbounded** queue never blocks the producer. That sounds good until producers outrun
the consumer and the queue grows until you get an `OutOfMemoryError`. Unbounded queues
don't prevent overload; they convert it from a visible slowdown into a delayed crash.

A **bounded** queue applies **backpressure**: when full, `put()` blocks the producer,
propagating "slow down" upstream. That's usually what you want - but now you must decide
what happens when a real client can't wait: block, reject with an error, or drop? Every
real trading gateway makes this choice explicitly.

### Memory visibility

This is the part people skip and then get bitten by.

```java
boolean running = true;    // NOT volatile

// thread A
while (running) { doWork(); }

// thread B
running = false;           // thread A may never see this. Ever.
```

Without synchronisation, the JVM and CPU are free to reorder and cache. Thread A may hoist
`running` into a register and loop forever. This is not theoretical.

`volatile` guarantees reads and writes go to main memory and establishes a
**happens-before** relationship: everything thread B did before writing `running` is
visible to thread A after it reads the new value. It does **not** make compound operations
atomic - `count++` is broken under concurrency whether or not it's volatile, because it's
three operations. For that you need `AtomicInteger` or a lock.

The good news: **`BlockingQueue` already establishes happens-before.** Everything a
producer did before `put()` is visible to the consumer after `take()`. So if orders only
cross the thread boundary through the queue, you get safe publication for free. That's
another argument for the architecture.

Where you *will* need `volatile` is the matcher's shutdown flag.

### Shutdown

A long-running consumer loop needs a way to stop cleanly, without losing queued work.

- **Poison pill** - a sentinel object meaning "no more work". The consumer sees it and
  exits. Works naturally with `take()`, and guarantees everything queued before it is
  processed first.
- **Interruption** - `Thread.interrupt()` causes `take()` to throw `InterruptedException`.
  Correct, but you must handle the exception properly: either restore the interrupt flag
  (`Thread.currentThread().interrupt()`) or exit. **Never swallow `InterruptedException`
  with an empty catch block** - it destroys the ability to cancel anything, and reviewers
  look for it specifically.
- **A `volatile` flag** - simple, but the consumer is blocked in `take()` and won't check
  the flag until something arrives. Combine with a poison pill or interruption.

### `CountDownLatch`

A one-shot gate: initialise with a count, `countDown()` decrements, `await()` blocks until
it hits zero.

```java
CountDownLatch done = new CountDownLatch(3);
// ... three tasks each call done.countDown() when finished
done.await();     // returns once all three are done
```

You need this to know when a simulated batch has fully drained before asserting on
results. **Always use the timeout form (`await(5, TimeUnit.SECONDS)`) in tests** and fail
if it returns false - otherwise a bug becomes a test that hangs forever, and a hanging CI
build is much worse than a failing one.

---

## The determinism test: read this carefully

The roadmap calls this "the most valuable test in the project". It is, and it's also the
easiest one to write in a way that proves nothing.

### The trap

You cannot assert that concurrent and sequential runs produce the *same sequence of
trades*. Think about why: if three threads submit orders concurrently, the order they land
in the queue genuinely varies between runs. Different arrival order means different
price-time priority means legitimately different trades. That's not a bug - it's what
concurrent submission *means*.

So a test asserting identical trade lists will fail intermittently. And a flaky test is
worse than no test: people re-run it until it's green and stop believing it.

### What can you actually assert?

Two honest approaches. Understand both, then choose.

**Approach A - assert on invariants that must hold regardless of ordering.** Total quantity
filled, number of trades, total notional value, and conservation: every unit of quantity
submitted is either filled or still resting, never lost, never duplicated. These hold no
matter how submissions interleave. This is what the roadmap means by "identical trade
counts and fill totals".

Design your scenario so these are ordering-independent. If your input set is such that
different arrival orders produce different total fill quantities, your test is measuring
the scenario, not the engine.

**Approach B - control the ordering, then assert exact equality.** Give each order a
sequence number assigned atomically as it enters the gateway. The matcher then processes
in sequence order, and you can compare the exact trade list against a sequential run of
the same sequence. This is closer to what real exchanges do - a **sequencer** stamping a
total order onto incoming messages is standard architecture.

Approach A is simpler and proves the core claim: the concurrent wrapper hasn't corrupted
the engine. Approach B proves more, and is the better interview answer.

**Whichever you pick, be able to explain why the naive version doesn't work.** That
explanation is the whole point of the test.

### Making it a real test

- Run enough orders that interleaving actually happens - hundreds at minimum, not five
- Use a latch with a timeout; never `Thread.sleep()` to "wait for things to finish"
- Run it repeatedly (`@RepeatedTest`) - concurrency bugs are probabilistic
- **Verify the test can fail.** Temporarily break something and confirm it goes red. A
  concurrency test that has never failed may simply be unable to.

---

## Prerequisites

- Phase 2 gate closed, with a fully green scenario-table suite. You will use that suite as
  a regression baseline; if it isn't trustworthy, this phase has no foundation.

---

## Build tasks

### 3.1 - Keep the engine single-threaded

No changes to the engine. Add a Javadoc note on the class stating explicitly that it is
**not thread-safe by design**, and that all access is confined to the matcher thread.

Documenting thread-confinement is professional practice - it tells the next developer this
is a decision, not an oversight.

### 3.2 - `OrderGateway`

The concurrent boundary. Producers submit; internally a `BlockingQueue<Order>`; one
matcher thread drains it and calls the engine.

Design questions: does `submit` block or reject when full? Does it return anything -
an acknowledgement, a future, a sequence number? Who owns the matcher thread's lifecycle?
Does the gateway own the engine, or is it injected?

### 3.3 - The matcher loop

One platform thread, looping on `take()`, feeding the engine, collecting trades.

Where do the resulting trades *go*? Phase 5's strategy needs to know about its own fills.
Design the outlet now - a listener callback, an output queue, a consumer - but keep it
minimal. What you must avoid is running arbitrary listener code *on the matcher thread*,
where a slow or throwing listener stalls the entire exchange. Think about that trade-off.

### 3.4 - Simulated clients on virtual threads

`Executors.newVirtualThreadPerTaskExecutor()`, each task submitting orders to the gateway.

Vary the timing so interleaving is real. Note that `Random` is not thread-safe in the sense
you want here - look up `ThreadLocalRandom` and understand why it exists.

### 3.5 - Graceful shutdown

Poison pill or interruption, plus `close()`/`shutdown()` on the executor. Requirements:

- Everything submitted before shutdown is processed - no silent loss
- The matcher thread actually terminates (assert it, don't assume it)
- No `InterruptedException` swallowed anywhere

### 3.6 - The determinism test

Per the section above. This is the deliverable of the phase.

### 3.7 - Stretch: JMH

Benchmark submission latency across `LinkedBlockingQueue`, `ArrayBlockingQueue`, and a
`ConcurrentLinkedQueue` with your own backpressure.

Only attempt this if the rest is solid. If you do: understand *why* JMH exists rather than
timing with `System.nanoTime()` in a loop - JIT warmup, dead-code elimination, constant
folding, and on-stack replacement all make naive microbenchmarks confidently wrong. That
understanding is worth more than the numbers.

---

## Design decisions you make yourself

1. **Bounded or unbounded queue?** If bounded, what capacity, and what happens on full -
   block, reject, or drop? Justify against a real trading gateway's requirements.
2. **Poison pill or interruption** for shutdown? What does each guarantee about in-flight work?
3. **Determinism test: Approach A or B?** Whichever you choose, explain why the naive
   "assert identical trade lists" version is broken.
4. **How do trades leave the matcher thread** without letting listener code block matching?
5. **Does the gateway own the matcher thread**, or does the caller supply one? What does
   that mean for testability?

---

## Definition of done

- [ ] Engine unchanged and documented as thread-confined
- [ ] `OrderGateway` with a `BlockingQueue` and one dedicated matcher thread
- [ ] Simulated producers running on virtual threads
- [ ] Graceful shutdown that loses no submitted orders and terminates the matcher
- [ ] No swallowed `InterruptedException` anywhere
- [ ] Determinism test passing, repeatedly, under genuine concurrent load
- [ ] You have verified the determinism test **can** fail
- [ ] All Phase 2 tests still green
- [ ] `./mvnw clean test` green

---

## Review checklist

- Is there any `synchronized` on the engine or book? (There should not be.)
- Is every cross-thread mutable field `volatile` or otherwise safely published?
- Is `InterruptedException` handled - flag restored or thread exited - never swallowed?
- Do all latch and queue waits in tests have timeouts?
- Any `Thread.sleep()` used to sequence a test? (Replace it.)
- Is the queue's boundedness decision documented?
- Does the matcher thread have a meaningful name? (`Thread.setName` - it's what you'll see
  in a stack trace at 3am, and "Thread-4" tells you nothing.)
- Any shared mutable state reachable from two threads that isn't the queue?

---

## Interview questions

1. Why is the matching engine single-threaded? Give me three distinct reasons.
2. Why not just put `synchronized` on `submit`? What specifically goes wrong?
3. What's a virtual thread, how does it differ from a platform thread, and when is it the
   wrong choice?
4. What's `volatile` for? What does it *not* give you?
5. What is happens-before, and how does a `BlockingQueue` provide it?
6. Bounded vs unbounded queue - what's the failure mode of each?
7. Why can't your determinism test assert identical trade sequences? What does it assert
   instead, and why is that sufficient?
8. What does swallowing `InterruptedException` break?
9. Your gateway accepts orders faster than the matcher drains them. Walk me through what
   happens, end to end.

---

## Common mistakes

- **Making the engine thread-safe.** The headline mistake. Undoes the phase's entire point.
- **Asserting identical trade sequences** across concurrent and sequential runs, producing
  a flaky test that gets ignored.
- **`Thread.sleep()` in tests** to wait for work to finish. Timing-dependent, slow, and
  flaky on a loaded CI machine. Use latches.
- **Swallowing `InterruptedException`** to make the compiler stop complaining.
- **Unbounded queue with no thought**, then surprise `OutOfMemoryError` under load.
- **A latch `await()` with no timeout**, turning a bug into a hung build.
- **Unnamed threads**, making stack traces unreadable.
- **Testing with five orders** and concluding it's concurrent. It never interleaved.
- **Never verifying the test can fail**, and shipping a test that's structurally incapable
  of catching the bug it was written for.
