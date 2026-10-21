package com.nanomatch.sim;

import com.nanomatch.model.Order;
import com.nanomatch.model.Side;

/**
 * Deterministic, seeded generator of random limit orders.
 *
 * <p>The same seed and parameters always give the same orders, in the same order, on every machine and
 * every JDK version. That is what makes the parity check and the benchmark runs reproducible. For that
 * reason the generator uses its own tiny pseudo-random number generator (SplitMix64) instead of
 * {@code java.util.Random}, whose exact output is not guaranteed to stay the same across versions.
 *
 * <p>Orders get sequential ids starting at 1. Each order draws, in this fixed order: side, price, quantity.
 * Prices are spread uniformly around {@code midPrice}, so a healthy share of orders cross the book and
 * the rest rest on it.
 *
 * <p>Not thread-safe: use one generator per thread, with different seeds.
 */
public final class OrderGenerator {

    public static final long DEFAULT_MID_PRICE = 10_000;
    public static final long DEFAULT_PRICE_BAND = 50;
    public static final long DEFAULT_MIN_QUANTITY = 1;
    public static final long DEFAULT_MAX_QUANTITY = 100;
    public static final int DEFAULT_BUY_PERCENT = 50;

    private final long midPrice;
    private final long priceBand;
    private final long minQuantity;
    private final long maxQuantity;
    private final int buyPercent;

    private long state;
    private long nextId = 1;

    /** Generator with default parameters. */
    public OrderGenerator(long seed) {
        this(seed, DEFAULT_MID_PRICE, DEFAULT_PRICE_BAND, DEFAULT_MIN_QUANTITY, DEFAULT_MAX_QUANTITY,
                DEFAULT_BUY_PERCENT);
    }

    /**
     * @param seed        random seed
     * @param midPrice    centre of the price range, in ticks
     * @param priceBand   prices fall in [midPrice - priceBand, midPrice + priceBand]
     * @param minQuantity smallest quantity (at least 1)
     * @param maxQuantity largest quantity (at least minQuantity)
     * @param buyPercent  chance in percent (0 to 100) that an order is a BUY
     */
    public OrderGenerator(long seed, long midPrice, long priceBand, long minQuantity, long maxQuantity,
                          int buyPercent) {
        if (priceBand < 0) {
            throw new IllegalArgumentException("priceBand must be >= 0");
        }
        if (midPrice - priceBand < 1) {
            throw new IllegalArgumentException("midPrice - priceBand must be >= 1 so every price is positive");
        }
        if (minQuantity < 1 || maxQuantity < minQuantity) {
            throw new IllegalArgumentException("need 1 <= minQuantity <= maxQuantity");
        }
        if (buyPercent < 0 || buyPercent > 100) {
            throw new IllegalArgumentException("buyPercent must be between 0 and 100");
        }
        this.state = seed;
        this.midPrice = midPrice;
        this.priceBand = priceBand;
        this.minQuantity = minQuantity;
        this.maxQuantity = maxQuantity;
        this.buyPercent = buyPercent;
    }

    /** Returns the next order in the sequence. */
    public Order next() {
        long id = nextId++;
        Side side = nextBounded(100) < buyPercent ? Side.BUY : Side.SELL;
        long price = midPrice - priceBand + nextBounded(2 * priceBand + 1);
        long quantity = minQuantity + nextBounded(maxQuantity - minQuantity + 1);
        return new Order(id, side, price, quantity);
    }

    /** Returns the next {@code count} orders. Allocates, so call it during setup, not in a timed section. */
    public Order[] generate(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0");
        }
        Order[] orders = new Order[count];
        for (int i = 0; i < count; i++) {
            orders[i] = next();
        }
        return orders;
    }

    /** SplitMix64: a small, well-known generator with a fully specified output. */
    private long nextLong() {
        state += 0x9E3779B97F4A7C15L;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Uniform-enough value in [0, bound). The tiny modulo bias is irrelevant for small bounds. */
    private long nextBounded(long bound) {
        return (nextLong() >>> 1) % bound;
    }
}
