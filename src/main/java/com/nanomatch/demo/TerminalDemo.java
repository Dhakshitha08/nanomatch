package com.nanomatch.demo;

import com.nanomatch.model.Trade;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Terminal version of the demo: runs the scripted scenarios on the real engines, a short live comparison with
 * a parity check, and prints the measured JMH results.
 *
 * <pre>
 * java -cp target/benchmarks.jar com.nanomatch.demo.TerminalDemo [options]
 *   --engine baseline|lockfree|both   engines for the scenarios (default both)
 *   --scenario KEY|all                 match, nomatch, partial, price, time, execprice, sweep (default all)
 *   --no-live                          skip the live comparison
 *   --no-results                       skip the measured benchmark results
 * </pre>
 */
public final class TerminalDemo {

    private TerminalDemo() {
    }

    public static void main(String[] args) throws Exception {
        List<EngineKind> engines = List.of(EngineKind.LOCK_BASED, EngineKind.LOCK_FREE);
        List<DemoScenario> scenarios = DemoScenarios.all();
        boolean live = true;
        boolean results = true;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--engine":
                    String engine = args[++i];
                    engines = "both".equalsIgnoreCase(engine) ? engines : List.of(EngineKind.fromCliName(engine));
                    break;
                case "--scenario":
                    String key = args[++i];
                    scenarios = "all".equalsIgnoreCase(key) ? scenarios : List.of(DemoScenarios.byKey(key));
                    break;
                case "--no-live":
                    live = false;
                    break;
                case "--no-results":
                    results = false;
                    break;
                case "--help":
                case "-h":
                    System.out.println("Options: --engine baseline|lockfree|both  --scenario KEY|all  --no-live  "
                            + "--no-results");
                    return;
                default:
                    System.err.println("Unknown option " + args[i] + " (try --help)");
                    System.exit(2);
            }
        }

        banner("NanoMatch terminal demo");
        System.out.println("Every order below is sent to the real engine; every trade is reported by the engine.");
        int failures = 0;
        for (EngineKind kind : engines) {
            banner("Engine: " + kind.displayName() + " (" + kind.className() + ")");
            for (DemoScenario scenario : scenarios) {
                if (!runScenario(kind, scenario)) {
                    failures++;
                }
            }
        }
        if (live) {
            liveComparison();
        }
        if (results) {
            measuredResults();
        }
        banner(failures == 0 ? "All scenarios passed." : failures + " scenario(s) FAILED.");
        System.exit(failures == 0 ? 0 : 1);
    }

    static boolean runScenario(EngineKind kind, DemoScenario scenario) {
        System.out.println();
        System.out.println("[" + scenario.title() + "]");
        System.out.println("  Rule: " + scenario.rule());
        List<Trade> actual = new ArrayList<>();
        try (EngineSession session = new EngineSession(kind)) {
            int n = 1;
            for (DemoScenario.Step step : scenario.steps()) {
                var order = step.order();
                SubmitResult result = session.submitAndWait(order.id(), order.side(), order.price(), order.quantity());
                System.out.printf(Locale.ROOT, "  %d. %-4s #%-3d %4d @ %-5d  %s%n", n++, order.side(), order.id(),
                        order.quantity(), order.price(), step.narration());
                if (!result.accepted()) {
                    System.out.println("       REJECTED: " + result.message());
                    continue;
                }
                for (SessionSnapshot.Execution fill : result.fills()) {
                    System.out.printf(Locale.ROOT, "       TRADE  buy #%d / sell #%d  %d @ %d%n", fill.buyOrderId(),
                            fill.sellOrderId(), fill.quantity(), fill.price());
                    actual.add(new Trade(fill.buyOrderId(), fill.sellOrderId(), fill.price(), fill.quantity()));
                }
                if (result.restedQuantity() > 0) {
                    System.out.println("       rests " + result.restedQuantity() + " on the book");
                }
            }
            SessionSnapshot after = session.snapshot();
            System.out.println("  Book after: " + describeBook(after));
            System.out.println("  Engine's own report: best bid " + price(after.engineView().bestBid())
                    + ", best ask " + price(after.engineView().bestAsk()) + " -> "
                    + (after.engineView().consistent() ? "matches the book above" : "DOES NOT MATCH"));
        }
        DemoScenario.Check check = scenario.check(actual);
        System.out.println("  " + check.summary());
        System.out.println("  " + scenario.explanation());
        return check.passed();
    }

    private static String describeBook(SessionSnapshot s) {
        StringBuilder sb = new StringBuilder("BIDS ");
        appendLevels(sb, s.bids());
        sb.append(" | ASKS ");
        appendLevels(sb, s.asks());
        if (s.spread() >= 0) {
            sb.append(" | spread ").append(s.spread());
        }
        return sb.toString();
    }

    private static void appendLevels(StringBuilder sb, List<SessionSnapshot.Level> levels) {
        if (levels.isEmpty()) {
            sb.append("(none)");
            return;
        }
        for (int i = 0; i < levels.size(); i++) {
            SessionSnapshot.Level level = levels.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(level.price()).append(": ").append(level.totalQuantity()).append(" [");
            for (int j = 0; j < level.queue().size(); j++) {
                SessionSnapshot.RestingOrder o = level.queue().get(j);
                sb.append(j == 0 ? "" : " ").append('#').append(o.orderId()).append('=').append(o.remainingQuantity());
            }
            sb.append(']');
        }
    }

    private static String price(long p) {
        return p == 0 ? "none" : Long.toString(p);
    }

    private static void liveComparison() throws InterruptedException {
        banner("Live comparison (quick run, NOT the JMH benchmark)");
        int producers = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        System.out.println(producers + " producer threads x 50,000 orders, 3 timed runs per engine, max speed.");
        ComparisonRun.Result r = ComparisonRun.run(producers, 50_000, 3, 42, msg -> System.out.println("  " + msg));
        for (ComparisonRun.EngineMeasurement m : List.of(r.baseline(), r.lockFree())) {
            System.out.printf(Locale.ROOT, "  %-24s median %6.2f M orders/s  (min %.2f, max %.2f)%n",
                    m.kind().displayName(), m.median() / 1e6, m.min() / 1e6, m.max() / 1e6);
        }
        System.out.println("  Parity (concurrent): " + (r.concurrentParity().passed() ? "PASS" : "FAIL") + " - "
                + r.concurrentParity().detail());
        System.out.println("  Parity (one producer): " + (r.singleProducerParity().passed() ? "PASS" : "FAIL") + " - "
                + r.singleProducerParity().detail());
    }

    private static void measuredResults() {
        banner("Measured JMH results (frozen)");
        Optional<Path> dir = BenchmarkResults.findDefaultDirectory(Path.of(""));
        if (dir.isEmpty()) {
            System.out.println("  No results folder with summary.csv found (run from the project folder).");
            return;
        }
        BenchmarkResults results;
        try {
            results = BenchmarkResults.load(dir.get());
        } catch (Exception e) {
            System.out.println("  Could not read " + dir.get() + ": " + e.getMessage());
            return;
        }
        System.out.println("  Source: " + results.directory() + " (median of " + results.runs() + " runs)");
        System.out.printf(Locale.ROOT, "  %-9s | %-21s | %-23s | %-23s%n", "producers", "throughput M/s B / LF",
                "steady p50/p99 us (B)", "steady p50/p99 us (LF)");
        for (int p : results.producers()) {
            System.out.printf(Locale.ROOT, "  %-9d | %8s / %-10s | %10s / %-10s | %10s / %-10s%n", p,
                    fmt(results, p, BenchmarkResults.BASELINE, BenchmarkResults.THROUGHPUT, BenchmarkResults.OPS, 1e6),
                    fmt(results, p, BenchmarkResults.LOCK_FREE, BenchmarkResults.THROUGHPUT, BenchmarkResults.OPS, 1e6),
                    fmt(results, p, BenchmarkResults.BASELINE, BenchmarkResults.STEADY, "p50", 1e3),
                    fmt(results, p, BenchmarkResults.BASELINE, BenchmarkResults.STEADY, "p99", 1e3),
                    fmt(results, p, BenchmarkResults.LOCK_FREE, BenchmarkResults.STEADY, "p50", 1e3),
                    fmt(results, p, BenchmarkResults.LOCK_FREE, BenchmarkResults.STEADY, "p99", 1e3));
        }
    }

    private static String fmt(BenchmarkResults r, int p, String engine, String bench, String metric, double div) {
        return r.get(p, engine, bench, metric).map(s -> String.format(Locale.ROOT, "%.2f", s.median() / div))
                .orElse("n/a");
    }

    private static void banner(String text) {
        System.out.println();
        System.out.println("=".repeat(Math.max(60, text.length() + 4)));
        System.out.println("  " + text);
        System.out.println("=".repeat(Math.max(60, text.length() + 4)));
    }
}
