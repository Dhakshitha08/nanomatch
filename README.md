# NanoMatch: A Lock-Free, Low-Latency Order-Matching Engine

NanoMatch is a stock-exchange-style order-matching engine written from scratch in Java. It pairs incoming
buy and sell orders by **price-time priority**: the best price wins, and among equal prices the earlier
order wins.

The engine is built **twice** and the two versions are compared:

1. **Baseline**: a simple, correct engine using a standard Java lock.
2. **Lock-free**: a rebuild using a Disruptor-style ring buffer, cache-line padding, an allocation-free
   array-based order book, and a single matching thread.

A multi-threaded client simulator drives both engines at increasing load. JMH measures throughput and
tail latency (p99, p99.9). Both engines are also verified to produce **identical trades on identical input**.

**Team:** Dhakshitha S (24pw12) and Esther Philemena S (24pw14)

**Stack:** Java 17+ (builds on 17 to 25), Maven, JUnit 5, JMH, Git/GitHub

## The report

The full report is [report/REPORT.md](report/REPORT.md): the design and why it is correct, the benchmark method, the
results with charts, and the conclusions, including what the results do **not** show.

In short (numbers and evidence are in the report):

- both engines produce identical trades on identical input, also under concurrent load;
- with more than one producer, the lock-free engine's p99 latency is clearly lower than the baseline's;
- it is not a universal win: in these measurements the lock-free engine's throughput was lower with 4 and 8
  producers, and its median latency was higher with 4 and 8 producers.

## Build, test, benchmark

```
mvn clean test                 # compile and run all 115 tests
mvn clean package              # also builds target/benchmarks.jar

# quick check that the benchmark runs (short settings, not results)
java -jar target/benchmarks.jar EngineBenchmark -p engine=lockfree -t 2 -f 1 -wi 1 -i 2 -w 1s -r 1s

# the full suite, as used for the report (about 20 minutes per run; keep the machine otherwise idle)
python results/run_suite.py --out results/<date> --run 1        # and --run 2, --run 3
python results/analyze_runs.py --dir results/<date> --runs 1 2 3
python report/charts/make_charts.py --date <date> --check
python report/assemble_report.py
```

The frozen results used in the report are in `results/2026-10-24/` (raw JMH JSON and console logs of every run, the
environment description, the summary, and both authors' independent reviews).

## Docs

| File | What it is |
|---|---|
| [PLAN.md](PLAN.md) | The 1-week plan: day-by-day tasks, owners, checkpoints |
| [docs/PROJECT_OVERVIEW.md](docs/PROJECT_OVERVIEW.md) | Problem statement and solution walkthrough |
| [docs/SCOPE_LOCK.md](docs/SCOPE_LOCK.md) | What is in and out of scope, with both sign-offs |
| [docs/PERSON_A_PLAN.md](docs/PERSON_A_PLAN.md) | Person A (core engine): tasks and files, day by day |
| [docs/PERSON_B_PLAN.md](docs/PERSON_B_PLAN.md) | Person B (load + benchmarks): tasks and files, day by day |
| [docs/MERGING_REVIEW_PLAN.md](docs/MERGING_REVIEW_PLAN.md) | Branches, file ownership, conflict-free merge protocol, checkpoint reviews |
| [docs/BENCHMARKING_PLAN.md](docs/BENCHMARKING_PLAN.md) | JMH methodology, fair-comparison rules, reproducibility |
| [docs/JMH_NOTES.md](docs/JMH_NOTES.md) | JMH notes and the decisions behind the benchmark harness |

## Branches and tags

`main` (always green), `person-a` (Dhakshitha), `person-b` (Esther). Tags: `baseline-freeze`, `parity-verified`,
`lockfree-freeze`, `benchmarks-frozen`, `v1.0`.

## Repo layout

```
src/main/java/com/nanomatch/
    model/        Order, Trade, Side, TradeSink, EngineFactory, MatchingEngine   (Person A)
    smoke/        JMH smoke test and the ring-buffer padding benchmark            (Person A)
    baseline/     lock-based engine and its order book                            (Person A)
    lockfree/     ring buffer, padded counters, lock-free engine                  (Person A)
    orderbook/    allocation-free array-based order book                          (Person A)
    sim/          order generator and client/load simulator                       (Person B)
    bench/        JMH benchmark and its workload                                  (Person B)
src/test/java/com/nanomatch/
    engine/       unit tests, differential and allocation tests                   (Person A)
    verify/       contract, concurrency, parity, simulator and workload tests     (Person B)
results/          run_suite.py, analyze_runs.py, frozen results by date
report/           sections/ (one file per author), charts/, REPORT.md
docs/             team documents
```

## Status

| Day | Theme | Status |
|---|---|---|
| 0 | Repo + docs + scope lock | done |
| 1 | Skeleton, JMH, domain model | done |
| 2 | Baseline engine + tests (Checkpoint 2) | done |
| 3 | Ring buffer + load simulator | done |
| 4 | Parity check (Checkpoint 3) | done |
| 5 | Hardening + lock-free freeze (Checkpoint 4) | done |
| 6 | Full benchmarks (Checkpoint 5) | done |
| 7 | Report + final review (Checkpoint 6) | done (tag `v1.0`) |
