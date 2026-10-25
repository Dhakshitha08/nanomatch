#!/usr/bin/env python3
"""Runs the NanoMatch JMH suite for every producer count and saves the raw JSON results and the console logs.

Usage (from the repository root, after `mvn clean package -DskipTests`):

    python results/run_suite.py --out results/2026-10-27 --run 1            # full suite, run number 1
    python results/run_suite.py --out results/smoke --run 1 --quick         # short smoke run
    python results/run_suite.py --out results/2026-10-27 --run 1 --dry-run  # only print the commands

For each producer count N it runs `java -jar target/benchmarks.jar EngineBenchmark -t N` (all engines, all three
benchmarks) and writes <out>/run<RUN>-t<N>.json plus the complete console output as <out>/run<RUN>-t<N>.log.
With --gc it adds `-prof gc` and uses run<RUN>-gc-t<N>.* instead. It also writes <out>/ENVIRONMENT.md the first
time, describing the machine. Use python3 instead of python on macOS / Linux if needed.
"""
import argparse
import os
import platform
import subprocess
import sys
import time
from pathlib import Path

JAR = Path("target") / "benchmarks.jar"


def java_version() -> str:
    try:
        result = subprocess.run(["java", "-version"], capture_output=True, text=True)
        return (result.stderr or result.stdout).strip().splitlines()[0]
    except OSError:
        return "java not found"


def git_output(*arguments) -> str:
    try:
        result = subprocess.run(["git", *arguments], capture_output=True, text=True)
        return result.stdout.strip() if result.returncode == 0 else "unknown"
    except OSError:
        return "unknown"


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
        f"- Code version (git): {git_output('describe', '--tags', '--always', '--dirty')} "
        f"(commit {git_output('rev-parse', '--short', 'HEAD')})",
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

    # JMH prints characters such as the plus-minus sign; never crash on a console that cannot show them.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")

    if not JAR.exists() and not args.dry_run:
        print(f"{JAR} not found. Run `mvn clean package -DskipTests` first, from the repository root.")
        return 1

    out = Path(args.out)
    cpus = os.cpu_count() or 1
    if not args.dry_run:
        out.mkdir(parents=True, exist_ok=True)
        write_environment(out, args.producers, args.quick)

    started = time.time()
    for producers in args.producers:
        if producers + 1 > cpus:
            print(f"WARNING: {producers} producers + 1 matching thread is more than the {cpus} logical cores; "
                  f"results for this count will be distorted by CPU sharing. Say so in the report.")
        stem = f"run{args.run}-{'gc-' if args.gc else ''}t{producers}"
        command = ["java", "-jar", str(JAR), "EngineBenchmark", "-t", str(producers),
                   "-rf", "json", "-rff", str(out / (stem + ".json"))]
        if args.quick:
            command += ["-f", "1", "-wi", "1", "-i", "2", "-w", "1s", "-r", "1s"]
        if args.gc:
            command += ["-prof", "gc"]
        print("\n$ " + " ".join(command), flush=True)
        if args.dry_run:
            continue
        with open(out / (stem + ".log"), "w", encoding="utf-8") as log:
            log.write("$ " + " ".join(command) + "\n")
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                       encoding="utf-8", errors="replace", bufsize=1)
            for line in process.stdout:
                sys.stdout.write(line)
                log.write(line)
            returncode = process.wait()
        if returncode != 0:
            print(f"JMH failed for {producers} producers (exit code {returncode}). See {out / (stem + '.log')}")
            return returncode
        print(f"[{(time.time() - started) / 60:.1f} min elapsed]", flush=True)
    print(f"\nDone. Results are in {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
