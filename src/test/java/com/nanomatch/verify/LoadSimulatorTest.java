package com.nanomatch.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nanomatch.baseline.LockBasedEngine;
import com.nanomatch.lockfree.LockFreeEngine;
import com.nanomatch.model.EngineFactory;
import com.nanomatch.model.Order;
import com.nanomatch.model.TradeSink;
import com.nanomatch.sim.LoadConfig;
import com.nanomatch.sim.LoadPattern;
import com.nanomatch.sim.LoadSimulator;
import org.junit.jupiter.api.Test;

/** Checks that the load simulator really drives an engine the way its configuration says. */
class LoadSimulatorTest {

    private static final EngineFactory BASELINE = LockBasedEngine::new;
    private static final EngineFactory LOCK_FREE = LockFreeEngine::new;

    /** Counts trades. Called by one thread at a time (engine contract), read after the run. */
    private static final class CountingSink implements TradeSink {
        long trades;

        @Override
        public void onTrade(long buyOrderId, long sellOrderId, long price, long quantity) {
            trades++;
        }
    }

    private static void assertEveryOrderWasSubmittedAndProcessedOnce(String engineName, EngineFactory factory)
            throws InterruptedException {
        CountingSink sink = new CountingSink();
        LoadConfig config = new LoadConfig(4, 5_000, LoadPattern.maxSpeed(), 1, true);
        LoadSimulator.Result result = LoadSimulator.run(factory, sink, config);

        assertEquals(20_000, result.totalOrders(), engineName);
        assertEquals(20_000, result.submittedCount(), engineName + ": submitted");
        assertEquals(20_000, result.processedCount(), engineName + ": processed");
        assertTrue(result.totalNanos() >= result.submitNanos(), engineName + ": timing order");
        assertTrue(result.ordersPerSecond() > 0, engineName + ": throughput");
        assertTrue(sink.trades > 0, engineName + ": expected some trades");

        Order[] byTicket = result.ordersByTicket();
        assertNotNull(byTicket);
        assertEquals(20_000, byTicket.length);
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (int ticket = 0; ticket < byTicket.length; ticket++) {
            assertNotNull(byTicket[ticket], engineName + ": ticket " + ticket + " has no order");
            assertTrue(ids.add(byTicket[ticket].id()), engineName + ": order submitted twice: " + byTicket[ticket]);
        }
    }

    @Test
    void maxSpeedDrivesTheBaselineEngine() throws Exception {
        assertEveryOrderWasSubmittedAndProcessedOnce("baseline", BASELINE);
    }

    @Test
    void maxSpeedDrivesTheLockFreeEngine() throws Exception {
        assertEveryOrderWasSubmittedAndProcessedOnce("lock-free", LOCK_FREE);
    }

    @Test
    void steadyPatternIsPacedAtTheRequestedRate() throws Exception {
        // 2 threads x 200 orders at 2,000 orders/s per thread: the schedule alone takes about 100 ms.
        LoadConfig config = new LoadConfig(2, 200, LoadPattern.steady(2_000), 3, false);
        LoadSimulator.Result result = LoadSimulator.run(BASELINE, new CountingSink(), config);

        long expectedMinimumNanos = 199L * 500_000L; // 199 gaps of 0.5 ms
        assertTrue(result.submitNanos() >= expectedMinimumNanos * 9 / 10,
                "submitted faster than the schedule allows: " + result.submitNanos() + " ns");
        assertEquals(400, result.processedCount());
    }

    @Test
    void burstPatternPausesBetweenBursts() throws Exception {
        // 1 thread, 100 orders in bursts of 10 with 2 ms gaps: 9 gaps, so at least ~18 ms.
        LoadConfig config = new LoadConfig(1, 100, LoadPattern.burst(10, 2_000), 3, false);
        LoadSimulator.Result result = LoadSimulator.run(BASELINE, new CountingSink(), config);

        assertTrue(result.submitNanos() >= 9L * 2_000_000L * 9 / 10,
                "bursts were not separated by the gap: " + result.submitNanos() + " ns");
        assertEquals(100, result.processedCount());
    }

    @Test
    void theSameSeedGivesTheSameTradesForASingleThread() throws Exception {
        LoadConfig config = new LoadConfig(1, 3_000, LoadPattern.maxSpeed(), 42, false);
        RecordingSink first = new RecordingSink();
        RecordingSink second = new RecordingSink();
        LoadSimulator.run(BASELINE, first, config);
        LoadSimulator.run(BASELINE, second, config);
        assertFalse(first.trades().isEmpty());
        assertEquals(first.trades(), second.trades());
    }

    @Test
    void ticketsAreNotRecordedUnlessRequested() throws Exception {
        LoadSimulator.Result result =
                LoadSimulator.run(BASELINE, new CountingSink(), new LoadConfig(1, 10, LoadPattern.maxSpeed(), 1, false));
        assertEquals(null, result.ordersByTicket());
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LoadConfig(0, 10, LoadPattern.maxSpeed(), 1, false));
        assertThrows(IllegalArgumentException.class, () -> new LoadConfig(1, 0, LoadPattern.maxSpeed(), 1, false));
        assertThrows(IllegalArgumentException.class, () -> new LoadConfig(1, 10, null, 1, false));
        assertThrows(IllegalArgumentException.class, () -> LoadPattern.steady(0));
        assertThrows(IllegalArgumentException.class, () -> LoadPattern.burst(0, 10));
        assertThrows(IllegalArgumentException.class, () -> LoadPattern.burst(5, -1));
    }
}
