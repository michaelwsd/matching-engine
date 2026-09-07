# Phase 2 - Matching Logic

**Estimate:** 5-7 days
**Goal:** turn a passive book into an engine that actually crosses orders and produces
trades.

Phase 1 built a filing cabinet. This phase makes it an exchange.

---

## Objectives

An engine that accepts an incoming order, matches it against resting liquidity according
to price-time priority, emits the resulting trades, and rests any unfilled remainder -
verified against a scenario table you worked out by hand before writing the code.

---

## Trading concepts

### Aggressive and passive

Every trade has two sides with different roles:

- The **passive** (resting, liquidity-*making*) order was already on the book, waiting
- The **aggressive** (taking, liquidity-*removing*) order arrived and crossed the spread

Real exchanges charge different fees for each - often a rebate for making and a fee for
taking, because resting liquidity is what makes a market usable. Your Phase 5 market maker
will try to be passive almost all the time. That's the whole business model.

### Crossing the spread

The book from Phase 1:

```
   BIDS                    ASKS
   200 @ 9998              10001 @ 150
   500 @ 9997              10002 @ 300
```

A **buy limit at 10001** can trade - there's a seller at exactly that price. It's
*marketable*. A buy limit at 9999 cannot; it rests as the new best bid, narrowing the
spread. Same order type, entirely different behaviour, decided purely by price.

**The matching condition for an incoming buy:** cross while `incoming.price >= ask.price`,
walking asks from the best (lowest) upward. For a sell, `incoming.price <= bid.price`,
walking bids from the best (highest) downward. Get these inequalities right; getting them
backwards produces an engine that looks like it works and is deeply wrong.

### What price does the trade happen at?

Not necessarily the incoming order's price. A buy limit at 10005 hitting a resting ask at
10001 trades at **10001** - the resting order's price. The buyer gets **price
improvement**: they were willing to pay 10005 and paid 10001.

The rule is that the *resting* order sets the price. It was there first; it established
the terms. Getting this wrong is a common bug and an excellent interview question.

### Walking multiple levels

A large marketable order exhausts the best level and continues into the next:

```
buy 400 @ 10002  against  10001 @ 150, 10002 @ 300

  -> 150 filled at 10001   (best level exhausted)
  -> 250 filled at 10002   (partial fill of the 300)
  -> incoming fully filled, 50 remains resting at 10002 on the ask side
```

Two trades from one incoming order, at two different prices. This is why `submit` returns
a `List<Trade>`, not a single trade.

### Market orders

A market order says "fill me now, whatever the price". No price protection at all - in a
thin book, a market order can execute catastrophically far from the mid. Real exchanges
mitigate this with circuit breakers and price bands.

**The interesting question is what happens to an unfilled remainder.** If you send a market
buy for 1000 and only 400 rests on the book, what happens to the other 600?

- **Cancel it** (IOC-like) - most exchanges do this. A market order has no price, so it
  cannot rest; there'd be nothing to rest *at*.
- **Reject the whole order** if it can't fill entirely (FOK-like)
- Convert it to a limit at the last traded price - some venues, rare

There's no universally correct answer, which is exactly why the roadmap says "decide **and
document** what happens". Being able to state your choice and defend it is the skill.

While you're here, learn the vocabulary - **IOC** (immediate-or-cancel: fill what you can
now, cancel the rest) and **FOK** (fill-or-kill: all of it right now or none of it). You
aren't implementing them, but knowing the terms is table stakes.

### Self-trade prevention

If the same account is on both sides of a match, the "trade" is fictitious - no position
changes hands. It inflates reported volume, and doing it deliberately is **wash trading**,
a form of market manipulation regulators prosecute. Exchanges therefore prevent it
structurally rather than trusting participants.

Three standard policies:

- **Cancel newest** - reject the incoming order
- **Cancel oldest** - remove the resting order, let the incoming continue
- **Cancel both**

Each has different fairness properties and different effects on queue position. Pick one,
document it, and be ready to explain what a market maker would prefer and why. (Think
about what happens to your own queue priority under each.)

---

## Java concepts

### Interfaces and programming to abstractions

An interface is a contract - what a thing can do, with no statement about how.

```java
public interface Shape {
    double area();
}
```

Depending on `Shape` rather than `Circle` means your code works with any shape, including
ones written later. That's the payoff, and it's why the roadmap has you extract an
interface for a single implementation: the cost is one file today, and the benefit is that
adding pro-rata matching later is *additive* rather than a rewrite.

Since Java 8, interfaces can have `default` methods (an implementation subclasses inherit)
and `static` methods. `default` exists mainly so interfaces can evolve without breaking
every implementor - a genuine problem when your interface is used by code you don't own.

**Sealed interfaces** (Java 17+) let you restrict who can implement:

```java
public sealed interface Result permits Success, Failure { }
```

