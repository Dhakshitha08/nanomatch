# Project Overview: Problem Statement and Solution

## Submitted abstract

> **15 | 24pw12, 24pw14 | Dhakshitha S, Esther Philemena S**
> **NanoMatch: A Lock-Free, Low-Latency Order-Matching Engine**
> **Tools:** Java, Custom Disruptor-style Ring Buffer, java.util.concurrent (Atomics/VarHandles), JMH (Java Microbenchmark Harness), JUnit, Maven/Gradle, Git/GitHub
>
> NanoMatch is a stock-exchange-style order-matching engine built from scratch in Java. It takes incoming buy and sell orders and pairs them by price-time priority: the best price wins, and among equal prices, the earlier order wins. This is the core component inside real exchanges like NSE and NASDAQ. The engine is built twice, and the two versions are compared. The first is a simple baseline that uses standard Java locks. The second is a lock-free rebuild that uses a ring buffer, cache-line padding to avoid hidden CPU contention, and allocation-free processing so that garbage-collection pauses don't cause latency spikes. A multi-threaded client simulator sends orders at increasing loads. JMH benchmarks then measure throughput and tail latency (p99 and p99.9) for both engines. Both engines are also checked to produce identical trades on identical input. The result is a measured demonstration of why lock-free design matters in high-frequency systems, not just a working engine.

(Build tool decision for the project: **Maven**.)

---

## 1. What problem are we solving?

Every stock exchange (NASDAQ, NSE, BSE) runs a **matching engine**: a system that continuously matches
incoming buy and sell orders by **price-time priority** (best price first; among equal prices, the order
that arrived first wins), at very high throughput and with very low, predictable latency.

A naive Java implementation using `synchronized` or `java.util.concurrent` locks is correct but too slow
under trading-level load. Lock contention between the threads producing orders and the thread matching
them becomes the bottleneck, and latency becomes unpredictable (occasional large spikes), which is
unacceptable where microseconds matter. Real trading systems use specialised lock-free data structures.
This project builds and benchmarks a working version of exactly that.

## 2. Precise problem statement

> Design and implement an in-memory order-matching engine in Java that matches buy/sell orders by
> price-time priority, using lock-free concurrent data structures instead of traditional locking, and
> demonstrate, through rigorous benchmarking, that it sustains high throughput (orders per second) with low
> and predictable tail latency (p99, p99.9) under concurrent load, compared to a traditional lock-based
> baseline implementation.

The deliverable is **not** just "an engine that works". It is an engine whose performance claims are proven
with real benchmark data against a lock-based baseline.

## 3. Solution, step by step

**Step 1: Baseline (lock-based).** A simple, correct engine using a `ReentrantLock`-protected order book.
Price-time priority must be exact. We expect it to struggle under load; it is what we prove the lock-free
version improves on.

**Step 2: Lock-free core engine.** The same behaviour, rebuilt with:
- a **pre-allocated ring buffer** (LMAX Disruptor pattern): producers claim slots with atomic sequence
  counters, so producer and consumer threads never block each other;
- **cache-line padding** to avoid false sharing (unrelated variables on the same CPU cache line causing
  hidden contention between threads);
- **no allocation in the hot path**, so garbage-collection pauses cannot cause latency spikes.

**Step 3: Order book.** A concurrency-friendly order book (price levels with FIFO queues) designed so the
single matching thread does minimal work per order.

**Step 4: Benchmarking (the actual proof).** JMH measures throughput and p50/p99/p99.9 latency at increasing
producer counts, for both engines, under identical load. See `BENCHMARKING_PLAN.md`.

**Step 5: Client simulation.** A load generator simulates many concurrent clients submitting orders at
configurable rates and patterns (steady and bursty), so results reflect a genuinely concurrent scenario.

## 4. What makes this senior-level

- **Correctness under concurrency is hard.** Price-time priority must hold exactly under heavy load, which
  needs careful reasoning about the Java Memory Model, not just "it compiles and runs".
- **Lock-free programming is a narrow, real expertise.** The Disruptor pattern and false-sharing avoidance
  are named techniques in professional low-latency engineering.
- **The benchmark methodology is part of the result.** Correct JMH use, tail latency instead of averages,
  and a fair baseline are what separate credible performance claims from hand-waving.
- **The result is falsifiable and demo-able.** A chart of p99 latency at increasing load, lock-based vs
  lock-free, is a concrete, defensible result.
