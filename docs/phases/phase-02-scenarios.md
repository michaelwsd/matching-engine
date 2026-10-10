# Phase 2 - Matching Scenario Table

Hand-worked expected behaviour for the matching engine, written **before** the
implementation. This is the specification: the code is correct when it reproduces this
table, not when it looks plausible.

Each scenario starts from the same book unless stated otherwise, and scenarios do not
carry state between them.

## Starting book

| Order | Account | Side | Price | Qty |
|---|---|---|---|---|
| `B1` | 1 | BUY | 9998 | 200 |
| `B2` | 1 | BUY | 9997 | 500 |
| `A1` | 2 | SELL | 10001 | 150 |
| `A2` | 3 | SELL | 10002 | 300 |

```
        BIDS                    ASKS
   200 @ 9998   (B1)       10001 @ 150   (A1)
   500 @ 9997   (B2)       10002 @ 300   (A2)

   best bid 9998 · best ask 10001 · spread 3
```

## Policies these scenarios assume

- **Trade price is the resting order's price.** The aggressor may receive price
  improvement.
- **Market order remainder is cancelled** (immediate-or-cancel). A market order has no
  price, so there is nowhere on the book to rest it.
- **Self-trade prevention cancels the newest**: if the incoming order would match a
  resting order from the same account, the *incoming* order is rejected outright and the
  resting order keeps its queue position.

---

## 1. Exact fill

Incoming: **BUY 150 @ 10001**, account 9, id `100`

`10001 >= 10001` ✓ - matches `A1` exactly.

**Trades**

| buyOrderId | sellOrderId | price | qty |
|---|---|---|---|
| 100 | A1 | 10001 | 150 |

**Book after** - the 10001 level is emptied and deleted; the incoming order does not rest.

```
   200 @ 9998               10002 @ 300
   500 @ 9997
```

---

## 2. Partial fill of the incoming order

Incoming: **BUY 200 @ 10001**, account 9, id `101`

`A1` supplies 150. The next ask is 10002, and `10001 >= 10002` is false, so matching
stops. The unfilled 50 rests as a **limit order on the bid side**.

**Trades**

| buyOrderId | sellOrderId | price | qty |
|---|---|---|---|
| 101 | A1 | 10001 | 150 |

**Book after** - note the remainder becomes the new best bid, narrowing the spread to 1.

```
    50 @ 10001  (101)       10002 @ 300
   200 @ 9998
   500 @ 9997
```

---

## 3. Partial fill of the resting order

Incoming: **BUY 100 @ 10001**, account 9, id `102`

`A1` has more than enough. The incoming order fills completely and does not rest;
`A1` stays on the book with reduced quantity.

**Trades**

| buyOrderId | sellOrderId | price | qty |
|---|---|---|---|
| 102 | A1 | 10001 | 100 |

**Book after**

```
   200 @ 9998               10001 @  50   (A1, was 150)
   500 @ 9997               10002 @ 300
```

---

## 4. Marketable limit crossing two price levels

Incoming: **BUY 400 @ 10002**, account 9, id `103`

```
10001: 10002 >= 10001 ✓  take all 150       level emptied, deleted
10002: 10002 >= 10002 ✓  take 250 of 300    A2 reduced to 50
incoming 400/400 filled
```

**Trades** - one incoming order, two trades, two different prices.

| buyOrderId | sellOrderId | price | qty |
|---|---|---|---|
| 103 | A1 | 10001 | 150 |
| 103 | A2 | 10002 | 250 |

**Book after**

```
   200 @ 9998               10002 @  50   (A2, was 300)
   500 @ 9997
```

---

## 5. Non-marketable limit - rests, no trade

Incoming: **BUY 100 @ 9999**, account 9, id `104`

`9999 >= 10001` is false. No matching occurs at all.

**Trades** - none.

**Book after** - becomes the new best bid.

```
   100 @ 9999   (104)       10001 @ 150
   200 @ 9998               10002 @ 300
   500 @ 9997
```