The compiler then knows the complete set, which makes `switch` over them exhaustive with
no `default` branch. Worth knowing; think about whether anything in your design is
genuinely a closed set.

### The strategy pattern

Encapsulate an algorithm behind an interface so it can be swapped without changing the
code that uses it. `Comparator` is the pattern in the JDK - `List.sort` doesn't know how
you want things ordered, you hand it the algorithm.

Here: `MatchingStrategy` is the algorithm (price-time now, pro-rata later),
`MatchingEngine` is what the rest of the system talks to. Keeping those two ideas separate
is the design work of this phase. Ask yourself what each is responsible for, and whether
you actually need both or are cargo-culting an abstraction. **Defend your answer either
way** - "I added an interface because the roadmap said so" is not a defence.

### Exceptions: checked vs unchecked

- **Checked** (extends `Exception`): the compiler forces you to `catch` or `throws`
- **Unchecked** (extends `RuntimeException`): no compiler ceremony

The conventional rule: checked for recoverable conditions the caller can reasonably handle
(a file is missing, so prompt for another), unchecked for programming errors and violated
preconditions (you passed a negative quantity - there's no sensible recovery, the caller
has a bug).

The modern consensus, informed by twenty years of experience, leans heavily unchecked.
Checked exceptions don't compose with lambdas or streams, they leak implementation details
into signatures, and in practice they mostly produce `catch (Exception e) { }` blocks that
swallow problems. Kotlin removed them entirely.

`InvalidOrderException` and `UnknownOrderException` should be unchecked. **Be ready to
argue why**, and be ready to argue the other side too - this is a genuine debate and an
interviewer may push you on it to see whether you have a position or a memorised answer.

Two more things that separate a good exception from a bad one:

- **The message must contain the values.** `"Unknown order"` is useless at 3am.
  `"Unknown order id: 4471"` tells you what to look for.
- **Don't catch and rethrow without cause.** If you wrap, pass the original as the cause,
  or you destroy the stack trace that would have told you what happened.

### Defensive validation, and where it belongs

Validate at the **boundary** - the point where untrusted input enters your system. Once
inside, code can trust its inputs.

If `Order`'s constructor guarantees a positive quantity and a valid side, the engine
doesn't need to re-check. Validating everywhere is noise that obscures the checks that
matter. Validating nowhere means bad data propagates until it corrupts something far from
its origin.

So: what is the boundary here? `Order`'s constructor handles structural validity. The
engine handles *contextual* validity - things you can't know at construction. What falls
into that second category? (A limit order with no price. A market order *with* one. Think
about what else.)

### Parameterised tests

When you have one behaviour and many input combinations, a scenario table beats fifteen
near-identical test methods:

```java
@ParameterizedTest
@CsvSource({
    "1, 1, 2",
    "2, 3, 5",
    "0, 0, 0"
})
void shouldAddNumbers(int a, int b, int expected) {
    assertThat(a + b).isEqualTo(expected);
}
```

For richer cases, `@MethodSource` supplies a `Stream` of argument objects, which is what
you'll want for full order scenarios.

**Use these judiciously.** A parameterised test with eight columns and a wall of numbers is
less readable than three well-named explicit tests. The rule: parameterise when the cases
differ only in *data*, write separate tests when they differ in *meaning*.

---

## Prerequisites

- Phase 1 gate closed
- Your book correctly maintains price-time priority - matching built on a broken book
  produces bugs that look like matching bugs

---

## Build tasks

### 2.1 - Write the scenario table first

Before any code. In a comment block, a markdown file, or on paper, work out by hand:

| # | Book before | Incoming | Trades expected | Book after |
|---|---|---|---|---|
| 1 | ask 100 @ 10001 | buy 100 @ 10001 | 100 @ 10001 | empty |
| 2 | ... | ... | ... | ... |

Cover: exact full fill, partial fill of incoming, partial fill of resting, a marketable
limit crossing multiple levels, a non-marketable limit that just rests, an unfilled market
order, a market order in an empty book, and self-trade prevention.

**Do this before implementing.** If you write the code first, you'll unconsciously write
tests that agree with whatever it does, including the bugs. Working the table out by hand
first is the single highest-value habit in this phase.

### 2.2 - `MatchingEngine` interface

`List<Trade> submit(Order incoming)`.

Questions to settle: can it return an empty list, and what does that mean? Should the
return be immutable? Does the caller learn *why* nothing matched? Is `submit` the right
verb?

### 2.3 - `PriceTimeMatchingEngine`

The core loop. For an incoming buy: walk the asks from best upward while the price
condition holds and the incoming order still has quantity, filling FIFO within each level,
emitting a `Trade` per fill.

Watch for:

