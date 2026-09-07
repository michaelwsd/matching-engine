# Phase 1 - Core Order Book

**Estimate:** 4-6 days
**Goal:** model resting orders and price levels correctly before any matching exists.

This is the phase where most of Java's "basics" actually live, and where the data
structure choices you make will either make Phase 2 easy or make it painful. Take your
time here.

---

## Objectives

By the end of this phase you have an order book that stores, cancels and displays resting
orders with correct price-time priority, and a `Main` that prints a book you built by
hand. Nothing matches yet - orders just rest.

---

## Trading concepts

### What an order book actually is

An exchange maintains, per instrument, a list of every **resting order** - orders that
have been submitted but not yet filled. That's the order book. It has two sides:

```
        BIDS (buyers)              ASKS (sellers)
   qty    price              price    qty
   200 @  99.98              100.01 @ 150     <- best ask (lowest sell)
   500 @  99.97              100.02 @ 300
   150 @  99.95              100.05 @ 800
         ^
    best bid (highest buy)
```

**Best bid** is the highest price a buyer will pay. **Best ask** is the lowest price a
seller will accept. Note they're at *opposite* ends: buyers want to pay less, sellers want
to receive more, so the aggressive end of each side faces the middle.

The gap between them is the **spread** (here, 100.01 - 99.98 = 0.03). The **mid** is the
average (99.995) - the market's implied fair price.

The book cannot be *crossed* - if the best bid ever became higher than the best ask, those
two orders would have matched. Once you build Phase 2, this becomes an invariant you can
assert in tests.

### Price-time priority

When an incoming order can fill against several resting orders, which one fills first?

1. **Price first.** A seller crossing into the bids fills against the highest bid
   available - the buyer offering the most.
2. **Time second.** Among orders at the *same* price, the one that arrived first fills
   first. FIFO.

This is why a price level is a queue, not a set. It's also why "time priority" is valuable
to traders: being early in the queue at a good price means you get filled and the person
behind you doesn't.

### Depth

How much quantity sits at each price. A book with 10,000 lots at each of the top five
levels is *deep* - a large order barely moves the price. A thin book moves a lot. Depth is
what you'd display in a trading UI, and what your Phase 5 strategy will read.

### Why money is never a `double`

This is the single most important idea in this phase, and a classic interview question.

```java
System.out.println(0.1 + 0.2);          // 0.30000000000000004
System.out.println(0.1 + 0.2 == 0.3);   // false
```

`double` is binary floating point. It cannot represent 0.1 exactly, any more than decimal
can represent 1/3 exactly. Errors accumulate. In a system that adds up millions of prices
and quantities, they accumulate into real money.

Worse, equality breaks. Two orders you'd expect to match at the same price don't, because
one price is `100.01` and the other is `100.00999999999999`.

**The fix used by every real exchange: store prices as integers in the smallest unit.**
Instead of `100.01` dollars, store `10001` cents as a `long`. All arithmetic is exact, all
comparisons are exact, and `long` gives you room for prices far beyond anything realistic.

This connects to **tick size** - the minimum price increment for an instrument. If the
tick is one cent, every valid price is an integer number of cents by definition, so the
scaled representation isn't even a compromise. It's the natural one.

`BigDecimal` is the other correct answer, and it's what you'd use for currency conversion
or interest calculations where you need arbitrary precision and scale. It's much slower
and allocates objects. For a matching engine, where the price domain is a fixed grid of
ticks, scaled `long` is the right call. **Be able to argue both sides of this.**

---

## Java concepts

### Records vs classes

A **record** is an immutable data carrier. Declaring one:

```java
public record Point(int x, int y) { }
```

gives you, for free: a constructor, `x()` and `y()` accessors, `equals`, `hashCode`,
and `toString`. All fields are `final`. You cannot change a record after construction.

Use a record when the type *is* its data and that data doesn't change. Use a class when
the object has identity and mutable state, or when you need to control construction
beyond what a record allows.

In this phase: a `Trade` is a historical fact - it happened, at a price, for a quantity,
and can never change. That's a record. An `Order` has a remaining quantity that shrinks as
it partially fills. That's a class.