---

## 6. Market order with sufficient liquidity

Incoming: **MARKET BUY 200**, account 9, id `105`

No price condition. Consumes the best levels in order until filled.

**Trades**

| buyOrderId | sellOrderId | price | qty |
|---|---|---|---|
| 105 | A1 | 10001 | 150 |
| 105 | A2 | 10002 | 50 |

**Book after**

```
   200 @ 9998               10002 @ 250   (A2, was 300)
   500 @ 9997
```

---

## 7. Market order with insufficient liquidity

Incoming: **MARKET BUY 1000**, account 9, id `106`

Only 450 is available across the whole ask side. The remaining 550 is **cancelled** -
a market order has no price and cannot rest.

**Trades**

| buyOrderId | sellOrderId | price | qty |
|---|---|---|---|
| 106 | A1 | 10001 | 150 |
| 106 | A2 | 10002 | 300 |

**Book after** - ask side completely empty, nothing rests from the incoming order.

```
   200 @ 9998               (empty)
   500 @ 9997
```

---

## 8. Market order into an empty book

Starting book: bids as normal, **ask side empty**.

Incoming: **MARKET BUY 100**, account 9, id `107`

Nothing to match against. The entire order is cancelled.

**Trades** - none.

**Book after** - unchanged.

> Design question this raises: an empty trade list means both "nothing matched" and
> "rejected". If the caller needs to tell those apart, `submit` should return something
> richer than a bare `List<Trade>`.

---

## 9. Self-trade prevention

Incoming: **BUY 200 @ 10001**, **account 2**, id `108`

`A1` is also account 2. Under cancel-newest, the incoming order is rejected before any
matching happens.

**Trades** - none.

**Book after** - unchanged. `A1` keeps its queue position.

> Sub-decision to settle in the implementation: if the incoming order would match a
> self-owned order *and* a third party's order, is the whole incoming order rejected, or
> only the self-matching portion? Simplest is to reject the entire incoming order on the
> first self-match detected. Whatever you choose, document it.

---

## 10. Sell side mirror of scenario 4

Every buy-side behaviour must have a sell-side twin; an engine with correct buy logic and
sloppy sell logic is a common and expensive bug.

Incoming: **SELL 600 @ 9997**, account 9, id `109`

```
9998: 9997 <= 9998 ✓  take all 200        level emptied, deleted
9997: 9997 <= 9997 ✓  take 400 of 500     B2 reduced to 100
incoming 600/600 filled
```

**Trades** - note the incoming order is now the *sell* side.

| buyOrderId | sellOrderId | price | qty |
|---|---|---|---|
| B1 | 109 | 9998 | 200 |
| B2 | 109 | 9997 | 400 |

**Book after**

```
   100 @ 9997   (B2)        10001 @ 150
                            10002 @ 300
```

---

## 11. FIFO within a price level

Starting book: **two resting asks at the same price**, `A3` added before `A4`.

| Order | Side | Price | Qty | Arrived |
|---|---|---|---|---|
| `A3` | SELL | 10001 | 100 | first |
| `A4` | SELL | 10001 | 100 | second |

Incoming: **BUY 150 @ 10001**, account 9, id `110`

Time priority: `A3` fills completely before `A4` is touched at all.

**Trades**

| buyOrderId | sellOrderId | price | qty |
|---|---|---|---|
| 110 | A3 | 10001 | 100 |
| 110 | A4 | 10001 | 50 |

**Book after**

```
                            10001 @  50   (A4, was 100)
```

If `A4` fills before `A3`, price-time priority is broken - the fairness promise the whole
exchange rests on. Assert the trades in order, not just their total.

---

## Invariant to assert after every scenario

**The book is never crossed.** After any sequence of operations:

```
bestBid() < bestAsk()        whenever both sides are non-empty
```

If the best bid ever reaches or exceeds the best ask, those two orders should have traded
and the engine failed to match them. This single assertion catches a large class of bugs
and is worth adding to every test in the suite.
