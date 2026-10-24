package com.nanomatch.bench;

import com.nanomatch.model.MatchingEngine;
import com.nanomatch.model.Order;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The JMH benchmark that compares the lock-based baseline with the lock-free engine.
 *
 * <h2>What varies</h2>
 * <ul>
 *   <li><b>engine</b>: {@code baseline} or {@code lockfree} (a JMH {@code @Param}).</li>
 *   <li><b>producers</b>: the number of JMH threads, set on the command line with {@code -t} (1, 2, 4, 8).
 *       Every thread is a client submitting orders to the same engine.</li>
 *   <li><b>what is measured</b>: three benchmark methods, below.</li>
 * </ul>
 *
 * <h2>The three benchmarks</h2>
 * <table>
 *   <caption>benchmark methods</caption>
 *   <tr><td>{@link #throughput}</td><td>Throughput mode. One order per call, submitted without waiting. Reports orders
 *       per second the engine sustains with all producers pushing as fast as they can. (For the lock-free engine,
 *       once its ring buffer is full, producers wait for the matching thread, so the rate is the matching
 *       rate.)</td></tr>
 *   <tr><td>{@link #steadyLatency}</td><td>SampleTime mode. One order per call: submit it and wait until it is fully
 *       processed. Reports the latency distribution (p50, p99, p99.9) under a constant load of one in-flight
 *       order per producer.</td></tr>
 *   <tr><td>{@link #burstLatency}</td><td>SampleTime mode. A burst of {@code burstSize} orders back to back, then wait until
 *       the last one is processed. One sample is the time to absorb and finish a whole burst. This is the spike
 *       pattern where lock contention hurts most.</td></tr>
 * </table>
 *
 * <p>The latency definition is the same for both engines (see {@link BenchmarkWorkload#submitAndWait}). The engine
 * is created once per fork and keeps running between iterations; the workload keeps the book small (see
 * {@link BenchmarkWorkload}). Run it with {@code run_suite.py}, or by hand:
 * <pre>
 * java -jar target/benchmarks.jar EngineBenchmark -t 4 -p engine=lockfree -rf json -rff results/x.json
 * </pre>
 * Run with {@code -prof gc} to see allocation per operation ({@code gc.alloc.rate.norm}).
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(2)
public class EngineBenchmark {

    @Param({"baseline", "lockfree"})
    public String engine;

    @Param({"32"})
    public int burstSize;

    private MatchingEngine instance;

    /** Each benchmark thread gets its own pre-generated orders, so generating them is never timed. */
    @State(Scope.Thread)
    public static class Orders {
        private Order[] orders;
        private int cursor;

        @Setup(Level.Trial)
        public void generate() {
            orders = BenchmarkWorkload.ordersForNextThread();
            cursor = 0;
        }

        Order next() {
            Order order = orders[cursor];
            cursor = (cursor + 1) & (orders.length - 1);
            return order;
        }

        Order[] all() {
            return orders;
        }

        int advance(int count) {
            int first = cursor;
            cursor = (cursor + count) & (orders.length - 1);
            return first;
        }
    }

    @Setup(Level.Trial)
    public void startEngine() {
        instance = BenchmarkWorkload.createEngine(engine);
    }

    @TearDown(Level.Trial)
    public void stopEngine() {
        instance.awaitIdle();
        instance.close();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public long throughput(Orders orders) {
        return instance.submit(orders.next());
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public long steadyLatency(Orders orders) {
        return BenchmarkWorkload.submitAndWait(instance, orders.next());
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public long burstLatency(Orders orders) {
        int first = orders.advance(burstSize);
        return BenchmarkWorkload.submitBurstAndWait(instance, orders.all(), first, burstSize);
    }
}
