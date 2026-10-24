package com.nanomatch.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nanomatch.model.Trade;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The parity check is only trustworthy if the tool that compares trades really catches differences. */
class TradeDiffTest {

    private static List<Trade> sample() {
        List<Trade> trades = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            trades.add(new Trade(i, 100 + i, 10_000 + i, 5));
        }
        return trades;
    }

    @Test
    void identicalListsHaveNoDifference() {
        assertNull(TradeDiff.describeFirstDifference(sample(), sample()));
        assertNull(TradeDiff.describeFirstDifference(List.of(), List.of()));
        TradeDiff.assertIdentical("same", "a", sample(), "b", sample());
    }

    @Test
    void aDifferentTradeIsReportedAtItsIndexWithContext() {
        List<Trade> changed = sample();
        changed.set(4, new Trade(4, 104, 10_004, 6)); // quantity differs

        String text = TradeDiff.describeFirstDifference(sample(), changed);
        assertTrue(text.contains("first difference at index 4"), text);
        assertTrue(text.contains(">>> #4"), text);
        assertTrue(text.contains("#1 "), "context should start 3 trades before: " + text);
        assertTrue(text.contains("quantity=6"), text);
    }

    @Test
    void aMissingTradeIsReported() {
        List<Trade> shorter = sample().subList(0, 3);
        String text = TradeDiff.describeFirstDifference(sample(), shorter);
        assertTrue(text.contains("left=6, right=3"), text);
        assertTrue(text.contains("first difference at index 3"), text);
        assertTrue(text.contains("(missing)"), text);
    }

    @Test
    void assertIdenticalFailsWhenListsDiffer() {
        List<Trade> changed = sample();
        changed.remove(0);
        AssertionError error = assertThrows(AssertionError.class,
                () -> TradeDiff.assertIdentical("ctx", "baseline", sample(), "lock-free", changed));
        assertTrue(error.getMessage().contains("ctx"));
        assertTrue(error.getMessage().contains("baseline"));
        assertTrue(error.getMessage().contains("lock-free"));
        assertEquals(6, sample().size());
    }
}