- Removing fully-filled resting orders from the book and from your O(1) cancel index
- Removing price levels that become empty
- **Not mutating a collection while iterating it** - this will throw
  `ConcurrentModificationException` and it is very easy to do here. Understand why, and
  what the legitimate ways around it are.
- The trade price coming from the resting order, not the incoming one

Then rest any unfilled LIMIT remainder on the book.

### 2.4 - Market orders

Match against best available liquidity regardless of price. Implement the remainder policy
you chose and **write your reasoning in the Javadoc**, not just the behaviour.

### 2.5 - Exceptions

`InvalidOrderException` and `UnknownOrderException`, both unchecked. Wire `UnknownOrderException`
into `cancelOrder` for a non-existent id - which means revisiting the Phase 1 decision
about what cancel does when it can't find the order.

Messages must include the offending values.

### 2.6 - Self-trade prevention

Implement your chosen policy. Test it on both sides. Test that two *different* accounts
still match normally - it's easy to write STP that accidentally blocks everything.

### 2.7 - Extract `MatchingStrategy`

Pull the algorithm behind an interface, even though you ship one implementation.

Then do the real test of the abstraction: **sketch** (don't build) how pro-rata allocation
would slot in. Pro-rata splits an incoming order across all resting orders at a price
proportionally to their size, rather than FIFO. If your interface can't express that
without changing, the abstraction is in the wrong place - fix it now while it's cheap.

### 2.8 - Tests

Turn the scenario table into tests. Add an invariant test worth more than most: **after
any sequence of operations, the book is never crossed** - best bid is always strictly
below best ask.

---

## Design decisions you make yourself

1. **What does `submit` return** - `List<Trade>`, or a richer result object carrying
   rejection reasons and remaining quantity? What does each cost the caller?
2. **Market order remainder** - cancel, reject, or rest? Justify against real exchange
   behaviour.
3. **Self-trade prevention policy** - newest, oldest, or both? What would a market maker
   prefer, and why?
4. **Where does validation live** - `Order`'s constructor, the engine, or both? What's the
   boundary?
5. **Do you need both `MatchingEngine` and `MatchingStrategy`?** Or is one of them
   ceremony? Argue it.
6. **Does the engine own the book, or is the book passed in?** Think about Phase 3.

---

## Definition of done

- [ ] Scenario table written by hand **before** the implementation
- [ ] `MatchingEngine` interface, with `PriceTimeMatchingEngine` behind it
- [ ] Limit orders cross correctly, including across multiple price levels
- [ ] Trades execute at the **resting** order's price
- [ ] Unfilled limit remainder rests on the book
- [ ] Market orders handled, remainder policy documented in Javadoc
- [ ] `InvalidOrderException` and `UnknownOrderException`, unchecked, with useful messages
- [ ] Self-trade prevention working, with tests proving it doesn't block normal matching
- [ ] `MatchingStrategy` extracted, and you can explain how pro-rata would slot in
- [ ] Scenario-table tests green, plus a book-never-crossed invariant test
- [ ] `./mvnw clean test` green

---

## Review checklist

- Are the matching inequalities right for **both** sides? (Mirror every test.)
- Is trade price taken from the resting order?
- Are filled orders removed from the book *and* the cancel index?
- Are empty price levels cleaned up?
- Any collection mutated during iteration?
- Do exception messages include the values?
- Does any method exceed ~30 lines? The matching loop is the one at risk - can it be
  decomposed without obscuring the algorithm?
- Are the tests readable as a specification of the engine's behaviour?

---

## Interview questions

1. Walk me through what happens when a buy limit at 10005 hits a book with asks at 10001,
   10002 and 10006. What trades, at what prices, and what's left?
2. Why does the trade execute at the resting order's price rather than the aggressor's?
3. What happens to the unfilled part of a market order in your engine, and why did you
   choose that? What do real exchanges do?
4. Checked or unchecked for `InvalidOrderException`? Argue both sides, then tell me your
   position.
5. Why prevent self-trades at all? What's the regulatory concern?
6. You have one matching strategy. Justify the interface - or admit it's premature.
7. What's `ConcurrentModificationException`, when does it fire, and how do you avoid it
   legitimately?
8. How do you know your engine is correct? What would convince a sceptic?

---

## Common mistakes

- **Inequality direction reversed** on one side. Mirror every test.
- **Trading at the aggressor's price.** Subtle, plausible-looking, wrong.
- **Mutating the book while iterating it.** Learn the right idiom, don't just copy the map
  every time.
- **Forgetting to remove the resting order** when it's fully filled - it stays on the book
  as phantom liquidity and matches again.
- **Writing tests after the code**, which encodes your bugs as expected behaviour.
- **A god-method** that does matching, resting, validation and STP in eighty lines.
- **Self-trade prevention that blocks all trading** because the account check is inverted
  and every test uses the same account id.
- **Swallowing exceptions** in a `catch` block to make a test pass.
