package com.nanomatch.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nanomatch.baseline.LockBasedEngine;
import com.nanomatch.lockfree.LockFreeEngine;
import com.nanomatch.model.EngineFactory;
import com.nanomatch.model.MatchingEngine;
import com.nanomatch.model.Order;
import com.nanomatch.model.Side;
import com.nanomatch.model.Trade;
import com.nanomatch.sim.OrderGenerator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Many producer threads hit one engine at the same time. Using only the public interface, this checks what
 * must be true no matter how the threads interleave:
 *
 * <ol>
 *   <li>every order gets exactly one ticket, and all of them are processed;</li>
 *   <li>every trade respects both orders' limit prices and sides, and no order is filled for more than its
 *       quantity;</li>
 *   <li><b>replay check:</b> if the same orders are replayed one by one, in ticket order, through a fresh
 *       engine, the trades are identical. This proves the ticket really is the processing order, and it is
 *       the technique the Day 4 parity test uses between the two engines.</li>
 * </ol>
 *
 * <p>(That the book is never left crossed is checked in Person A's own tests, which can look inside the
 * books.) To cover another engine, add it to {@link #ENGINES}. The cross-engine comparison (lock-free against
 * baseline) is {@code ParityTest}.
 */
class ConcurrentInvariantsTest {

    private static final int THREADS = 4;
    private static final int ORDERS_PER_THREAD = 20_000;

    private static final Map<String, EngineFactory> ENGINES = new LinkedHashMap<>();

    static {
        ENGINES.put("baseline", LockBasedEngine::new);
        ENGINES.put("lockfree", LockFreeEngine::new);
    }

    @Test
    void concurrentSubmissionKeepsEveryInvariant() throws Exception {
        for (Map.Entry<String, EngineFactory> engine : ENGINES.entrySet()) {
            check(engine.getKey(), engine.getValue());
        }
    }

    private void check(String name, EngineFactory factory) throws Exception {
        // Orders are generated up front, one batch per thread, with ids that are unique across threads.
        final int total = THREADS * ORDERS_PER_THREAD;
        Order[][] perThread = new Order[THREADS][];
        Map<Long, Order> byId = new HashMap<>();
        for (int t = 0; t < THREADS; t++) {
            Order[] generated = new OrderGenerator(100 + t).generate(ORDERS_PER_THREAD);
            perThread[t] = new Order[generated.length];
            for (int i = 0; i < generated.length; i++) {
                Order o = generated[i];
                Order unique = new Order(t * 1_000_000L + o.id(), o.side(), o.price(), o.quantity());
                perThread[t][i] = unique;
                byId.put(unique.id(), unique);
            }
        }

        Order[] byTicket = new Order[total];
        RecordingSink sink = new RecordingSink();
        MatchingEngine engine = factory.create(sink);
        List<Trade> trades;
        try {
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread[] producers = new Thread[THREADS];
            for (int t = 0; t < THREADS; t++) {
                final Order[] mine = perThread[t];
                producers[t] = new Thread(() -> {
                    try {
                        start.await();
                        for (Order o : mine) {
                            long ticket = engine.submit(o);
                            byTicket[(int) ticket] = o; // each ticket is unique, so no two threads write the same slot
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                }, "producer-" + t);
                producers[t].start();
            }
            start.countDown();
            for (Thread producer : producers) {
                producer.join(60_000);
                assertFalse(producer.isAlive(), name + ": producer thread did not finish");
            }
            if (failure.get() != null) {
                throw new AssertionError(name + ": a producer thread failed", failure.get());
            }

            engine.awaitIdle();
            assertEquals(total, engine.submittedCount(), name + ": submittedCount");
            assertEquals(total, engine.processedCount(), name + ": processedCount");
            for (int i = 0; i < total; i++) {
                assertNotNull(byTicket[i], name + ": ticket " + i + " was never issued");
            }
            trades = new ArrayList<>(sink.trades());
        } finally {
            engine.close();
        }

        checkLimitsAndFills(name, byId, trades);

        // Replay check: same orders, one thread, in ticket order, fresh engine.
        RecordingSink replaySink = new RecordingSink();
        MatchingEngine replay = factory.create(replaySink);
        try {
            for (Order o : byTicket) {
                replay.submit(o);
            }
            replay.awaitIdle();
            assertEquals(trades, replaySink.trades(), name + ": replay in ticket order must give identical trades");
        } finally {
            replay.close();
        }
    }

    private static void checkLimitsAndFills(String name, Map<Long, Order> byId, List<Trade> trades) {
        assertFalse(trades.isEmpty(), name + ": expected some trades");
        Map<Long, Long> filled = new HashMap<>();
        for (Trade t : trades) {
            Order buyOrder = byId.get(t.buyOrderId());
            Order sellOrder = byId.get(t.sellOrderId());
            assertNotNull(buyOrder, name + ": trade names an unknown buy order: " + t);
            assertNotNull(sellOrder, name + ": trade names an unknown sell order: " + t);
            assertEquals(Side.BUY, buyOrder.side(), name + ": " + t);
            assertEquals(Side.SELL, sellOrder.side(), name + ": " + t);
            assertTrue(t.quantity() > 0, name + ": non-positive trade quantity: " + t);
            assertTrue(t.price() <= buyOrder.price(), name + ": buyer paid above their limit: " + t);
            assertTrue(t.price() >= sellOrder.price(), name + ": seller received below their limit: " + t);
            filled.merge(t.buyOrderId(), t.quantity(), Long::sum);
            filled.merge(t.sellOrderId(), t.quantity(), Long::sum);
        }
        for (Map.Entry<Long, Long> entry : filled.entrySet()) {
            long quantity = byId.get(entry.getKey()).quantity();
            assertTrue(entry.getValue() <= quantity,
                    name + ": order " + entry.getKey() + " overfilled: " + entry.getValue() + " > " + quantity);
        }
    }
}
