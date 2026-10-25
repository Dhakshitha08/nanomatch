#!/usr/bin/env python3
"""Prints Person A's evidence numbers exactly as measured, ready to paste into the engine chapter of the report.

Usage (from the repository root):

    python report/evidence_numbers.py --date 2026-10-24

Reads:
  * results/day5-evidence/padding-on.txt and padding-off.txt   (console output of PaddingBenchmark)
  * results/<date>/A/run1-gc-t*.json                           (EngineBenchmark with -prof gc)
  * results/<date>/A/ring-scaling-t*.json                      (PaddingBenchmark at 1, 2, 4, 8 producers)
Anything that is missing is reported as missing rather than guessed.
"""
import argparse
import json
import re
import sys
from pathlib import Path


def read_text(path: Path) -> str:
    raw = path.read_bytes()
    for encoding in ("utf-8-sig", "utf-16"):
        try:
            return raw.decode(encoding)
        except UnicodeError:
            continue
    return raw.decode("latin-1")


def padding_line(path: Path) -> str:
    if not path.exists():
        return f"missing: {path}"
    for line in read_text(path).splitlines():
        m = re.search(r"PaddingBenchmark\.produce\s+thrpt\s+(\d+)\s+([\d.,]+)(?:\s+\S+\s+([\d.,]+|NaN))?\s+ops/s", line)
        if m:
            score = float(m.group(2).replace(",", "."))
            error = m.group(3)
            return f"{score:,.0f} ops/s (+/- {error} over {m.group(1)} iterations)"
    return f"no PaddingBenchmark.produce result line found in {path}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--date", required=True)
    parser.add_argument("--padding-dir", default="results/day5-evidence")
    args = parser.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")

    folder = Path("results") / args.date / "A"
    pad = Path(args.padding_dir)
    print("## Padding on vs off (PaddingBenchmark, `produce`)\n")
    print(f"- padding ON:  {padding_line(pad / 'padding-on.txt')}")
    print(f"- padding OFF: {padding_line(pad / 'padding-off.txt')}")

    print("\n## Allocation per operation (-prof gc, `gc.alloc.rate.norm`)\n")
    files = sorted(folder.glob("run*-gc-t*.json"))
    if not files:
        print(f"missing: no run*-gc-t*.json in {folder}")
    print("| Producers | Engine | Benchmark | Bytes per operation |\n|---:|---|---|---:|")
    for file in files:
        for entry in json.loads(file.read_text(encoding="utf-8")):
            norm = [v for k, v in entry.get("secondaryMetrics", {}).items() if k.endswith("gc.alloc.rate.norm")]
            if norm:
                print(f"| {entry.get('threads')} | {entry.get('params', {}).get('engine')} | "
                      f"{entry['benchmark'].rsplit('.', 1)[-1]} | {norm[0]['score']:.3f} |")

    print("\n## Ring buffer alone, producer throughput (PaddingBenchmark `produce`, do-nothing consumer)\n")
    ring = sorted(folder.glob("ring-scaling-t*.json"),
                  key=lambda f: int(re.search(r"-t(\d+)\.json$", f.name).group(1)))
    if not ring:
        print(f"missing: no ring-scaling-t*.json in {folder}")
    print("| Producers | Orders per second (all producers together) |\n|---:|---:|")
    for file in ring:
        for entry in json.loads(file.read_text(encoding="utf-8")):
            if entry["benchmark"].endswith(".produce"):
                print(f"| {entry.get('threads')} | {entry['primaryMetric']['score']:,.0f} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
