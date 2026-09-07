# Phase 5 - Market-Making Strategy

**Estimate:** 5-7 days
**Goal:** tie every earlier phase together into something that resembles what IMC actually
does, with a strategy quoting against your own book.

Phases 1-4 built an exchange. This phase builds a *participant* - and it's the part of the
project that will most interest a trading firm, because it's the part that shows you
understand what they do rather than just how to write Java.

---

## Objectives

A self-contained simulation that runs for a fixed duration and prints a coherent P&L
summary for a market maker quoting against your own engine, with a risk limit that
demonstrably changes its behaviour.

---

## Trading concepts

This is the most domain-heavy phase. Read this whole section before writing code - the Java
here is easier than in Phase 3, but the ideas are the ones you'll be asked about.

### What a market maker does

A market maker continuously quotes **both sides**: a bid below fair value and an ask above
it. It doesn't predict direction. It provides liquidity and earns the spread.

```
       fair value (mid) = 10000
       
  bid 9998  <--- half-spread 2 --->  ask 10002
```

Quote both, get filled on both, and you've bought at 9998 and sold at 10002 - four ticks of
profit on a round trip, with no view on where the price goes. Repeat thousands of times a
day. That is, in essence, IMC's business.

The exchange wants you there: continuous two-sided quotes are what make a market tradeable.
Many venues formalise this with market-maker agreements - obligations to quote a maximum
spread a minimum percentage of the time, in exchange for fee rebates.

### Inventory risk

The problem is that fills don't arrive in matched pairs.

Buy 100 at 9998 with no offsetting sell and you are **long 100** - you own something whose
price can fall. That's **inventory risk**, and it's the reason market making is hard rather
than free money.

The standard mitigation is **skewing**: shift both quotes to encourage the fill you want.
Long 100 and wanting to get flat, you lower both bid and ask - your ask becomes more
attractive to buyers, your bid less attractive to sellers, so you're more likely to sell
and less likely to buy more.

```
  flat:      bid 9998        ask 10002
  long 100:  bid 9996        ask 10000     (both shifted down)
```

You're still quoting both sides, still earning a spread, but you're steering your position
back toward flat. Skewing is more sophisticated than the roadmap's "widen or stop", and
it's a strong thing to be able to discuss even if you implement the simpler version.

### Adverse selection

The deepest idea in this phase, and the one that separates people who've thought about
market making from people who've read about it.

**You get filled precisely when you least want to be.**

Your bid at 9998 sits there. Who takes it? Someone who wants to sell at 9998. When does
someone urgently want to sell? When they believe the price is about to fall - because they
have information you don't, or they saw an order flow signal, or news just broke.

So your fills are **biased toward the times you were wrong**. In a market with no
information asymmetry, you'd capture the spread every time. In a real market, some fraction
of your fills come from better-informed counterparties, and those fills lose money.

This is why:

- Spreads exist at all, and why they widen when uncertainty rises
- Market makers pull quotes before scheduled news events
- Faster reaction speed is worth so much money - the ability to cancel a stale quote before
  someone picks it off
- A market maker with no risk controls eventually blows up: they get repeatedly filled on
  the wrong side by people who knew more

Your simulation won't model informed traders, so you won't observe real adverse selection.
**Say so** when you present it - identifying the limitation of your own model is exactly
the kind of thing that impresses an interviewer, and pretending it's realistic is the kind
that doesn't.

### Realised and unrealised P&L

- **Realised** - locked in by closing a position. Bought at 9998, sold at 10002: 4 ticks
  realised per unit, permanently.
- **Unrealised** - paper profit on what you still hold, marked at the current market price.
  Long 100 at an average of 9998, mid now 10000: 200 ticks unrealised, which evaporates if
  the price comes back.

**Total = realised + unrealised.** Report both separately. A strategy showing great total
P&L that is entirely unrealised on a large open position hasn't made money; it has made a
bet.

Marking unrealised P&L requires a reference price - the mid is conventional. Note that mid
is a fiction when the book is one-sided or empty, which your code must handle.

Watch your signs. Short positions profit when the price falls. Getting the sign wrong on
short P&L is the most common bug in this phase, and it produces a strategy that looks
profitable while losing money.

### Position limits

A hard risk control: once `|position|` exceeds a maximum, change behaviour. Options, in
increasing sophistication:

- **Stop quoting the side that would increase the position** (long? stop bidding)
- **Widen the spread**, making fills less likely and better compensated
- **Skew**, as above
- **Actively flatten** - cross the spread to get out, accepting a loss to remove risk

Real firms run these limits at every level - per instrument, per desk, per firm - with
automated kill switches. The 2012 Knight Capital collapse (about $460m lost in 45 minutes
to a runaway algorithm) is the canonical example of what missing controls cost.

The roadmap calls this "the single most IMC-flavoured piece of the whole build", and it's
right. Risk management *is* the job. Anyone can write a quoting loop.

### Quote staleness and cancel-replace

