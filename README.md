# Matching Engine

A limit order book and matching engine in Java, built from scratch.

The goal is a self-contained trading simulation: a price-time priority matching engine
that crosses orders and produces trades, fed by concurrent order intake and synthetic
market data, with a market-making strategy quoting against it and reporting its own P&L.

Built in phases, each layering new language and concurrency concepts on top of a working
system - nothing is thrown away between phases.

## Status

**Phase 0 complete** - project scaffold, build and test infrastructure.

| Phase | Scope | Status |
|---|---|---|
| 0 | Environment & tooling | done |
| 1 | Core order book | not started |
| 2 | Matching logic | not started |
| 3 | Concurrency | not started |
| 4 | Market data & persistence | not started |
| 5 | Market-making strategy | not started |

There is no trading logic yet. Phase 1 is where the order book appears.

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
