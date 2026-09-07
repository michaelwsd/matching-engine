# Mentored Build: How This Works

This directory holds the build documents for the matching engine project described in
`../roadmap.html`. One document per phase, plus this one.

Read this file once, then work through the phases in order.

---

## The contract

**I write documentation. You write every line of Java.**

That includes `pom.xml`, `logback.xml`, `.gitignore` and any CI config. I will not write
project code, method skeletons, or build config into your files. If you ask me to "just
write this bit", I'll decline and explain the concept again instead.

**What I will do:**

- Teach the Java and trading concepts you need, immediately before you need them
- Show small standalone examples that illustrate a language feature (never your engine's code)
- Review every diff you commit, in detail, against professional standards
- Push back on design choices and make you justify them
- Quiz you before opening the next phase

**What you get out of it:** you'll be able to explain every line of this repo in an
interview, because you wrote all of it and defended each decision.

---

## The loop

Each phase runs the same cycle:

1. I hand you the phase document
2. I lecture the concepts for the next task
3. You build, in small increments, committing as you go
4. I review your diff and push back
5. Repeat 2-4 until the Definition of Done is complete
6. **Gate** - see below

A phase does not open until the previous gate closes.

---

## Phase gate checklist

Every item must pass before we move on:

- [ ] Every Definition of Done item in the phase doc is checked
- [ ] `./mvnw test` is green from the terminal, not just the IDE
- [ ] The phase's runnable demo does what the doc says it does
- [ ] I have reviewed the final diff and have no outstanding blocking comments
- [ ] No IDE files, build output or secrets are tracked in git
- [ ] Commit messages are meaningful (see conventions below)
- [ ] `README.md` at repo root reflects what the project currently does
- [ ] **You answer the phase's interview questions aloud, in your own words**

That last one is not optional. Being able to build something and being able to explain
it are different skills, and only one of them gets you an internship.

---

## Repo conventions

**Commits.** Imperative mood, present tense, capitalised, no trailing full stop:

```
Add PriceLevel with FIFO ordering
Fix best bid returning stale price after cancel
```

Not `added stuff` or `wip`. Commit small and often - one logical change per commit. Your
git history is a portfolio artifact, and interviewers do look at it.

**Branches.** Work on `master` for now; this is a solo project and branch ceremony would
be noise. If we do the GitHub Actions stretch goal, we revisit.

**Naming.** Classes are nouns (`OrderBook`), methods are verbs (`cancelOrder`), booleans
read as questions (`isMarketable`). No abbreviations except universally understood ones
(`id`, `qty` is borderline - prefer `quantity`).

**Javadoc.** On every public type and public method that isn't self-evident. Say *why*,
not *what* - `// increment i` is noise, `// prices are scaled to ticks to avoid FP error`
is valuable.

**Tests.** Named `should<Behaviour>When<Condition>`. One assertion concept per test. If a
test needs a comment to explain what it's testing, rename the test.

---

## Trading glossary

You'll meet these as the phases introduce them. Come back here when a term goes fuzzy.

| Term | Meaning |
|---|---|
| **Limit order** | An order to buy/sell at a specified price *or better*. Rests on the book if it can't fill immediately. |
| **Market order** | An order to buy/sell immediately at whatever price is available. No price protection. |
| **Order book** | The set of all resting (unfilled) orders for an instrument, organised by price. |
| **Bid** | A buy order. **Best bid** = highest price anyone is willing to buy at. |
| **Ask / offer** | A sell order. **Best ask** = lowest price anyone is willing to sell at. |
| **Spread** | Best ask minus best bid. The market maker's raw revenue per round trip. |
| **Mid** | (best bid + best ask) / 2. The market's implied "fair" price. |
| **Depth** | How much quantity rests at each price level. A "deep" book absorbs large orders without moving much. |
| **Price-time priority** | The matching rule: better prices fill first; at equal price, whoever arrived first fills first (FIFO). |
| **Passive / resting** | An order sitting on the book waiting to be filled. Adds liquidity. |
| **Aggressive / taking** | An order that crosses the spread and fills against resting orders. Removes liquidity. |
| **Marketable limit** | A limit order priced such that it crosses immediately - behaves like a market order but with a price cap. |
| **Partial fill** | Only some of an order's quantity executed; the remainder either rests or is cancelled. |
| **Tick size** | The minimum price increment. Prices are always integer multiples of it. |
| **Self-trade** | An account matching against its own resting order. Usually prevented - it can look like market manipulation. |
| **Market maker** | A participant quoting both sides continuously, earning the spread, carrying inventory risk. |
| **Inventory / position** | Net quantity currently held. Long = positive, short = negative. |
| **Adverse selection** | Getting filled precisely because the market is about to move against you. The market maker's central risk. |
| **Realised P&L** | Profit locked in by closing a position. |
| **Unrealised P&L** | Paper profit on an open position, marked at current market price. |

---

## Asking for help

Good: "I don't understand why `TreeMap` needs a `Comparator` here when `Long` is already
comparable." - specific, shows you've thought about it.

Also good: "Here's my `PriceLevel`, I think the cancel path is O(n) and I don't like it."

Less useful: "it doesn't work" - tell me what you expected, what happened, and what you've
already ruled out. Learning to ask this way is itself an industry skill.

**When you're stuck for more than ~30 minutes, ask.** Productive struggle is valuable;
unproductive thrashing is not, and telling the difference is something I can help with.
