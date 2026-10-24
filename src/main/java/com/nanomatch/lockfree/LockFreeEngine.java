package com.nanomatch.lockfree;

import com.nanomatch.model.MatchingEngine;
import com.nanomatch.model.Side;
import com.nanomatch.model.TradeSink;
import com.nanomatch.orderbook.ArrayOrderBook;
import java.util.Objects;

/**
 * The lock-free engine: many producer threads put orders into an {@link OrderRingBuffer}, and <b>one</b>
 * matching thread takes them out in sequence order and matches them.
 *
 * <p>Because only the matching thread ever touches the order book (the "single writer" principle), the book
 * needs no locks at all. Producers never take a lock either: they claim a slot with a CAS and write into it.
 * {@code submit} returns as soon as the order is published; matching happens a little later on the matching
 * thread. The ticket {@code submit} returns is the order's ring sequence, and {@code processedCount() > ticket}
 * means it has been fully matched.
 *
 * <p>The matching thread uses the allocation-free {@link ArrayOrderBook}. With that book, the ring buffer's
 * pre-allocated slots and a sink that does not allocate (for example {@link TradeSink#NOOP}), the whole path
 * from {@code submit} to the matched trade allocates nothing per order, so garbage-collection pauses cannot add
 * latency spikes. {@code AllocationTest} checks this automatically.
 *
 * <p><b>Price range:</b> the book keeps one array slot per price tick, so the engine accepts prices only
 * inside {@code [minPrice, maxPrice]} (a price outside is rejected by {@code submit} like any invalid order,
 * without using a ticket). Choose the range to fit the workload: the tighter it is, the faster the book finds
 * the next best price after a level empties. The default range is 1 to 65,535.
 *
 * <p>The {@link TradeSink} is called only from the matching thread, one trade at a time.
 *
 * <p><b>Lifecycle:</b> the matching thread starts in the constructor. Call {@link #close()} once, after all
 * producers have finished; it processes everything already submitted and then stops the thread.
 */
public final class LockFreeEngine implements MatchingEngine {

    public static final int DEFAULT_CAPACITY = 1 << 16;
    public static final long DEFAULT_MIN_PRICE = 1;
    public static final long DEFAULT_MAX_PRICE = 65_535;

    private final OrderRingBuffer ring;
    private final ArrayOrderBook book;           // touched by the matching thread only
    private final long minPrice;
    private final long maxPrice;
    private final TradeSink sink;
    private final Thread matcher;

    private volatile boolean closed;
    private volatile Throwable failure;

    public LockFreeEngine(TradeSink sink) {
        this(sink, DEFAULT_CAPACITY);
    }

    /** @param capacity ring buffer size; a power of two, at least 2 (a small ring makes producers wait more) */
    public LockFreeEngine(TradeSink sink, int capacity) {
        this(sink, capacity, DEFAULT_MIN_PRICE, DEFAULT_MAX_PRICE, ArrayOrderBook.DEFAULT_MAX_RESTING_ORDERS);
    }

    /**
     * @param capacity         ring buffer size; a power of two, at least 2
     * @param minPrice         lowest accepted price in ticks (at least 1)
     * @param maxPrice         highest accepted price in ticks
     * @param maxRestingOrders most orders that can rest on the book at once. If a run would exceed it, the
     *                         matching thread fails visibly (see {@link #failure()}) instead of allocating
     */
    public LockFreeEngine(TradeSink sink, int capacity, long minPrice, long maxPrice, int maxRestingOrders) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.ring = new OrderRingBuffer(capacity);
        this.book = new ArrayOrderBook(minPrice, maxPrice, maxRestingOrders);
        this.minPrice = minPrice;
        this.maxPrice = maxPrice;
        this.matcher = new Thread(this::runMatchingLoop, "nanomatch-matcher");
        this.matcher.setDaemon(true);
        this.matcher.start(); // last statement: every field above is already assigned
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
        if (price < minPrice || price > maxPrice) {
            throw new IllegalArgumentException("price " + price + " is outside this engine's range ["
                    + minPrice + ", " + maxPrice + "]");
        }
        if (closed) {
            throw new IllegalStateException("engine is closed");
        }
        Throwable f = failure;
        if (f != null) {
            throw new IllegalStateException("the matching thread failed", f);
        }
        long sequence = ring.claim();
        OrderSlot slot = ring.slot(sequence);
        slot.orderId = orderId;
        slot.side = side;
        slot.price = price;
        slot.quantity = quantity;
        ring.publish(sequence);
        return sequence;
    }

    @Override
    public long submittedCount() {
        return ring.claimedCount();
    }

    @Override
    public long processedCount() {
        return ring.consumedCount();
    }

    /** Id of the matching thread (used by the allocation test). */
    public long matcherThreadId() {
        return matcher.getId();
    }

    /** The exception that stopped matching, or null. Once set, further submits are rejected. */
    public Throwable failure() {
        return failure;
    }

    /** Highest resting buy price, or 0 if none. <b>Only when idle</b> (after {@code awaitIdle()}, no producers running). */
    public long bestBidPrice() {
        return book.bestBidPrice();
    }

    /** Lowest resting sell price, or 0 if none. <b>Only when idle.</b> */
    public long bestAskPrice() {
        return book.bestAskPrice();
    }

    /** Unmatched quantity resting on one side. <b>Only when idle.</b> */
    public long restingQuantity(Side side) {
        return book.restingQuantity(side);
    }

    @Override
    public void close() {
        closed = true;
        try {
            matcher.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runMatchingLoop() {
        long next = 0;
        int idle = 0;
        for (;;) {
            if (ring.isPublished(next)) {
                idle = 0;
                if (failure == null) {
                    OrderSlot slot = ring.slot(next);
                    try {
                        book.process(slot.orderId, slot.side, slot.price, slot.quantity, sink);
                    } catch (Throwable t) {
                        failure = t;
                        ring.halt();
                    }
                }
                next++;
                ring.markConsumed(next); // after the sink calls, so a reader of processedCount sees the trades
            } else if (closed && next >= ring.claimedCount()) {
                return; // closed, and nothing left that has been claimed
            } else {
                OrderRingBuffer.backoff(++idle);
            }
        }
    }
}
