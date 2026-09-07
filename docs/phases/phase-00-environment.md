# Phase 0 - Environment & Tooling

**Estimate:** 0.5-1 day
**Goal:** get a project scaffold running end to end before writing any real logic, so
every later phase starts from a known-good baseline.

There is no trading logic in this phase. That's deliberate - you want the build, the test
runner and the version control working *before* you have interesting code to debug, so
that when something breaks later you know it isn't the plumbing.

---

## Objectives

By the end of this phase:

- `./mvnw test` runs from your terminal and passes
- One real (if trivial) JUnit 5 test executes
- The repo has commits, a README, and a GitHub remote
- Nothing IDE-specific is tracked in git

---

## Java & tooling concepts

### Maven's model

Maven is a *convention over configuration* build tool. It expects a fixed directory
layout and, if you follow it, needs almost no configuration:

```
src/main/java      production code
src/main/resources production non-code files (config, logback.xml later)
src/test/java      test code
src/test/resources test fixtures
target/            build output - never committed
```

You currently have `src/main/java` only. `src/test/java` doesn't exist yet, which is why
you have no tests.

### Coordinates

Every Maven artifact is identified by three things:

- **groupId** - who made it, reverse-DNS style (`com.mwang`)
- **artifactId** - what it is (`matching-engine`)
- **version** - which build (`1.0-SNAPSHOT`)

`SNAPSHOT` means "in development, may change without the version changing". Released
artifacts drop it. This is why you can't rely on a SNAPSHOT dependency being stable.

**Semantic versioning** (`MAJOR.MINOR.PATCH`): major = breaking change, minor = new
features backwards-compatibly, patch = bug fixes only. When you pick dependency versions,
this tells you how risky an upgrade is.

### Dependency scopes

A dependency's `<scope>` controls when it's on the classpath:

| Scope | Available in | Ships in your jar? |
|---|---|---|
| `compile` (default) | everything | yes |
| `test` | test compile + test run only | no |
| `provided` | compile + test, but the runtime supplies it | no |
| `runtime` | run time only, not compile | yes |

JUnit and AssertJ are `test` scope. Getting this wrong - shipping your test framework to
production - is a classic junior mistake and an easy interview question.

### `release` vs `source`/`target`

Your `pom.xml` currently sets:

```xml
<maven.compiler.source>25</maven.compiler.source>
<maven.compiler.target>25</maven.compiler.target>
```

`source` controls which *language* features are allowed. `target` controls which bytecode
version is emitted. Neither checks the **API** you call. So with `source`/`target` set to
21 but compiling on JDK 25, you could call a JDK 25-only library method, compile fine, and
then get `NoSuchMethodError` at runtime on a real JDK 21.

`<maven.compiler.release>` sets both *and* restricts the visible API to that version's.
It is strictly better and there is no reason to use the old pair in new projects.

### Transitive dependencies and BOMs

If you depend on A, and A depends on B, you get B too - transitively. This is convenient
until two dependencies want different versions of B and Maven has to pick one
("dependency mediation" - nearest wins, ties broken by declaration order).

A **BOM** (Bill of Materials) is a POM that declares a consistent set of versions for a
family of artifacts. Importing JUnit's BOM in `<dependencyManagement>` lets you declare
`junit-jupiter` without a version and get a coherent set. Worth learning now; it's
standard practice everywhere.

### Why pin plugin versions

If you don't specify a version for a Maven plugin, Maven picks one based on your Maven
version. Two developers on different Maven versions then get different builds from
identical source. That's not reproducible, and reproducibility is the whole point of a
build tool. **Always pin plugin versions explicitly.**

Surefire is the plugin that runs your tests. Old Surefire versions don't discover JUnit 5
tests at all - they look for JUnit 4's naming conventions and silently find nothing. A
green build with zero tests run is worse than a red one.

---

## Prerequisites

- JDK 25 Temurin installed (`java -version` confirms 25.0.4.1 - already done)
- IntelliJ IDEA Community Edition (already done)
- A GitHub account

---

## Build tasks

Commit after each numbered task.

### 0.1 - Generate the Maven Wrapper

You have no `mvn` on your PATH, but IntelliJ ships one. Use it once to generate the
wrapper, after which you'll never need a system Maven again:

```bash
"/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -N wrapper:wrapper
```

This creates `mvnw`, `mvnw.cmd` and `.mvn/wrapper/`. **Commit all of them.** That's the
point - anyone cloning your repo gets the exact same Maven version with no install step,
and GitHub Actions works for free.

Verify: `./mvnw -v` prints a Maven version.

> Note the empty `.mvn/` directory already in your repo - IntelliJ left it there. The
> wrapper will populate it.

### 0.2 - Fix the compiler configuration

Replace the `source`/`target` properties with `maven.compiler.release`. Set it to `25`.

Ask yourself before you do it: the roadmap says "21 or higher". Why might a real team pin
lower than their installed JDK? (Answer in the interview questions below.)

### 0.3 - Decide your base package, and move `Main`

Currently: `com.mwang.Main`, sitting directly in the group package.

Maven convention is that the base package matches `groupId` + `artifactId`, which would
give you `com.mwang.matchingengine`. That leaves room for the subpackages coming in later
phases without cluttering `com.mwang` if you ever build something else.

**Your decision.** If you move it, use IntelliJ's *Refactor > Move* rather than dragging
files - watch what it does to the `package` declaration and imports.

