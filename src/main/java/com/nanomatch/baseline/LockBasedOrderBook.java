package com.nanomatch.baseline;

import com.nanomatch.model.Side;
import com.nanomatch.model.TradeSink;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Order book for the lock-based baseline engine: the simplest correct implementation of price-time priority.
 *
 * <p>Each side is a {@link TreeMap} from price to a FIFO queue of resting orders, sorted so that the
 * <b>best price is always first</b> (highest price for bids, lowest for asks). Within a price level the
 * queue gives time priority.
 *
 * <p>This class is deliberately simple and allocates freely (boxed prices, queue nodes, one object per
 * resting order). That is fine: it is the baseline the lock-free engine is compared against.
 *
 * <p><b>Not thread-safe.</b> {@link LockBasedEngine} guards every call with its lock.
 */
public final class LockBasedOrderBook {

    private static final class RestingOrder {
        final long id;
        long remaining;

        RestingOrder(long id, long remaining) {
            this.id = id;
            this.remaining = remaining;
        }
    }

    private final TreeMap<Long, ArrayDeque<RestingOrder>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, ArrayDeque<RestingOrder>> asks = new TreeMap<>();

    /**
     * Matches the incoming order against the opposite side, reporting each trade to {@code sink}, and rests
     * whatever quantity is left. Arguments are assumed valid (the engine checks them).
     */
    public void process(long orderId, Side side, long price, long quantity, TradeSink sink) {
        if (side == Side.BUY) {
            long remaining = match(asks, Side.BUY, orderId, price, quantity, sink);
            if (remaining > 0) {
                rest(bids, price, orderId, remaining);
            }
        } else {
            long remaining = match(bids, Side.SELL, orderId, price, quantity, sink);
            if (remaining > 0) {
                rest(asks, price, orderId, remaining);
            }
        }
    }

    /** Returns the quantity of the incoming order that is still unmatched. */
    private static long match(TreeMap<Long, ArrayDeque<RestingOrder>> opposite, Side incomingSide,
                              long incomingId, long limitPrice, long remaining, TradeSink sink) {
        while (remaining > 0) {
            Map.Entry<Long, ArrayDeque<RestingOrder>> level = opposite.firstEntry(); // best price
            if (level == null) {
                break;
            }
            long levelPrice = level.getKey();
            boolean crosses = incomingSide == Side.BUY ? levelPrice <= limitPrice : levelPrice >= limitPrice;
            if (!crosses) {
                break;
            }
            ArrayDeque<RestingOrder> queue = level.getValue();
            while (remaining > 0 && !queue.isEmpty()) {
                RestingOrder resting = queue.peekFirst(); // earliest order at this price
                long traded = Math.min(remaining, resting.remaining);
                // the trade price is always the resting order's price
                if (incomingSide == Side.BUY) {
                    sink.onTrade(incomingId, resting.id, levelPrice, traded);
                } else {
                    sink.onTrade(resting.id, incomingId, levelPrice, traded);
                }
                remaining -= traded;
                resting.remaining -= traded;
                if (resting.remaining == 0) {
                    queue.pollFirst();
                }
            }
            if (queue.isEmpty()) {
                opposite.pollFirstEntry();
            }
        }
        return remaining;
    }

    private static void rest(TreeMap<Long, ArrayDeque<RestingOrder>> side, long price, long id, long remaining) {
        side.computeIfAbsent(price, p -> new ArrayDeque<>()).addLast(new RestingOrder(id, remaining));
    }

    /** Highest resting buy price, or 0 if there are no resting buys. */
    public long bestBidPrice() {
        return bids.isEmpty() ? 0 : bids.firstKey();
    }

    /** Lowest resting sell price, or 0 if there are no resting sells. */
    public long bestAskPrice() {
        return asks.isEmpty() ? 0 : asks.firstKey();
    }

    /** Total unmatched quantity resting on the given side. */
    public long restingQuantity(Side side) {
        long total = 0;
        for (ArrayDeque<RestingOrder> queue : (side == Side.BUY ? bids : asks).values()) {
            for (RestingOrder order : queue) {
                total += order.remaining;
            }
        }
        return total;
    }

    /** Number of resting orders on both sides. */
    public int restingOrderCount() {
        int count = 0;
        for (ArrayDeque<RestingOrder> queue : bids.values()) {
            count += queue.size();
        }
        for (ArrayDeque<RestingOrder> queue : asks.values()) {
            count += queue.size();
        }
        return count;
    }
}
