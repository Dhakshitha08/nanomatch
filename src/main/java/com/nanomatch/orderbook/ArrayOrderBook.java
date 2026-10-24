package com.nanomatch.orderbook;

import com.nanomatch.model.Side;
import com.nanomatch.model.TradeSink;

/**
 * Order book designed for the lock-free engine's single matching thread: <b>no allocation after construction,
 * no boxing, no object per order, no tree</b>.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li><b>Price levels are array slots.</b> Prices are integer ticks in a fixed range
 *       {@code [minPrice, maxPrice]}; the level for a price is simply {@code price - minPrice}. Looking up a
 *       level is one array index instead of a {@code TreeMap} search with boxed keys.</li>
 *   <li><b>Time priority is a linked list per level.</b> Each level keeps the head and tail of a FIFO queue of
 *       resting orders. Matching takes from the head, new orders join at the tail.</li>
 *   <li><b>Resting orders live in a pre-allocated pool</b> of parallel primitive arrays (id, remaining quantity,
 *       next link), with a free list. Resting an order takes a node from the free list; a fully matched order
 *       returns its node.</li>
 *   <li><b>Best prices are tracked as indexes</b> ({@code bestBid}, {@code bestAsk}). When the best level
 *       empties, the next best is found by scanning outward over the (usually few) empty levels.</li>
 * </ul>
 *
 * <p>Matching rules are exactly those of {@code MatchingEngine}: price priority, FIFO at equal price, trade at
 * the resting order's price, remainder rests. The Day 4 differential tests check this book against the simple
 * baseline book order by order.
 *
 * <h2>Limits (and why)</h2>
 * <ul>
 *   <li>Prices outside {@code [minPrice, maxPrice]} are rejected with {@link IllegalArgumentException}. A real
 *       engine would re-centre the range; here the range is a constructor parameter.</li>
 *   <li>At most {@code maxRestingOrders} orders can rest at once. Growing the pool would allocate, which is
 *       exactly what this class avoids, so a full pool throws {@link IllegalStateException}. There is no cancel
 *       or expiry in scope, so the pool must be sized for the workload.</li>
 *   <li>Scanning for the next best level is linear in the number of empty levels crossed. Fine for a band of
 *       a few hundred ticks; a bitmap would be the next step for much wider ranges.</li>
 * </ul>
 *
 * <p><b>Not thread-safe</b>: it belongs to the matching thread (single writer).
 */
public final class ArrayOrderBook {

    public static final int DEFAULT_MAX_RESTING_ORDERS = 1 << 20;

    private static final int NONE = -1;
    private static final int MAX_LEVELS = 1 << 24;

    private final long minPrice;
    private final int levels;

    // order pool: parallel arrays indexed by node number
    private final long[] nodeOrderId;
    private final long[] nodeRemaining;
    private final int[] nodeNext;
    private int freeHead;
    private int restingCount;

    // FIFO queue per price level, per side: head and tail node (or NONE)
    private final int[] bidHead;
    private final int[] bidTail;
    private final int[] askHead;
    private final int[] askTail;

    private int bestBid = NONE; // index of the highest non-empty bid level
    private int bestAsk = NONE; // index of the lowest non-empty ask level

    public ArrayOrderBook(long minPrice, long maxPrice) {
        this(minPrice, maxPrice, DEFAULT_MAX_RESTING_ORDERS);
    }

    /**
     * @param minPrice         lowest accepted price in ticks (at least 1)
     * @param maxPrice         highest accepted price in ticks
     * @param maxRestingOrders size of the order pool
     */
    public ArrayOrderBook(long minPrice, long maxPrice, int maxRestingOrders) {
        if (minPrice < 1 || maxPrice < minPrice) {
            throw new IllegalArgumentException("need 1 <= minPrice <= maxPrice");
        }
        if (maxPrice - minPrice + 1 > MAX_LEVELS) {
            throw new IllegalArgumentException("price range too wide (max " + MAX_LEVELS + " levels)");
        }
        if (maxRestingOrders < 1) {
            throw new IllegalArgumentException("maxRestingOrders must be >= 1");
        }
        this.minPrice = minPrice;
        this.levels = (int) (maxPrice - minPrice + 1);

        this.nodeOrderId = new long[maxRestingOrders];
        this.nodeRemaining = new long[maxRestingOrders];
        this.nodeNext = new int[maxRestingOrders];
        for (int i = 0; i < maxRestingOrders - 1; i++) {
            nodeNext[i] = i + 1;
        }
        nodeNext[maxRestingOrders - 1] = NONE;
        this.freeHead = 0;

        this.bidHead = filled(levels);
        this.bidTail = filled(levels);
        this.askHead = filled(levels);
        this.askTail = filled(levels);
    }

