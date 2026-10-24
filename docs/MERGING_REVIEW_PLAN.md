# Merging and Review Plan

Goal: **zero merge conflicts**, and a `main` branch whose history clearly shows both team members' work.

## 1. Branches

| Branch | Who commits | Purpose |
|---|---|---|
| `main` | Nobody commits directly. Only merges from `person-a` and `person-b`, and tags | Always green (`mvn clean test` passes). This is what the professor sees |
| `person-a` | Dhakshitha S | Person A's working branch |
| `person-b` | Esther Philemena S | Person B's working branch |

Each person merges **their own** branch into `main` after their turn. So `main`'s history shows merge commits
and underlying commits from both people.

## 2. Why conflicts cannot happen (the four rules)

A merge conflict only happens when two branches change the **same lines of the same file**. So:

1. **One owner per file.** Every file below has exactly one owner. You only create or edit files you own.
   New files in a shared *folder* are fine; the rule is per file, not per folder.
2. **Never edit someone else's file.** If you need a change in their file, message them. The owner makes the
   change in their next turn.
3. **Always bring `main` into your branch before you start**, and merge your branch into `main` only after your
   tests pass. Your branch then already contains everything on `main`, so the merge cannot clash.
4. **No direct commits to `main`, no force pushes, no rebasing of shared branches.**

### File ownership table

| Path | Owner | Notes |
|---|---|---|
| `pom.xml`, `.gitignore`, `.gitattributes` | A | B asks A if a dependency is needed |
| `README.md`, `PLAN.md` | A | A updates the status table |
| `docs/PROJECT_OVERVIEW.md`, `docs/MERGING_REVIEW_PLAN.md`, `docs/BENCHMARKING_PLAN.md`, `docs/PERSON_A_PLAN.md`, `docs/PERSON_B_PLAN.md` | A | |
| `docs/SCOPE_LOCK.md` | A created it; **B edits only her own sign-off line, once (Day 0)**; A never edits it again | Only one side changes the file after Day 0 |
| `docs/JMH_NOTES.md` | B | |
| `src/main/java/com/nanomatch/model/**` | A | `MatchingEngine` interface frozen at end of Day 1 |
| `src/main/java/com/nanomatch/smoke/**` | A | Day 1 JMH smoke test |
| `src/main/java/com/nanomatch/baseline/**` | A | |
| `src/main/java/com/nanomatch/lockfree/**` | A | |
| `src/main/java/com/nanomatch/orderbook/**` | A | B reviews, does not edit |
| `src/main/java/com/nanomatch/sim/**` | B | Order generator, load simulator |
| `src/main/java/com/nanomatch/bench/**` | B | JMH benchmark harness |
| `src/test/java/com/nanomatch/engine/**` | A | A's unit tests for their own classes |
| `src/test/java/com/nanomatch/verify/**` | B | Contract, concurrency, parity, simulator tests |
| `results/<date>/**` | B | Raw JMH JSON, `ENVIRONMENT.md`. A adds only a separately named file, `results/<date>/gc-profile-A.txt` |
| `results/run_suite.py` | B | Script that runs the full JMH suite and writes the JSON files |
| `results/day5-evidence/**` | A | A's padding A/B and GC-profile output files from Day 5 |
| `results/<date>/A/**` | A | A's Day 6 evidence (GC profile, ring scaling) and `review-A.md`. B never edits this folder |
| `report/charts/**` | B | Chart script and PNGs |
| `report/sections/A_design_and_correctness.md` | A | |
| `report/sections/B_results_and_methodology.md` | B | |
| `report/REPORT.md` | A | Assembled on Day 7 from the two section files |
| Tags (`baseline-freeze`, `parity-verified`, `lockfree-freeze`, `benchmarks-frozen`, `v1.0`) | A | |

Package layout (adapted from the original top-level-folder layout, to avoid multi-module Maven overhead in a
one-week schedule): `baseline_engine` = `baseline/`, `lockfree_engine` = `lockfree/`, `orderbook` = `orderbook/`,
`load_simulator` = `sim/`, `benchmarks` = `bench/`.

## 3. Daily routine (exact git flow)

Replace `N` and the message. The day's `COMMANDS.md` will contain this already filled in.

