# Benchmark methodology review: Person B

Copy this file to `results/<date>/METHODOLOGY_REVIEW.md` and fill it in after the three runs and the analysis. Answer
every question with a real answer. Be honest about anything that went wrong: a documented problem is fine, a
hidden one is not. Dhakshitha writes her own independent review (`results/<date>/A/review-A.md`).

- Date of the runs:
- Machine (model, CPU, logical cores, RAM):
- Plugged in? Power mode? Other applications closed? Sleep disabled? (also put these in `ENVIRONMENT.md`)
- Code version the benchmarks ran on (copy the "Code version (git)" line from `ENVIRONMENT.md`):

## 1. What was run
- Command(s) used:
- Number of runs: (should be 3) Producer counts: (1, 2, 4, 8)
- Any run interrupted, repeated or discarded? If yes: which, why, and what was done. (A run that was interrupted is
  redone in full. Runs are never discarded because their numbers look unwelcome.)

## 2. Settings and why
| Setting | Value | Reason |
|---|---|---|
| Warm-up | 3 iterations x 2 s | |
| Measurement | 5 iterations x 3 s | |
| Forks | 2 | |
| Modes | Throughput; SampleTime (p50, p99, p99.9) | |
- Warm-up check from `SUMMARY.md` (paste the sentence "N of M forks were flagged"):
- Is warm-up long enough? If not, what are we doing about it?

## 3. Stability
- Tolerances used (default 10 % for throughput and p50, 25 % for p99 and p99.9) and why:
- Paste the sentence "N of M measurements exceeded their tolerance":
- List the unstable measurements and what the report will do about them (for example "report the median and the
  min to max range"):

## 4. Fairness checklist (yes / no, and a note if no)
- [ ] Same machine, same JVM, same JVM flags for both engines
- [ ] Same orders for both engines (same seeds)
- [ ] Same latency definition for both engines
- [ ] Same warm-up, measurement and fork settings for both engines
- [ ] Both engines send trades to a no-op sink
- [ ] Nothing else was running during the runs

## 5. CPU cores
- Logical cores:
- Producer counts where producers + 1 matching thread exceed the cores (distorted by CPU sharing):
- What the report will say about this:

## 6. Limitations to state in the report
Check each one and add your own.
- [ ] The book stays tiny in this workload (a handful of resting orders), so this is not a deep-book benchmark
- [ ] The lock-free engine's book is bounded (fixed price range and pool); the baseline's is not
- [ ] The baseline uses a standard non-fair `ReentrantLock`
- [ ] One machine only; results are not claims about other hardware
- [ ] High percentiles are estimated from a limited number of samples
- [ ] Throughput counts accepted orders; for the lock-free engine the backlog at the end of an iteration is at most one ring buffer
- [ ] (add more)

## 7. What the numbers show (data only, no explanations yet)
Copy the most important rows from `SUMMARY.md` and describe them in plain words: where each engine is better and
where it is worse, at each producer count. Include the cases where the lock-free engine is **worse** (for example
throughput at 4 and 8 producers, or median latency).

## 8. Decision
- Do I agree that the comparison is fair and the results can be frozen? yes / no
- Concerns (or "none"):
- Signed (name, date):
