#!/usr/bin/env python3
"""Summarises repeated NanoMatch JMH runs: median and range across runs, run-to-run stability, warm-up check.

Usage (from the repository root):

    python results/analyze_runs.py --dir results/2026-10-27 --runs 1 2 3
    python results/analyze_runs.py --dir results/smoke --runs 1                 # works on a single run too

Reads <dir>/run<N>-t<T>.json (and the matching .log files, if present) and writes SUMMARY.md and summary.csv
(into --out, which defaults to --dir). summary.csv is the input for the charts script (Day 7).

What it reports, per producer count and engine:
  * throughput (orders per second), and steady / burst latency p50, p99, p99.9
  * the median over the runs, the smallest and the largest value, and the spread (max - min) / median
  * UNSTABLE when the spread is larger than the tolerance: --tolerance (default 10 %) for throughput and p50,
    --tail-tolerance (default 25 %) for p99 and p99.9, because high percentiles are estimated from few samples
    and naturally vary more. Choosing these two numbers is a team decision: write it down in the methodology review.
  * a warm-up check from the .log files: is the last warm-up iteration within --warmup-tolerance (default 15 %)
    of the average of the measured iterations?
"""
import argparse
import csv
import json
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

BENCHMARKS = ["throughput", "steadyLatency", "burstLatency"]
PERCENTILES = [("p50", 50.0), ("p99", 99.0), ("p99.9", 99.9)]


def load_json(path: Path):
    """Yields (producers, engine, benchmark, metric, unit, value) for one JMH JSON file."""
    producer_match = re.search(r"-t(\d+)\.json$", path.name)
    data = json.loads(path.read_text(encoding="utf-8"))
    for entry in data:
        benchmark = entry["benchmark"].rsplit(".", 1)[-1]
        producers = entry.get("threads") or (int(producer_match.group(1)) if producer_match else 0)
        engine = entry.get("params", {}).get("engine", "?")
        primary = entry["primaryMetric"]
        unit = primary.get("scoreUnit", "")
        if benchmark == "throughput":
            yield producers, engine, benchmark, "ops_per_s", unit, float(primary["score"])
        else:
            percentiles = {float(k): float(v) for k, v in primary.get("scorePercentiles", {}).items()}
            for name, wanted in PERCENTILES:
                match = [v for k, v in percentiles.items() if abs(k - wanted) < 1e-9]
                if match:
                    yield producers, engine, benchmark, name, unit, match[0]


def spread(values):
    if len(values) < 2:
        return 0.0
    median = statistics.median(values)
    return (max(values) - min(values)) / median if median else 0.0


def parse_logs(directory: Path, runs):
    """Returns {(run, producers, engine, benchmark, fork): ([warm-up values], [measured values])}."""
    result = {}
    for run in runs:
        for log in sorted(directory.glob(f"run{run}-t*.log")):
            if "-gc-" in log.name:
                continue
            producers = int(re.search(r"-t(\d+)\.log$", log.name).group(1))
            benchmark = engine = None
            fork = 0
            for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
                m = re.match(r"# Benchmark: .*\.(\w+)$", line)
                if m:
                    benchmark = m.group(1)
                m = re.match(r"# Parameters: .*engine = (\w+)", line)
                if m:
                    engine = m.group(1)
                m = re.match(r"# Fork: (\d+) of", line)
                if m:
                    fork = int(m.group(1))
                m = re.match(r"(# Warmup )?Iteration\s+\d+:\s+([\d.,]+)", line)
                if m and benchmark and engine:
                    value = float(m.group(2).replace(",", "."))
                    key = (run, producers, engine, benchmark, fork)
                    warm, measured = result.setdefault(key, ([], []))
                    (warm if m.group(1) else measured).append(value)
    return result