A quote you posted when the mid was 10000 is a liability once the mid moves to 10010 - your
ask at 10002 is now well below fair value and someone will take it for free. That's being
**picked off**.

So market makers continuously cancel and replace quotes as fair value moves. The trade-off:
cancel and replace too often and you lose queue position (your new order goes to the back
of the FIFO queue from Phase 1, so you fill less); too rarely and you get picked off. Real
firms invest enormously in reacting faster than everyone else, and that's most of what the
latency arms race is about.

Your requote interval is a crude version of this decision. Be able to discuss the trade-off.

---

## Java concepts

### Functional interfaces as extension points

```java
@FunctionalInterface
public interface Strategy {
    void onMarketData(BookSnapshot snapshot, OrderGateway gateway);
}
```

One abstract method, so any lambda matching the shape is a `Strategy`. That makes testing
trivial - a test can supply a two-line lambda instead of a mock framework.

Note what the signature says: a strategy *reacts to market data* by *submitting orders*. It
doesn't own the book, doesn't match, doesn't know how the engine works. That separation is
the design, and it's why you can write a second strategy later without touching anything
else.

### Composition over inheritance

The instinct from a uni OOP course is `class SimpleMarketMaker extends AbstractStrategy`.
Resist it.

Inheritance couples you to a base class's internals, is fixed at compile time, and Java
only gives you one parent. Composition - holding a reference to a collaborator - is
flexible, swappable at runtime, and testable in isolation.

Your market maker *has a* `Position`, *has a* risk limit, *has a* gateway. It *is a*
`Strategy`. That's the rule of thumb: implement interfaces, compose behaviour, inherit
almost never.

### `ScheduledExecutorService`

```java
ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
scheduler.scheduleAtFixedRate(task, 0, 100, TimeUnit.MILLISECONDS);
```

Know the difference between the two scheduling methods:

- `scheduleAtFixedRate` - every 100ms regardless of task duration. If a task overruns, the
  next starts immediately and they can bunch up.
- `scheduleWithFixedDelay` - 100ms *between* the end of one and the start of the next.
  Never overlaps.

For requoting, think about which you want when a requote occasionally takes longer than the
interval.

**A silent trap:** if a task submitted to a `ScheduledExecutorService` throws, the task is
**cancelled silently** - no stack trace, no further executions, no error. Your strategy just
stops quoting and nothing tells you. Wrap the body in try/catch and log, or you will lose
an afternoon to this.

### Wiring a system without a framework

You'll assemble engine, gateway, market data, strategy and logging by hand in
`SimulationRunner`. No Spring, no DI container.

That's the right call at this size, and doing it manually teaches you what a DI framework
actually does: constructs your object graph and hands each object its dependencies.
Constructor injection - taking collaborators as constructor parameters rather than
constructing them internally - is what makes both testing and framework-based wiring
possible. Do that consistently and you'll never need to refactor for testability.

### Testing time-dependent code

A strategy that calls `Instant.now()` internally is nearly untestable - you can't run "an
hour of trading" in a unit test.

The standard fix is to inject the clock. Java has `java.time.Clock` for exactly this:
`Clock.systemUTC()` in production, `Clock.fixed(...)` in tests. Then a test can advance time
deliberately and assert what the strategy does.

You don't have to do this. But if you find yourself writing `Thread.sleep(1000)` in a test
to let a requote happen, that's the signal you should have.

---

## Prerequisites

- Phase 4 gate closed, snapshots available, trades logged

---

## Build tasks

### 5.1 - `Strategy` interface

One method, `@FunctionalInterface`, as above. Settle whether the strategy receives the
gateway as a parameter or holds it as a field, and why.

### 5.2 - `Position`

Tracks net quantity, average entry price, realised and unrealised P&L.

The hard part is the arithmetic, not the Java. Work through on paper, before coding:

- Buy 100 @ 9998, then buy 100 @ 9996. Position and average price?
- Then sell 150 @ 10002. Realised P&L? Remaining position and average price?
- Then the mid moves to 9990. Unrealised P&L?
- Now do the whole thing starting from a **short** position.

Decide your accounting convention (average cost is simplest; FIFO lot-matching is what real
systems do) and document it. Test each of those cases explicitly - this is where sign bugs
live, and a sign bug here silently inverts your results.

### 5.3 - `SimpleMarketMaker`

Quotes two-sided around the current mid at a configurable half-spread and size.

Handle the degenerate cases deliberately: empty book, one-sided book, mid unavailable. A
strategy that throws on an empty book at startup is a strategy that never starts.

### 5.4 - Fill feedback

The strategy must learn about its own fills to update `Position`. This is where Phase 3's
"how do trades leave the matcher thread" decision pays off - or doesn't.

Requirements: the strategy sees only trades where it was a counterparty (match on account
id), and **listener code must not run on the matcher thread** where it could stall matching.
If your Phase 3 design didn't allow for this, fix it now - and note in your commit message
that this is what a design assumption looks like when it comes due.