### 0.4 - Write a conventional `Main`

Your current `Main.java` is IntelliJ's template. It uses JDK 25's instance `main()` and
`IO.println` (JEP 512). Those are real, final features - not preview - and they're
genuinely nice for scratch files and teaching.

For this project, use the conventional form:

```java
public static void main(String[] args) { ... }
```

Why: an interviewer scanning your repo in 60 seconds should recognise everything
instantly. Novel syntax makes them stop and wonder if you know the standard form. Save
your novelty budget for the parts of the project that are actually interesting.

Have it print one line for now. It gets replaced in Phase 1.

### 0.5 - Add the test dependencies

You need `junit-jupiter` and `assertj-core`, both `test` scope.

I'm not giving you the XML. Go to [central.sonatype.com](https://central.sonatype.com),
search for each, and read what it gives you. Things to work out for yourself:

- What's the difference between `junit-jupiter`, `junit-jupiter-api` and
  `junit-jupiter-engine`? Which do you actually want, and why?
- Should you import the JUnit BOM in `<dependencyManagement>`, or just pin the version
  directly? Both are defensible at this project size - pick one and be able to justify it.

### 0.6 - Pin Surefire

Add `maven-surefire-plugin` to `<build><plugins>` with an explicit recent version.

Verify it actually ran your tests - the output must say `Tests run: N` with N > 0 once you
have a test. If it says `No tests to run`, something is wrong and a green build is lying
to you.

### 0.7 - Write your first test

Create `src/test/java/...` mirroring your main package structure (this mirroring is a
convention, and it matters - it's how you find the test for a class).

Write one trivially-true test that uses an **AssertJ** assertion, not a JUnit one, so you
confirm both dependencies resolve. Something like asserting a string you built has the
content you expect.

Run it three ways and confirm all three agree:
- IntelliJ's green gutter arrow
- `./mvnw test`
- `./mvnw clean test` (proves it works without stale build output)

### 0.8 - Clean up git tracking

Currently staged: `.idea/misc.xml`, `.idea/vcs.xml`, `.idea/encodings.xml`.

IntelliJ's default `.gitignore` ignores *some* `.idea` files but not others. For a solo
portfolio repo, ignore the whole directory - IDE configuration isn't portable, it changes
constantly, and it makes your diffs noisy for no benefit.

You'll need to both add `.idea/` to `.gitignore` **and** untrack what's already staged.
Work out the git command for that yourself - `git rm --cached` is the family you want, and
understanding the difference between `git rm` and `git rm --cached` is worth five minutes.

### 0.9 - README

Two lines minimum, per the roadmap. Say what the project is and what it will become. It
grows each phase.

### 0.10 - Push to GitHub

Create a **public** repo and push. Public matters: the roadmap is explicit that this
becomes an interview talking point, and a private repo can't be one.

Your first commit message: `Project scaffold`.

---

## Design decisions you make yourself

1. **Base package** - keep `com.mwang`, or move to `com.mwang.matchingengine`? What does
   each cost you later?
2. **JUnit BOM or direct version pin?** What does the BOM buy you at this project's size,
   and is that worth the extra indirection in the POM?
3. **Track `.idea/` or ignore it?** Some teams commit a curated subset (code style, run
   configs) so everyone shares settings. Why might that be right for a team and wrong for
   you here?

---

## Definition of done

- [ ] `mvnw`, `mvnw.cmd`, `.mvn/wrapper/` committed; `./mvnw -v` works
- [ ] `maven.compiler.release` set; old `source`/`target` removed
- [ ] `junit-jupiter` and `assertj-core` present at `test` scope
- [ ] `maven-surefire-plugin` pinned to an explicit version
- [ ] One passing test, using an AssertJ assertion, in `src/test/java`
- [ ] `./mvnw clean test` green from the terminal, reporting `Tests run: 1`
- [ ] `Main` uses conventional `public static void main(String[] args)`
- [ ] `.idea/` ignored and untracked
- [ ] README describing the project
- [ ] Pushed to a public GitHub repo

---

## Review checklist

What I'll look at in your diff:

- Is `target/` definitely not tracked?
- Are dependency versions pinned, not ranges or missing?
- Is the test in a package mirroring the class it tests?
- Does the test name describe a behaviour, not a method name?
- Is the README written for a reader who's never seen the project?
- Are commits atomic, or is this one giant "setup" commit?

---

## Interview questions

Answer these aloud before we open Phase 1.

1. What's the difference between `maven.compiler.source`, `target`, and `release`? Give a
   concrete bug that `release` prevents.
2. What does `test` scope actually do, and what breaks if you leave it off AssertJ?
3. What is a transitive dependency, and what happens when two of your dependencies need
   different versions of the same library?
4. Why pin plugin versions when Maven will happily pick one for you?
5. Why would a team target a lower Java release than the JDK they compile with?
6. What does `SNAPSHOT` mean, and why can't you depend on one in a released product?

---

## Common mistakes

- **Committing `target/`.** Check `git status` before your first commit.
- **A green build that ran zero tests.** Always read the Surefire output line.
- **Only running tests in the IDE.** IntelliJ has its own test runner and its own
  classpath assembly - it can pass when Maven fails. The terminal is the source of truth.
- **One enormous first commit.** Ten small commits tell a story; one blob doesn't.
- **Using `junit-jupiter-api` alone** and wondering why nothing runs - the API lets you
  compile tests, the engine runs them.
