package com.nanomatch.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nanomatch.baseline.LockBasedEngine;
import com.nanomatch.lockfree.LockFreeEngine;
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

/**
 * Person A's own tests for the lock-free engine. (Person B's contract, invariants and parity tests also run
 * against it from Day 4.) The trade list is only read after {@code awaitIdle()}, which makes the matching
 * thread's writes visible.
 */
class LockFreeEngineTest {

    private final List<Trade> trades = new ArrayList<>();
    private LockFreeEngine engine;

    @BeforeEach
    void setUp() {
        trades.clear();
        engine = new LockFreeEngine((buy, sell, price, qty) -> trades.add(new Trade(buy, sell, price, qty)));
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void aMatchTradesAtTheRestingPrice() {
        engine.submit(1, Side.SELL, 100, 10);
        engine.submit(2, Side.BUY, 105, 10);
        engine.awaitIdle();
        assertEquals(List.of(new Trade(2, 1, 100, 10)), trades);
        assertEquals(0, engine.bestBidPrice());
        assertEquals(0, engine.bestAskPrice());
    }

    @Test
    void priceThenTimePriority() {
        engine.submit(1, Side.SELL, 101, 5);
        engine.submit(2, Side.SELL, 100, 5);
        engine.submit(3, Side.SELL, 100, 5);
        engine.submit(4, Side.BUY, 101, 12);
        engine.awaitIdle();
        assertEquals(List.of(new Trade(4, 2, 100, 5), new Trade(4, 3, 100, 5), new Trade(4, 1, 101, 2)), trades);
        assertEquals(101, engine.bestAskPrice());
        assertEquals(3, engine.restingQuantity(Side.SELL));
    }

    @Test
    void partialFillsLeaveTheRemainderResting() {
        engine.submit(1, Side.SELL, 100, 10);
        engine.submit(2, Side.BUY, 100, 4);
        engine.submit(3, Side.BUY, 100, 7);
        engine.awaitIdle();
        assertEquals(List.of(new Trade(2, 1, 100, 4), new Trade(3, 1, 100, 6)), trades);
        assertEquals(100, engine.bestBidPrice());
        assertEquals(1, engine.restingQuantity(Side.BUY));
    }

    @Test
    void ticketsFollowSubmissionOrderAndCountsTrack() {
        assertEquals(0, engine.submit(1, Side.BUY, 100, 1));
        assertEquals(1, engine.submit(2, Side.SELL, 200, 1));
        assertEquals(2, engine.submit(3, Side.BUY, 100, 1));
        assertEquals(3, engine.submittedCount());
        engine.awaitIdle();
        assertEquals(3, engine.processedCount());
    }

    @Test
    void invalidOrdersAreRejectedWithoutUsingATicket() {
        assertThrows(IllegalArgumentException.class, () -> engine.submit(1, Side.BUY, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> engine.submit(1, Side.BUY, 100, 0));
        assertThrows(NullPointerException.class, () -> engine.submit(1, null, 100, 10));
        assertEquals(0, engine.submittedCount());
        assertEquals(0, engine.submit(1, Side.BUY, 100, 10));
    }

    @Test
    void closeIsIdempotentAndSubmitAfterCloseIsRejected() {
        engine.submit(1, Side.BUY, 100, 1);
        engine.close();
        engine.close();
        assertEquals(1, engine.processedCount(), "close() must finish everything already submitted");
        assertThrows(IllegalStateException.class, () -> engine.submit(2, Side.BUY, 100, 1));
    }

    private static long[][] randomOrders(long seed, int count) {
        SplittableRandom random = new SplittableRandom(seed);
        long[][] orders = new long[count][];
        for (int i = 0; i < count; i++) {
            orders[i] = new long[] {i + 1, random.nextBoolean() ? 0 : 1, 9_950 + random.nextInt(101), 1 + random.nextInt(50)};
        }
        return orders;
    }

    /**
     * Single producer, tiny ring (so the producer waits and the slots wrap hundreds of times): the lock-free
     * engine must give exactly the same trades as the baseline.
     */
    @Test
    void singleProducerGivesTheSameTradesAsTheBaselineEvenWithATinyRing() {
        long[][] orders = randomOrders(77, 5_000);

        List<Trade> expected = new ArrayList<>();
        try (LockBasedEngine baseline = new LockBasedEngine((b, s, p, q) -> expected.add(new Trade(b, s, p, q)))) {
            for (long[] o : orders) {
                baseline.submit(o[0], o[1] == 0 ? Side.BUY : Side.SELL, o[2], o[3]);
            }
        }

        List<Trade> actual = new ArrayList<>();
        try (LockFreeEngine tiny = new LockFreeEngine((b, s, p, q) -> actual.add(new Trade(b, s, p, q)), 4)) {
            for (long[] o : orders) {
                tiny.submit(o[0], o[1] == 0 ? Side.BUY : Side.SELL, o[2], o[3]);
            }
            tiny.awaitIdle();
            assertEquals(null, tiny.failure());
        }
        assertFalse(expected.isEmpty());
        assertEquals(expected, actual);
    }

    /** Day 3 goal: 4 producers x 100k orders, every order processed exactly once. */
    @Test
    void fourProducersAHundredThousandOrdersEachAreProcessedExactlyOnce() throws Exception {
        final int threads = 4;
        final int perThread = 100_000;
        final int total = threads * perThread;
        final long[] tradedQuantity = new long[1]; // touched only by the matching thread
        LockFreeEngine concurrent = new LockFreeEngine((b, s, p, q) -> tradedQuantity[0] += q, 1 << 12);

        long[] submittedBuy = new long[threads];
        long[] submittedSell = new long[threads];
        boolean[] ticketSeen = new boolean[total];
        boolean[] duplicateTicket = new boolean[1];
        Object seenLock = new Object();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int index = t;
            workers[t] = new Thread(() -> {
                try {
                    SplittableRandom random = new SplittableRandom(500 + index);
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
                        long price = 9_950 + random.nextInt(101);
                        long quantity = 1 + random.nextInt(50);
                        long ticket = concurrent.submit(index * 1_000_000L + i, side, price, quantity);
                        synchronized (seenLock) {
                            if (ticketSeen[(int) ticket]) {
                                duplicateTicket[0] = true;
                            }
                            ticketSeen[(int) ticket] = true;
                        }
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
            worker.join(120_000);
            assertFalse(worker.isAlive(), "producer thread did not finish");
        }
        assertEquals(null, failure.get());

        concurrent.awaitIdle();
        assertEquals(null, concurrent.failure());
        assertFalse(duplicateTicket[0], "a ticket was handed out twice");
        for (int i = 0; i < total; i++) {
            assertTrue(ticketSeen[i], "ticket " + i + " was never handed out");
        }
        assertEquals(total, concurrent.submittedCount());
        assertEquals(total, concurrent.processedCount());

        long totalBuy = 0;
        long totalSell = 0;
        for (int t = 0; t < threads; t++) {
            totalBuy += submittedBuy[t];
            totalSell += submittedSell[t];
        }
        // every unit submitted is either traded or still resting, on each side
        assertEquals(totalBuy, tradedQuantity[0] + concurrent.restingQuantity(Side.BUY));
        assertEquals(totalSell, tradedQuantity[0] + concurrent.restingQuantity(Side.SELL));
        long bid = concurrent.bestBidPrice();
        long ask = concurrent.bestAskPrice();
        if (bid != 0 && ask != 0) {
            assertTrue(bid < ask, "crossed book: bid " + bid + " >= ask " + ask);
        }
        concurrent.close();
    }
}
