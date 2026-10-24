package com.nanomatch.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nanomatch.baseline.LockBasedOrderBook;
import com.nanomatch.model.Side;
import com.nanomatch.model.Trade;
import com.nanomatch.orderbook.ArrayOrderBook;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Tests for the allocation-free {@link ArrayOrderBook}. The scenario tests pin the matching rules by hand; the
 * differential tests then run long random order streams through this book and through the simple baseline book
 * and require identical trades and identical book state after every single order.
 */
class ArrayOrderBookTest {

    private final List<Trade> trades = new ArrayList<>();
    private final ArrayOrderBook book = new ArrayOrderBook(90, 120, 64);

    private void buy(long id, long price, long quantity) {
        book.process(id, Side.BUY, price, quantity, (b, s, p, q) -> trades.add(new Trade(b, s, p, q)));
    }

    private void sell(long id, long price, long quantity) {
        book.process(id, Side.SELL, price, quantity, (b, s, p, q) -> trades.add(new Trade(b, s, p, q)));
    }

    @Test
    void ordersThatDoNotCrossRestOnTheBook() {
        buy(1, 99, 10);
        sell(2, 100, 10);
        assertEquals(List.of(), trades);
        assertEquals(99, book.bestBidPrice());
        assertEquals(100, book.bestAskPrice());
        assertEquals(2, book.restingOrderCount());
    }

    @Test
    void exactMatchEmptiesTheBook() {
        sell(1, 100, 10);
        buy(2, 100, 10);
        assertEquals(List.of(new Trade(2, 1, 100, 10)), trades);
        assertEquals(0, book.bestBidPrice());
        assertEquals(0, book.bestAskPrice());
        assertEquals(0, book.restingOrderCount());
    }

    @Test
    void tradeHappensAtTheRestingOrdersPrice() {
        sell(1, 100, 5);
        buy(2, 105, 5);
        buy(3, 105, 5);
        sell(4, 100, 5);
        assertEquals(List.of(new Trade(2, 1, 100, 5), new Trade(3, 4, 105, 5)), trades);
    }

    @Test
    void bestPriceIsMatchedBeforeAnEarlierWorsePrice() {
        sell(1, 101, 5);
        sell(2, 100, 5);
        buy(3, 101, 8);
        assertEquals(List.of(new Trade(3, 2, 100, 5), new Trade(3, 1, 101, 3)), trades);
    }

    @Test
    void earlierOrderAtTheSamePriceIsMatchedFirstAndKeepsItsPlaceAfterAPartialFill() {
        sell(1, 100, 10);
        sell(2, 100, 5);
        buy(3, 100, 4);
        buy(4, 100, 7);
        assertEquals(List.of(new Trade(3, 1, 100, 4), new Trade(4, 1, 100, 6), new Trade(4, 2, 100, 1)), trades);
        assertEquals(4, book.restingQuantity(Side.SELL));
    }

    @Test
    void aggressiveBuySweepsSeveralLevelsAndTheBestAskMovesUp() {
        sell(1, 100, 2);
        sell(2, 101, 2);
        sell(3, 103, 2);
        buy(4, 103, 5);
        assertEquals(List.of(new Trade(4, 1, 100, 2), new Trade(4, 2, 101, 2), new Trade(4, 3, 103, 1)), trades);
        assertEquals(103, book.bestAskPrice());
        assertEquals(1, book.restingQuantity(Side.SELL));
    }

    @Test
    void aggressiveSellSweepsSeveralLevelsAndTheBestBidMovesDown() {
        buy(1, 98, 5);
        buy(2, 100, 5);
        buy(3, 95, 5);
        sell(4, 95, 12);
        assertEquals(List.of(new Trade(2, 4, 100, 5), new Trade(1, 4, 98, 5), new Trade(3, 4, 95, 2)), trades);
        assertEquals(95, book.bestBidPrice());
        assertEquals(3, book.restingQuantity(Side.BUY));
    }

    @Test
    void unmatchedRemainderRestsAtTheIncomingLimitPrice() {
        sell(1, 100, 5);
        buy(2, 100, 8);
        assertEquals(100, book.bestBidPrice());
        assertEquals(3, book.restingQuantity(Side.BUY));
        assertEquals(0, book.bestAskPrice());
    }

    @Test
    void theFirstAndLastPriceOfTheRangeWork() {
        buy(1, 90, 1);
        sell(2, 120, 1);
        assertEquals(90, book.bestBidPrice());
        assertEquals(120, book.bestAskPrice());
        sell(3, 90, 1);
        buy(4, 120, 1);
        assertEquals(List.of(new Trade(1, 3, 90, 1), new Trade(4, 2, 120, 1)), trades);
    }

