# NanoMatch: 1-Week Plan

**Team:** Dhakshitha S (**Person A**, core engine) and Esther Philemena S (**Person B**, load simulator and benchmarks)

This replaces the original 8-week plan. Same project, same deliverable, compressed to 7 working days
(Day 1 to Day 7) after a Day 0 setup. The deliverable is unchanged: **a measured comparison of the
lock-based and lock-free engines (p99 / p99.9 latency and throughput), with both engines proven to
produce identical trades.**

---

## 1. At a glance

Each day has an **A turn** first, then a **B turn**. Checkpoint days end with a short tagging step run by A.

| Day | Theme | Person A | Person B | Checkpoint |
|---|---|---|---|---|
| 0 | Setup | Create repo, push docs, invite B | Clone, check tools, sign scope lock | 1: Scope lock |
| 1 | Foundation | Maven + JMH skeleton, domain model, `MatchingEngine` interface | JMH notes, seeded order generator | |
| 2 | Baseline | Lock-based engine | Correctness test suite v1 | **2: Baseline freeze** |
| 3 | Lock-free start | Ring buffer + minimal lock-free engine | Client/load simulator | |
| 4 | Parity | Fix divergences, finalize order book | Parity test + trade diff tool | **3: Correctness parity** |
| 5 | Hardening | Cache-line padding, allocation-free hot path | JMH harness (both engines) | **4: Lock-free freeze** |
| 6 | Benchmarks | Support, GC/allocation profiling | Full JMH runs (3 times), save results | **5: Methodology review** |
| 7 | Report | Design + correctness section, README | Charts, results + methodology section | **6: Final review**, tag `v1.0` |

---

## 2. Assumptions and what was compressed

- **Time:** about 3 hours per day per person (around 21 hours each). The original plan had 6 hrs x 8 weeks
  = 48 hrs each, so this is roughly 40% of the original effort. If you have more hours per day, Days 6 and 7
  simply get more slack.
- **Roles:** Dhakshitha = Person A, Esther = Person B (swap only if you both agree, before Day 1).
- **Nothing in the correctness story is cut.** The parity check (Day 4) and the benchmark methodology
  review (Day 6) stay in full. What is compressed:
  - Benchmark matrix: 1, 2, 4, 8 producer threads (not a wider sweep), 2 forks, 3 benchmark runs.
  - Single Maven module with packages per lane, instead of separate top-level folders per component.
  - Report is a Markdown file plus PNG charts.
- **Original scope limits stay exactly as they were** (see `docs/SCOPE_LOCK.md`).

---

## 3. Locked decisions

Decided now so nobody renegotiates them mid-week.

| Topic | Decision |
|---|---|
| Language / build | Java 17, Maven, JUnit 5 |
| Benchmarking | JMH (`Throughput` and `SampleTime` modes; `-prof gc` for allocation) |
| Prices and quantities | `long` values (price in integer ticks), no `double` anywhere in matching |
| Order types | Limit orders only, good-till-cancelled, no cancel/modify (scope lock) |
| Trade price | Price of the **resting** order |
| Architecture of lock-free engine | Many producer threads write to a pre-allocated ring buffer; **one** consumer thread does all matching (the single writer to the order book) |
| Parity definition | Same input sequence in the same order must give an identical trade list (buy id, sell id, price, quantity, in order). With multiple producers, the order the ring buffer assigned is **recorded** and replayed through the baseline |
| Latency definition | Time from `submit(order)` call until that order has been fully processed, measured the same way for **both** engines |
| Repo | One shared repo with three branches: `main`, `person-a`, `person-b`. After each turn, that person merges their own branch into `main` (tests must pass first), so `main` always stays green |
| Conflicts | Every file has exactly one owner (see `docs/MERGING_REVIEW_PLAN.md`). Nobody edits a file they don't own, so merges cannot conflict |

---

## 4. Day-by-day detail

### Day 0: Setup (today)
- **A:** create the repo from the Day 0 zip, push `main`, `person-a` and `person-b`, add B as collaborator.
- **B:** accept invite, clone, run tool checks (`git`, `java -version`, `mvn -v`, Python), tick her box in `docs/SCOPE_LOCK.md`, push to `person-b` and merge it into `main`.
- **Done when:** both can pull and push on their own branch, and `main` shows commits from both of you.

### Day 1: Foundation
- **A:** `pom.xml` (Java 17, JUnit 5, JMH, shade plugin producing `target/benchmarks.jar`); model classes `Side`, `Order`, `Trade`; the `MatchingEngine` interface; one trivial JMH benchmark proving the setup works.
- **B:** `docs/JMH_NOTES.md` (warm-up, forks, dead-code elimination and `Blackhole`, `SampleTime` vs `Throughput`); deterministic seeded `OrderGenerator` with a determinism test.
- **Done when:** `mvn clean test` passes on both lane branches and the trivial JMH benchmark runs.

### Day 2: Baseline engine (Checkpoint 2)
- **A:** lock-based order book (price levels with FIFO queues, bids high-to-low, asks low-to-high) and `LockBasedEngine` using `ReentrantLock`. Partial fills supported.
- **B:** `EngineContractTest` (runs against any `MatchingEngine`): price priority, time priority at equal price, partial fills, no match when not crossing, sweeping several levels, plus one concurrent test checking invariants (traded quantity balances, book not crossed at the end).
- **Checkpoint step (A):** once `main` contains both lanes and `mvn clean test` is green for both of you, tag `baseline-freeze`.
- **Done when:** the whole test suite is green against the baseline.

