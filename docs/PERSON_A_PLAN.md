# Person A Plan: Core Engine Engineer (Dhakshitha S)

**Branch:** `person-a`  **Owns:** domain model, `MatchingEngine` interface, baseline engine, ring buffer,
lock-free engine, order book, `pom.xml`, project docs, tags, final report assembly.
This is the "hard concurrency" lane. Ownership of every path is in `docs/MERGING_REVIEW_PLAN.md`.

Each day: run the day's `COMMANDS.md` from `dayN-person-a.zip`, then forward `dayN-person-b.zip` to Esther.

## Day-by-day

| Day | Tasks | Files (all yours) | Done when |
|---|---|---|---|
| 0 | Create the repo, push `main`, `person-a`, `person-b`; add Esther as collaborator | whole initial repo | Esther can clone and push |
| 1 | Maven project (Java 17, JUnit 5, JMH, shade plugin); `Side`, `Order`, `Trade`; `MatchingEngine` interface; one trivial JMH benchmark | `pom.xml`, `model/*`, `smoke/SmokeBenchmark.java`, `engine/ModelSmokeTest.java` | `mvn clean test` green; `java -jar target/benchmarks.jar` runs; interface frozen |
| 2 | Lock-based order book (bids high to low, asks low to high, FIFO per price) and `LockBasedEngine` with `ReentrantLock`; partial fills; trade at resting order's price | `baseline/*`, `engine/BaselineEngineTest.java` | Baseline passes your tests and B's contract suite. Tag `baseline-freeze` |
| 3 | Ring buffer: pre-allocated power-of-two slots, CAS claim, per-slot publish flag, single consumer loop; minimal `LockFreeEngine` using the simple book | `lockfree/*`, `engine/RingBufferTest.java` | 4 producers x 100k orders: each processed exactly once |
| 4 | Fix any parity mismatch first; then finalize the concurrency-aware order book | `orderbook/*`, fixes in `lockfree/*`, `engine/OrderBookTest.java` | Parity green for 5 seeds x 100k orders. Tag `parity-verified` |
| 5 | Cache-line padding on hot sequences; allocation-free hot path (pooled order slots, primitives, preallocated trade output); `-prof gc` evidence. Re-run the whole suite after every change | `lockfree/*`, `orderbook/*` | Full suite green; allocation about zero. Tag `lockfree-freeze` |
| 6 | Support B's benchmark runs; fix anything they surface; GC profile run | `results/<date>/gc-profile-A.txt` plus fixes in your packages | Benchmark-ready engine; methodology review done with B |
| 7 | "Engine design and correctness" and "What production would require" sections; README run instructions; assemble `report/REPORT.md`; final tag | `report/sections/A_design_and_correctness.md`, `report/REPORT.md`, `README.md` | Fresh clone works. Tag `v1.0` |

## Your standing responsibilities
- You update the `README.md` status table at the end of each day.
- You create all tags, after both lanes are merged and `main` is green.
- You never edit B's files. If a test of B's looks wrong, message her.
- The `MatchingEngine` interface is frozen after Day 1. Changing it later needs both of you to agree first.
- Before merging into `main`, `mvn clean test` must pass.

## Notes for the hard parts
- **Single writer:** only the consumer thread touches the order book. That is what makes matching safe without locks.
- **Publishing a slot:** the producer fills the slot completely, then sets its availability flag with a
  release-style write (`VarHandle.setRelease` / `AtomicLongArray.lazySet`); the consumer reads it with an acquire-style read.
- **Padding is only worth claiming if measured:** do a quick JMH A/B with and without padding and keep
  the result for the report.