You can still validate in a record via a **compact constructor**:

```java
public record Point(int x, int y) {
    public Point {
        if (x < 0) throw new IllegalArgumentException("x must be non-negative");
    }
}
```

Note there's no parameter list and no assignment - the canonical constructor assigns the
fields for you after your validation runs.

### Enums are more than constants

```java
public enum Direction { NORTH, SOUTH }
```

Java enums are full classes. They can have fields, constructors, methods, and even
per-constant method implementations. They're type-safe (you cannot pass a `String` where a
`Direction` is expected), they work in `switch` with exhaustiveness checking, and
`EnumMap`/`EnumSet` give you very efficient collections keyed by them.

When you write `Side`, ask whether it should carry behaviour - for instance, whether a side
knows how to produce its opposite. That's a design decision, not a given.

### Generics

`List<String>` means "a list that holds Strings". The compiler enforces it; you never cast
on the way out. Before generics (Java 4 and earlier) collections held `Object` and every
read was a cast that could blow up at runtime.

Two things worth knowing now:

**Type erasure.** Generics are compile-time only. At runtime `List<String>` and
`List<Integer>` are both just `List`. This is why you can't write `new T[10]` and why you
can't overload on `List<String>` vs `List<Integer>`.

**Generics are invariant.** `List<Dog>` is *not* a `List<Animal>`, even though `Dog` is an
`Animal`. If it were, you could add a `Cat` to your `List<Dog>` through the `List<Animal>`
reference. The wildcards `? extends` and `? super` exist to relax this safely - you likely
won't need them this phase, but you'll meet them.

### Choosing collections: this phase's central decision

**`TreeMap<K,V>`** is a sorted map, implemented as a red-black tree. Keys are kept in
order, giving you O(log n) get/put and - crucially - navigation:

```java
TreeMap<Integer, String> map = new TreeMap<>();
map.put(3, "c"); map.put(1, "a"); map.put(2, "b");

map.firstKey();        // 1  - smallest
map.lastKey();         // 3  - largest
map.firstEntry();      // 1=a
map.headMap(3);        // {1=a, 2=b}  - everything below 3
map.descendingMap();   // a reversed VIEW: {3=c, 2=b, 1=a}
```

`descendingMap()` returns a **view**, not a copy - it's backed by the same tree, so it's
free and changes write through. Understanding view semantics matters; accidentally
mutating through a view you thought was a snapshot is a real bug class.

The alternative for reversing is constructing the map with a `Comparator`:

```java
new TreeMap<>(Comparator.reverseOrder());
```

Both work for your bid side. **They are not equivalent** and choosing between them is one
of your design decisions this phase.

**`ArrayDeque<E>`** is a double-ended queue backed by a growable circular array. O(1)
add/remove at *either* end, good cache locality, no per-element node object.

`LinkedList` also implements `Deque` and is the instinctive choice for "a queue". It is
almost always the wrong one: every element costs a heap-allocated node with two pointers,
traversal jumps around memory defeating the CPU cache, and its only real advantage -
O(1) removal *given an iterator position* - is rarely what you actually have. Modern
guidance is unambiguous: prefer `ArrayDeque`.

Read the Javadoc for both before you choose. Being able to say *why* `ArrayDeque` in an
interview is worth more than knowing that it's the answer.

### Encapsulation, and the leaky getter

```java
public List<Order> getOrders() {
    return orders;          // caller can now clear() your internal list
}
```

Returning a direct reference to internal mutable state means callers can modify your
object's guts behind your back, bypassing every invariant you maintain. Options:

- `Collections.unmodifiableList(orders)` - a cheap read-only *view*; caller can't modify,
  but sees your later changes
- `List.copyOf(orders)` - an immutable *snapshot*; caller sees a frozen moment
- Don't expose the collection at all; expose the operations callers actually need

Which is right depends on whether the caller should see live updates. Think about this
carefully for `depth()`.

### The `equals`/`hashCode` contract

If you override one, override the other. The contract:

