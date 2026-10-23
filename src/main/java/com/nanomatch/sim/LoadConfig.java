package com.nanomatch.sim;

/**
 * Settings for one simulator run.
 *
 * @param threads        number of client (producer) threads, at least 1
 * @param ordersPerThread orders each thread submits, at least 1
 * @param pattern        how each thread paces its orders
 * @param seed           base seed; thread {@code t} uses {@code seed + t}, so a run is reproducible
 * @param recordTickets  if true, remember which order got which ticket (needed for the parity replay check)
 */
public record LoadConfig(int threads, int ordersPerThread, LoadPattern pattern, long seed, boolean recordTickets) {

    /** Largest number of orders a single thread may submit (keeps order ids unique across threads). */
    public static final int MAX_ORDERS_PER_THREAD = 10_000_000;

    public LoadConfig {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1");
        }
        if (ordersPerThread < 1 || ordersPerThread > MAX_ORDERS_PER_THREAD) {
            throw new IllegalArgumentException("ordersPerThread must be between 1 and " + MAX_ORDERS_PER_THREAD);
        }
        if ((long) threads * ordersPerThread > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("too many orders in total");
        }
        if (pattern == null) {
            throw new IllegalArgumentException("pattern must not be null");
        }
    }

    public int totalOrders() {
        return threads * ordersPerThread;
    }
}