### 5.5 - Risk limit

Once `|position|` exceeds the configured maximum, change behaviour: widen, skew, or stop
quoting the side that would make it worse.

**Test it deliberately.** Force the position past the limit and assert the quotes actually
change. A risk limit that's never been observed to trigger is not a risk limit.

### 5.6 - Requoting

`ScheduledExecutorService` on a fixed interval: cancel stale quotes, post fresh ones around
the current mid.

Cancel before posting, or you double your exposure for a moment. Handle the cancel of an
order that already filled - that's `UnknownOrderException` from Phase 2 and it is a normal,
expected event here, not an error. How you handle it says a lot about whether you've
thought about the concurrent reality.

Wrap the scheduled body in try/catch. See the silent-cancellation trap above.

### 5.7 - `SimulationRunner`

Wires Phases 1-5 together, runs for a fixed duration, prints a summary:

```
=== Simulation complete ===
Duration:            60s
Orders submitted:    12,431
Trades executed:     3,204
Final position:      -40
Average entry:       10001
Realised P&L:        +1,284 ticks
Unrealised P&L:        -160 ticks
Total P&L:           +1,124 ticks
Risk limit breaches: 7
```

Make it genuinely readable. Align the columns, use the right units, label everything. This
output is the first thing anyone will see, and a scrappy summary undersells four weeks of
work.

### 5.8 - End-to-end test

A short simulation, deterministic seed, asserting it completes, produces trades, and
reports internally consistent P&L (realised + unrealised = total; position matches the sum
of fills). Not asserting a specific profit - that would be testing the random seed.

---

## Design decisions you make yourself

1. **How does the strategy learn about its fills** without blocking the matcher thread?
2. **What does the risk limit do at the boundary** - widen, skew, or stop? Argue it in
   terms of inventory risk.
3. **Requote interval** - what are you trading off? What would make you choose differently?
4. **Average cost or FIFO lot matching** for realised P&L?
5. **`scheduleAtFixedRate` or `scheduleWithFixedDelay`?** What happens when a requote
   overruns the interval?
6. **What's the mid when the book is one-sided or empty**, and what should the strategy do?

---

## Definition of done

- [ ] `Strategy` functional interface
- [ ] `SimpleMarketMaker` quoting two-sided with configurable half-spread and size
- [ ] `Position` tracking net quantity, average price, realised and unrealised P&L
- [ ] P&L arithmetic tested against hand-worked cases, **including short positions**
- [ ] Fill feedback reaching the strategy without running on the matcher thread
- [ ] Risk limit enforced, with a test proving quoting behaviour actually changes
- [ ] Requoting on a schedule, cancelling stale quotes, tolerating already-filled cancels
- [ ] Scheduled task body wrapped so a throw doesn't silently kill it
- [ ] `SimulationRunner` printing a clear, aligned P&L summary
- [ ] End-to-end test with a fixed seed
- [ ] `./mvnw clean test` green
- [ ] README updated to describe the finished system

---

## Review checklist

- Signs correct throughout - test a short position explicitly
- Realised and unrealised reported separately, never conflated
- Risk limit has a test that observes it triggering
- No listener work on the matcher thread
- Scheduled tasks can't die silently
- Cancel-of-filled-order handled as normal flow, not an error path
- Composition, not inheritance, for strategy internals
- Constructor injection throughout - no `new` on a collaborator inside a class that uses it
- Summary output is aligned, labelled and unambiguous about units
- No magic numbers - half-spread, size, limit and interval are all named configuration

---

## Interview questions

1. What does a market maker do, and how does it make money?
2. What's inventory risk, and how do you manage it?
3. What's adverse selection? Why does it mean quoting a spread isn't free money?
4. Realised vs unrealised P&L - why report both? What's misleading about total alone?
5. Your risk limit triggers. Walk me through exactly what your strategy does and why.
6. Why cancel and replace quotes at all? What does it cost you?
7. `scheduleAtFixedRate` vs `scheduleWithFixedDelay` - which did you use and why?
8. What's unrealistic about your simulation? What would you add to make it more so?
9. Your strategy shows a large profit, entirely unrealised, on a big position. Is it making
   money?

That last one is the question. Have an answer.

---

## Common mistakes

- **Sign errors on short positions.** The defining bug of this phase. Test shorts first.
- **Conflating realised and unrealised**, reporting one number that means nothing.
- **A risk limit that never triggers** because it was never tested.
- **Running strategy code on the matcher thread**, so a slow strategy stalls the exchange.
- **A scheduled task that throws once and silently stops**, and a simulation that quietly
  does nothing for the rest of its run.
- **Posting before cancelling**, doubling exposure every requote cycle.
- **Treating cancel-of-filled-order as an error** and crashing on a routine race.
- **Magic numbers** scattered through the strategy instead of named configuration.
- **Claiming the simulation proves the strategy is profitable.** It proves the code runs.
  Know the difference, and say so before someone else does.