    private static int[] filled(int size) {
        int[] array = new int[size];
        java.util.Arrays.fill(array, NONE);
        return array;
    }

    /**
     * Matches the incoming order against the opposite side, reporting each trade to {@code sink}, and rests
     * whatever quantity is left.
     *
     * @throws IllegalArgumentException if the price is outside the configured range
     * @throws IllegalStateException    if the remainder needs to rest but the order pool is full
     */
    public void process(long orderId, Side side, long price, long quantity, TradeSink sink) {
        if (price < minPrice || price >= minPrice + levels) {
            throw new IllegalArgumentException("price " + price + " is outside the book's range ["
                    + minPrice + ", " + (minPrice + levels - 1) + "]");
        }
        final int priceIndex = (int) (price - minPrice);
        long remaining = quantity;

        if (side == Side.BUY) {
            while (remaining > 0 && bestAsk != NONE && bestAsk <= priceIndex) {
                remaining = fillLevel(askHead, askTail, bestAsk, true, orderId, remaining, sink);
                if (askHead[bestAsk] == NONE) {
                    bestAsk = nextAskAbove(bestAsk);
                }
            }
            if (remaining > 0) {
                rest(bidHead, bidTail, priceIndex, orderId, remaining);
                if (bestBid == NONE || priceIndex > bestBid) {
                    bestBid = priceIndex;
                }
            }
        } else {
            while (remaining > 0 && bestBid != NONE && bestBid >= priceIndex) {
                remaining = fillLevel(bidHead, bidTail, bestBid, false, orderId, remaining, sink);
                if (bidHead[bestBid] == NONE) {
                    bestBid = nextBidBelow(bestBid);
                }
            }
            if (remaining > 0) {
                rest(askHead, askTail, priceIndex, orderId, remaining);
                if (bestAsk == NONE || priceIndex < bestAsk) {
                    bestAsk = priceIndex;
                }
            }
        }
    }

    /** Matches the incoming order against one price level, oldest resting order first. Returns what is left. */
    private long fillLevel(int[] head, int[] tail, int level, boolean incomingIsBuy, long incomingId,
                           long remaining, TradeSink sink) {
        final long levelPrice = minPrice + level;
        int node = head[level];
        while (remaining > 0 && node != NONE) {
            long traded = Math.min(remaining, nodeRemaining[node]);
            if (incomingIsBuy) {
                sink.onTrade(incomingId, nodeOrderId[node], levelPrice, traded);
            } else {
                sink.onTrade(nodeOrderId[node], incomingId, levelPrice, traded);
            }
            remaining -= traded;
            nodeRemaining[node] -= traded;
            if (nodeRemaining[node] == 0) {
                int next = nodeNext[node];
                release(node);
                node = next;
                head[level] = next;
            }
        }
        if (head[level] == NONE) {
            tail[level] = NONE;
        }
        return remaining;
    }

    private void rest(int[] head, int[] tail, int level, long orderId, long remaining) {
        if (freeHead == NONE) {
            throw new IllegalStateException("order pool is full (" + nodeOrderId.length + " resting orders)");
        }
        int node = freeHead;
        freeHead = nodeNext[node];
        restingCount++;
        nodeOrderId[node] = orderId;
        nodeRemaining[node] = remaining;
        nodeNext[node] = NONE;
        if (tail[level] == NONE) {
            head[level] = node;
        } else {
            nodeNext[tail[level]] = node;
        }
        tail[level] = node;
    }

    private void release(int node) {
        nodeNext[node] = freeHead;
        freeHead = node;
        restingCount--;
    }

    private int nextAskAbove(int from) {
        for (int i = from + 1; i < levels; i++) {
            if (askHead[i] != NONE) {
                return i;
            }
        }
        return NONE;
    }

    private int nextBidBelow(int from) {
        for (int i = from - 1; i >= 0; i--) {
            if (bidHead[i] != NONE) {
                return i;
            }
        }
        return NONE;
    }

    /** Highest resting buy price, or 0 if there are no resting buys. */
    public long bestBidPrice() {
        return bestBid == NONE ? 0 : minPrice + bestBid;
    }

    /** Lowest resting sell price, or 0 if there are no resting sells. */
    public long bestAskPrice() {
        return bestAsk == NONE ? 0 : minPrice + bestAsk;
    }

    /** Total unmatched quantity resting on the given side. */
    public long restingQuantity(Side side) {
        int[] head = side == Side.BUY ? bidHead : askHead;
        long total = 0;
        for (int level = 0; level < levels; level++) {
            for (int node = head[level]; node != NONE; node = nodeNext[node]) {
                total += nodeRemaining[node];
            }
        }
        return total;
    }

    /** Number of resting orders on both sides. */
    public int restingOrderCount() {
        return restingCount;
    }
}
