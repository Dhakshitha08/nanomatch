# Engine design and correctness

*Written by Person A (Dhakshitha S). Numbers in this chapter that come from measurements are marked in the text where
they come from; the benchmark measurements themselves are in the next chapter.*

## 1. The contract both engines implement

Both engines implement one interface, `MatchingEngine`. The matching rules are written into it and are identical for
both engines:

- only limit orders; an order that is not fully matched rests on the book until matched;
- a buy crosses when its price is at or above the best (lowest) resting sell, a sell crosses when its price is at or
  below the best (highest) resting buy;
- **price priority**: the incoming order trades against the best opposite price first, then the next best, while it
  still crosses and has quantity left;
- **time priority**: at the same price, the earlier resting order trades first (first in, first out);
- the trade price is the price of the **resting** order;
- prices and quantities are integers (price in ticks); no floating point is used anywhere in matching.

Many threads may call `submit` at once. The engine puts every order into **one single sequence** and matches strictly in
that sequence. `submit` returns the order's zero-based position in the sequence, its **ticket**, and the order with
ticket *t* is fully processed once `processedCount() > t`. This one idea does three jobs: it defines the arrival order
(there is exactly one), it lets a benchmark time "submit until processed" the same way for both engines, and it lets a
test record the order a concurrent run was processed in and replay it through the other engine (Section 6).

Trades are reported through a `TradeSink` with primitive arguments (no allocation), one trade at a time and never from two
threads at once, in the order the trades happen.

## 2. The baseline engine

`LockBasedEngine` wraps a simple order book in one `ReentrantLock`. Each side of the book is a `TreeMap` from price to a
first-in-first-out queue of resting orders, sorted so the best price comes first. A call to `submit` takes the lock,
takes the next ticket, matches the order, and releases the lock, so an order is already processed when `submit` returns.
Lock acquisition order is the arrival order.

It is deliberately simple and allocates freely (boxed prices, queue nodes, one object per resting order). That is fine:
its job is to be obviously correct and to be the thing the lock-free engine is measured against. It uses a standard,
non-fair lock, which is the usual default; Section 7 explains why that matters when reading the results.

## 3. The lock-free engine

### 3.1 Architecture

```
producer threads  --claim, fill, publish-->  ring buffer  --consume-->  one matching thread  -->  order book, trades
   (many)              (no locks)           (pre-allocated)                 (the only writer)
```

Producers never take a lock and never touch the order book. One matching thread takes orders out in sequence order and
is the **only** thread that ever reads or writes the order book (the "single writer" principle), so the book needs no
synchronisation at all.

### 3.2 The ring buffer protocol

The ring buffer is a fixed array of reusable slots (a power of two in size). The *n*-th order lives in slot
*n* mod size. Four steps, each with a precise memory-ordering job:

1. **Claim.** A producer reserves the next sequence number with a compare-and-set (CAS) on a shared counter. Exactly
   one producer wins each number; a producer that loses simply retries, and no producer ever waits for another. A number
   can only be claimed while its slot is free, that is, while it is less than the consumed count plus the ring size. If
   the ring is full the producer waits for the matching thread (back-pressure).
2. **Fill.** The producer writes the order into its slot with plain writes.
3. **Publish.** The producer stores the slot's *round number* (sequence divided by ring size) into a per-slot
   availability flag with **release** semantics. Producers finish in any order, so the consumer cannot just look at how
   many numbers were claimed: a claimed slot may still be half written. It looks at the flag, which equals the expected
   round only once the slot is completely written.
4. **Consume.** The matching thread reads the flag with **acquire** semantics, processes the order, then advances the
   consumed counter with **release** semantics, which frees the slot for reuse.

Why it is correct: the producer's release store of the flag happens after its slot writes, and the consumer's acquire
load of the flag happens before its slot reads, so the consumer always sees a complete order. Symmetrically, the
consumer's release store of the consumed counter happens after it has finished reading the slot, and a producer reads
that counter before overwriting the slot, so a slot is never overwritten while it is being read. The counter that tells
callers how many orders are processed is the same consumed counter, advanced only after the order's trades have
been reported, so a thread that sees `processedCount() > t` also sees those trades.

### 3.3 Cache-line padding