### Day 3: Lock-free ingestion and load simulator
- **A:** pre-allocated power-of-two ring buffer with reusable event slots, producer claim by CAS on a sequence, per-slot publish flag so the consumer never reads a half-written slot, single consumer matching loop. Minimal end-to-end `LockFreeEngine` (reuses the simple book for now; correctness before speed).
- **B:** `LoadSimulator`: N client threads, configurable rate per thread, STEADY and BURST patterns, takes an engine factory. Validate it against the baseline first.
- **Done when:** 4 producers submit 100k orders into the lock-free engine and every order is processed exactly once (count check).

### Day 4: Correctness parity (Checkpoint 3, the most important one)
- **B:** `ParityTest` + trade-diff tool. Same seeded sequence through both engines, trade lists compared exactly. For multi-producer runs: record the ring buffer's processing order, replay it through the baseline, compare.
- **A:** fix any mismatch **before anything else**. Then finalize the concurrency-aware order book structure.
- **Rule:** if parity fails, stop and fix it together. An incorrect lock-free engine invalidates every benchmark after it. A red test never goes into `main`; B pushes to `person-b` only until it is fixed. Tag `parity-verified` once it is green.
- **Done when:** parity passes for at least 5 different seeds at 100k orders each.

### Day 5: Hardening and lock-free freeze (Checkpoint 4)
- **A:** cache-line padding on the hot sequences, allocation-free hot path (pooled order slots, primitive fields, trades written into a pre-allocated buffer). Show with `-prof gc` that steady-state allocation is about zero. Re-run the **entire** correctness and parity suite after every change.
- **B:** JMH harness `EngineBenchmark`: engine = baseline / lock-free, producers = 1, 2, 4, 8, modes `Throughput` and `SampleTime`, steady and burst load. Run once as a smoke test on both engines.
- **Checkpoint step (A):** once `main` contains both lanes and is green, tag `lockfree-freeze`.
- **Done when:** full suite green on the frozen lock-free engine and the harness runs end to end.

### Day 6: Benchmarks and methodology review (Checkpoint 5)
- **B:** run the full suite **3 times** on one machine (plugged in, other apps closed), save raw JSON and an `ENVIRONMENT.md` (CPU, OS, Java version, settings) under `results/<date>/`.
- **A:** fix anything the benchmarks surface; run the `-prof gc` evidence run.
- **Both:** review warm-up and fork settings, compare the 3 runs for stability, agree the comparison is fair. Write down the tolerance you accept between runs (suggested: about 10%). Then **freeze the numbers**.
- **Done when:** results are committed and neither of you wants to rerun them.

### Day 7: Report and final review (Checkpoint 6)
- **B:** chart script (Python + matplotlib reading the JMH JSON): throughput vs producers, p50/p99/p99.9 baseline vs lock-free, steady vs burst. "Results and methodology" section.
- **A:** "Engine design and correctness" section, "What production would additionally require" section, README run instructions.
- **Both:** every chart has a one-line inference; every number in the report matches the frozen result files exactly; fresh clone + `mvn clean test` + one benchmark run works.
- **Checkpoint step (A):** assemble `report/REPORT.md` from the two section files, then tag `v1.0` on green `main`.

Each of you writes your own report section file (`report/sections/`), so you never edit the same file. A assembles `report/REPORT.md` at the end (convert to PDF/Word if the professor needs that format).

---

## 5. Daily routine (the zip workflow)

Each day Dhakshitha gets **two zips**:

```
dayN-person-a.zip      ->  files/  (repo-relative paths)  +  COMMANDS.md
dayN-person-b.zip      ->  files/  (repo-relative paths)  +  COMMANDS.md
```

1. **A turn:** extract `dayN-person-a.zip`, run its `COMMANDS.md` (update `person-a`, copy files, test, commit, push, merge into `main`).
2. **A forwards `dayN-person-b.zip` to Esther.**
3. **B turn:** Esther extracts her zip and runs its `COMMANDS.md` **on her own computer and GitHub account** (update `person-b` from `main`, copy files, test, commit, push, merge into `main`).
4. **Checkpoint days only (2, 4, 5, 7):** A pulls `main`, runs `mvn clean test`, and tags it.
5. A updates the status table in `README.md`.

Esther must commit her own files herself. Never commit her files from your account: the professor will see
the contributors, and the history should show who actually did what.

Branch rules, file ownership and the conflict-free merge protocol are in `docs/MERGING_REVIEW_PLAN.md`.
Per-person task lists are in `docs/PERSON_A_PLAN.md` and `docs/PERSON_B_PLAN.md`.

---

## 6. If we fall behind

**Never cut:** the parity check (Day 4), the 3 repeated benchmark runs, documenting warm-up/fork settings, the honest "limitations" section.

**Cut in this order if needed:**
1. README polish and the p50 chart.
2. Producer levels 1, 2, 4, 8 down to 1, 4, 8.
3. Burst pattern kept, but with a single burst size.
4. If the ring buffer is still unstable at the end of Day 4: fall back to a simpler ring buffer variant, still lock-free on the producer side, and say so in the report.

---

## 7. Definition of done

See the deliverable checklist at the bottom of `docs/BENCHMARKING_PLAN.md`.
