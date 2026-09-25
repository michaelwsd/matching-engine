# Phase 3b - Network Order Entry

**Estimate:** 3-4 days
**Goal:** replace simulated in-process clients with real ones connecting over TCP, without
changing anything downstream of the gateway.

This phase is optional in the sense that Phases 4 and 5 don't depend on it. It's here
because "how do orders actually reach the engine" is a fair interview question, and
"they're constructed in a for-loop" is a weak answer.

It also tests something the architecture has been claiming since Phase 3: that the gateway
is a genuine boundary. If adding a completely new ingress path requires touching the
engine, the boundary was in the wrong place.

---

## Objectives

External clients connect over TCP, send orders in a documented text protocol, and receive
acknowledgements or rejections - feeding the *same* `BlockingQueue` the simulated producers
use, with the matcher and engine entirely unaware anything changed.

The in-process producers stay. They're what the determinism test uses, and routing that
test through sockets would make it slow and flaky for no gain.

---

## Trading concepts

### How orders really reach an exchange

Two protocol families dominate:

**FIX** (Financial Information eXchange) - the industry standard since the 90s. Text,
tag-value pairs separated by a control character:

```
8=FIX.4.4|35=D|55=AAPL|54=1|38=100|44=150.25|
   ^        ^     ^     ^    ^      ^
 version  NewOrder symbol Buy  qty   price
```

Verbose and slow to parse, but universal - every venue and every broker speaks it. If you
work in trading you will meet FIX.

**Binary protocols** - NASDAQ's OUCH (order entry) and ITCH (market data), or SBE (Simple
Binary Encoding). Fixed-width binary fields, no parsing to speak of - you cast a byte
buffer to a struct. Used where FIX's parsing cost is unacceptable.

You're building neither. A simple line protocol teaches the same lessons - framing,
parsing, validation, acknowledgement - without a week of spec reading.

### Order entry vs market data: TCP vs UDP

A real venue splits these, and the transport choice differs:

| | Order entry | Market data |
|---|---|---|
| Transport | **TCP** | **UDP multicast** |
| Shape | one connection per client | one publisher, thousands of listeners |
| Delivery | guaranteed, ordered | best-effort, can drop |

Order entry is TCP because losing an order is unacceptable - you need the retransmission
and ordering guarantees.

Market data is multicast because the exchange publishes *once* and every subscriber
receives it, rather than the exchange sending 5,000 copies. That's the only way the
economics work at scale. The cost is that UDP can drop packets - so feeds carry sequence
numbers, and when a consumer notices a gap it recovers from a separate snapshot channel.
That's the snapshot-vs-incremental distinction from Phase 4, and now you know why it
exists.

You're building order entry, so TCP.

### Session management

Real order-entry protocols have a session layer above the messages:

- **Logon** - authenticate, agree sequence numbers
- **Heartbeats** - detect a dead peer. TCP alone won't tell you the other end is gone; a
  connection can sit "open" for a long time after the client's machine has died.
- **Sequence numbers** - every message numbered, so a gap is detectable
- **Resend requests** - recover missed messages after a reconnect

You're skipping all of it. But know it exists, and know *why*: without heartbeats you
cannot distinguish a quiet client from a dead one, and without sequence numbers you cannot
tell that you missed message 47.

### Acknowledgements

When a client sends an order, what does it get back, and *when*?

- Ack on receipt - "I have your bytes"
- Ack on acceptance - "it's queued and validated"
- Ack on match - "here's what happened"

These are meaningfully different promises, and the client's risk depends on which one it
got. A market maker that thinks its quote is live when it's still sitting in a queue is
carrying risk it doesn't know about.

This is your design decision below, and it's a better interview answer than most people
have.

---

## Java concepts

### Sockets

```java
try (ServerSocket server = new ServerSocket(8081)) {
    while (running) {
        Socket client = server.accept();   // blocks until someone connects
        // handle client
    }
}
```

`ServerSocket` listens on a port. `accept()` blocks and returns a `Socket` per connection.
Each `Socket` gives you an `InputStream` and an `OutputStream` - from there it's ordinary
stream I/O.

### Thread-per-connection is viable again

Historically, one thread per connection didn't scale. Each platform thread costs ~1MB of
stack, so 10,000 connections meant 10GB and a scheduler in distress. The industry moved to
NIO selectors and event loops - much better scaling, much harder code: callbacks, state
machines, and no way to just write straight-line logic.

