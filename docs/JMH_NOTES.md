# JMH Notes (Person B)

Working notes on how we benchmark NanoMatch. Read this before writing the Day 5 harness, and update it if
you learn something that changes the plan.

## 1. Why JMH and not a stopwatch

Timing a loop with `System.nanoTime()` gives misleading numbers on the JVM:

| Problem | What happens | How JMH helps |
|---|---|---|
| **JIT warm-up** | The first iterations run in the interpreter, later ones in optimized machine code | Warm-up iterations are run and thrown away |
| **Dead-code elimination** | If a result is never used, the JIT may delete the code you meant to time | Returned values (or `Blackhole.consume`) count as used |
| **Constant folding** | Inputs the JIT knows at compile time can be pre-computed away | `@State` fields that are not `final` act as unknown inputs |
| **Loop optimizations** | A hand-written loop can be unrolled or hoisted in ways real code is not | JMH controls the measurement loop |
| **One JVM, one profile** | JIT decisions made in one run can bias the next | `@Fork` runs each benchmark in fresh JVMs |

## 2. Building blocks we will use

- `@Benchmark`: the method that is measured.
- `@State(Scope.Benchmark | Scope.Thread)`: holds the objects a benchmark needs. `Scope.Thread` gives each
  benchmark thread its own copy; `Scope.Benchmark` shares one copy between all threads (this is how all
  producer threads share one engine).
- `@Setup` / `@TearDown` with a level: `Trial` (once per fork), `Iteration` (before each iteration),
  `Invocation` (before each call; costs overhead, avoid on tiny benchmarks).
- `@Param`: runs the same benchmark for several values (for us: engine type and producer count).
- `@Threads`: how many threads call the benchmark method at once.
- `Blackhole`: consume a value so the JIT cannot discard it.

## 3. Modes

| Mode | Reports | Use for us |
|---|---|---|
| `Throughput` | operations per unit of time | Orders per second. **Used** |
| `AverageTime` | mean time per operation | Context only; averages hide tail latency |
| `SampleTime` | time of sampled calls, with a **percentile distribution** (p50, p90, p99, p99.9, p99.99, max) | p99 and p99.9 latency. **Used** |

Averages are not the point of this project. A trading system is judged by its slow outliers, which is
why we report p99 and p99.9.

## 4. Settings we start with (documented in `docs/BENCHMARKING_PLAN.md`)

| Setting | Value | CLI flag |
|---|---|---|
| Warm-up | 3 iterations x 2 s | `-wi 3 -w 2s` |
| Measurement | 5 iterations x 3 s | `-i 5 -r 3s` |
| Forks | 2 | `-f 2` |
| Threads | 1, 2, 4, 8 producers | `-t N` or `@Param` |
| Output | JSON | `-rf json -rff results/<date>/runN.json` |

Warm-up is long enough when the iteration scores stop drifting. If they still trend, raise it and
write down why.

## 5. Useful command lines

```
# build the runnable jar
mvn clean package -DskipTests

# list benchmarks
java -jar target/benchmarks.jar -l

# run one benchmark, 1 fork, short settings (smoke test)
java -jar target/benchmarks.jar SmokeBenchmark -f 1 -wi 1 -i 2 -w 1s -r 1s

# set a @Param value from the command line
java -jar target/benchmarks.jar EngineBenchmark -p engine=lockfree

# allocation evidence: look at gc.alloc.rate.norm (bytes allocated per operation)
java -jar target/benchmarks.jar EngineBenchmark -prof gc
```

## 6. Latency in an asynchronous engine (the design question for Day 5)

Our locked definition: latency is the time from `submit(order)` until that order has been **fully
processed**, measured the same way for both engines.

The baseline processes the order inside `submit`. The lock-free engine returns from `submit` as soon as the
order is published to the ring buffer; the matching thread processes it a little later. Timing only the
`submit` call would flatter the lock-free engine unfairly.

The `MatchingEngine` interface was designed for this. `submit` returns a **ticket**, and
`processedCount() > ticket` means that order is done. So the same timed section works for both engines:

```
long t0 = System.nanoTime();
long ticket = engine.submit(order);
while (engine.processedCount() <= ticket) { Thread.onSpinWait(); }
long t1 = System.nanoTime();     // latency = t1 - t0
```

For the baseline the wait loop exits immediately. In JMH's `SampleTime` mode, the whole benchmark method is the
timed section, so the method body above is exactly what gets sampled.

Things to think about and settle with Person A on Day 5:
- This measures one order in flight per thread (a closed loop). It shows contention and handoff cost
  between producers and the matching thread, but not queueing under overload.
- Each thread must use different order ids and sensible prices so the book does not grow without bound.
- The benchmark state needs a fresh engine per iteration, or a bounded book, so a long run does not distort results.

## 7. Pitfalls checklist

- [ ] Every benchmark result is returned or consumed (no dead code).
- [ ] Inputs are generated in `@Setup`, not inside the timed method.
- [ ] No printing or logging inside timed code.
- [ ] Same JVM, flags, machine and load pattern for both engines.
- [ ] The laptop is plugged in, other applications are closed, and power saving is off.
- [ ] Results come from several runs, and the spread between runs is reported.
- [ ] Warm-up and fork counts are written down with the reason.
- [ ] Both engines were checked for correct output **before** timing them. A fast wrong answer is worthless.

## 8. Reproducibility

- The workload comes from `OrderGenerator` with a fixed seed, so every run sees the same orders.
- Each run's JSON goes to `results/<date>/` together with `ENVIRONMENT.md` (CPU, OS, Java version, flags).
