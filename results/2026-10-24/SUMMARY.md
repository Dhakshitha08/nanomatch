# Benchmark summary (3 run(s): 1, 2, 3)

Source: `results\2026-10-24`. Values are the median over the runs; the range is min to max.
Stability tolerance: 10% for throughput and p50, 25% for p99 and p99.9 (spread = (max - min) / median).

## Engine comparison (medians)

| Producers | Metric | Baseline | Lock-free | Better by |
|---:|---|---:|---:|---|
| 1 | Throughput | 19.26 M/s | 29.38 M/s | lock-free 1.53x |
| 1 | Steady p50 | 0.1 us | 0.1 us | lock-free 1.00x |
| 1 | Steady p99 | 0.6 us | 0.6 us | lock-free 1.00x |
| 1 | Steady p99.9 | 10.9 us | 13.0 us | baseline 1.19x |
| 1 | Burst p50 | 1.4 us | 1.1 us | lock-free 1.27x |
| 1 | Burst p99 | 3.7 us | 4.4 us | baseline 1.19x |
| 1 | Burst p99.9 | 51.5 us | 71.7 us | baseline 1.39x |
| 2 | Throughput | 10.35 M/s | 12.27 M/s | lock-free 1.19x |
| 2 | Steady p50 | 0.2 us | 0.3 us | baseline 1.50x |
| 2 | Steady p99 | 4.4 us | 0.9 us | lock-free 4.89x |
| 2 | Steady p99.9 | 47.0 us | 18.0 us | lock-free 2.61x |
| 2 | Burst p50 | 4.7 us | 3.3 us | lock-free 1.42x |
| 2 | Burst p99 | 48.1 us | 14.4 us | lock-free 3.34x |
| 2 | Burst p99.9 | 228.6 us | 138.0 us | lock-free 1.66x |
| 4 | Throughput | 10.68 M/s | 7.29 M/s | baseline 1.47x |
| 4 | Steady p50 | 0.2 us | 0.7 us | baseline 3.50x |
| 4 | Steady p99 | 40.1 us | 1.4 us | lock-free 28.62x |
| 4 | Steady p99.9 | 114.2 us | 21.8 us | lock-free 5.24x |
| 4 | Burst p50 | 2.2 us | 17.2 us | baseline 7.81x |
| 4 | Burst p99 | 98.3 us | 33.2 us | lock-free 2.97x |
| 4 | Burst p99.9 | 289.1 us | 113.3 us | lock-free 2.55x |
| 8 | Throughput | 11.23 M/s | 6.94 M/s | baseline 1.62x |
| 8 | Steady p50 | 0.2 us | 1.2 us | baseline 6.00x |
| 8 | Steady p99 | 85.1 us | 3.4 us | lock-free 25.04x |
| 8 | Steady p99.9 | 240.1 us | 39.7 us | lock-free 6.04x |
| 8 | Burst p50 | 2.0 us | 31.9 us | baseline 15.94x |
| 8 | Burst p99 | 221.2 us | 103.4 us | lock-free 2.14x |
| 8 | Burst p99.9 | 456.2 us | 303.1 us | lock-free 1.51x |

## Run-to-run stability

