package com.nanomatch.verify;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nanomatch.baseline.LockBasedEngine;
import com.nanomatch.bench.BenchmarkWorkload;
import com.nanomatch.lockfree.LockFreeEngine;
import com.nanomatch.model.MatchingEngine;
import com.nanomatch.model.Order;
import com.nanomatch.model.Side;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Tests the benchmark's own ingredients without JMH: the workload, the engine factory, and the submit-and-wait
 * helpers. A benchmark that crashes, or whose book grows without limit, is worthless, so this runs the same
 * operations the JMH methods run, concurrently, against both engines.
 */
class BenchmarkWorkloadTest {

    @Test
    void theWorkloadIsDeterministicAndDiffersBetweenThreads() {
        assertArrayEquals(BenchmarkWorkload.ordersForThread(0), BenchmarkWorkload.ordersForThread(0));
        assertFalse(Arrays.equals(BenchmarkWorkload.ordersForThread(0), BenchmarkWorkload.ordersForThread(1)));
    }

    @Test
    void theWorkloadStaysInsideTheDocumentedBand() {
        long low = BenchmarkWorkload.MID_PRICE - BenchmarkWorkload.PRICE_BAND;
        long high = BenchmarkWorkload.MID_PRICE + BenchmarkWorkload.PRICE_BAND;
        Order[] orders = BenchmarkWorkload.ordersForThread(3);
        assertEquals(BenchmarkWorkload.ORDERS_PER_THREAD, orders.length);
        assertEquals(1, Integer.bitCount(orders.length), "the benchmark wraps with a bit mask, so the size must be a power of two");
        for (Order o : orders) {
            assertTrue(o.price() >= low && o.price() <= high, "price " + o.price());
            assertTrue(o.quantity() >= BenchmarkWorkload.MIN_QUANTITY && o.quantity() <= BenchmarkWorkload.MAX_QUANTITY);
        }
    }

    @Test
    void everyBuyIsAtOrAboveTheMidPriceAndEverySellAtOrBelowIt() {
        for (Order o : BenchmarkWorkload.ordersForThread(2)) {
            if (o.side() == Side.BUY) {
                assertTrue(o.price() >= BenchmarkWorkload.MID_PRICE, "buy below mid: " + o);
            } else {
                assertTrue(o.price() <= BenchmarkWorkload.MID_PRICE, "sell above mid: " + o);
            }
        }
    }

    @Test
    void everyCycleOfTheWorkloadNetsToZeroSoTheBookCannotGrowWithRunLength() {
        long buyQuantity = 0;
        long sellQuantity = 0;
        for (int thread = 0; thread < 4; thread++) {
            Order[] orders = BenchmarkWorkload.ordersForThread(thread);
            long balance = 0;
            long worst = 0;
            for (Order o : orders) {
                balance += o.side() == Side.BUY ? o.quantity() : -o.quantity();
                worst = Math.max(worst, Math.abs(balance));
                if (o.side() == Side.BUY) {
                    buyQuantity += o.quantity();
                } else {
                    sellQuantity += o.quantity();
                }
            }
            assertEquals(0, balance, "thread " + thread + ": a cycle must net to zero");
            assertTrue(worst <= BenchmarkWorkload.MAX_QUANTITY, "running imbalance within a cycle: " + worst);
        }
        assertEquals(buyQuantity, sellQuantity);
    }

    @Test
    void unknownEngineNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> BenchmarkWorkload.createEngine("fast"));
    }

    @Test
    void bothEnginesGiveIdenticalTradesOnTheBenchmarkWorkload() {
        Order[] orders = BenchmarkWorkload.ordersForThread(0);
        RecordingSink baselineSink = new RecordingSink();
        RecordingSink lockFreeSink = new RecordingSink();
        try (MatchingEngine baseline = BenchmarkWorkload.createEngine(BenchmarkWorkload.BASELINE, baselineSink);
             MatchingEngine lockFree = BenchmarkWorkload.createEngine(BenchmarkWorkload.LOCK_FREE, lockFreeSink)) {
            for (Order order : orders) {
                baseline.submit(order);
                lockFree.submit(order);
            }
            baseline.awaitIdle();
            lockFree.awaitIdle();
        }
        assertTrue(baselineSink.trades().size() > orders.length / 4, "the workload should trade a lot");
        TradeDiff.assertIdentical("benchmark workload", "baseline", baselineSink.trades(), "lock-free",
                lockFreeSink.trades());
    }

    /** The same operations the three JMH methods perform, from several threads, against one engine. */
    private static void hammer(String engineName) throws Exception {
        final int threads = 4;
        final int steadyOrders = 20_000;
        final int bursts = 500;
        final int burstSize = 32;
        MatchingEngine engine = BenchmarkWorkload.createEngine(engineName);
        try {
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread[] workers = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                final Order[] orders = BenchmarkWorkload.ordersForThread(t);
                workers[t] = new Thread(() -> {
                    try {
                        start.await();
                        int cursor = 0;
                        for (int i = 0; i < steadyOrders; i++) {               // like throughput + steadyLatency
                            BenchmarkWorkload.submitAndWait(engine, orders[cursor]);
                            cursor = (cursor + 1) & (orders.length - 1);
                        }
                        for (int b = 0; b < bursts; b++) {                     // like burstLatency
                            BenchmarkWorkload.submitBurstAndWait(engine, orders, cursor, burstSize);
                            cursor = (cursor + burstSize) & (orders.length - 1);
                        }
                        for (int i = 0; i < steadyOrders; i++) {               // like throughput (no waiting)
                            engine.submit(orders[cursor]);
                            cursor = (cursor + 1) & (orders.length - 1);
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                });
                workers[t].start();
            }
            start.countDown();
            for (Thread worker : workers) {
                worker.join(120_000);
                assertFalse(worker.isAlive(), engineName + ": worker did not finish");
            }
            assertNull(failure.get(), engineName + ": a worker failed: " + failure.get());

            engine.awaitIdle();
            long expected = (long) threads * (steadyOrders + (long) bursts * burstSize + steadyOrders);
            assertEquals(expected, engine.submittedCount(), engineName + ": submitted");
            assertEquals(expected, engine.processedCount(), engineName + ": processed");

            // the book must stay small, otherwise the benchmark would be measuring memory growth
            if (engine instanceof LockFreeEngine) {
                LockFreeEngine lockFree = (LockFreeEngine) engine;
                assertNull(lockFree.failure(), engineName + ": the matching thread failed");
                assertTrue(lockFree.restingQuantity(Side.BUY) + lockFree.restingQuantity(Side.SELL) < 50_000);
            } else {
                LockBasedEngine baseline = (LockBasedEngine) engine;
                assertTrue(baseline.restingQuantity(Side.BUY) + baseline.restingQuantity(Side.SELL) < 50_000);
            }
        } finally {
            engine.close();
        }
    }

    @Test
    void theBaselineSurvivesTheBenchmarkOperationsFromManyThreads() throws Exception {
        hammer(BenchmarkWorkload.BASELINE);
    }

    @Test
    void theLockFreeEngineSurvivesTheBenchmarkOperationsFromManyThreads() throws Exception {
        hammer(BenchmarkWorkload.LOCK_FREE);
    }
}
