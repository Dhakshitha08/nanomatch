package com.nanomatch.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.nanomatch.baseline.LockBasedEngine;
import com.nanomatch.lockfree.LockFreeEngine;
import com.nanomatch.model.Order;
import com.nanomatch.model.Side;
import com.nanomatch.sim.LoadConfig;
import com.nanomatch.sim.LoadPattern;
import com.nanomatch.sim.LoadSimulator;
import com.nanomatch.sim.OrderGenerator;
import org.junit.jupiter.api.Test;

/**
 * Checkpoint 3, the parity check: <b>identical input must give identical trades</b> from the lock-based
 * baseline and the lock-free engine. If this fails, no benchmark number means anything.
 *
 * <p>Two kinds of runs:
 * <ol>
 *   <li><b>Single producer.</b> The same seeded order sequence is fed, in the same order, to both engines. The
 *       trade lists must be identical, and so must the final book state.</li>
 *   <li><b>Many producers.</b> Here the arrival order is decided by thread timing, so two runs can never be
 *       compared directly. Instead the lock-free engine runs with several concurrent producers while recording
 *       which order got which ticket. The ticket is the processing order, so replaying that exact sequence,
 *       one order at a time, through the baseline must give exactly the same trades.</li>
 * </ol>
 */
class ParityTest {

    private static final long[] SEEDS = {1, 2, 3, 4, 5};
    private static final int ORDERS_PER_SEED = 100_000;

    @Test
    void singleProducerStreamsGiveIdenticalTradesAndBookState() {
        for (long seed : SEEDS) {
            Order[] orders = new OrderGenerator(seed).generate(ORDERS_PER_SEED);
            RecordingSink baselineSink = new RecordingSink();
            RecordingSink lockFreeSink = new RecordingSink();
            LockBasedEngine baseline = new LockBasedEngine(baselineSink);
            LockFreeEngine lockFree = new LockFreeEngine(lockFreeSink, 1 << 10); // small ring: wraps and waits a lot
            try {
                for (Order order : orders) {
                    baseline.submit(order);
                    lockFree.submit(order);
                }
                baseline.awaitIdle();
                lockFree.awaitIdle();
                String run = "seed " + seed;

                assertNull(lockFree.failure(), run + ": the matching thread failed");
                assertFalse(baselineSink.trades().isEmpty(), run + ": expected trades");
                TradeDiff.assertIdentical(run, "baseline", baselineSink.trades(), "lock-free", lockFreeSink.trades());

                assertEquals(baseline.bestBidPrice(), lockFree.bestBidPrice(), run + ": best bid");
                assertEquals(baseline.bestAskPrice(), lockFree.bestAskPrice(), run + ": best ask");
                assertEquals(baseline.restingQuantity(Side.BUY), lockFree.restingQuantity(Side.BUY), run + ": resting buys");
                assertEquals(baseline.restingQuantity(Side.SELL), lockFree.restingQuantity(Side.SELL), run + ": resting sells");
                assertEquals(ORDERS_PER_SEED, lockFree.processedCount(), run + ": processed");
            } finally {
                baseline.close();
                lockFree.close();
            }
        }
    }

    /** Runs the lock-free engine under the given load, replays its recorded order through the baseline, compares. */
    private static void checkConcurrentRunReplaysIdentically(String label, int ringCapacity, LoadConfig config)
            throws Exception {
        RecordingSink lockFreeSink = new RecordingSink();
        LoadSimulator.Result result =
                LoadSimulator.run(sink -> new LockFreeEngine(sink, ringCapacity), lockFreeSink, config);

        assertEquals(config.totalOrders(), result.processedCount(), label + ": processed");
        Order[] processingOrder = result.ordersByTicket();
        assertNotNull(processingOrder, label);
        for (int ticket = 0; ticket < processingOrder.length; ticket++) {
            assertNotNull(processingOrder[ticket], label + ": ticket " + ticket + " has no order");
        }

        RecordingSink replaySink = new RecordingSink();
        try (LockBasedEngine baseline = new LockBasedEngine(replaySink)) {
            for (Order order : processingOrder) {
                baseline.submit(order);
            }
            baseline.awaitIdle();
        }
        assertFalse(replaySink.trades().isEmpty(), label + ": expected trades");
        TradeDiff.assertIdentical(label, "lock-free (concurrent run)", lockFreeSink.trades(),
                "baseline (replay in ticket order)", replaySink.trades());
    }

    @Test
    void concurrentRunsReplayIdenticallyThroughTheBaseline() throws Exception {
        for (long seed : SEEDS) {
            checkConcurrentRunReplaysIdentically("4 producers, seed " + seed, 1 << 12,
                    new LoadConfig(4, 25_000, LoadPattern.maxSpeed(), seed * 1_000, true));
        }
    }

    @Test
    void concurrentRunsReplayIdenticallyWithManyProducersAndATinyRing() throws Exception {
        checkConcurrentRunReplaysIdentically("8 producers, ring of 8", 8,
                new LoadConfig(8, 5_000, LoadPattern.maxSpeed(), 77, true));
        checkConcurrentRunReplaysIdentically("2 producers, ring of 2", 2,
                new LoadConfig(2, 20_000, LoadPattern.maxSpeed(), 78, true));
    }

    @Test
    void burstyConcurrentRunsReplayIdentically() throws Exception {
        checkConcurrentRunReplaysIdentically("4 producers, bursts", 1 << 10,
                new LoadConfig(4, 10_000, LoadPattern.burst(50, 100), 99, true));
    }
}