| Producers | Engine | Benchmark | Metric | Median | Min | Max | Spread | Stable |
|---:|---|---|---|---:|---:|---:|---:|:--:|
| 1 | baseline | burstLatency | p50 | 1.4 us | 1.4 us | 1.4 us | 0.0% | yes |
| 1 | lockfree | burstLatency | p50 | 1.1 us | 1.1 us | 1.2 us | 9.1% | yes |
| 1 | baseline | burstLatency | p99 | 3.7 us | 3.6 us | 5.0 us | 37.8% | NO |
| 1 | lockfree | burstLatency | p99 | 4.4 us | 3.9 us | 5.6 us | 38.6% | NO |
| 1 | baseline | burstLatency | p99.9 | 51.5 us | 46.6 us | 63.7 us | 33.2% | NO |
| 1 | lockfree | burstLatency | p99.9 | 71.7 us | 55.4 us | 83.4 us | 38.9% | NO |
| 1 | baseline | steadyLatency | p50 | 0.1 us | 0.1 us | 0.2 us | 100.0% | NO |
| 1 | lockfree | steadyLatency | p50 | 0.1 us | 0.1 us | 0.1 us | 0.0% | yes |
| 1 | baseline | steadyLatency | p99 | 0.6 us | 0.5 us | 0.6 us | 16.7% | yes |
| 1 | lockfree | steadyLatency | p99 | 0.6 us | 0.5 us | 0.6 us | 16.7% | yes |
| 1 | baseline | steadyLatency | p99.9 | 10.9 us | 10.7 us | 15.1 us | 40.2% | NO |
| 1 | lockfree | steadyLatency | p99.9 | 13.0 us | 10.8 us | 15.6 us | 36.9% | NO |
| 1 | baseline | throughput | ops_per_s | 19.26 M/s | 18.35 M/s | 20.49 M/s | 11.1% | NO |
| 1 | lockfree | throughput | ops_per_s | 29.38 M/s | 26.72 M/s | 30.87 M/s | 14.1% | NO |
| 2 | baseline | burstLatency | p50 | 4.7 us | 4.6 us | 5.0 us | 8.5% | yes |
| 2 | lockfree | burstLatency | p50 | 3.3 us | 3.2 us | 3.8 us | 18.2% | NO |
| 2 | baseline | burstLatency | p99 | 48.1 us | 41.1 us | 70.0 us | 60.2% | NO |
| 2 | lockfree | burstLatency | p99 | 14.4 us | 13.2 us | 15.6 us | 16.7% | yes |
| 2 | baseline | burstLatency | p99.9 | 228.6 us | 159.9 us | 369.2 us | 91.6% | NO |
| 2 | lockfree | burstLatency | p99.9 | 138.0 us | 68.0 us | 142.1 us | 53.7% | NO |
| 2 | baseline | steadyLatency | p50 | 0.2 us | 0.2 us | 0.2 us | 0.0% | yes |
| 2 | lockfree | steadyLatency | p50 | 0.3 us | 0.2 us | 0.3 us | 33.3% | NO |
| 2 | baseline | steadyLatency | p99 | 4.4 us | 4.2 us | 5.1 us | 20.4% | yes |
| 2 | lockfree | steadyLatency | p99 | 0.9 us | 0.8 us | 1.0 us | 22.2% | yes |
| 2 | baseline | steadyLatency | p99.9 | 47.0 us | 46.1 us | 47.9 us | 3.7% | yes |
| 2 | lockfree | steadyLatency | p99.9 | 18.0 us | 13.7 us | 24.2 us | 58.5% | NO |
| 2 | baseline | throughput | ops_per_s | 10.35 M/s | 9.92 M/s | 10.39 M/s | 4.5% | yes |
| 2 | lockfree | throughput | ops_per_s | 12.27 M/s | 10.85 M/s | 14.35 M/s | 28.5% | NO |
| 4 | baseline | burstLatency | p50 | 2.2 us | 2.1 us | 2.3 us | 9.1% | yes |
| 4 | lockfree | burstLatency | p50 | 17.2 us | 17.0 us | 17.6 us | 3.5% | yes |
| 4 | baseline | burstLatency | p99 | 98.3 us | 98.0 us | 121.9 us | 24.2% | yes |
| 4 | lockfree | burstLatency | p99 | 33.2 us | 32.1 us | 36.0 us | 11.7% | yes |
| 4 | baseline | burstLatency | p99.9 | 289.1 us | 281.1 us | 399.4 us | 40.9% | NO |
| 4 | lockfree | burstLatency | p99.9 | 113.3 us | 106.9 us | 146.4 us | 34.9% | NO |
| 4 | baseline | steadyLatency | p50 | 0.2 us | 0.2 us | 0.2 us | 0.0% | yes |
| 4 | lockfree | steadyLatency | p50 | 0.7 us | 0.7 us | 0.7 us | 0.0% | yes |
| 4 | baseline | steadyLatency | p99 | 40.1 us | 37.6 us | 48.3 us | 26.7% | NO |
| 4 | lockfree | steadyLatency | p99 | 1.4 us | 1.4 us | 1.6 us | 14.3% | yes |
| 4 | baseline | steadyLatency | p99.9 | 114.2 us | 100.8 us | 164.6 us | 55.9% | NO |
| 4 | lockfree | steadyLatency | p99.9 | 21.8 us | 19.4 us | 59.2 us | 182.5% | NO |
| 4 | baseline | throughput | ops_per_s | 10.68 M/s | 10.63 M/s | 11.06 M/s | 4.1% | yes |
| 4 | lockfree | throughput | ops_per_s | 7.29 M/s | 7.25 M/s | 7.32 M/s | 0.9% | yes |
| 8 | baseline | burstLatency | p50 | 2.0 us | 1.9 us | 2.1 us | 10.0% | yes |
| 8 | lockfree | burstLatency | p50 | 31.9 us | 31.4 us | 32.7 us | 4.0% | yes |
| 8 | baseline | burstLatency | p99 | 221.2 us | 205.6 us | 233.0 us | 12.4% | yes |
| 8 | lockfree | burstLatency | p99 | 103.4 us | 97.8 us | 293.4 us | 189.1% | NO |
| 8 | baseline | burstLatency | p99.9 | 456.2 us | 431.1 us | 599.0 us | 36.8% | NO |
| 8 | lockfree | burstLatency | p99.9 | 303.1 us | 249.1 us | 1421.3 us | 386.7% | NO |
| 8 | baseline | steadyLatency | p50 | 0.2 us | 0.2 us | 0.2 us | 0.0% | yes |
| 8 | lockfree | steadyLatency | p50 | 1.2 us | 1.1 us | 1.2 us | 8.3% | yes |
| 8 | baseline | steadyLatency | p99 | 85.1 us | 82.2 us | 119.7 us | 44.1% | NO |
| 8 | lockfree | steadyLatency | p99 | 3.4 us | 3.3 us | 3.6 us | 8.8% | yes |
| 8 | baseline | steadyLatency | p99.9 | 240.1 us | 205.1 us | 477.7 us | 113.5% | NO |
| 8 | lockfree | steadyLatency | p99.9 | 39.7 us | 29.3 us | 138.0 us | 273.4% | NO |
| 8 | baseline | throughput | ops_per_s | 11.23 M/s | 9.53 M/s | 11.26 M/s | 15.4% | NO |
| 8 | lockfree | throughput | ops_per_s | 6.94 M/s | 6.93 M/s | 6.96 M/s | 0.5% | yes |

