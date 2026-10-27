package com.nanomatch.demo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the project's measured JMH results so the demo can show them. Nothing is estimated or invented:
 * <ul>
 *   <li>p50, p99, p99.9 and throughput come from the frozen {@code summary.csv} written by
 *       {@code results/analyze_runs.py} (median, minimum and maximum over the runs);</li>
 *   <li>p90 and p95 are not in that summary, so they are read from the raw JMH result files
 *       ({@code runN-tP.json}, the {@code scorePercentiles} JMH recorded) and summarised the same way: median,
 *       minimum and maximum over the runs. They are marked with their source.</li>
 * </ul>
 * Anything that is in neither file is reported as not available.
 */
public final class BenchmarkResults {

    public static final String BASELINE = "baseline";
    public static final String LOCK_FREE = "lockfree";
    public static final String THROUGHPUT = "throughput";
    public static final String STEADY = "steadyLatency";
    public static final String BURST = "burstLatency";
    public static final String OPS = "ops_per_s";

    /** Percentiles the demo offers for latency, in order. */
    public static final List<String> PERCENTILES = List.of("p50", "p90", "p95", "p99", "p99.9");

    private static final Pattern RUN_FILE = Pattern.compile("run(\\d+)-t(\\d+)\\.json");
    private static final Pattern DATE_DIR = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    public enum Source {
        SUMMARY("summary.csv (frozen)"),
        RAW_JSON("raw JMH JSON, median of runs");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * One measured value summarised over the runs.
     *
     * @param values     the value of each run
     * @param median     median over the runs
     * @param min        smallest run
     * @param max        largest run
     * @param unit       {@code ops/s} or {@code ns/op}
     * @param stable     the analysis script's stability verdict, or null if not computed
     * @param spreadPct  (max - min) / median in percent
     * @param source     which file the value came from
     */
    public record Stat(double[] values, double median, double min, double max, String unit, Boolean stable,
                       double spreadPct, Source source) {
    }

    private record Key(int producers, String engine, String benchmark, String metric) {
    }

    private final Path directory;
    private final Map<Key, Stat> stats;
    private final List<Integer> producers;
    private final int runs;
    private final List<String> environment;

    private BenchmarkResults(Path directory, Map<Key, Stat> stats, List<Integer> producers, int runs,
                             List<String> environment) {
        this.directory = directory;
        this.stats = stats;
        this.producers = producers;
        this.runs = runs;
        this.environment = environment;
    }

    public Path directory() {
        return directory;
    }

    /** Producer counts that were measured, ascending. */
    public List<Integer> producers() {
        return producers;
    }

    public int runs() {
        return runs;
    }

    /** The filled-in lines of the run's ENVIRONMENT.md (placeholders are skipped). */
    public List<String> environment() {
        return environment;
    }

    /** The measured value, or empty if it was not measured. */
    public Optional<Stat> get(int producerCount, String engine, String benchmark, String metric) {
        return Optional.ofNullable(stats.get(new Key(producerCount, engine, benchmark, metric)));
    }

    /**
     * Looks for the newest dated results folder ({@code results/YYYY-MM-DD} with a {@code summary.csv}) in the
     * given folder, its {@code results} subfolder, or a parent's.
     */
    public static Optional<Path> findDefaultDirectory(Path start) {
        Path current = start.toAbsolutePath().normalize();
        for (int depth = 0; current != null && depth < 4; depth++, current = current.getParent()) {
            for (Path candidate : List.of(current, current.resolve("results"))) {
                Optional<Path> found = newestDatedFolder(candidate);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> newestDatedFolder(Path folder) {
        if (Files.isRegularFile(folder.resolve("summary.csv"))) {
            return Optional.of(folder);
        }
        if (!Files.isDirectory(folder)) {
            return Optional.empty();
        }
        TreeSet<String> names = new TreeSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (DATE_DIR.matcher(name).matches() && Files.isRegularFile(entry.resolve("summary.csv"))) {
                    names.add(name);
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return names.isEmpty() ? Optional.empty() : Optional.of(folder.resolve(names.last()));
    }

    /** Loads {@code summary.csv}, the raw run files and {@code ENVIRONMENT.md} from a results folder. */
    public static BenchmarkResults load(Path directory) throws IOException {
        Path csv = directory.resolve("summary.csv");
        if (!Files.isRegularFile(csv)) {
            throw new IOException("no summary.csv in " + directory);
        }
        Map<Key, Stat> stats = new HashMap<>();
        TreeSet<Integer> producerSet = new TreeSet<>();
        int maxRuns = 0;

        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IOException("summary.csv is empty");
        }
        List<String> header = Arrays.asList(lines.get(0).trim().split(","));
        int iProducers = column(header, "producers");
        int iEngine = column(header, "engine");
        int iBenchmark = column(header, "benchmark");
        int iMetric = column(header, "metric");
        int iUnit = column(header, "unit");
        int iValues = column(header, "values");
        int iMedian = column(header, "median");
        int iMin = column(header, "min");
        int iMax = column(header, "max");
        int iSpread = header.indexOf("spread_pct");
        int iStable = header.indexOf("stable");
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] cells = line.trim().split(",", -1);
            int producerCount = Integer.parseInt(cells[iProducers].trim());
            double[] values = Arrays.stream(cells[iValues].split(";")).filter(s -> !s.isBlank())
                    .mapToDouble(Double::parseDouble).toArray();
            Boolean stable = iStable < 0 ? null : "yes".equalsIgnoreCase(cells[iStable].trim());
            double spread = iSpread < 0 ? Double.NaN : Double.parseDouble(cells[iSpread]);
            stats.put(new Key(producerCount, cells[iEngine].trim(), cells[iBenchmark].trim(), cells[iMetric].trim()),
                    new Stat(values, Double.parseDouble(cells[iMedian]), Double.parseDouble(cells[iMin]),
                            Double.parseDouble(cells[iMax]), cells[iUnit].trim(), stable, spread, Source.SUMMARY));
            producerSet.add(producerCount);
            maxRuns = Math.max(maxRuns, values.length);
        }

        addRawPercentiles(directory, stats, producerSet);
        return new BenchmarkResults(directory, Collections.unmodifiableMap(stats), List.copyOf(producerSet), maxRuns,
                readEnvironment(directory.resolve("ENVIRONMENT.md")));
    }

    private static int column(List<String> header, String name) throws IOException {
        int index = header.indexOf(name);
        if (index < 0) {
            throw new IOException("summary.csv has no '" + name + "' column");
        }
        return index;
    }

    /** Adds p90 and p95 (not in summary.csv) from the raw JMH files, as median / min / max over the runs. */
    private static void addRawPercentiles(Path directory, Map<Key, Stat> stats, TreeSet<Integer> producerSet)
            throws IOException {
        Map<Key, List<Double>> perRun = new HashMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "run*-t*.json")) {
            for (Path file : files) {
                Matcher m = RUN_FILE.matcher(file.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                int producerCount = Integer.parseInt(m.group(2));
                Object parsed;
                try {
                    parsed = MiniJson.parse(Files.readString(file, StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    throw new IOException("could not read " + file.getFileName() + ": " + e.getMessage(), e);
                }
                if (!(parsed instanceof List<?> benchmarks)) {
                    continue;
                }
                for (Object item : benchmarks) {
                    if (!(item instanceof Map<?, ?> benchmark) || !"sample".equals(benchmark.get("mode"))) {
                        continue;
                    }
                    String name = String.valueOf(benchmark.get("benchmark"));
                    name = name.substring(name.lastIndexOf('.') + 1);
                    Object params = benchmark.get("params");
                    Object primary = benchmark.get("primaryMetric");
                    if (!(params instanceof Map<?, ?> p) || !(primary instanceof Map<?, ?> metric)
                            || !(metric.get("scorePercentiles") instanceof Map<?, ?> percentiles)) {
                        continue;
                    }
                    String engine = String.valueOf(p.get("engine"));
                    for (String[] wanted : new String[][] {{"p90", "90.0"}, {"p95", "95.0"}}) {
                        if (percentiles.get(wanted[1]) instanceof Double value) {
                            perRun.computeIfAbsent(new Key(producerCount, engine, name, wanted[0]),
                                    k -> new ArrayList<>()).add(value);
                        }
                    }
                }
            }
        }
        for (Map.Entry<Key, List<Double>> entry : perRun.entrySet()) {
            if (stats.containsKey(entry.getKey())) {
                continue; // the frozen summary wins
            }
            double[] values = entry.getValue().stream().mapToDouble(Double::doubleValue).toArray();
            double[] sorted = values.clone();
            Arrays.sort(sorted);
            int n = sorted.length;
            double median = n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
            double spread = median == 0 ? 0 : (sorted[n - 1] - sorted[0]) / median * 100;
            stats.put(entry.getKey(), new Stat(values, median, sorted[0], sorted[n - 1], "ns/op", null, spread,
                    Source.RAW_JSON));
            producerSet.add(entry.getKey().producers());
        }
    }

    private static List<String> readEnvironment(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim().replace("\\-", "-");
                if (!(line.startsWith("*") || line.startsWith("-"))) {
                    continue;
                }
                line = line.substring(1).trim();
                if (line.isEmpty() || line.contains("<") || line.contains(">")) {
                    continue; // skip unfilled placeholders
                }
                result.add(line);
            }
        } catch (IOException e) {
            return List.of();
        }
        return List.copyOf(result);
    }
}
