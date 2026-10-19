package com.nanomatch.smoke;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Day 1 smoke test: proves that JMH (annotation processing, the shaded benchmarks.jar and the runner)
 * works on this machine. It measures nothing about matching.
 *
 * <p>Run: {@code java -jar target/benchmarks.jar SmokeBenchmark -f 1 -wi 1 -i 2 -w 1s -r 1s}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 2, time = 1)
@Fork(1)
@State(Scope.Thread)
public class SmokeBenchmark {

    // Not final on purpose: a non-constant input stops the JIT from folding the whole loop away.
    private int size = 1_000;

    @Benchmark
    public long sumLoop() {
        long sum = 0;
        for (int i = 0; i < size; i++) {
            sum += i;
        }
        return sum; // returning the result makes JMH consume it, so the loop is not removed as dead code
    }
}