**Person A turn**
```
git checkout person-a
git pull origin person-a
git fetch origin
git merge --no-edit origin/main        # brings in B's work from the previous day
# copy the day's files from the zip into the repo (paths are already repo-relative)
mvn clean test                         # must pass before you go on
git add -A
git commit -m "dayN(A): <what was done>"
git push origin person-a
git checkout main
git pull origin main
git merge --no-ff person-a -m "dayN(A): merge person-a into main"
git push origin main
git checkout person-a
```

**Person B turn** (after A has pushed and merged)
```
git checkout person-b
git pull origin person-b
git fetch origin
git merge --no-edit origin/main        # brings in A's work from today
# copy the day's files from the zip into the repo
mvn clean test
git add -A
git commit -m "dayN(B): <what was done>"
git push origin person-b
git checkout main
git pull origin main
git merge --no-ff person-b -m "dayN(B): merge person-b into main"
git push origin main
git checkout person-b
```

Commit message format: `dayN(A): ...` or `dayN(B): ...`. Always set your own `user.name` and `user.email`
(the email must be the one linked to your GitHub account) so GitHub credits the commits to you.

### If something goes wrong
- **`mvn clean test` fails:** do **not** merge into `main`. Push only your own branch, tell the other person,
  fix it, and merge once it is green.
- **`git push` is rejected:** run `git pull origin <your-branch>` and push again. Never use `--force`.
- **A conflict appears anyway** (it means someone edited a file they don't own): run `git merge --abort`,
  message the other person, and sort out together who owns that change. Never resolve it by overwriting
  their version.

## 4. Integration checkpoints

| # | Checkpoint | Day | What is reviewed | Tag |
|---|---|---|---|---|
| 1 | Scope lock | 0 | Both signatures in `SCOPE_LOCK.md` | |
| 2 | Baseline freeze | 2 | Baseline engine + correctness suite, both on `main`, suite green | `baseline-freeze` |
| 3 | Correctness parity | 4 | Lock-free vs baseline identical on same inputs | `parity-verified` |
| 4 | Lock-free freeze | 5 | Finalized lock-free engine, full suite green, `-prof gc` shows ~no allocation | `lockfree-freeze` |
| 5 | Benchmark methodology review | 6 | Settings documented, 3 runs stable, comparison fair | `benchmarks-frozen` |
| 6 | Final review | 7 | Report matches frozen numbers; fresh clone works | `v1.0` |

**Checkpoint step (A, after B's turn).** On checkpoint days, once both of you have merged into `main`:
```
git checkout main
git pull origin main
mvn clean test
git tag <tag-name>
git push origin <tag-name>
git checkout person-a
git merge --no-edit main
```
Both of you must have run the suite locally on that same `main` commit (B ran it before her merge, A runs it
here). A quick glance at a diff is not enough.

## 5. Review standards at each checkpoint

**Checkpoint 1: Scope lock (Day 0).** Is the scope single-instrument, in-memory, single-machine? Any "just one
more feature" idea is written in `SCOPE_LOCK.md` under future work, not built.

**Checkpoint 2: Baseline freeze (Day 2).**
- Does the baseline enforce price-time priority under a simple **concurrent** test, not only single-threaded?
- Is JMH integrated and running a trivial benchmark (not just installed)?

**Checkpoint 3: Correctness parity (Day 4), the most important checkpoint.**
- Identical input sequences through both engines; the trade sequences must match exactly.
- If they don't match, stop and fix it together. An incorrect lock-free engine invalidates every benchmark
  that follows. Nothing red goes into `main`.

**Checkpoint 4: Lock-free freeze (Day 5).**
- Full correctness suite on the **finalized** lock-free engine, not the Day 4 version.
- Padding and allocation changes did not break correctness.

**Checkpoint 5: Benchmark methodology review (Day 6).**
- Warm-up iterations and fork count are reasonable and documented with the reason.
- Results come from repeated runs and are stable within the agreed tolerance.
- Both of you independently agree the comparison is fair: same hardware, same load pattern, same
  measurement window.

**Checkpoint 6: Final review (Day 7).**
- Every chart has an explicit one-line inference.
- Report numbers match the frozen result files exactly, with no loose rounding.
- A fresh clone builds, passes tests, and runs a benchmark.

## 6. Showing the project to the professor as a team project

The history will show what each of you really did, as long as each person commits from their own account:
```
git shortlog -sn --all                 # commits per person
git log --graph --oneline --all        # branch and merge history
```
On GitHub: **Insights, then Contributors**, and the commit list on `main`. Each day both of you have commits,
so the history shows steady work from both sides, and the file ownership table above doubles as your
"who built what" answer.
