# \# Benchmark methodology review: Person B

# 

# Dhakshitha writes her own independent review (`results/2026-10-24/A/review-A.md`).

# 

# \- Date of the runs: 2026-10-24, 22:02 to 22:54 IST (run 1: 22:02 to 22:19, run 2: 22:20 to 22:37, run 3: 22:37 to 22:54)

# \- Machine (model, CPU, logical cores, RAM): <LAPTOP MODEL>, AMD64 Family 23 Model 104 (<CPU NAME, e.g. Ryzen 5 5500U>), 12 logical cores, <RAM> GB

# \- Plugged in? Power mode? Other applications closed? Sleep disabled? Plugged in: yes. Power mode: Best performance. Other applications closed: yes, except <CONFIRM: was the laptop used around 22:26, during run 2 (t2 / t4)? If yes, say so here>. Sleep disabled: yes.

# \- Code version the benchmarks ran on: `lockfree-freeze-2-g904ae70-dirty (commit 904ae70)`.

# &#x20; "dirty" means the working tree had uncommitted changes when the runs started: the Day 6 scripts

# &#x20; (`run\\\_suite.py` replaced, `analyze\\\_runs.py` and the review template new), not yet committed. No engine or

# &#x20; benchmark code was changed: `git diff --stat lockfree-freeze 904ae70 -- src pom.xml` and

# &#x20; `git diff --stat 904ae70 -- src pom.xml` both print nothing <CONFIRM by running both commands>.

# 

# \## 1. What was run

# \- Command(s) used:

# &#x20; `mvn clean package -DskipTests`, then

# &#x20; `python results/run\\\_suite.py --out results/2026-10-24 --run N` for N = 1, 2, 3, back to back in one PowerShell loop.

# \- Number of runs: 3. Producer counts: 1, 2, 4, 8.

# \- Any run interrupted, repeated or discarded? No. All 3 runs finished without errors on the first attempt, and all

# &#x20; 12 JSON files and 12 logs are present. No core-count warning appeared (8 producers + 1 matching thread = 9 < 12 logical cores).

# 

# \## 2. Settings and why

# | Setting | Value | Reason |

# |---|---|---|

# | Warm-up | 3 iterations x 2 s | Lets the JIT compile the hot paths and the caches settle before anything is measured |

# | Measurement | 5 iterations x 3 s | Enough iterations to see drift within a fork, and enough samples for p99 / p99.9 |

# | Forks | 2 | Each fork is a fresh JVM, so one lucky or unlucky JIT decision cannot decide the result |

# | Modes | Throughput; SampleTime (p50, p99, p99.9) | Throughput for orders per second; SampleTime for the latency distribution, because tail latency is what matters for a matching engine |

# 

# \- Warm-up check from `SUMMARY.md`: "31 of 144 forks were flagged."

# \- Is warm-up long enough? Mostly yes. 26 of the 31 flags are `steadyLatency`, 4 are `burstLatency` and only 1 is

# &#x20; `throughput`. The differences go in \*\*both directions\*\* (sometimes the last warm-up iteration is slower than the

# &#x20; measured mean, sometimes faster), which looks like noise in the SampleTime mean (it is pulled around by rare

# &#x20; outliers) rather than a steady warm-up trend. Throughput, the mode most sensitive to JIT warm-up, is flagged

# &#x20; once in 48 forks. Proposal: keep the settings, do not rerun, and state the flags in the report. <AGREE WITH DHAKSHITHA>

# 

# \## 3. Stability

# \- Tolerances used: the defaults, 10 % for throughput and p50 and 25 % for p99 and p99.9. High percentiles are

# &#x20; based on few samples and naturally vary more, so they get a wider tolerance.

# \- "28 of 56 measurements exceeded their tolerance."

# \- The unstable measurements fall into four groups:

# &#x20; 1. \*\*p99.9 rows (13 of the 28).\*\* The far tail is estimated from few samples and is hit by OS scheduling events.

# &#x20;    The worst cases are lock-free t8 burst p99.9 (249 to 1,421 us, one bad run) and lock-free t8 steady p99.9

# &#x20;    (29.3 to 138.0 us).

# &#x20; 2. \*\*p99 rows (6).\*\* For example, lock-free t8 burst p99 is 97.8 to 103.4 us in two runs and 293.4 us in one.

# &#x20; 3. \*\*p50 rows at the timer floor (3).\*\* On this Windows machine the latency values come in steps of 100 ns, so

# &#x20;    0.1 vs 0.2 us is "100 %" spread but is really one timer tick. These rows say nothing about stability.

# &#x20; 4. \*\*Throughput (5).\*\* t1 baseline 11.1 % and t1 lock-free 14.1 % (just over 10 %), t2 lock-free 28.5 %,

# &#x20;    t8 baseline 15.4 % (one run at 9.53 M/s, the other two about 11.2 M/s).

# \- What the report will do: report the \*\*median and the min to max range\*\* for every number, and only claim an

# &#x20; engine is better where the two min-max ranges \*\*do not overlap\*\*. Checked against the table, all of the main

# &#x20; findings in section 7 hold under that rule.

# 

