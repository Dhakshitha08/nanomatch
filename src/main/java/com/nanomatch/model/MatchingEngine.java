package com.nanomatch.model;

/**
 * The contract shared by the lock-based baseline engine and the lock-free engine.
 *
 * <p><b>This interface is frozen after Day 1.</b> Changing it later needs both team members to agree.
 *
 * <h2>Matching rules (identical for both engines)</h2>
 * <ul>
 *   <li>Only limit orders. An order that is not fully matched rests on the book until matched.</li>
 *   <li>A BUY crosses when its price is greater than or equal to the best (lowest) resting SELL price.
 *       A SELL crosses when its price is less than or equal to the best (highest) resting BUY price.</li>
 *   <li><b>Price priority:</b> the incoming order is matched against the best opposite price first,
 *       then the next best, and so on, while it still crosses and has quantity left.</li>
 *   <li><b>Time priority:</b> at the same price, the earlier resting order is matched first (FIFO).</li>
 *   <li>The trade price is the price of the <b>resting</b> order, not the incoming one.</li>
 *   <li>Each match produces one trade for min(incoming remaining, resting remaining) and is reported
 *       to the {@link TradeSink} in the order it happens.</li>
 * </ul>
 *
 * <h2>Arrival order and tickets</h2>
 * Many threads may call {@code submit} at the same time. The engine puts every order into one single
 * sequence (lock acquisition order for the baseline, ring-buffer sequence for the lock-free engine) and
 * matches strictly in that sequence. {@code submit} returns the order's zero-based position in that
 * sequence: its <i>ticket</i>. The order with ticket {@code t} is fully processed once
 * {@code processedCount() > t}. Because the ticket is also the processing order, a test can record
 * {@code ticket -> order} on the producer side and replay exactly the same sequence through the other
 * engine. Both engines must then produce identical trades.
 *
 * <p>Engine implementations take a {@link TradeSink} in their constructor (see {@link EngineFactory}).
 */
public interface MatchingEngine extends AutoCloseable {

    /**
     * Submits a limit order. Safe to call from many threads at the same time.
     *
     * @return the order's ticket (zero-based position in the processing sequence)
     * @throws IllegalArgumentException if {@code price <= 0} or {@code quantity <= 0}
     */
    long submit(long orderId, Side side, long price, long quantity);

    /** Convenience overload for callers that already hold an {@link Order}. */
    default long submit(Order order) {
        return submit(order.id(), order.side(), order.price(), order.quantity());
    }

    /** Number of orders accepted so far, i.e. how many tickets have been issued. */
    long submittedCount();

    /** Number of orders fully processed so far (matched, and any trades reported to the sink). */
    long processedCount();

    /**
     * Waits until every order whose {@code submit} had already returned when this method was called has
     * been fully processed. Intended for tests and benchmarks, not for the hot path.
     *
     * @throws IllegalStateException if that does not happen within 30 seconds (so a bug shows up as a failing
     *                               test instead of a hang)
     */
    default void awaitIdle() {
        awaitIdle(30_000);
    }

    /** Like {@link #awaitIdle()} but with an explicit time limit in milliseconds. */
    default void awaitIdle(long timeoutMillis) {
        final long target = submittedCount();
        final long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (processedCount() < target) {
            if (System.nanoTime() - deadline > 0) {
                throw new IllegalStateException("engine did not become idle within " + timeoutMillis
                        + " ms: processed " + processedCount() + " of " + target + " orders");
            }
            Thread.onSpinWait();
        }
    }

    /** Stops any background thread the engine owns. Safe to call more than once. */
    @Override
    void close();
}
