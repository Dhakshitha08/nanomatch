package com.nanomatch.lockfree;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A single {@code long} counter that owns its cache line: nothing else shares the cache line it lives on.
 *
 * <h2>Why</h2>
 * A CPU does not move single variables between cores; it moves 64-byte <i>cache lines</i>. If two unrelated
 * variables sit on the same line and two threads keep writing them, the line bounces between the cores even
 * though the threads never touch each other's data. That is <i>false sharing</i>, and it silently costs
 * latency. In the ring buffer, {@code claimed} is hammered by every producer (CAS) while {@code consumed} is
 * written by the matching thread after every order. Two plain {@code AtomicLong}s allocated one after the other
 * would very likely share a cache line.
 *
 * <h2>How</h2>
 * The value sits in the middle of a {@code long[]}, with 8 unused longs (64 bytes) on each side, so no other
 * object's data can be within one cache line of it. Access goes through a {@link VarHandle} on the array
 * element, which gives the same volatile / acquire / release / CAS operations as {@code AtomicLong}.
 *
 * <h2>Measuring the effect</h2>
 * Padding is only worth claiming if it is measured. Start the JVM with {@code -Dnanomatch.padding=false} to
 * switch it off (the value then sits alone in a 1-element array); {@code smoke/PaddingBenchmark} compares the two.
 * The flag is read once, into a {@code static final}, so it costs nothing when the code is JIT-compiled.
 */
final class PaddedSequence {

    private static final boolean PADDED = !"false".equalsIgnoreCase(System.getProperty("nanomatch.padding"));
    private static final int PAD_LONGS = 8; // 8 x 8 bytes = 64 bytes on each side of the value
    private static final int INDEX = PADDED ? PAD_LONGS : 0;
    private static final VarHandle CELL = MethodHandles.arrayElementVarHandle(long[].class);

    private final long[] cell = new long[PADDED ? 2 * PAD_LONGS + 1 : 1];

    /** Volatile read. */
    long get() {
        return (long) CELL.getVolatile(cell, INDEX);
    }

    /** Atomically sets the value to {@code update} if it currently equals {@code expected}. */
    boolean compareAndSet(long expected, long update) {
        return CELL.compareAndSet(cell, INDEX, expected, update);
    }

    /** Ordered write: everything written before it is visible to a thread that later reads this value. */
    void setRelease(long value) {
        CELL.setRelease(cell, INDEX, value);
    }
}