# \## 4. Fairness checklist (yes / no, and a note if no)

# \- \[x] Same machine, same JVM, same JVM flags for both engines

# \- \[x] Same orders for both engines (same seeds)

# \- \[x] Same latency definition for both engines (`BenchmarkWorkload.submitAndWait`)

# \- \[x] Same warm-up, measurement and fork settings for both engines

# \- \[x] Both engines send trades to a no-op sink

# \- \[ ] Nothing else was running during the runs: <yes, OR "no: I used the laptop briefly around 22:26, during run 2 (t2/t4)">

# 

# \## 5. CPU cores

# \- Logical cores: 12

# \- Producer counts where producers + 1 matching thread exceed the cores: none (the largest case is 8 + 1 = 9).

# \- What the report will say about this: no producer count was distorted by having fewer cores than threads on this

# &#x20; machine. The cores are still shared with the OS and background services.

# 

# \## 6. Limitations to state in the report

# \- \[x] The book stays tiny in this workload (a handful of resting orders), so this is not a deep-book benchmark

# \- \[x] The lock-free engine's book is bounded (fixed price range and pool); the baseline's is not

# \- \[x] The baseline uses a standard non-fair `ReentrantLock`

# \- \[x] One machine only; results are not claims about other hardware

# \- \[x] High percentiles are estimated from a limited number of samples

# \- \[x] Throughput counts accepted orders; for the lock-free engine the backlog at the end of an iteration is at most one ring buffer

# \- \[x] Timer resolution on Windows is about 100 ns, so p50 values of 0.1 to 0.3 us are coarse and differences of one step are not meaningful

# \- \[x] A laptop, not a tuned server: no CPU pinning, frequency boost not controlled, OS background work possible

# \- \[x] Code version is marked "dirty" because the Day 6 scripts were not yet committed; engine code was identical to commit 904ae70

# 

# \## 7. What the numbers show (data only, no explanations yet)

# Medians of 3 runs, baseline vs lock-free.

# 

# | Producers | Throughput (M/s) | Steady p50 / p99 (us) | Burst p50 / p99 (us) |

# |---:|---|---|---|

# | 1 | 19.26 vs \*\*29.38\*\* | 0.1 / 0.6 vs 0.1 / 0.6 | 1.4 / \*\*3.7\*\* vs \*\*1.1\*\* / 4.4 |

# | 2 | 10.35 vs \*\*12.27\*\* | \*\*0.2\*\* / 4.4 vs 0.3 / \*\*0.9\*\* | 4.7 / 48.1 vs \*\*3.3 / 14.4\*\* |

# | 4 | \*\*10.68\*\* vs 7.29 | \*\*0.2\*\* / 40.1 vs 0.7 / \*\*1.4\*\* | \*\*2.2\*\* / 98.3 vs 17.2 / \*\*33.2\*\* |

# | 8 | \*\*11.23\*\* vs 6.94 | \*\*0.2\*\* / 85.1 vs 1.2 / \*\*3.4\*\* | \*\*2.0\*\* / 221.2 vs 31.9 / \*\*103.4\*\* |

# 

# Where the lock-free engine is \*\*better\*\*:

# \- Throughput at 1 producer (1.53x) and 2 producers (1.19x).

# \- Tail latency (p99 and p99.9) at 2, 4 and 8 producers, in both steady and burst load. The biggest gaps are

# &#x20; steady p99 at 4 producers (40.1 vs 1.4 us, 28.6x) and 8 producers (85.1 vs 3.4 us, 25.0x).

# \- Burst p50 at 1 and 2 producers.

# 

# Where the lock-free engine is \*\*worse\*\*:

# \- Throughput at 4 producers (7.29 vs 10.68 M/s, baseline 1.47x better) and 8 producers (6.94 vs 11.23 M/s,

# &#x20; baseline 1.62x better). The lock-free throughput also drops as producers are added (29.4, 12.3, 7.3, 6.9 M/s).

# \- Median (p50) latency at 4 and 8 producers: steady p50 0.7 and 1.2 us vs 0.2 us; burst p50 17.2 and 31.9 us vs

# &#x20; 2.2 and 2.0 us (7.8x and 15.9x worse).

# \- At 1 producer, the lock-free tails are slightly worse (steady p99.9 13.0 vs 10.9 us, burst p99 4.4 vs 3.7 us,

# &#x20; burst p99.9 71.7 vs 51.5 us), but the min-max ranges overlap, so this is not a clear difference.

# 

# In plain words: with 1 or 2 producers the lock-free engine is faster overall. With 4 or 8 producers the baseline

# handles more orders per second and has the lower typical (median) latency, while the lock-free engine has a far

# lower and more predictable tail latency. The two engines trade places depending on which percentile is looked at.

# 

# \## 8. Decision

# \- Do I agree that the comparison is fair and the results can be frozen? <yes / no>

# \- Concerns (or "none"): the 28 unstable measurements (mostly far-tail percentiles) and the 31 warm-up flags are

# &#x20; documented above; the report must use median plus range and only claim differences where ranges do not overlap.

# &#x20; <add "laptop briefly used during run 2" if that happened>

# \- Signed: Esther Philemena S, <date>