    @Test
    void pricesOutsideTheRangeAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> buy(1, 89, 1));
        assertThrows(IllegalArgumentException.class, () -> sell(2, 121, 1));
        assertEquals(0, book.restingOrderCount());
    }

    @Test
    void theOrderPoolIsReusedAndAFullPoolIsReportedNotSilentlyIgnored() {
        ArrayOrderBook small = new ArrayOrderBook(100, 110, 4);
        List<Trade> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            small.process(i + 1, Side.BUY, 100, 1, (b, s, p, q) -> out.add(new Trade(b, s, p, q)));
        }
        assertEquals(4, small.restingOrderCount());
        assertThrows(IllegalStateException.class,
                () -> small.process(5, Side.BUY, 100, 1, (b, s, p, q) -> out.add(new Trade(b, s, p, q))));

        // a sell matches the two oldest buys and frees two nodes, which can be used again
        small.process(6, Side.SELL, 100, 2, (b, s, p, q) -> out.add(new Trade(b, s, p, q)));
        assertEquals(List.of(new Trade(1, 6, 100, 1), new Trade(2, 6, 100, 1)), out);
        assertEquals(2, small.restingOrderCount());
        for (int i = 0; i < 2; i++) {
            small.process(10 + i, Side.BUY, 101, 1, (b, s, p, q) -> out.add(new Trade(b, s, p, q)));
        }
        assertEquals(4, small.restingOrderCount());
        assertEquals(101, small.bestBidPrice());
    }

    @Test
    void invalidConstructionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ArrayOrderBook(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new ArrayOrderBook(10, 9));
        assertThrows(IllegalArgumentException.class, () -> new ArrayOrderBook(1, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> new ArrayOrderBook(1, 1L << 40));
    }

    // ---- differential tests: the new book against the simple baseline book ----

    /**
     * Runs {@code orders} random orders with prices in [lo, hi] through both books and requires, after every
     * order, the same trades so far and the same best prices and resting order count; at the end the same
     * resting quantity on both sides.
     */
    private static void compareWithBaseline(long seed, long lo, long hi, int orders, int maxQuantity) {
        List<Trade> expected = new ArrayList<>();
        List<Trade> actual = new ArrayList<>();
        LockBasedOrderBook reference = new LockBasedOrderBook();
        ArrayOrderBook candidate = new ArrayOrderBook(lo, hi, orders + 1);
        SplittableRandom random = new SplittableRandom(seed);

        for (int i = 1; i <= orders; i++) {
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            long price = lo + random.nextInt((int) (hi - lo + 1));
            long quantity = 1 + random.nextInt(maxQuantity);
            reference.process(i, side, price, quantity, (b, s, p, q) -> expected.add(new Trade(b, s, p, q)));
            candidate.process(i, side, price, quantity, (b, s, p, q) -> actual.add(new Trade(b, s, p, q)));

            String where = "seed " + seed + ", order " + i;
            assertEquals(expected.size(), actual.size(), where + ": trade count");
            assertEquals(reference.bestBidPrice(), candidate.bestBidPrice(), where + ": best bid");
            assertEquals(reference.bestAskPrice(), candidate.bestAskPrice(), where + ": best ask");
            assertEquals(reference.restingOrderCount(), candidate.restingOrderCount(), where + ": resting orders");
        }
        assertFalse(expected.isEmpty(), "the stream should produce trades");
        assertEquals(expected, actual, "seed " + seed + ": trades");
        assertEquals(reference.restingQuantity(Side.BUY), candidate.restingQuantity(Side.BUY));
        assertEquals(reference.restingQuantity(Side.SELL), candidate.restingQuantity(Side.SELL));
    }

    @Test
    void matchesTheBaselineBookOnMediumBandStreams() {
        for (long seed = 1; seed <= 5; seed++) {
            compareWithBaseline(seed, 9_950, 10_050, 100_000, 50);
        }
    }

    @Test
    void matchesTheBaselineBookWhenAlmostEveryOrderCrosses() {
        for (long seed = 21; seed <= 23; seed++) {
            compareWithBaseline(seed, 100, 103, 100_000, 100); // tiny band, large quantities: constant sweeping
        }
    }

    @Test
    void matchesTheBaselineBookOnAWideBandWithLongScans() {
        compareWithBaseline(31, 1, 5_000, 50_000, 20);
    }
}
