#!/usr/bin/env python3
"""Assembles report/REPORT.md from the section files, after checking that it is complete and consistent.

Usage (from the repository root):

    python report/assemble_report.py                      # check and build report/REPORT.md
    python report/assemble_report.py --allow-placeholders # build even if [[FILL ...]] markers remain (dry run)

Sections, in order:
    report/sections/A0_introduction.md              (Person A)
    report/sections/A_design_and_correctness.md     (Person A)
    report/sections/B_results_and_methodology.md    (Person B)
    report/sections/A9_conclusion.md                (Person A)

Checks, any of which stops the build (exit code 1):
  * every section file exists;
  * no [[FILL ...]] placeholder is left;
  * no generated block is still "(not generated yet)";
  * every image the text refers to exists.
Then it removes the HTML comments (generated-block markers and claim ids), appends Appendix A (who committed what,
from git) and Appendix B (how to reproduce, with the code version), and writes report/REPORT.md.
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

REPORT_DIR = Path("report")
SECTIONS = [
    "A0_introduction.md",
    "A_design_and_correctness.md",
    "B_results_and_methodology.md",
    "A9_conclusion.md",
]


def git(*arguments) -> str:
    try:
        result = subprocess.run(["git", *arguments], capture_output=True, text=True)
        return result.stdout.strip() if result.returncode == 0 else ""
    except OSError:
        return ""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--allow-placeholders", action="store_true")
    parser.add_argument("--out", default=str(REPORT_DIR / "REPORT.md"))
    args = parser.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")

    problems = []
    texts = []
    for name in SECTIONS:
        path = REPORT_DIR / "sections" / name
        if not path.exists():
            problems.append(f"missing section file: {path}")
            continue
        text = path.read_text(encoding="utf-8")
        for number, line in enumerate(text.splitlines(), start=1):
            if "[[FILL" in line and not args.allow_placeholders:
                problems.append(f"{path}:{number}: placeholder still present: {line.strip()[:90]}")
            if "(not generated yet)" in line:
                problems.append(f"{path}:{number}: a generated block was never generated "
                                "(run report/charts/make_charts.py)")
        # a placeholder can span several lines; flag an unclosed one too
        if text.count("[[FILL") != text.count("]]") and not args.allow_placeholders:
            problems.append(f"{path}: unbalanced [[FILL ... ]] markers")
        texts.append(text)

    body = "\n\n".join(texts)
    body = body.replace("](../charts/", "](charts/")
    for image in re.findall(r"!\[[^\]]*\]\(([^)]+)\)", body):
        if not (REPORT_DIR / image).exists():
            problems.append(f"image not found: {REPORT_DIR / image}")

    if problems:
        print("The report is not ready to assemble:\n")
        for problem in problems:
            print(f"  - {problem}")
        print(f"\n{len(problems)} problem(s). Fix them and run again.")
        return 1

    body = re.sub(r"<!--.*?-->", "", body, flags=re.DOTALL)
    body = re.sub(r"\n{3,}", "\n\n", body)

    headings = [(m.group(1), m.group(2).strip()) for m in re.finditer(r"^(#{1,2}) (.+)$", body, flags=re.MULTILINE)]
    contents = ["## Contents", ""]
    for level, title in headings[1:]:          # the first heading is the report title
        indent = "" if level == "#" else "  "
        contents.append(f"{indent}- {title}")

    shortlog = git("shortlog", "-sn", "--no-merges", "HEAD") or "(git history not available)"
    version = git("describe", "--tags", "--always") or "unknown"
    commit = git("rev-parse", "--short", "HEAD") or "unknown"

    appendix = [
        "# Appendix A: who committed what",
        "",
        "Commits per author on the final version, merge commits excluded (`git shortlog -sn --no-merges`):",
        "",
        "```",
        shortlog,
        "```",
        "",
        "Each file in the repository has exactly one owner (the table is in `docs/MERGING_REVIEW_PLAN.md`), so the history "
        "can be read per person with `git log --author=<name>`.",
        "",
        "# Appendix B: reproducing this work",
        "",
        f"Code version of this report: `{version}` (commit `{commit}`).",
        "",
        "```",
        "mvn clean test                                        # 115 tests",
        "mvn clean package -DskipTests                         # builds target/benchmarks.jar",
        "python results/run_suite.py --out results/<date> --run 1      # also --run 2 and --run 3 (about 20 minutes each)",
        "python results/analyze_runs.py --dir results/<date> --runs 1 2 3",
        "python report/charts/make_charts.py --date <date> --check",
        "python report/assemble_report.py",
        "```",
        "",
        "The raw JMH output of the runs reported here is committed under `results/2026-10-24/`.",
        "",
    ]

    # contents go after the introduction, i.e. before the second top-level heading
    marker = re.search(r"^# (?!NanoMatch)", body, flags=re.MULTILINE)
    split = marker.start() if marker else len(body)
    report = body[:split].rstrip() + "\n\n" + "\n".join(contents) + "\n\n" + body[split:].lstrip() + "\n\n" + "\n".join(appendix)
    Path(args.out).write_text(report, encoding="utf-8")

    words = len(re.findall(r"\w+", report))
    print(f"Wrote {args.out}: {len(texts)} sections, about {words} words, {len(headings)} headings.")
    if args.allow_placeholders:
        print("NOTE: built with --allow-placeholders; the report is not final.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