**Virtual threads change this back.** A virtual thread costs a few hundred bytes and parks
cheaply when blocked. So:

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    while (running) {
        Socket client = server.accept();
        executor.submit(() -> handleClient(client));
    }
}
```

You get straight-line blocking code *and* scale to tens of thousands of connections. This
is the single most compelling use case for virtual threads, and it's why Java 21 mattered.

Be able to explain this trade-off - it's a strong answer to "why virtual threads?" that
goes beyond reciting the feature list.

### TCP is a byte stream, not a message stream

**This is the bug everyone writes once.** TCP guarantees bytes arrive in order. It does
*not* preserve your message boundaries.

Send two orders quickly and one `read()` may return both, or one and a half. Send one
large order and it may arrive in three pieces. There is no "one read = one message".

So you need **framing** - a rule for finding message boundaries in the stream:

- **Delimiter** - messages end with `\n`. Simple, requires escaping if the delimiter can
  appear in the payload.
- **Length prefix** - send 4 bytes of length, then that many bytes. What binary protocols
  do.

For a line protocol, `BufferedReader.readLine()` handles delimiter framing for you - it
buffers partial reads and returns complete lines. Use it, but understand what it's doing,
because it's the thing you'd have to write yourself in a binary protocol.

### Always specify the charset

```java
new InputStreamReader(socket.getInputStream())                    // WRONG
new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)   // right
```

The first uses the platform default charset, which differs between machines. Your Mac and a
Linux server can disagree about what bytes mean, and it will work perfectly in testing and
corrupt data in production.

Never rely on the platform default for anything crossing a machine boundary. Same applies
to files in Phase 4.

### Timeouts

```java
socket.setSoTimeout(30_000);   // read() throws SocketTimeoutException after 30s
```

Without a read timeout, a connection where the client has vanished - laptop closed, cable
pulled - holds a thread forever. TCP won't necessarily tell you; there's no traffic to
fail on. Timeouts are how you reclaim dead connections.

This is the mechanism heartbeats build on: expect traffic within N seconds, and treat
silence as death.

### Shutting down an accept loop

`accept()` blocks. A `volatile boolean running` won't help - you're not at the top of the
loop to check it.

The idiom: **close the `ServerSocket` from another thread.** The blocked `accept()` throws
`SocketException`, which you catch and treat as a shutdown signal. That's expected control
flow, not an error - and your logging should reflect that, or you'll ship a stack trace on
every clean shutdown.

### Never trust the network

Everything arriving on a socket is hostile until proven otherwise. Clients send malformed
lines, negative quantities, absurd prices, 10MB of garbage with no newline, then disconnect
mid-message.

Your parser must handle all of it without taking down the server. One misbehaving client
must not affect the others, and must never affect the matcher.

This is Phase 2's "validate at the boundary" principle with real teeth. The socket is now
the boundary.

> **Security note:** this is an unauthenticated plaintext socket, appropriate for a local
> simulation and nothing else. Bind it to localhost. Don't expose it to a network, and say
> so in the Javadoc so nobody mistakes it for something production-shaped.

---

## Prerequisites

- Phase 3 gate closed: gateway, queue, matcher thread, determinism test all green

---

## Build tasks

### 3b.1 - Design the protocol

Write it down before coding, in a markdown file or class Javadoc. Decide:

- Order entry format - something like `NEW <side> <qty> <price> [account]`
- Cancel format
- Response format for accept, reject, and fill notification
- What terminates a message
- What happens on a malformed line

Keep it human-readable so you can test with `telnet` or `nc`. Being able to demo your
exchange by typing at it is worth a lot when showing this to someone.

### 3b.2 - The accept loop

`ServerSocket`, accept loop, one virtual thread per connection.

Bind to localhost explicitly. Make the port configurable - and for tests, port `0` tells the
OS to pick a free one, which you then read back with `getLocalPort()`. Hardcoded ports in
tests fail on busy machines and can't run in parallel.

### 3b.3 - Connection handler

Per connection: read lines, parse, validate, construct an `Order`, submit to the existing
`OrderGateway`. Reply with an ack or a rejection.

Requirements:

- UTF-8 explicitly, both directions
- A read timeout
- try-with-resources so the socket always closes
- A parse failure rejects that message and keeps the connection alive - one bad line is not
  a reason to disconnect
- An exception in one connection's handler cannot affect any other connection

### 3b.4 - Acknowledgements

Implement the ack semantics you chose. The client must be able to tell the difference
between accepted and rejected, and a rejection must say why.

### 3b.5 - Fill notifications

If a client's order trades, tell it. This is where Phase 3's "how do trades leave the
matcher thread" decision gets its second consumer - and Phase 5's strategy will be the
third.

Writing to a socket from the matcher thread is exactly the mistake Phase 3 warned about: a
slow or dead client would stall matching for everyone. Route it properly.

### 3b.6 - Graceful shutdown

Close the `ServerSocket` to break the accept loop, then let in-flight connections finish.
No stack traces on a clean shutdown.

### 3b.7 - Tests

- **Framing test** - the important one. Send a message split across two writes with a
  delay, and one write containing two complete messages. Both must parse correctly. This is
  the bug this phase exists to teach.
- Malformed input gets a rejection, connection survives
- Two concurrent clients don't interfere
- Order submitted over TCP appears on the book
- Shutdown terminates cleanly with clients connected

Use an ephemeral port. Never `Thread.sleep()` to wait for a connection - use latches, as in
Phase 3.

### 3b.8 - Keep the in-process path

The simulated producers and the determinism test stay exactly as they are. Two ingress
paths, one queue.

If adding TCP required changing the engine or the matcher, stop and work out why - the
boundary was drawn wrong, and that's a more valuable finding than the feature.

---

## Design decisions you make yourself

1. **Text or length-prefixed binary?** Text is debuggable with `nc`; binary is what real
   venues use. What does each cost?
2. **Ack on queue-accept or on match?** What does the client believe in each case, and what
   risk is it carrying if it's wrong?
3. **Queue full - what does the TCP client see?** Block the connection thread, reject with a
   "busy" response, or disconnect? This is Phase 3's backpressure decision, now with a real
   client on the other end.
4. **Thread-per-connection or NIO?** You'll pick thread-per-connection with virtual threads
   - be able to say why that's now defensible when it wasn't in 2015.
5. **How does a client identify itself?** You need an account id for self-trade prevention.
   Per-connection at logon, or per-message?

---

## Definition of done

- [ ] Protocol documented before implementation
- [ ] `ServerSocket` accept loop, one virtual thread per connection, bound to localhost
- [ ] Orders arriving over TCP reach the existing `OrderGateway` unchanged
- [ ] **Engine, matcher and book untouched by this phase**
- [ ] Acks and rejections returned, rejections state a reason
- [ ] UTF-8 specified explicitly on both directions
- [ ] Read timeout set
- [ ] Malformed input rejected without dropping the connection or affecting other clients
- [ ] Framing test: split message and coalesced messages both parse
- [ ] Graceful shutdown, no stack traces
- [ ] Tests use an ephemeral port, no sleeps
- [ ] In-process producers and the determinism test still green
- [ ] You can demo it by typing orders into `nc localhost <port>`

---

## Review checklist

- Any charset left to the platform default?
- Any socket, reader or writer not in try-with-resources?
- Does one client's exception reach another client, or the matcher?
- Is `SocketException` on shutdown handled as expected flow rather than logged as an error?
- Is the accept loop's shutdown actually tested with a connection open?
- Are ports hardcoded anywhere in tests?
- Does anything write to a socket from the matcher thread?
- Is the "localhost only, unauthenticated" constraint documented?
- Did the engine change? It shouldn't have.

---

## Interview questions

1. Why does TCP not preserve message boundaries? What did you do about it?
2. Why is order entry TCP and market data UDP multicast?
3. Thread-per-connection was abandoned as an architecture. Why is it defensible again?
4. Your client's laptop dies mid-session. How does your server find out, and how long does
   it take?
5. What does your ack actually promise the client? What is it *not* promising?
6. The queue is full and a TCP client sends an order. Walk me through what happens.
7. How do you shut down a thread blocked in `accept()`?
8. What's FIX, and why do venues offer binary protocols alongside it?
9. What changed in the engine when you added networking? Why is that the answer you want?

---

## Common mistakes

- **Assuming one `read()` is one message.** The defining bug of network programming. Test
  it deliberately - split a message across two writes and prove it still works.
- **Platform default charset**, which works on your machine and corrupts data on the server.
- **No read timeout**, leaking a thread per dead client.
- **Disconnecting on a malformed message** when a rejection would do.
- **Writing to sockets from the matcher thread**, letting one slow client stall the exchange.
- **Hardcoded port in tests**, failing whenever the port is busy or two tests run at once.
- **Logging a stack trace on clean shutdown**, training everyone to ignore stack traces.
- **Letting one client's exception kill the accept loop.**
- **Changing the engine to accommodate the network.** If you need to, the boundary was wrong.
