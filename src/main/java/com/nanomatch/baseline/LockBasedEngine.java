package com.nanomatch.baseline;

import com.nanomatch.model.MatchingEngine;
import com.nanomatch.model.Side;
import com.nanomatch.model.TradeSink;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The baseline engine: one {@link ReentrantLock} around a simple order book.
 *
 * <p>Every {@code submit} takes the lock, so orders are processed one at a time, in the order the lock is
 * acquired. That lock-acquisition order is the arrival order, and the ticket returned by {@code submit} is
 * the order's position in it. Matching therefore happens on the calling thread, inside {@code submit}, which
 * means an order is already fully processed when {@code submit} returns.
 *
 * <p>This is the design the lock-free engine is measured against: correct and easy to reason about, but all
 * producer threads contend for one lock.
 *
 * <p>The {@link TradeSink} is called while the lock is held, so it is never called concurrently. It must not
 * throw; a sink that throws leaves the book in an unspecified state.
 */
public final class LockBasedEngine implements MatchingEngine {

    private final ReentrantLock lock = new ReentrantLock();
    private final LockBasedOrderBook book = new LockBasedOrderBook();
    private final TradeSink sink;

    // Both counters are only written while holding the lock, but are read without it, hence volatile.
    private volatile long submitted;
    private volatile long processed;

    public LockBasedEngine(TradeSink sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public long submit(long orderId, Side side, long price, long quantity) {
        Objects.requireNonNull(side, "side");
        if (price <= 0) {
            throw new IllegalArgumentException("price must be > 0 but was " + price);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0 but was " + quantity);
        }
        lock.lock();
        try {
            long ticket = submitted;
            submitted = ticket + 1;
            try {
                book.process(orderId, side, price, quantity, sink);
            } finally {
                processed = ticket + 1; // the sequence always advances, even if the sink threw
            }
            return ticket;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long submittedCount() {
        return submitted;
    }

    @Override
    public long processedCount() {
        return processed;
    }

    /** Highest resting buy price, or 0 if none. For tests and diagnostics. */
    public long bestBidPrice() {
        lock.lock();
        try {
            return book.bestBidPrice();
        } finally {
            lock.unlock();
        }
    }

    /** Lowest resting sell price, or 0 if none. For tests and diagnostics. */
    public long bestAskPrice() {
        lock.lock();
        try {
            return book.bestAskPrice();
        } finally {
            lock.unlock();
        }
    }

    /** Total unmatched quantity resting on one side. For tests and diagnostics. */
    public long restingQuantity(Side side) {
        lock.lock();
        try {
            return book.restingQuantity(side);
        } finally {
            lock.unlock();
        }
    }

    /** Nothing to stop: the baseline owns no background threads. */
    @Override
    public void close() {
    }
}
