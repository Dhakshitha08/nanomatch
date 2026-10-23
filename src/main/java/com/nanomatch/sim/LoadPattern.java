package com.nanomatch.sim;

/**
 * How each client thread paces its orders.
 *
 * <ul>
 *   <li>{@link Kind#MAX_SPEED}: no pacing, submit as fast as possible. Used to find an engine's throughput ceiling.</li>
 *   <li>{@link Kind#STEADY}: one order every {@code 1 / rate} seconds, per thread (an even, constant load).</li>
 *   <li>{@link Kind#BURST}: {@code burstSize} orders back to back, then a pause, repeated. Sudden spikes like
 *       this are where lock-based and lock-free engines usually differ most in tail latency.</li>
 * </ul>
 */
public final class LoadPattern {

    public enum Kind { MAX_SPEED, STEADY, BURST }

    private final Kind kind;
    private final long ordersPerSecondPerThread;
    private final int burstSize;
    private final long burstGapNanos;

    private LoadPattern(Kind kind, long ordersPerSecondPerThread, int burstSize, long burstGapNanos) {
        this.kind = kind;
        this.ordersPerSecondPerThread = ordersPerSecondPerThread;
        this.burstSize = burstSize;
        this.burstGapNanos = burstGapNanos;
    }

    /** No pacing. */
    public static LoadPattern maxSpeed() {
        return new LoadPattern(Kind.MAX_SPEED, 0, 0, 0);
    }

    /** @param ordersPerSecondPerThread between 1 and 1,000,000,000 */
    public static LoadPattern steady(long ordersPerSecondPerThread) {
        if (ordersPerSecondPerThread < 1 || ordersPerSecondPerThread > 1_000_000_000L) {
            throw new IllegalArgumentException("ordersPerSecondPerThread must be between 1 and 1e9");
        }
        return new LoadPattern(Kind.STEADY, ordersPerSecondPerThread, 0, 0);
    }

    /**
     * @param burstSize      orders submitted back to back in each burst (at least 1)
     * @param burstGapMicros pause between bursts, in microseconds (0 or more)
     */
    public static LoadPattern burst(int burstSize, long burstGapMicros) {
        if (burstSize < 1) {
            throw new IllegalArgumentException("burstSize must be >= 1");
        }
        if (burstGapMicros < 0) {
            throw new IllegalArgumentException("burstGapMicros must be >= 0");
        }
        return new LoadPattern(Kind.BURST, 0, burstSize, burstGapMicros * 1_000L);
    }

    public Kind kind() {
        return kind;
    }

    public long ordersPerSecondPerThread() {
        return ordersPerSecondPerThread;
    }

    public int burstSize() {
        return burstSize;
    }

    public long burstGapNanos() {
        return burstGapNanos;
    }

    @Override
    public String toString() {
        switch (kind) {
            case STEADY:
                return "STEADY(" + ordersPerSecondPerThread + " orders/s per thread)";
            case BURST:
                return "BURST(" + burstSize + " orders, gap " + burstGapNanos / 1_000 + " us)";
            default:
                return "MAX_SPEED";
        }
    }
}
