package com.nanomatch.bench;

import com.nanomatch.baseline.LockBasedEngine;
import com.nanomatch.lockfree.LockFreeEngine;
import com.nanomatch.model.MatchingEngine;
import com.nanomatch.model.Order;
import com.nanomatch.model.Side;
import com.nanomatch.model.TradeSink;
import com.nanomatch.sim.OrderGenerator;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Everything the JMH benchmark needs that has nothing to do with JMH itself: the workload, the two engines
 * under test, and how "an order is fully processed" is awaited. It is kept in a plain class so it can be unit
 * tested ({@code BenchmarkWorkloadTest}) and so the benchmark class stays a thin wrapper.
 *
 * <h2>The workload</h2>
 * Random limit orders in a <b>tight price band</b> (10,000 +/- 3 ticks) with small quantities, with one
 * deliberate rule: <b>every BUY is priced at or above the mid price and every SELL at or below it.</b> So any
 * buy crosses any resting sell and vice versa. That gives the benchmark three properties:
 * <ul>
 *   <li>almost every order trades, so the matching code is really exercised;</li>
 *   <li>several price levels are in use (the offset from the mid price is random), so price priority and FIFO
 *       within a level both matter;</li>
 *   <li><b>the book stays small however long the benchmark runs.</b> Orders can rest on only one side at a time
 *       (the side with the surplus), and that surplus is a random walk of the buy/sell quantity imbalance.</li>
 * </ul>
 * The last point matters because the engines have no cancel. If buys and sells could rest side by side (for
 * example with plain uniform random prices), the book would grow without limit and the benchmark would end up
 * measuring memory growth instead of matching. (A first version of this workload did exactly that, and
 * {@code BenchmarkWorkloadTest} caught it.)
 *
 * <p>The benchmark cycles through a thread's orders over and over, so one more thing matters: the quantities
 * come in equal BUY/SELL pairs, which makes every cycle net to exactly zero. Without that, the same small
 * imbalance would be added again on every cycle and the book would grow linearly with the run length. (The
 * first probe of a version without it showed 60,000 resting units after 6 million orders.)
 *
 * <p>Both engines get exactly the same orders: thread {@code i} always uses seed {@code BASE_SEED + i}.
 *
 * <h2>The engines</h2>
 * The lock-free engine's order book needs its price range to match the workload ({@code MID - BAND} to
 * {@code MID + BAND}); that keeps its search for the next best price trivial. Trades go to
 * {@link TradeSink#NOOP}, so neither engine allocates or does work in the sink.
 */
public final class BenchmarkWorkload {

    public static final long MID_PRICE = 10_000;
    public static final long PRICE_BAND = 3;
    public static final long MIN_QUANTITY = 1;
    public static final long MAX_QUANTITY = 5;
    public static final long BASE_SEED = 1_000;

    /** Orders pre-generated per thread; the benchmark cycles through them. A power of two. */
    public static final int ORDERS_PER_THREAD = 1 << 16;

    public static final String BASELINE = "baseline";
    public static final String LOCK_FREE = "lockfree";

    private static final int RING_CAPACITY = 1 << 16;
    private static final int MAX_RESTING_ORDERS = 1 << 18;

    private static final AtomicInteger NEXT_THREAD_INDEX = new AtomicInteger();

    private BenchmarkWorkload() {
    }

    /** The order stream of benchmark thread {@code threadIndex}. Deterministic. */
    public static Order[] ordersForThread(int threadIndex) {
        Order[] base = new OrderGenerator(BASE_SEED + threadIndex, MID_PRICE, PRICE_BAND, MIN_QUANTITY, MAX_QUANTITY, 50)
                .generate(ORDERS_PER_THREAD);
        Order[] orders = new Order[base.length];
        for (int i = 0; i < base.length; i++) {
            Order o = base[i];
            long offset = Math.abs(o.price() - MID_PRICE);
            boolean buy = (i & 1) == 0;                      // BUY, SELL, BUY, SELL, ...
            long quantity = base[i & ~1].quantity();         // each BUY/SELL pair has the same quantity
            // buys at or above the mid price, sells at or below it, so opposite orders always cross
            long price = buy ? MID_PRICE + offset : MID_PRICE - offset;
            orders[i] = new Order(o.id(), buy ? Side.BUY : Side.SELL, price, quantity);
        }
        return orders;
    }

    /** Hands out thread indexes 0, 1, 2, ... to the benchmark threads of this JVM. */
    public static Order[] ordersForNextThread() {
        return ordersForThread(NEXT_THREAD_INDEX.getAndIncrement());
    }

    /** Creates an engine by name ({@link #BASELINE} or {@link #LOCK_FREE}) that ignores its trades. */
    public static MatchingEngine createEngine(String name) {
        return createEngine(name, TradeSink.NOOP);
    }

    /** Creates an engine by name that reports its trades to {@code sink}. */
    public static MatchingEngine createEngine(String name, TradeSink sink) {
        switch (name) {
            case BASELINE:
                return new LockBasedEngine(sink);
            case LOCK_FREE:
                return new LockFreeEngine(sink, RING_CAPACITY, MID_PRICE - PRICE_BAND, MID_PRICE + PRICE_BAND,
                        MAX_RESTING_ORDERS);
            default:
                throw new IllegalArgumentException("unknown engine '" + name + "', expected '" + BASELINE
                        + "' or '" + LOCK_FREE + "'");
        }
    }

    /**
     * Submits one order and waits until it has been fully processed. This is the latency definition used for
     * both engines: the time from the {@code submit} call until the order is done. For the baseline the order
     * is already done when {@code submit} returns, so the wait ends immediately.
     *
     * @return the order's ticket
     */
    public static long submitAndWait(MatchingEngine engine, Order order) {
        long ticket = engine.submit(order);
        awaitProcessed(engine, ticket);
        return ticket;
    }

    /**
     * Submits {@code count} orders back to back and waits until the last one has been processed. Orders are
     * processed in ticket order, so that means the whole burst is done.
     *
     * @return the ticket of the last order
     */
    public static long submitBurstAndWait(MatchingEngine engine, Order[] orders, int firstIndex, int count) {
        long last = -1;
        for (int i = 0; i < count; i++) {
            last = engine.submit(orders[(firstIndex + i) & (orders.length - 1)]);
        }
        awaitProcessed(engine, last);
        return last;
    }

    /** Waits until {@code ticket} has been processed: a short spin, then yielding the CPU. */
    public static void awaitProcessed(MatchingEngine engine, long ticket) {
        int attempts = 0;
        while (engine.processedCount() <= ticket) {
            if (++attempts < 1_000) {
                Thread.onSpinWait();
            } else {
                Thread.yield();
            }
        }
    }
}