A processor moves memory in 64-byte cache lines. If two unrelated variables share a line and two threads keep writing
them, the line bounces between the cores even though the threads never touch each other's data (*false sharing*). In the
ring buffer, the claim counter is written by every producer and the consumed counter by the matching thread after every
order. Each is therefore stored in its own padded cell: a `long` in the middle of an array with 64 bytes of unused space
on each side, accessed through a `VarHandle`. Padding can be switched off with a JVM property
(`-Dnanomatch.padding=false`), which allows it to be measured instead of assumed.

**Measured effect of padding** (ring buffer with several producers and a do-nothing consumer, `PaddingBenchmark`):

[[FILL: padding on vs off, orders per second, from `python report/evidence_numbers.py --date 2026-10-24`, with the
number of producers used and the machine (logical cores)]]

[[FILL: one sentence stating plainly what that shows: padding helped / made no measurable difference / hurt. Report
it as measured, whatever it is.]]

### 3.4 An allocation-free hot path

Garbage-collection pauses are caused by allocation, so a path that allocates nothing per order cannot be paused by the
collector. The lock-free path was designed to allocate nothing:

- the ring buffer's slots are created once and then only overwritten;
- the **array-based order book** replaces the tree: a price level is an array slot (price minus the lowest price), the
  queue at a level is a linked list threaded through pre-allocated arrays, and resting orders come from a fixed pool
  with a free list. There are no boxed numbers, no objects per order and no tree;
- trades are reported through the primitive `TradeSink`.

This has a price. The price range and the number of resting orders are fixed when the engine is created, an order
outside the range is rejected, and a full pool stops the engine visibly instead of growing (growing would allocate).

**Evidence.** An automated test counts the bytes allocated by the producer thread and by the matching thread while
500,000 orders are processed, and requires each to stay below 16 KB (a single 16-byte object per order would be 8 MB). A
companion test checks that the baseline does allocate, so the counter is known to see allocation. In addition, the
benchmark was run with JMH's GC profiler (`-prof gc`), which reports bytes allocated per operation:

[[FILL: `gc.alloc.rate.norm` in bytes per operation for the baseline and for the lock-free engine, from
`python report/evidence_numbers.py --date 2026-10-24`, and the producer count used]]

## 4. The two order books

| | Baseline book | Array-based book |
|---|---|---|
| Price levels | `TreeMap` with boxed prices | one array slot per price tick |
| Queue at a level | `ArrayDeque` | linked list through pre-allocated arrays |
| Resting orders | one object each | slots in a fixed pool |
| Finding the next best price | tree lookup | scan over the (few) empty levels |
| Allocation | per order | none |
| Limits | none | fixed price range and pool size |

The array book's linear scan for the next best price is cheap when the price range is tight and would be the first thing
to replace (with a bitmap) for a wide range. For that reason the benchmark configures the range to match its workload.

## 5. How correctness was established

Five layers of automated tests, 115 tests in total, all passing at the final version:

| Layer | What it checks | Tests |
|---|---|---|
| Unit tests (Person A) | the interface and model, the baseline engine, the ring buffer (including wrap-around and blocking when full), the lock-free engine, the array book, and the allocation claim | 54 |
| Shared contract tests (Person B) | the same price-time-priority scenarios, written once against the interface and run against **both** engines (14 scenarios each) | 28 |
| Concurrent invariants (Person B) | with several threads submitting at once: every order gets one ticket, every trade respects both orders' limits, nobody is overfilled, and replaying the recorded order gives identical trades | 1 |
| Differential tests of the books (Person A) | long random streams through the array book and the baseline book, requiring the same trades and the same book state after **every** order | within the 54 above |
| Parity tests (Person B) | the lock-free engine against the baseline on identical input, single producer and many producers (Section 6) | 4 |

The remaining tests cover the simulator, the order generator, the diff tool and the benchmark workload.

## 6. The parity check

*Identical input must give identical trades.* For a single producer this is direct: the same seeded sequence goes to
both engines in the same order, and the two trade lists must be identical, as must the final book (best prices and
resting quantities).

With many producers the arrival order depends on thread timing, so two runs can never be compared directly. Instead the
lock-free engine runs with several concurrent producers while the test records which order received which ticket. The
ticket **is** the processing order, so replaying exactly that sequence, one order at a time, through the baseline must
give exactly the same trades. A diff tool reports the index of the first trade that differs. The check was run with 2,
4 and 8 producers, with ring buffers as small as 2 slots (which forces constant waiting and wrap-around), with maximum-speed
and bursty load, and repeated several times because a concurrency fault can show up only occasionally.

