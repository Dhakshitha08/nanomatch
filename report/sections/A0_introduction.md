# NanoMatch: A Lock-Free, Low-Latency Order-Matching Engine

**Dhakshitha S (24pw12) and Esther Philemena S (24pw14)**

*Java, a custom Disruptor-style ring buffer, `java.util.concurrent` (atomics and VarHandles), JMH (Java Microbenchmark
Harness), JUnit, Maven, Git and GitHub.*

## Abstract

NanoMatch is a stock-exchange-style order-matching engine built from scratch in Java. It takes incoming buy and sell
orders and pairs them by price-time priority: the best price wins, and among equal prices, the earlier order wins.
This is the core component inside real exchanges like NSE and NASDAQ. The engine is built twice, and the two versions
are compared. The first is a simple baseline that uses standard Java locks. The second is a lock-free rebuild that uses
a ring buffer, cache-line padding to avoid hidden CPU contention, and allocation-free processing so that
garbage-collection pauses do not cause latency spikes. A multi-threaded client simulator sends orders at increasing
loads. JMH benchmarks then measure throughput and tail latency (p99 and p99.9) for both engines. Both engines are also
checked to produce identical trades on identical input. The result is a measured demonstration of why lock-free design
matters in high-frequency systems, not just a working engine.

## 1. The problem

A matching engine decides, for every incoming order, which resting orders it trades against. It must follow exact
rules (price priority, then time priority) and it must do so at very high rates with low and *predictable* latency:
in trading, one slow order is worse than a slightly slower average, because everything queued behind it waits too.

A straightforward Java implementation protects the order book with a lock. That is easy to get right, but under load
the threads that submit orders contend for the lock, and latency becomes unpredictable. Professional low-latency
systems avoid locks on the hot path. The goal of this project is not only to build such a design but to **measure**
honestly whether, and where, it helps.

## 2. What we built

- **A shared contract** (`MatchingEngine`): submit a limit order, receive a *ticket* (its position in the single
  processing sequence), and a way to know when it has been processed. Both engines implement it.
- **A lock-based baseline engine**: one lock around a simple order book. Easy to reason about, and the reference for
  correctness.
- **A lock-free engine**: many producer threads write into a pre-allocated ring buffer without locks; one matching
  thread takes orders out in sequence order, with an allocation-free, array-based order book and cache-line padding on
  the hot counters.
- **A client simulator** with steady, burst and maximum-speed load patterns, and reproducible seeded workloads.
- **Correctness checking at several levels**, including a parity check that replays a concurrent run's recorded order
  through the baseline and requires identical trades.
- **A JMH benchmark suite** measuring throughput and p50 / p99 / p99.9 latency for 1, 2, 4 and 8 producers, run three
  times, summarised by scripts, and charted.

## 3. Team and division of work

| | Person A: Dhakshitha S | Person B: Esther Philemena S |
|---|---|---|
| Engines | the shared model and interface, the baseline engine, the ring buffer, the lock-free engine, both order books | |
| Load and benchmarks | | the order generator, the client simulator, the JMH benchmark, the suite runner |
| Verification | unit tests of the engines, the ring buffer and the order books; the allocation test | the shared contract tests run against both engines, the concurrent-invariants test, the parity test and its diff tool, tests of the simulator and the benchmark workload |
| Evidence and analysis | padding and allocation evidence, independent review of the results | the three full benchmark runs, the analysis scripts, the charts, the methodology review |
| Report | the engine design and correctness chapter, introduction and conclusion | the results and methodology chapter |

Each file in the repository has exactly one owner, so the two branches (`person-a`, `person-b`) could be merged into
`main` without conflicts; the commit history in Appendix A shows who committed what.

## 4. How to read this report

The chapter *Engine design and correctness* explains how the two engines work, why the lock-free one is correct, and
what evidence exists for padding and allocation. The chapter *Results and methodology* presents the benchmark
measurements and what they show. The *Conclusion* draws the conclusions, including what the results do **not** show.
Appendix A lists the contributions of each author, and Appendix B how to reproduce the work.

## 5. Summary of the findings

- Both engines produce identical trades on identical input, including when many producer threads run at once.
- The lock-free engine's tail latency (p99) is clearly lower than the baseline's whenever more than one producer is
  active <!-- C3 --><!-- C4 -->.
- The result is not a universal win. In our measurements the lock-free engine's *throughput* was lower than the
  baseline's with 4 and 8 producers <!-- C2 -->, and its *median* latency was higher with 4 and 8 producers <!-- C5 --><!-- C6 -->. The design chapter separates what
  was tested from what is only a hypothesis; the results chapter gives the numbers.