**28 of 56 measurements exceeded their tolerance.**

## Warm-up check (from the .log files)

Compares the last warm-up iteration with the average of the measured iterations of the same fork (tolerance 15%). 31 of 144 forks were flagged.

| Run | Producers | Engine | Benchmark | Fork | Last warm-up | Measured mean | Difference |
|---:|---:|---|---|---:|---:|---:|---:|
| 1 | 1 | baseline | steadyLatency | 1 | 188.0 | 230.6 | 18% |
| 1 | 1 | baseline | steadyLatency | 2 | 288.1 | 215.1 | 34% |
| 1 | 1 | lockfree | steadyLatency | 1 | 448.7 | 223.2 | 101% |
| 1 | 1 | lockfree | steadyLatency | 2 | 276.2 | 341.1 | 19% |
| 1 | 2 | baseline | steadyLatency | 1 | 640.6 | 781.3 | 18% |
| 1 | 2 | baseline | steadyLatency | 2 | 819.6 | 708.6 | 16% |
| 1 | 2 | lockfree | burstLatency | 2 | 7,440.5 | 4,990.8 | 49% |
| 1 | 2 | lockfree | steadyLatency | 2 | 335.0 | 482.0 | 30% |
| 1 | 4 | baseline | steadyLatency | 2 | 2,334.3 | 1,923.3 | 21% |
| 1 | 4 | lockfree | steadyLatency | 2 | 1,175.5 | 911.7 | 29% |
| 1 | 8 | baseline | steadyLatency | 1 | 3,645.5 | 5,939.1 | 39% |
| 1 | 8 | baseline | steadyLatency | 2 | 6,160.2 | 4,326.3 | 42% |
| 1 | 8 | lockfree | steadyLatency | 1 | 1,826.4 | 2,167.2 | 16% |
| 1 | 8 | lockfree | steadyLatency | 2 | 2,127.1 | 1,604.0 | 33% |
| 2 | 1 | baseline | steadyLatency | 1 | 191.2 | 258.6 | 26% |
| 2 | 1 | baseline | steadyLatency | 2 | 408.0 | 234.5 | 74% |
| 2 | 1 | lockfree | burstLatency | 2 | 1,505.5 | 1,776.3 | 15% |
| 2 | 2 | baseline | steadyLatency | 2 | 924.4 | 661.2 | 40% |
| 2 | 2 | lockfree | burstLatency | 2 | 4,088.7 | 5,738.2 | 29% |
| 2 | 2 | lockfree | steadyLatency | 2 | 428.1 | 586.8 | 27% |
| 2 | 4 | baseline | steadyLatency | 2 | 2,523.7 | 1,827.6 | 38% |
| 2 | 4 | lockfree | steadyLatency | 1 | 1,374.1 | 920.5 | 49% |
| 2 | 8 | baseline | burstLatency | 1 | 34,651.3 | 23,163.1 | 50% |
| 3 | 1 | baseline | steadyLatency | 2 | 173.6 | 308.8 | 44% |
| 3 | 1 | lockfree | burstLatency | 2 | 1,416.2 | 1,938.2 | 27% |
| 3 | 2 | baseline | burstLatency | 1 | 9,314.0 | 7,339.1 | 27% |
| 3 | 2 | baseline | steadyLatency | 2 | 587.8 | 763.9 | 23% |
| 3 | 2 | lockfree | burstLatency | 1 | 5,235.6 | 4,485.4 | 17% |
| 3 | 2 | lockfree | throughput | 1 | 12,041,269.9 | 14,487,684.5 | 17% |
| 3 | 4 | baseline | steadyLatency | 1 | 2,351.8 | 1,941.4 | 21% |
| 3 | 8 | baseline | steadyLatency | 2 | 5,511.2 | 3,939.1 | 40% |

A flagged fork means the engine was probably still warming up (or drifting) when measurement started. Discuss whether to lengthen warm-up and rerun, and write the decision down.