- Equal objects must have equal hash codes (the converse need not hold)
- `equals` must be reflexive, symmetric, transitive, consistent, and `x.equals(null)` is
  false

**The mutable-field trap:** if `equals`/`hashCode` depend on a field that changes, and you
put the object in a `HashMap` or `HashSet`, mutating that field strands it - it's in a
bucket that no longer matches its hash, and you can't find or remove it.

This bites directly here. An `Order` with mutable remaining quantity must not include that
quantity in `equals`/`hashCode`. What *should* identity be based on? (Hint: what makes two
orders "the same order"?)

Records generate `equals`/`hashCode` over all components - which is correct precisely
because records are immutable.

### JUnit 5 + AssertJ

```java
@Test
void shouldReturnHighestPriceAsBestBid() {
    // arrange, act, assert
    assertThat(book.bestBid()).isEqualTo(9998L);
}
```

AssertJ's fluent style reads as English and gives far better failure messages than
`assertEquals`. It chains, and it has rich collection assertions:

```java
assertThat(list).hasSize(3).containsExactly(a, b, c);   // order matters
assertThat(list).containsExactlyInAnyOrder(c, a, b);    // order doesn't
assertThatThrownBy(() -> book.cancel(99L))
        .isInstanceOf(UnknownOrderException.class);
```

`containsExactly` vs `containsExactlyInAnyOrder` is exactly the distinction you need for
FIFO tests - use the ordered one, or your test won't actually prove FIFO.

Structure every test **arrange / act / assert**. If a test has two acts, it's two tests.

---

## Prerequisites

- Phase 0 gate closed
- `./mvnw test` green

---

## Build tasks

### 1.1 - The `model` package

Create `Side` (BUY, SELL) and `OrderType` (LIMIT, MARKET) as enums.

Create `Trade` as a **record**: buy order id, sell order id, price, quantity, timestamp.
Think about what type each field should be - and specifically, what type a timestamp
should be. `long` epoch millis? `Instant`? What are the trade-offs for a system that will
later need to be deterministic under test?

Create `Order` as a **class**, because remaining quantity mutates on partial fills.

Decide for yourself: what's the full field list? You need at minimum enough to identify
the order, know its side and type, its price, its original and remaining quantity, and its
arrival time. Phase 2 will need an account id for self-trade prevention - add it now or
later, but know why you chose.

**Prices are scaled `long`s.** Document that decision in a Javadoc comment on the field, in
the units you chose. Future-you will thank present-you.

Validate in constructors. An order with negative quantity should never exist - not "should
be caught later", should *never exist*. Fail fast, at construction.

### 1.2 - `PriceLevel`

A class wrapping an `ArrayDeque<Order>` representing all orders resting at one price,
in arrival order. Expose: add, remove, total resting quantity, and whatever else you find
you need.

Design question to settle before you write it: is total quantity **computed on demand** by
summing the queue, or **maintained incrementally** as orders are added and removed? One is
simpler and obviously correct; the other is O(1) instead of O(n) but has two places to get
wrong. Which matters more here, and how would you know?

### 1.3 - `OrderBook`

Two `TreeMap<Long, PriceLevel>` - one for bids, one for asks - keyed by scaled price.

The asks side wants ascending order (best = lowest = first). The bids side wants
descending (best = highest = first). Getting both to present "best first" through the same
code shape is the interesting bit.

### 1.4 - Book operations

Implement `addOrder`, `cancelOrder`, `bestBid()`, `bestAsk()`, and `depth(int levels)`.

Things to get right:

- What does `bestBid()` return when the book is empty? `null`? `Optional`? A sentinel?
  Think about which forces the caller to handle the empty case correctly.
- When the last order at a price level is cancelled, the empty `PriceLevel` must be
  removed from the map. If you leave empty levels behind, `bestBid()` starts returning
  prices with no liquidity - a nasty, quiet bug. Write a test for exactly this.
- `cancelOrder` takes an order id. Finding that order by scanning every level is O(n). How
  do you make it O(1)? What extra state does that cost you, and what must you keep in sync?

### 1.5 - `OrderBookTest`

