#!/usr/bin/env python3
"""Turns the frozen benchmark summary into charts, report tables and checked one-line inferences.

Usage (from the repository root):

    python report/charts/make_charts.py --date 2026-10-24            # charts + fill the generated blocks of the section
    python report/charts/make_charts.py --date 2026-10-24 --check    # also verify the claims the report text makes
    python report/charts/make_charts.py --date 2026-10-24 --no-charts --check    # only the claims check (no matplotlib needed)

Input: results/<DATE>/summary.csv (written by analyze_runs.py), plus SUMMARY.md and ENVIRONMENT.md in the same
folder. Output: report/charts/throughput.png, steady_latency.png, burst_latency.png, and the "generated blocks" in
report/sections/B_results_and_methodology.md: everything between
    <!-- BEGIN GENERATED: name -->   and   <!-- END GENERATED: name -->
is replaced. The numbers in those blocks therefore come straight from the frozen results and can never be mistyped.
Everything outside the markers is hand-written text and is left alone.
"""
import argparse
import csv
import re
import sys
from pathlib import Path

PRODUCER_LABEL = "producer threads"
ENGINES = [("baseline", "lock-based baseline"), ("lockfree", "lock-free")]


def load(csv_path: Path):
    data = {}
    with open(csv_path, newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            key = (int(row["producers"]), row["engine"], row["benchmark"], row["metric"])
            data[key] = {
                "median": float(row["median"]), "min": float(row["min"]), "max": float(row["max"]),
                "unit": row["unit"], "stable": row["stable"], "runs": int(row["runs"]),
            }
    return data


def producers_of(data):
    return sorted({k[0] for k in data})


def scale(metric):
    return (1e6, "M orders/s") if metric == "ops_per_s" else (1e3, "us")


def fmt(metric, value):
    return f"{value / 1e6:.2f} M/s" if metric == "ops_per_s" else f"{value / 1e3:.1f} us"


def cell(data, p, engine, benchmark, metric):
    d = data.get((p, engine, benchmark, metric))
    if not d:
        return "n/a"
    div, _ = scale(metric)
    if metric == "ops_per_s":
        return f"{d['median'] / div:.2f} [{d['min'] / div:.2f} to {d['max'] / div:.2f}]"
    return f"{d['median'] / div:.1f} [{d['min'] / div:.1f} to {d['max'] / div:.1f}]"


# ----------------------------------------------------------------------------------------------- comparisons

def compare(data, benchmark, metric, higher_is_better):
    """For each producer count: who is better, whether the min-max ranges are clearly separated, and by what factor."""
    result = []
    for p in producers_of(data):
        b = data.get((p, "baseline", benchmark, metric))
        l = data.get((p, "lockfree", benchmark, metric))
        if not b or not l:
            continue
        if higher_is_better:
            lf_better = l["median"] > b["median"]
            clear = l["min"] > b["max"] if lf_better else b["min"] > l["max"]
            factor = max(l["median"] / b["median"], b["median"] / l["median"])
        else:
            lf_better = l["median"] < b["median"]
            clear = l["max"] < b["min"] if lf_better else b["max"] < l["min"]
            factor = max(b["median"] / l["median"], l["median"] / b["median"])
        result.append((p, "lockfree" if lf_better else "baseline", clear, factor))
    return result


def inference_line(label, outcomes):
    groups = {"lf_clear": [], "base_clear": [], "overlap": []}
    for p, who, clear, factor in outcomes:
        if clear:
            groups["lf_clear" if who == "lockfree" else "base_clear"].append((p, factor))
        else:
            groups["overlap"].append((p, factor))

    def listing(items, with_factor=True):
        ps = ", ".join(str(p) for p, _ in items)
        if not with_factor:
            return ps
        return f"{ps} ({', '.join(f'{f:.1f}x' for _, f in items)})"

    def noun(items):
        return "producer" if len(items) == 1 and items[0][0] == 1 else "producers"

    parts = []
    if groups["lf_clear"]:
        parts.append(f"the lock-free engine is clearly better at {listing(groups['lf_clear'])} {noun(groups['lf_clear'])}")
    if groups["base_clear"]:
        parts.append(f"the baseline is clearly better at {listing(groups['base_clear'])} {noun(groups['base_clear'])}")
    if groups["overlap"]:
        parts.append(f"the min-max ranges overlap (no clear difference) at {listing(groups['overlap'], False)} {noun(groups['overlap'])}")
    return f"- **{label}:** " + "; ".join(parts) + "."


def inference_block(data):
    specs = [("Throughput", "throughput", "ops_per_s", True),
             ("Steady latency p50", "steadyLatency", "p50", False),
             ("Steady latency p99", "steadyLatency", "p99", False),
             ("Steady latency p99.9", "steadyLatency", "p99.9", False),
             ("Burst latency p50", "burstLatency", "p50", False),
             ("Burst latency p99", "burstLatency", "p99", False),
             ("Burst latency p99.9", "burstLatency", "p99.9", False)]
    lines = ["Reading guide: \"clearly better\" means the whole min-max range over the runs of one engine is better than "
             "the whole range of the other, so the difference is larger than the run-to-run variation. The number in "
             "brackets is the ratio of the medians.", ""]
    for label, benchmark, metric, higher in specs:
        outcomes = compare(data, benchmark, metric, higher)
        if outcomes:
            lines.append(inference_line(label, outcomes))
    return "\n".join(lines)


def tables_block(data):
    ps = producers_of(data)
    lines = []

    def table(title, benchmark, metrics):
        lines.append(f"**{title}** (median of the runs, with the min to max range in brackets)")
        lines.append("")
        lines.append("| Producers | Metric | Lock-based baseline | Lock-free |")
        lines.append("|---:|---|---:|---:|")
        for p in ps:
            for metric, label in metrics:
                lines.append(f"| {p} | {label} | {cell(data, p, 'baseline', benchmark, metric)} | "
                             f"{cell(data, p, 'lockfree', benchmark, metric)} |")
        lines.append("")

    table("Throughput, in millions of orders per second", "throughput", [("ops_per_s", "orders/s")])
    table("Steady latency, in microseconds", "steadyLatency", [("p50", "p50"), ("p99", "p99"), ("p99.9", "p99.9")])
    table("Burst latency (32-order burst, time until the last order is processed), in microseconds",
          "burstLatency", [("p50", "p50"), ("p99", "p99"), ("p99.9", "p99.9")])
    return "\n".join(lines)


def stability_block(data):
    total = len(data)
    unstable = [k for k, v in data.items() if v["stable"] == "NO"]
    by_metric = {}
    for k in unstable:
        by_metric[k[3]] = by_metric.get(k[3], 0) + 1
    detail = ", ".join(f"{n} of them {m}" for m, n in sorted(by_metric.items(), key=lambda x: -x[1]))
    sentence = f"{len(unstable)} of {total} measurements exceeded their run-to-run tolerance"
    return sentence + (f" ({detail})." if detail else ".")


def warmup_block(summary_path: Path):
    text = summary_path.read_text(encoding="utf-8") if summary_path.exists() else ""
    m = re.search(r"(\d+) of (\d+) forks were flagged", text)
    return (f"The warm-up check flagged {m.group(1)} of {m.group(2)} forks." if m
            else "The warm-up check result could not be found in SUMMARY.md.")


def environment_block(env_path: Path):
    if not env_path.exists():
        return "(ENVIRONMENT.md not found)"
    lines = [l for l in env_path.read_text(encoding="utf-8").splitlines() if not l.startswith("# ")]
    return "\n".join(lines).strip()


# ----------------------------------------------------------------------------------------------- claims

def claims(data):
    """The statements the hand-written report text makes about the data. Each is checked against the real numbers."""
    def med(p, engine, benchmark, metric):
        return data[(p, engine, benchmark, metric)]["median"]

    def lf_higher(p):
        return med(p, "lockfree", "throughput", "ops_per_s") > med(p, "baseline", "throughput", "ops_per_s")

    def base_higher(p):
        return med(p, "baseline", "throughput", "ops_per_s") > med(p, "lockfree", "throughput", "ops_per_s")

    def lf_clearly_lower(p, benchmark, metric):
        return data[(p, "lockfree", benchmark, metric)]["max"] < data[(p, "baseline", benchmark, metric)]["min"]

    def base_lower_median(p, benchmark, metric):
        return med(p, "baseline", benchmark, metric) < med(p, "lockfree", benchmark, metric)

    return [
        ("C1", "Lock-free throughput is higher than the baseline's at 1 and 2 producers (medians)",
         lambda: all(lf_higher(p) for p in (1, 2))),
        ("C2", "The baseline's throughput is higher than the lock-free engine's at 4 and 8 producers (medians)",
         lambda: all(base_higher(p) for p in (4, 8))),
        ("C3", "Steady-latency p99 is clearly lower for the lock-free engine at 2, 4 and 8 producers (ranges do not overlap)",
         lambda: all(lf_clearly_lower(p, "steadyLatency", "p99") for p in (2, 4, 8))),
        ("C4", "Burst-latency p99 is clearly lower for the lock-free engine at 2, 4 and 8 producers (ranges do not overlap)",
         lambda: all(lf_clearly_lower(p, "burstLatency", "p99") for p in (2, 4, 8))),
        ("C5", "Steady-latency p50 is lower for the baseline than for the lock-free engine at 4 and 8 producers (medians)",
         lambda: all(base_lower_median(p, "steadyLatency", "p50") for p in (4, 8))),
        ("C6", "Burst-latency p50 is lower for the baseline than for the lock-free engine at 4 and 8 producers (medians)",
         lambda: all(base_lower_median(p, "burstLatency", "p50") for p in (4, 8))),
        ("C8", "The lock-free engine's own throughput with 1 producer is higher than with 4 producers and than with 8 producers",
         lambda: all(med(1, "lockfree", "throughput", "ops_per_s") > med(p, "lockfree", "throughput", "ops_per_s")
                     for p in (4, 8))),
        ("C7", "With 1 producer the two engines' p99 medians are within 25 % of each other, steady and burst",
         lambda: all(max(med(1, "baseline", b, "p99"), med(1, "lockfree", b, "p99"))
                     / min(med(1, "baseline", b, "p99"), med(1, "lockfree", b, "p99")) <= 1.25
                     for b in ("steadyLatency", "burstLatency"))),
    ]


def run_checks(data):
    failed = 0
    print("Claims made by the report text, checked against the frozen results:")
    for name, text, predicate in claims(data):
        try:
            ok = bool(predicate())
        except KeyError as missing:
            ok = False
            text += f" (missing data: {missing})"
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}: {text}")
        failed += 0 if ok else 1
    if failed:
        print(f"\n{failed} claim(s) do NOT hold for these numbers. Rewrite the sentence(s) in "
              "report/sections/B_results_and_methodology.md that use them (search for the claim id, e.g. C3), "
              "so that the text says what the data says. Do not change the data.")
    else:
        print("\nAll claims hold.")
    return failed


