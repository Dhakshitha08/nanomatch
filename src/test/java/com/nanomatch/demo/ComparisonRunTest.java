package com.nanomatch.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The live comparison in the GUI must run both engines and confirm parity. */
class ComparisonRunTest {

    @Test
    void measuresBothEnginesAndConfirmsParity() throws InterruptedException {
        List<String> progress = new ArrayList<>();
        ComparisonRun.Result r = ComparisonRun.run(2, 3_000, 2, 11, progress::add);
        assertEquals(EngineKind.LOCK_BASED, r.baseline().kind());
        assertEquals(EngineKind.LOCK_FREE, r.lockFree().kind());
        assertEquals(2, r.baseline().ordersPerSecond().length);
        assertTrue(r.baseline().median() > 0 && r.lockFree().median() > 0);
        assertTrue(r.baseline().min() <= r.baseline().median() && r.baseline().median() <= r.baseline().max());
        assertEquals(6_000, r.lockFree().ordersPerRun());
        assertTrue(r.concurrentParity().passed(), r.concurrentParity().detail());
        assertTrue(r.singleProducerParity().passed(), r.singleProducerParity().detail());
        assertTrue(r.concurrentParity().tradesCompared() > 0);
        assertEquals(6_000, r.concurrentParity().orders());
        assertTrue(progress.contains("Done."));
    }

    @Test
    void recorderDetectsADifference() {
        ComparisonRun.TradeRecorder a = new ComparisonRun.TradeRecorder();
        ComparisonRun.TradeRecorder b = new ComparisonRun.TradeRecorder();
        for (int i = 0; i < 2_000; i++) { // forces the recorder to grow
            a.onTrade(i, i + 1, 100, 1);
            b.onTrade(i, i + 1, 100, i == 1_500 ? 2 : 1);
        }
        assertEquals(2_000, a.size());
        assertTrue(a.same(1_499, b));
        assertTrue(!a.same(1_500, b));
    }
}
