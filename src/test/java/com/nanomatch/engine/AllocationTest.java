package com.nanomatch.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.nanomatch.baseline.LockBasedEngine;
import com.nanomatch.lockfree.LockFreeEngine;
import com.nanomatch.model.Side;
import com.nanomatch.model.TradeSink;
import java.lang.management.ManagementFactory;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Checks the "allocation-free" claim directly: how many bytes of heap do the producer thread and the matching
 * thread allocate while processing hundreds of thousands of orders? Garbage-collection pauses can only be
 * caused by allocation, so zero allocation per order means no GC-induced latency spikes from the hot path.
 *
 * <p>The numbers come from the JVM's per-thread allocation counter. The test is skipped on a JVM that does not
 * provide it.
 */
class AllocationTest {

    private static final int WARM_UP_ORDERS = 300_000;
    private static final int MEASURED_ORDERS = 500_000;

    /** Orders as plain arrays, so that the test itself allocates nothing while submitting. */
    private static final class Workload {
        final boolean[] buy;
        final long[] price;
        final long[] quantity;

        Workload(int size, long seed) {
            SplittableRandom random = new SplittableRandom(seed);
            buy = new boolean[size];
            price = new long[size];
            quantity = new long[size];
            for (int i = 0; i < size; i++) {
                buy[i] = random.nextBoolean();
                price[i] = 9_997 + random.nextInt(7);   // 9,997 to 10,003: a tight, busy book
                quantity[i] = 1 + random.nextInt(5);
            }
        }

        void submitAll(com.nanomatch.model.MatchingEngine engine, int count, long firstId) {
            for (int i = 0; i < count; i++) {
                int k = i % buy.length;
                engine.submit(firstId + i, buy[k] ? Side.BUY : Side.SELL, price[k], quantity[k]);
            }
        }
    }

    private static com.sun.management.ThreadMXBean allocationCounter() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean, "no per-thread allocation counter on this JVM");
        com.sun.management.ThreadMXBean sun = (com.sun.management.ThreadMXBean) bean;
        assumeTrue(sun.isThreadAllocatedMemorySupported(), "thread allocation counter not supported");
        sun.setThreadAllocatedMemoryEnabled(true);
        return sun;
    }

    @Test
    void theLockFreeHotPathAllocatesNothingPerOrder() {
        com.sun.management.ThreadMXBean counter = allocationCounter();
        Workload workload = new Workload(65_536, 11);
        LockFreeEngine engine = new LockFreeEngine(TradeSink.NOOP, 1 << 12, 9_997, 10_003, 1 << 18);
        try {
            // warm-up: lets the JIT compile the paths and classes load, so that is not counted below
            workload.submitAll(engine, WARM_UP_ORDERS, 1);
            engine.awaitIdle();

            long producerThread = Thread.currentThread().getId();
            long matcherThread = engine.matcherThreadId();
            long producerBefore = counter.getThreadAllocatedBytes(producerThread);
            long matcherBefore = counter.getThreadAllocatedBytes(matcherThread);

            workload.submitAll(engine, MEASURED_ORDERS, 1_000_000);
            engine.awaitIdle();

            long producerBytes = counter.getThreadAllocatedBytes(producerThread) - producerBefore;
            long matcherBytes = counter.getThreadAllocatedBytes(matcherThread) - matcherBefore;
            assertEquals(null, engine.failure());

            // A single 16-byte object per order would be 8,000,000 bytes. Allow 16 KB of slack for the
            // measurement itself.
            assertTrue(producerBytes < 16_384, "producer thread allocated " + producerBytes + " bytes");
            assertTrue(matcherBytes < 16_384, "matching thread allocated " + matcherBytes + " bytes");
        } finally {
            engine.close();
        }
    }

    /** Guards against the test above passing for the wrong reason: the counter must be able to see allocation. */
    @Test
    void theBaselineDoesAllocateSoTheMeasurementCanSeeIt() {
        com.sun.management.ThreadMXBean counter = allocationCounter();
        Workload workload = new Workload(65_536, 11);
        LockBasedEngine engine = new LockBasedEngine(TradeSink.NOOP);
        workload.submitAll(engine, 50_000, 1); // warm-up

        long thread = Thread.currentThread().getId();
        long before = counter.getThreadAllocatedBytes(thread);
        workload.submitAll(engine, 50_000, 1_000_000);
        long bytes = counter.getThreadAllocatedBytes(thread) - before;

        assertTrue(bytes > 500_000, "expected the baseline to allocate, but only saw " + bytes + " bytes");
    }
}