# ----------------------------------------------------------------------------------------------- charts

def make_charts(data, out_dir: Path):
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("matplotlib is not installed. Install it with:  python -m pip install matplotlib   and run again.")
        return False

    out_dir.mkdir(parents=True, exist_ok=True)
    ps = producers_of(data)
    style = {"baseline": ("#c0504d", "o", "lock-based baseline"), "lockfree": ("#1f6fb5", "s", "lock-free")}

    def plot_metric(ax, benchmark, metric, title, log_y):
        div, unit = scale(metric)
        for engine, (color, marker, label) in style.items():
            xs = [p for p in ps if (p, engine, benchmark, metric) in data]
            if not xs:
                continue
            med = [data[(p, engine, benchmark, metric)]["median"] / div for p in xs]
            lo = [m - data[(p, engine, benchmark, metric)]["min"] / div for m, p in zip(med, xs)]
            hi = [data[(p, engine, benchmark, metric)]["max"] / div - m for m, p in zip(med, xs)]
            ax.errorbar(xs, med, yerr=[lo, hi], color=color, marker=marker, capsize=3, linewidth=1.6, label=label)
        ax.set_xscale("log", base=2)
        ax.set_xticks(ps)
        ax.set_xticklabels([str(p) for p in ps])
        if log_y:
            ax.set_yscale("log")
        ax.set_xlabel(PRODUCER_LABEL)
        ax.set_ylabel(unit)
        ax.set_title(title)
        ax.grid(True, which="both", alpha=0.3)

    fig, ax = plt.subplots(figsize=(6.6, 4.2))
    plot_metric(ax, "throughput", "ops_per_s", "Throughput (higher is better)", False)
    ax.legend()
    fig.text(0.01, 0.01, "median of the runs; bars show min to max", fontsize=7, color="gray")
    fig.tight_layout(rect=(0, 0.03, 1, 1))
    fig.savefig(out_dir / "throughput.png", dpi=150)
    plt.close(fig)

    for benchmark, filename, title in [("steadyLatency", "steady_latency.png", "Steady latency"),
                                       ("burstLatency", "burst_latency.png", "Burst latency (32-order burst)")]:
        fig, axes = plt.subplots(1, 3, figsize=(12, 4.0))
        for ax, (metric, name) in zip(axes, [("p50", "p50"), ("p99", "p99"), ("p99.9", "p99.9")]):
            plot_metric(ax, benchmark, metric, f"{title}: {name} (lower is better)", True)
        axes[0].legend()
        fig.text(0.01, 0.01, "median of the runs; bars show min to max; log scale", fontsize=7, color="gray")
        fig.tight_layout(rect=(0, 0.03, 1, 1))
        fig.savefig(out_dir / filename, dpi=150)
        plt.close(fig)
    print(f"Wrote charts to {out_dir}")
    return True


