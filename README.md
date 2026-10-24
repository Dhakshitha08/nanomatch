# NanoMatch: A Lock-Free, Low-Latency Order-Matching Engine

NanoMatch is a stock-exchange-style order-matching engine written from scratch in Java. It pairs incoming
buy and sell orders by **price-time priority**: the best price wins, and among equal prices the earlier
order wins.

The engine is built **twice** and the two versions are compared:

1. **Baseline**: a simple, correct engine using standard Java locks.
2. **Lock-free**: a rebuild using a Disruptor-style ring buffer, cache-line padding, and an
   allocation-free hot path.

A multi-threaded client simulator drives both engines at increasing load. JMH measures throughput and
tail latency (p99, p99.9). Both engines are also verified to produce **identical trades on identical input**.

**Team:** Dhakshitha S (24pw12) and Esther Philemena S (24pw14)

**Stack:** Java 17+ (builds on 17 to 25), Maven, JUnit 5, JMH, Git/GitHub

## Docs (read in this order)

| File | What it is |
|---|---|
| [PLAN.md](PLAN.md) | The 1-week plan: day-by-day tasks, owners, checkpoints |
| [docs/PROJECT_OVERVIEW.md](docs/PROJECT_OVERVIEW.md) | Problem statement and solution walkthrough |
| [docs/SCOPE_LOCK.md](docs/SCOPE_LOCK.md) | What is in and out of scope, with both sign-offs |
| [docs/PERSON_A_PLAN.md](docs/PERSON_A_PLAN.md) | Person A (core engine): tasks and files, day by day |
| [docs/PERSON_B_PLAN.md](docs/PERSON_B_PLAN.md) | Person B (load + benchmarks): tasks and files, day by day |
| [docs/MERGING_REVIEW_PLAN.md](docs/MERGING_REVIEW_PLAN.md) | Branches, file ownership, conflict-free merge protocol, checkpoint reviews |
| [docs/BENCHMARKING_PLAN.md](docs/BENCHMARKING_PLAN.md) | JMH methodology, fair-comparison rules, reproducibility |
| [docs/JMH_NOTES.md](docs/JMH_NOTES.md) | JMH notes used for the benchmark design |

## Branches

`main` (always green, tagged at checkpoints), `person-a` (Dhakshitha), `person-b` (Esther).

## Repo layout

```
src/main/java/com/nanomatch/
    model/        Order, Trade, Side, TradeSink, EngineFactory, MatchingEngine   (Person A)
    smoke/        JMH smoke test from Day 1                                       (Person A)
    baseline/     lock-based engine                                               (Person A)
    lockfree/     ring buffer + lock-free engine                                  (Person A)
    orderbook/    concurrency-aware order book                                    (Person A, reviewed by B)
    sim/          order generator + client/load simulator                         (Person B)
    bench/        JMH benchmark harness                                           (Person B)
src/test/java/com/nanomatch/
    engine/       unit tests for the engine classes                               (Person A)
    verify/       contract, concurrency, parity and simulator tests               (Person B)
results/          frozen benchmark outputs, versioned by date
report/           sections/ (one file per person), charts/, REPORT.md
docs/             team documents
```

## Status

| Day | Theme | Status |
|---|---|---|
| 0 | Repo + docs + scope lock | done |
| 1 | Skeleton, JMH, domain model | done |
| 2 | Baseline engine + tests (Checkpoint 2) | done |
| 3 | Ring buffer + load simulator | done |
| 4 | Parity check (Checkpoint 3) | in progress |
| 5 | Hardening + lock-free freeze (Checkpoint 4) | not started |
| 6 | Full benchmarks (Checkpoint 5) | not started |
| 7 | Report + final review (Checkpoint 6) | not started |

## How to build and test

```
mvn clean test                 # compile and run all tests
mvn clean package              # also builds target/benchmarks.jar
java -jar target/benchmarks.jar SmokeBenchmark -f 1 -wi 1 -i 2 -w 1s -r 1s
```

The full benchmark and correctness instructions are filled in on Day 7.