**What parity proves and what it does not.** It proves that, for the inputs tried, the ring buffer delivers every order
exactly once in a consistent sequence and that the two books agree. It is evidence, not a proof of correctness for all
interleavings. The memory-ordering argument in Section 3.2 is the reasoning that covers the cases the tests cannot
enumerate.

## 7. Interpreting the benchmark results

The measurements are in the next chapter. This section is the place for explanations, and each is marked **tested**
(with the evidence) or **hypothesis** (not tested). A hypothesis is a candidate explanation, not a finding.

### 7.1 Lower throughput of the lock-free engine with 4 and 8 producers

*Data:* see the next chapter: the lock-free engine sustains more orders per second with 1 and 2 producers and fewer
with 4 and 8.

*One explanation was ruled out by the data itself:* "the single matching thread is the bottleneck". With one producer the
same matching thread sustained a higher rate than the total the engine reaches with 4 or 8 producers, so the matching
thread is not what limits it. <!-- C8 -->

*Evidence from the ring buffer alone.* `PaddingBenchmark` runs the ring buffer with a consumer that does no matching at
all. Its total producer throughput at 1, 2, 4 and 8 producers:

[[FILL: the ring-scaling numbers from `python report/evidence_numbers.py --date 2026-10-24`]]

[[FILL: state what the numbers show, then the explanation with its status. If producer throughput also falls with more
producers in that benchmark, write: "Tested: the limit is in claiming and publishing slots (many threads competing for
the same counter and cache lines), not in matching." If it does not fall, write that the cause is not in the ring buffer
alone and label whatever explanation you give as a hypothesis.]]

### 7.2 Higher median latency of the lock-free engine with 4 and 8 producers

*Data:* see the next chapter: the baseline's p50 is lower with 4 and 8 producers, for steady <!-- C5 --> and burst <!-- C6 --> latency.

*Hypothesis (not tested):* the standard lock is not fair, so a thread that has just released it can often take it again
at once. Most threads then get through quickly (a low median) while some are made to wait a long time (a bad tail). The
lock-free engine processes orders strictly in arrival order, so every order, including those in the middle of the
distribution, waits behind the orders ahead of it, which raises the median and flattens the tail. A fair-lock baseline
would test this and was not run.

### 7.3 Lower tail latency of the lock-free engine with several producers

*Data:* see the next chapter: clearly lower p99 for steady <!-- C3 --> and burst <!-- C4 --> latency with 2, 4 and 8 producers.

*Measured:* the baseline allocates on every order and the lock-free path does not (Section 3.4).

*Hypotheses (not tested individually):* the lock-based baseline's slow outliers come from threads waiting for the lock
(including a thread that holds the lock being descheduled by the operating system), and possibly from garbage-collection
pauses caused by its allocation. The benchmarks measure the outcome, not which of these causes produced it.

## 8. Limitations of the engines

- A single instrument, in memory only, on one machine; limit orders only, with no cancel or modify.
- The lock-free engine's book has a fixed price range and a fixed pool of resting orders; an order outside the range is
  rejected and a full pool stops the engine visibly.
- There is one matching thread by design, which caps the rate at what one core can match.
- Producers are lock-free in the CAS sense, but the design is not lock-free end to end: a producer that is descheduled
  between claiming a slot and publishing it holds up every order behind its slot, and a full ring makes producers wait
  for the matching thread.
- The matching thread waits for work by spinning and then yielding, which keeps latency low at the cost of using a core
  while idle.

## 9. What a production system would additionally require

These are outside the scope of this project and were not attempted.

- **Persistence and crash recovery.** A real exchange cannot lose accepted orders. This engine is in memory only. A
  production design would journal every order to durable storage before acknowledging it, and rebuild the book from
  the journal after a crash.
- **Network protocol and gateways.** Real systems expose a protocol such as FIX over a network, with session handling,
  authentication, and separation of the gateways from the matching engine. Here everything runs in one process.
- **Many instruments.** Real exchanges match thousands of instruments. The usual approach is one matching thread per
  instrument (or group of instruments), not one global thread.
- **Richer order handling.** Cancel and modify, market and stop orders, time-in-force rules, and pre-trade risk checks.
- **Regulatory and audit logging.** An immutable record of every order and every match, with timestamps from a trusted
  clock.
- **Operations.** Market-data publication of the book and the trades, monitoring and alerting, controlled start-up and
  fail-over, and capacity planning.
- **Platform tuning.** Pinning threads to dedicated cores, NUMA awareness, huge pages, and JVM and operating-system tuning
  for latency, none of which was done here.
