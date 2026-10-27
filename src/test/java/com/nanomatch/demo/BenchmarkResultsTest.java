package com.nanomatch.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The demo must show the measured numbers exactly, and say "not available" for anything missing. */
class BenchmarkResultsTest {

    private Path root;

    @BeforeEach
    void createFolder() throws IOException {
        root = Files.createTempDirectory("nanomatch-results");
    }

    @AfterEach
    void deleteFolder() throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static String run(String engine, double p90, double p95) {
        return "{\"benchmark\":\"com.nanomatch.bench.EngineBenchmark.steadyLatency\",\"mode\":\"sample\","
                + "\"params\":{\"burstSize\":\"32\",\"engine\":\"" + engine + "\"},\"primaryMetric\":{\"score\":1.0,"
                + "\"scoreError\":\"NaN\",\"scorePercentiles\":{\"50.0\":100.0,\"90.0\":" + p90 + ",\"95.0\":" + p95
                + ",\"99.0\":900.0},\"scoreUnit\":\"ns/op\"}}";
    }

    private Path writeFixture() throws IOException {
        Path dir = root.resolve("results").resolve("2026-01-02");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("summary.csv"), String.join("\n",
                "producers,engine,benchmark,metric,unit,runs,values,median,min,max,spread_pct,tolerance_pct,stable",
                "2,baseline,throughput,ops_per_s,ops/s,3,1.0;2.0;3.0,2.0,1.0,3.0,100.0,10.0,NO",
                "2,lockfree,throughput,ops_per_s,ops/s,3,4.0;5.0;6.0,5.0,4.0,6.0,40.0,10.0,yes",
                "2,baseline,steadyLatency,p99,ns/op,3,800;900;1000,900,800,1000,22.2,25.0,yes"), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("run1-t2.json"), "[" + run("baseline", 200, 300) + "," + run("lockfree", 150, 250)
                + "]", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("run2-t2.json"), "[" + run("baseline", 400, 500) + "]", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("run3-t2.json"), "[" + run("baseline", 300, 400) + "]", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("ENVIRONMENT.md"), "# env\n\n* CPU: Test CPU\n\\- Laptop: <model>\n- Java: 25\n",
                StandardCharsets.UTF_8);
        return dir;
    }

    @Test
    void readsTheSummaryExactlyAndAddsP90AndP95FromTheRawRuns() throws IOException {
        Path dir = writeFixture();
        assertEquals(dir, BenchmarkResults.findDefaultDirectory(root).orElseThrow());

        BenchmarkResults r = BenchmarkResults.load(dir);
        assertEquals(List.of(2), r.producers());
        assertEquals(3, r.runs());

        BenchmarkResults.Stat lf = r.get(2, "lockfree", "throughput", "ops_per_s").orElseThrow();
        assertEquals(5.0, lf.median(), 0);
        assertEquals(4.0, lf.min(), 0);
        assertEquals(6.0, lf.max(), 0);
        assertTrue(lf.stable());
        assertEquals(BenchmarkResults.Source.SUMMARY, lf.source());
        assertFalse(r.get(2, "baseline", "throughput", "ops_per_s").orElseThrow().stable());

        BenchmarkResults.Stat p95 = r.get(2, "baseline", "steadyLatency", "p95").orElseThrow();
        assertEquals(BenchmarkResults.Source.RAW_JSON, p95.source());
        assertEquals(400.0, p95.median(), 0);
        assertEquals(300.0, p95.min(), 0);
        assertEquals(500.0, p95.max(), 0);
        assertEquals(3, p95.values().length);
        assertNull(p95.stable());
        assertEquals(150.0, r.get(2, "lockfree", "steadyLatency", "p90").orElseThrow().median(), 0);

        assertTrue(r.get(2, "baseline", "steadyLatency", "p99").isPresent(), "summary value kept");
        assertTrue(r.get(4, "baseline", "throughput", "ops_per_s").isEmpty(), "not measured -> empty, never zero");
        assertTrue(r.get(2, "baseline", "burstLatency", "p50").isEmpty());
        assertEquals(List.of("CPU: Test CPU", "Java: 25"), r.environment(), "placeholder lines are skipped");
    }

    @Test
    void missingSummaryIsAnError() throws IOException {
        assertTrue(BenchmarkResults.findDefaultDirectory(root).isEmpty());
        assertThrows(IOException.class, () -> BenchmarkResults.load(root));
    }

    @Test
    void miniJsonParsesJmhStyleValues() {
        Object parsed = MiniJson.parse("{\"a\": [1, -2.5e3, \"x\\\"y\\u0041\"], \"b\": true, \"c\": null, "
                + "\"d\": NaN, \"e\": {}}");
        Map<?, ?> map = (Map<?, ?>) parsed;
        List<?> a = (List<?>) map.get("a");
        assertEquals(1.0, ((Double) a.get(0)).doubleValue(), 0.0);
        assertEquals(-2500.0, ((Double) a.get(1)).doubleValue(), 0.0);
        assertEquals("x\"yA", a.get(2));
        assertEquals(Boolean.TRUE, map.get("b"));
        assertNull(map.get("c"));
        assertTrue(((Double) map.get("d")).isNaN());
        assertTrue(((Map<?, ?>) map.get("e")).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"a\": 1"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("[1] x"));
    }
}
