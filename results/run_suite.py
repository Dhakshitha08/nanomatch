#!/usr/bin/env python3
"""Runs the NanoMatch JMH suite for every producer count and saves the raw JSON results.

Usage (from the repository root, after `mvn clean package -DskipTests`):

    python results/run_suite.py --out results/2026-10-27 --run 1            # full suite, run number 1
    python results/run_suite.py --out results/smoke --run 1 --quick         # short smoke run
    python results/run_suite.py --out results/2026-10-27 --run 1 --dry-run  # only print the commands

For each producer count N it runs `java -jar target/benchmarks.jar EngineBenchmark -t N` (all engines, all three
benchmarks) and writes <out>/run<RUN>-t<N>.json. With --gc it adds `-prof gc` and writes run<RUN>-gc-t<N>.json
instead. It also writes <out>/ENVIRONMENT.md the first time, describing the machine.
Use python3 instead of python on macOS / Linux if needed.
"""
import argparse
import os
import platform
import subprocess
import sys
from pathlib import Path

JAR = Path("target") / "benchmarks.jar"


def java_version() -> str:
    try:
        result = subprocess.run(["java", "-version"], capture_output=True, text=True)
        return (result.stderr or result.stdout).strip().splitlines()[0]
    except OSError:
        return "java not found"


def write_environment(out: Path, producers, quick: bool) -> None:
    path = out / "ENVIRONMENT.md"
    if path.exists():
        return
    lines = [
        "# Benchmark environment",
        "",
        f"- OS: {platform.platform()}",
        f"- CPU: {platform.processor() or 'unknown'} (logical cores: {os.cpu_count()})",
        f"- Java: {java_version()}",
        f"- Python: {platform.python_version()}",
        f"- Producer counts: {', '.join(str(p) for p in producers)}",
        f"- Settings: {'QUICK (smoke) run' if quick else 'warm-up 3 x 2 s, measurement 5 x 3 s, 2 forks (annotation defaults)'}",
        "",
        "Fill in by hand: laptop model, plugged in (yes/no), power mode, other applications closed (yes/no).",
        "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", required=True, help="output folder, for example results/2026-10-27")
    parser.add_argument("--run", type=int, default=1, help="run number (1, 2, 3, ...)")
    parser.add_argument("--producers", type=int, nargs="+", default=[1, 2, 4, 8], help="producer thread counts")
    parser.add_argument("--quick", action="store_true", help="short settings for a smoke test (not for results)")
    parser.add_argument("--gc", action="store_true", help="add -prof gc (allocation per operation)")
    parser.add_argument("--dry-run", action="store_true", help="print the commands without running them")
    args = parser.parse_args()

    if not JAR.exists() and not args.dry_run:
        print(f"{JAR} not found. Run `mvn clean package -DskipTests` first, from the repository root.")
        return 1

    out = Path(args.out)
    cpus = os.cpu_count() or 1
    if not args.dry_run:
        out.mkdir(parents=True, exist_ok=True)
        write_environment(out, args.producers, args.quick)

    for producers in args.producers:
        if producers + 1 > cpus:
            print(f"WARNING: {producers} producers + 1 matching thread is more than the {cpus} logical cores; "
                  f"results for this count will be distorted by CPU sharing. Say so in the report.")
        name = f"run{args.run}-{'gc-' if args.gc else ''}t{producers}.json"
        command = ["java", "-jar", str(JAR), "EngineBenchmark", "-t", str(producers),
                   "-rf", "json", "-rff", str(out / name)]
        if args.quick:
            command += ["-f", "1", "-wi", "1", "-i", "2", "-w", "1s", "-r", "1s"]
        if args.gc:
            command += ["-prof", "gc"]
        print("\n$ " + " ".join(command), flush=True)
        if args.dry_run:
            continue
        completed = subprocess.run(command)
        if completed.returncode != 0:
            print(f"JMH failed for {producers} producers (exit code {completed.returncode}).")
            return completed.returncode
    print(f"\nDone. Results are in {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
