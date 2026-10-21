package com.nanomatch.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nanomatch.baseline.LockBasedEngine;
import com.nanomatch.model.Side;
import com.nanomatch.model.Trade;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for the baseline engine and its order book (Person A's own tests). */
class BaselineEngineTest {

    private final List<Trade> trades = new ArrayList<>();
    private LockBasedEngine engine;

    @BeforeEach
    void setUp() {
        trades.clear();
        engine = new LockBasedEngine((buy, sell, price, qty) -> trades.add(new Trade(buy, sell, price, qty)));
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void firstOrderRestsOnTheBook() {
        assertEquals(0, engine.submit(1, Side.BUY, 100, 10));
        assertTrue(trades.isEmpty());
        assertEquals(100, engine.bestBidPrice());
        assertEquals(0, engine.bestAskPrice());
        assertEquals(10, engine.restingQuantity(Side.BUY));
    }

    @Test
    void nonCrossingOrdersDoNotTrade() {
        engine.submit(1, Side.BUY, 99, 10);
        engine.submit(2, Side.SELL, 100, 10);
        assertTrue(trades.isEmpty());
        assertEquals(99, engine.bestBidPrice());
        assertEquals(100, engine.bestAskPrice());
    }

    @Test
    void exactMatchEmptiesTheBook() {
        engine.submit(1, Side.SELL, 100, 10);
        engine.submit(2, Side.BUY, 100, 10);
        assertEquals(List.of(new Trade(2, 1, 100, 10)), trades);
        assertEquals(0, engine.bestBidPrice());
        assertEquals(0, engine.bestAskPrice());
    }

    @Test
    void tradeHappensAtTheRestingOrdersPrice() {
        engine.submit(1, Side.SELL, 100, 5);
        engine.submit(2, Side.BUY, 105, 5);
        engine.submit(3, Side.BUY, 105, 5);
        engine.submit(4, Side.SELL, 100, 5);
        assertEquals(List.of(new Trade(2, 1, 100, 5), new Trade(3, 4, 105, 5)), trades);
    }

    @Test
    void bestPriceIsMatchedBeforeEarlierWorsePrice() {
        engine.submit(1, Side.SELL, 101, 5);
        engine.submit(2, Side.SELL, 100, 5);
        engine.submit(3, Side.BUY, 101, 8);
        assertEquals(List.of(new Trade(3, 2, 100, 5), new Trade(3, 1, 101, 3)), trades);
    }

    @Test
    void earlierOrderAtTheSamePriceIsMatchedFirst() {
        engine.submit(1, Side.SELL, 100, 5);
        engine.submit(2, Side.SELL, 100, 5);
        engine.submit(3, Side.BUY, 100, 7);
        assertEquals(List.of(new Trade(3, 1, 100, 5), new Trade(3, 2, 100, 2)), trades);
    }

    @Test
    void partiallyFilledRestingOrderKeepsItsPlaceInTheQueue() {
        engine.submit(1, Side.SELL, 100, 10);
        engine.submit(2, Side.SELL, 100, 5);
        engine.submit(3, Side.BUY, 100, 4);
        engine.submit(4, Side.BUY, 100, 7);
        assertEquals(List.of(new Trade(3, 1, 100, 4), new Trade(4, 1, 100, 6), new Trade(4, 2, 100, 1)), trades);
        assertEquals(4, engine.restingQuantity(Side.SELL));
    }

    @Test
    void unmatchedRemainderRestsAtTheIncomingLimitPrice() {
        engine.submit(1, Side.SELL, 100, 5);
        engine.submit(2, Side.BUY, 100, 8);
        assertEquals(List.of(new Trade(2, 1, 100, 5)), trades);
        assertEquals(100, engine.bestBidPrice());
        assertEquals(3, engine.restingQuantity(Side.BUY));
        assertEquals(0, engine.bestAskPrice());
    }

    @Test
    void aggressiveOrderSweepsSeveralPriceLevels() {
        engine.submit(1, Side.SELL, 100, 2);
        engine.submit(2, Side.SELL, 101, 2);
        engine.submit(3, Side.SELL, 102, 2);
        engine.submit(4, Side.BUY, 102, 5);
        assertEquals(List.of(new Trade(4, 1, 100, 2), new Trade(4, 2, 101, 2), new Trade(4, 3, 102, 1)), trades);
        assertEquals(102, engine.bestAskPrice());
        assertEquals(1, engine.restingQuantity(Side.SELL));
    }

    @Test
    void sellSideSweepsBidsFromTheHighestDown() {
        engine.submit(1, Side.BUY, 98, 5);
        engine.submit(2, Side.BUY, 100, 5);
        engine.submit(3, Side.BUY, 99, 5);
        engine.submit(4, Side.SELL, 98, 10);
        assertEquals(List.of(new Trade(2, 4, 100, 5), new Trade(3, 4, 99, 5)), trades);
        assertEquals(98, engine.bestBidPrice());
    }

    @Test
    void ticketsAreSequentialAndCountsTrack() {
        assertEquals(0, engine.submit(1, Side.BUY, 100, 1));
        assertEquals(1, engine.submit(2, Side.SELL, 200, 1));
        assertEquals(2, engine.submit(3, Side.BUY, 100, 1));
        assertEquals(3, engine.submittedCount());
        assertEquals(3, engine.processedCount());
    }

    @Test
    void invalidOrdersAreRejectedWithoutUsingATicket() {
        assertThrows(IllegalArgumentException.class, () -> engine.submit(1, Side.BUY, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> engine.submit(1, Side.BUY, 100, 0));
        assertThrows(IllegalArgumentException.class, () -> engine.submit(1, Side.BUY, -1, 10));
        assertThrows(NullPointerException.class, () -> engine.submit(1, null, 100, 10));
        assertEquals(0, engine.submittedCount());
        assertEquals(0, engine.submit(1, Side.BUY, 100, 10));
    }

    @Test
    void concurrentSubmissionConservesQuantityAndNeverLeavesACrossedBook() throws Exception {
        final int threads = 4;
        final int perThread = 10_000;
        final long[] tradedQuantity = new long[1]; // only touched inside the engine's lock
        LockBasedEngine concurrent = new LockBasedEngine((buy, sell, price, qty) -> tradedQuantity[0] += qty);

        long[] submittedBuy = new long[threads];
        long[] submittedSell = new long[threads];
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int index = t;
            workers[t] = new Thread(() -> {
                try {
                    SplittableRandom random = new SplittableRandom(1_000 + index);
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
                        long price = 9_950 + random.nextInt(101);
                        long quantity = 1 + random.nextInt(50);
                        concurrent.submit(index * 1_000_000L + i, side, price, quantity);
                        if (side == Side.BUY) {
                            submittedBuy[index] += quantity;
                        } else {
                            submittedSell[index] += quantity;
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            workers[t].start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(60_000);
            assertFalse(worker.isAlive(), "producer thread did not finish");
        }
        assertEquals(null, failure.get());

        concurrent.awaitIdle();
        assertEquals((long) threads * perThread, concurrent.submittedCount());
        assertEquals((long) threads * perThread, concurrent.processedCount());

        long totalBuy = 0;
        long totalSell = 0;
        for (int t = 0; t < threads; t++) {
            totalBuy += submittedBuy[t];
            totalSell += submittedSell[t];
        }
        // every unit submitted is either traded or still resting, on each side
        assertEquals(totalBuy, tradedQuantity[0] + concurrent.restingQuantity(Side.BUY));
        assertEquals(totalSell, tradedQuantity[0] + concurrent.restingQuantity(Side.SELL));
        // if both sides still have resting orders, they must not cross
        long bid = concurrent.bestBidPrice();
        long ask = concurrent.bestAskPrice();
        if (bid != 0 && ask != 0) {
            assertTrue(bid < ask, "crossed book: bid " + bid + " >= ask " + ask);
        }
        concurrent.close();
    }
}