# ----------------------------------------------------------------------------------------------- section update

def update_section(section_path: Path, blocks):
    text = section_path.read_text(encoding="utf-8")
    for name, content in blocks.items():
        pattern = re.compile(rf"(<!-- BEGIN GENERATED: {name} -->)(.*?)(<!-- END GENERATED: {name} -->)", re.DOTALL)
        if not pattern.search(text):
            print(f"ERROR: markers for the generated block '{name}' not found in {section_path}")
            return False
        text = pattern.sub(lambda m: f"{m.group(1)}\n{content}\n{m.group(3)}", text)
    section_path.write_text(text, encoding="utf-8")
    print(f"Updated the generated blocks in {section_path}: {', '.join(blocks)}")
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--date", required=True, help="results folder name, for example 2026-10-24")
    parser.add_argument("--csv", help="override: path to summary.csv")
    parser.add_argument("--section", default="report/sections/B_results_and_methodology.md")
    parser.add_argument("--out", default="report/charts")
    parser.add_argument("--check", action="store_true", help="verify the claims made by the report text")
    parser.add_argument("--no-charts", action="store_true", help="do not draw charts (and do not need matplotlib)")
    parser.add_argument("--no-section", action="store_true", help="do not touch the section file")
    args = parser.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")

    folder = Path("results") / args.date
    csv_path = Path(args.csv) if args.csv else folder / "summary.csv"
    if not csv_path.exists():
        print(f"{csv_path} not found. Run analyze_runs.py first.")
        return 1
    data = load(csv_path)

    status = 0
    if not args.no_charts and not make_charts(data, Path(args.out)):
        status = 2
    if not args.no_section:
        blocks = {"tables": tables_block(data), "inferences": inference_block(data),
                  "stability": stability_block(data), "warmup": warmup_block(folder / "SUMMARY.md"),
                  "environment": environment_block(folder / "ENVIRONMENT.md")}
        if not update_section(Path(args.section), blocks):
            status = 1
    if args.check and run_checks(data):
        status = 1
    return status


if __name__ == "__main__":
    sys.exit(main())