def fmt(metric, value):
    if metric == "ops_per_s":
        return f"{value / 1e6:.2f} M/s"
    return f"{value / 1000:.1f} us"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dir", required=True, help="folder with run<N>-t<T>.json files")
    parser.add_argument("--runs", type=int, nargs="+", default=[1, 2, 3])
    parser.add_argument("--out", help="where to write SUMMARY.md and summary.csv (default: --dir)")
    parser.add_argument("--tolerance", type=float, default=0.10)
    parser.add_argument("--tail-tolerance", type=float, default=0.25)
    parser.add_argument("--warmup-tolerance", type=float, default=0.15)
    args = parser.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")

    directory = Path(args.dir)
    out = Path(args.out) if args.out else directory
    out.mkdir(parents=True, exist_ok=True)

    values = defaultdict(dict)   # (producers, engine, benchmark, metric) -> {run: value}
    units = {}
    found_runs = []
    for run in args.runs:
        files = sorted(directory.glob(f"run{run}-t*.json"))
        files = [f for f in files if "-gc-" not in f.name]
        if not files:
            print(f"WARNING: no JSON files for run {run} in {directory}")
            continue
        found_runs.append(run)
        for file in files:
            for producers, engine, benchmark, metric, unit, value in load_json(file):
                values[(producers, engine, benchmark, metric)][run] = value
                units[(benchmark, metric)] = unit
    if not values:
        print("No results found.")
        return 1

    rows = []
    for key in sorted(values, key=lambda k: (k[0], k[2], k[3], k[1])):
        producers, engine, benchmark, metric = key
        per_run = [values[key][r] for r in found_runs if r in values[key]]
        tolerance = args.tail_tolerance if metric in ("p99", "p99.9") else args.tolerance
        s = spread(per_run)
        rows.append({
            "producers": producers, "engine": engine, "benchmark": benchmark, "metric": metric,
            "unit": units.get((benchmark, metric), ""), "runs": len(per_run),
            "values": ";".join(f"{v:.3f}" for v in per_run), "median": statistics.median(per_run),
            "min": min(per_run), "max": max(per_run), "spread_pct": round(100 * s, 1),
            "tolerance_pct": round(100 * tolerance, 1), "stable": "yes" if (len(per_run) < 2 or s <= tolerance) else "NO",
        })

    with open(out / "summary.csv", "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)

    by_key = {(r["producers"], r["engine"], r["benchmark"], r["metric"]): r for r in rows}
    producers_list = sorted({r["producers"] for r in rows})

    lines = [f"# Benchmark summary ({len(found_runs)} run(s): {', '.join(map(str, found_runs))})", "",
             f"Source: `{directory}`. Values are the median over the runs; the range is min to max.",
             f"Stability tolerance: {args.tolerance:.0%} for throughput and p50, {args.tail_tolerance:.0%} for p99 and p99.9 "
             "(spread = (max - min) / median).", ""]

    lines += ["## Engine comparison (medians)", "",
              "| Producers | Metric | Baseline | Lock-free | Better by |", "|---:|---|---:|---:|---|"]
    for p in producers_list:
        for benchmark, metric, label in [("throughput", "ops_per_s", "Throughput"),
                                         ("steadyLatency", "p50", "Steady p50"), ("steadyLatency", "p99", "Steady p99"),
                                         ("steadyLatency", "p99.9", "Steady p99.9"), ("burstLatency", "p50", "Burst p50"),
                                         ("burstLatency", "p99", "Burst p99"), ("burstLatency", "p99.9", "Burst p99.9")]:
            b = by_key.get((p, "baseline", benchmark, metric))
            l = by_key.get((p, "lockfree", benchmark, metric))
            if not b or not l:
                continue
            if metric == "ops_per_s":
                ratio, who = l["median"] / b["median"], ("lock-free" if l["median"] >= b["median"] else "baseline")
                factor = max(ratio, 1 / ratio)
            else:
                ratio, who = b["median"] / l["median"], ("lock-free" if b["median"] >= l["median"] else "baseline")
                factor = max(ratio, 1 / ratio)
            lines.append(f"| {p} | {label} | {fmt(metric, b['median'])} | {fmt(metric, l['median'])} | {who} {factor:.2f}x |")
    lines.append("")

    lines += ["## Run-to-run stability", "",
              "| Producers | Engine | Benchmark | Metric | Median | Min | Max | Spread | Stable |",
              "|---:|---|---|---|---:|---:|---:|---:|:--:|"]
    unstable = 0
    for r in rows:
        if r["stable"] == "NO":
            unstable += 1
        lines.append(f"| {r['producers']} | {r['engine']} | {r['benchmark']} | {r['metric']} | {fmt(r['metric'], r['median'])} | "
                     f"{fmt(r['metric'], r['min'])} | {fmt(r['metric'], r['max'])} | {r['spread_pct']}% | {r['stable']} |")
    lines += ["", f"**{unstable} of {len(rows)} measurements exceeded their tolerance.**", ""]

    logs = parse_logs(directory, found_runs)
    if logs:
        flagged = []
        for (run, p, engine, benchmark, fork), (warm, measured) in sorted(logs.items()):
            if not warm or not measured:
                continue
            mean = statistics.mean(measured)
            drift = abs(warm[-1] - mean) / mean if mean else 0
            if drift > args.warmup_tolerance:
                flagged.append((run, p, engine, benchmark, fork, warm[-1], mean, drift))
        lines += ["## Warm-up check (from the .log files)", "",
                  f"Compares the last warm-up iteration with the average of the measured iterations of the same fork "
                  f"(tolerance {args.warmup_tolerance:.0%}). {len(flagged)} of {len(logs)} forks were flagged.", ""]
        if flagged:
            lines += ["| Run | Producers | Engine | Benchmark | Fork | Last warm-up | Measured mean | Difference |",
                      "|---:|---:|---|---|---:|---:|---:|---:|"]
            for run, p, engine, benchmark, fork, last, mean, drift in flagged:
                lines.append(f"| {run} | {p} | {engine} | {benchmark} | {fork} | {last:,.1f} | {mean:,.1f} | {drift:.0%} |")
            lines += ["", "A flagged fork means the engine was probably still warming up (or drifting) when measurement "
                      "started. Discuss whether to lengthen warm-up and rerun, and write the decision down.", ""]
    else:
        lines += ["## Warm-up check", "", "No .log files found, so the warm-up check was skipped.", ""]

    (out / "SUMMARY.md").write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines))
    print(f"\nWrote {out / 'SUMMARY.md'} and {out / 'summary.csv'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