Cover at minimum:

- Orders resting at multiple price levels, on both sides
- Best bid / best ask correct after inserts
- Best bid / best ask correct after cancels, **including cancelling the only order at the
  best level**
- FIFO preserved within a level - use an order-sensitive assertion
- Empty book behaviour
- Cancelling an unknown id
- Depth snapshot returns the right levels in the right order

### 1.6 - A visible `Main`

Build a book from a hardcoded list of orders and print its depth. Format it so it reads
like the ASCII diagram at the top of this document - bids and asks either side of the
spread.

This is your first visibly working milestone. Make it look good; you'll be showing it to
people.

---

## Design decisions you make yourself

1. **`Comparator.reverseOrder()` on the bid map, or `descendingMap()` on a natural-order
   map?** Consider: which one makes bids and asks share code? Which one surprises the next
   reader? What does `descendingMap()` being a *view* imply?
2. **Mutable `Order`, or immutable `Order` replaced on each fill?** The roadmap says
   mutable. Immutability is generally safer, especially with Phase 3 coming. What does the
   immutable version cost you, and why might the roadmap still be right?
3. **What does `depth(int levels)` return?** A `List` of what? Live view or snapshot? Who
   owns it? If Phase 5's strategy reads it on another thread, does your answer change?
4. **How does `cancelOrder` find its order in O(1)?** What's the failure mode of the
   auxiliary structure getting out of sync with the book?
5. **What is an `Order`'s identity for `equals`/`hashCode`?** Or should it not override
   them at all?

---

## Definition of done

- [ ] `Side`, `OrderType`, `Trade`, `Order` modelled, with validation at construction
- [ ] Prices stored as scaled `long`, documented, no `double` anywhere
- [ ] `PriceLevel` maintains FIFO within a price
- [ ] `OrderBook` with correct `bestBid()`, `bestAsk()`, `addOrder`, `cancelOrder`, `depth`
- [ ] Empty price levels are removed from the book
- [ ] Tests green for inserts, cancels, FIFO, empty book, unknown cancel, depth
- [ ] `Main` prints a readable book snapshot
- [ ] `./mvnw clean test` green

---

## Review checklist

- No `double` or `float` anywhere near a price
- No public mutable state; no getter leaking an internal collection
- `Order` doesn't put its mutable quantity into `equals`/`hashCode`
- Validation happens at construction, not scattered through callers
- `PriceLevel` and `OrderBook` each have one clear responsibility
- Tests name behaviours; FIFO test uses an order-sensitive assertion
- Javadoc on public types explains *why*, including the price-scaling decision
- No commented-out code, no leftover `System.out.println` in library classes

---

## Interview questions

1. Why not `double` for price? Give me the concrete failure. When *would* you use
   `BigDecimal` instead of a scaled `long`?
2. Why `TreeMap` rather than `HashMap` for price levels? What operation would you lose?
3. Why `ArrayDeque` rather than `LinkedList` for the queue at a price level?
4. What's the complexity of your `addOrder`, `cancelOrder`, and `bestBid()`? Justify each.
5. What breaks if an object's `hashCode` changes while it's a key in a `HashMap`?
6. Records vs classes - why is `Trade` one and `Order` the other?
7. What's type erasure, and name something it prevents you from writing.
8. Your `depth()` returns a collection. Can the caller modify your book through it? Prove it.

---

## Common mistakes

- **Using `double` for price** because it's what money looks like. Don't.
- **Leaving empty `PriceLevel`s in the map** after the last order at that price is
  cancelled. Silent, and it corrupts best bid/ask.
- **Testing FIFO with an unordered assertion**, which passes whether or not FIFO works.
- **Returning the internal `ArrayDeque`** from `PriceLevel`, letting callers corrupt it.
- **Forgetting the both-sides symmetry** - writing careful bid logic and sloppy ask logic.
  Every test you write for one side should have a mirror for the other.
- **Making `Order` a record** because records are new and nice, then discovering you can't
  decrement remaining quantity.
- **Skipping validation** on the grounds that "nothing will call it wrong". Phase 3 will.
