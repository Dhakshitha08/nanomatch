# Independent review of the benchmark results: Person A

Copy this file to `results/<date>/A/review-A.md` and answer **every** question with a real answer (yes / no plus
a sentence). This review is deliberately separate from Person B's `METHODOLOGY_REVIEW.md`: Checkpoint 5 asks
both of us to judge independently whether the comparison is fair. If an answer is "no" or "not sure", the
numbers are not frozen until it is resolved.

- Date of review:
- Results folder reviewed (`results/<date>`):
- Code version the benchmarks ran on (from `ENVIRONMENT.md`; it should be the `lockfree-freeze` tag or later with
  no engine changes):

## 1. Can I reproduce the summary?
- Did running `analyze_runs.py` on the committed JSON give exactly the same `SUMMARY.md`? (yes / no)
- If no, what differed?

## 2. Did the runs go cleanly?
- Any `Exception` / `ERROR` in the `.log` files? (yes / no; if yes, which)
- Did all three runs complete for all four producer counts, with no reruns? If anything was rerun, was the reason
  recorded in `METHODOLOGY_REVIEW.md`?

## 3. Is the comparison fair?
- Same machine, same JVM, same flags, same orders, same measurement settings for both engines? (yes / no)
- Same latency definition for both engines (time from `submit` until the order is processed)? (yes / no)
- Trades go to a no-op sink for both engines? (yes / no)
- Which logical core count does `ENVIRONMENT.md` give, and which producer counts exceed (cores - 1)? How should
  that be worded in the report?
- The baseline uses a standard (non-fair) `ReentrantLock`. Does that favour or disadvantage the baseline in these
  results, and how will the report say so?

## 4. Warm-up and stability
- Warm-up check from `SUMMARY.md`: how many forks were flagged? Is warm-up long enough, or must it be lengthened
  and everything rerun?
- Stability: how many measurements exceeded tolerance? Which ones? Do we agree to report medians with the
  min to max range for those, rather than hiding them?
- Do I agree with the tolerances chosen (10 % / 25 % unless changed)?

## 5. Allocation evidence
- `gc.alloc.rate.norm` for the baseline and for the lock-free engine (from `results/<date>/A/run1-gc-t*.json`
  or the console output), in bytes per operation:
  - baseline:
  - lock-free:
- Does it match the claim "the lock-free hot path allocates nothing per order"? (yes / no)

## 6. Things the numbers show that need an honest explanation in the report
Write what the data shows first (numbers from `SUMMARY.md`), and only then a possible explanation. Mark each
explanation as **tested** (with which evidence) or **hypothesis** (not tested).
- Lock-free throughput at 4 and 8 producers compared with the baseline:
  - data:
  - ring-scaling evidence (`ring-scaling-t*.json`: producer throughput with a do-nothing consumer, at 1, 2, 4, 8):
  - explanation (tested / hypothesis):
- Median (p50) latency of the lock-free engine at 2 or more producers compared with the baseline:
  - data:
  - explanation (tested / hypothesis):
- Tail latency (p99, p99.9) of the lock-free engine compared with the baseline:
  - data:

## 7. Decision
- Are there concerns that would change the numbers if resolved? (list them, or "none")
- **I agree the results can be frozen:** yes / no
- Signed (name, date):
