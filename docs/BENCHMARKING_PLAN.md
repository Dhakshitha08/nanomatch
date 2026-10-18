# Benchmarking and Productionization Plan

## 1. Framing

The deliverable is not a hosted service. It is a **benchmarked systems artifact**. The "deployment story" is
proving performance rigorously and packaging the project so anyone can reproduce the numbers.

## 2. Methodology (the core deliverable)

### Tooling
**JMH (Java Microbenchmark Harness)**, the standard tool for JVM microbenchmarks. Naive wall-clock timing
(`System.nanoTime()` around a loop) is unreliable because of JIT warm-up and dead-code elimination.

### What we measure

| Metric | Why it matters |
|---|---|
| Throughput (orders/sec) | Raw processing capacity of each engine |
| p50 latency | "Typical" latency, for context |
| p99 latency | A rare slow order matters more than the average in trading |
| p99.9 latency | Worst-case tail behaviour; the true test of "predictable" |
| Latency vs. producer count | How performance degrades or holds as concurrent load grows |
| Allocation rate (`-prof gc`) | Evidence that the lock-free hot path is allocation-free |

### JMH settings for the one-week schedule (starting point; document whatever we finally use)

| Setting | Starting value | Notes |
|---|---|---|
| Modes | `Throughput`, `SampleTime` | `SampleTime` reports the percentile distribution |
| Warm-up | 3 iterations x 2 s | Increase if results are still drifting |
| Measurement | 5 iterations x 3 s | |
| Forks | 2 | Separate JVMs per benchmark |
| Producer threads | 1, 2, 4, 8 | Same set for both engines |
| Full-suite repeats | 3 runs | Report variance, not one point estimate |

Expect a full suite run to take on the order of 20 to 30 minutes; plan the Day 6 session around that.
Output: `-rf json -rff results/<date>/runN.json`.

### Latency definition
Time from the `submit(order)` call until that order has been fully processed, measured **identically** for
both engines. This definition is written into the report.

### Fair-comparison discipline
- Same hardware, same JVM version, same JVM flags, same load pattern for both engines
- One machine for all runs; laptop plugged in; other applications closed
- Multiple runs; report variance
- Warm-up iteration count and fork count documented explicitly in the report

### Load patterns
- **Steady state:** concurrent order submission at increasing rates (find each engine's throughput ceiling)
- **Bursty:** sudden spikes. This is usually where tail-latency differences between lock-based and
  lock-free engines are most dramatic and most convincing

## 3. Packaging for reproducibility

- Runnable benchmark JAR: `target/benchmarks.jar`
- README with exact commands for (a) the correctness suite and (b) the JMH suite, with expected
  approximate output
- Raw JMH JSON committed under `results/<date>/`, plus `ENVIRONMENT.md` (CPU, OS, Java version, settings)
- Chart script committed, so charts regenerate from the raw JSON

## 4. What "production-grade" would additionally require (future work, not built)

Stating this clearly in the report is part of the deliverable.

- **Persistence and crash recovery:** a real exchange cannot lose in-flight orders; this project is in-memory only
- **Network protocol / client-server separation:** real systems expose this over a network (e.g. FIX); this
  project runs in-process for benchmarking clarity
- **Multi-instrument support:** real exchanges match thousands of instruments concurrently; this project
  has a single order book so the core concurrency problem stays front and centre
- **Regulatory / audit logging:** a full immutable audit trail of every order and match; not attempted here

## 5. Deliverable checklist

- [ ] Baseline (lock-based) engine implemented and correctness-tested
- [ ] Lock-free engine implemented, with cache-line padding and allocation reduction applied
- [ ] Both engines verified to produce identical trades on identical input
- [ ] JMH suite covering throughput and p50/p99/p99.9 latency, at multiple producer counts, for both engines
- [ ] Benchmark results reproducible by re-running the provided suite
- [ ] Report with charts, an explicit one-line inference per chart, and an honest "what production would
      additionally require" section
