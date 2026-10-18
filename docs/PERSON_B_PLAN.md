# Person B Plan: Load Simulation and Benchmarking Engineer (Esther Philemena S)

**Branch:** `person-b`  **Owns:** order generator, load simulator, correctness/parity test suite, JMH
benchmark suite, results, charts, JMH notes. This is the "prove it" lane: the project's central claim
rests on this work being rigorous. Ownership of every path is in `docs/MERGING_REVIEW_PLAN.md`.

Each day: Dhakshitha forwards `dayN-person-b.zip` after her turn. Run its `COMMANDS.md` **on your own computer
and GitHub account** so the commits are yours.

## Day-by-day

| Day | Tasks | Files (all yours) | Done when |
|---|---|---|---|
| 0 | Accept invite, clone, check tools, sign the scope lock, push, merge into `main` | `docs/SCOPE_LOCK.md` (your line only) | `main` shows your commit |
| 1 | Study JMH properly and write notes (warm-up, forks, dead-code elimination, `Blackhole`, `SampleTime` vs `Throughput`); seeded `OrderGenerator` | `docs/JMH_NOTES.md`, `sim/OrderGenerator.java`, `verify/OrderGeneratorTest.java` | Same seed gives the same orders (tested); `mvn clean test` green |
| 2 | `EngineContractTest` over any `MatchingEngine`: price priority, time priority at equal price, partial fills, no match when not crossing, sweeping multiple levels; one concurrent invariants test | `verify/EngineContractTest.java`, `verify/ConcurrentInvariantsTest.java` | Suite passes on the baseline (if not, tell A; do not touch her files) |
| 3 | `LoadSimulator`: N client threads, rate per thread, STEADY and BURST patterns, takes an engine factory; validate on the baseline first | `sim/LoadSimulator.java`, `sim/LoadPattern.java`, `verify/LoadSimulatorTest.java` | Simulator drives the baseline and reports submitted counts and throughput |
| 4 | Parity harness: same seeded sequence through both engines and compare trade lists exactly; for multi-producer runs, record the ring buffer's processing order, replay it through the baseline, compare. Extend the contract test to the lock-free engine | `verify/ParityTest.java`, `verify/TradeDiff.java`, update to `verify/EngineContractTest.java` | You can show a clear diff on a mismatch and a green result on a match |
| 5 | JMH `EngineBenchmark`: engine = baseline / lock-free; producers = 1, 2, 4, 8; modes `Throughput` and `SampleTime`; steady and burst. Smoke-run on both engines | `bench/EngineBenchmark.java` | Harness runs end to end on both engines |
| 6 | Full suite x3 on one machine (plugged in, other apps closed). Save raw JSON and `ENVIRONMENT.md`. Methodology review with A; freeze the numbers | `results/<date>/run1.json`, `run2.json`, `run3.json`, `ENVIRONMENT.md` | Results committed; run-to-run stability within the agreed tolerance |
| 7 | Chart script (Python + matplotlib) and PNGs: throughput vs producers, p50/p99/p99.9 baseline vs lock-free, steady vs burst. Write the "Results and methodology" section | `report/charts/make_charts.py`, `report/charts/*.png`, `report/sections/B_results_and_methodology.md` | Every chart has a one-line inference; numbers match the result files exactly |

## Your standing responsibilities
- You never edit A's files (`pom.xml`, `model/`, `baseline/`, `lockfree/`, `orderbook/`, docs other than
  your own). Need a dependency or an interface change? Message A.
- Before merging into `main`, `mvn clean test` must pass. If a test fails because of an engine bug, push
  to `person-b` only, tell A, and merge once it is fixed.
- Document every JMH setting you use and why.
- One benchmark run is not evidence: repeat it, and report variance.

## Notes for the hard parts
- **Fair comparison:** same machine, same JVM flags, same load pattern, same latency definition
  (`submit(order)` until that order is fully processed) for both engines.
- **Percentiles:** use `SampleTime` mode for p50/p99/p99.9; use `-rf json -rff <file>` for results.
- **Dead-code elimination:** consume results with `Blackhole` so the JIT cannot delete your benchmark.
