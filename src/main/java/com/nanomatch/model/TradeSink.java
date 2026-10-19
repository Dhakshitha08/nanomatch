package com.nanomatch.model;

/**
 * Receives trades as the engine produces them. Primitive arguments are used on purpose so that
 * reporting a trade never allocates.
 *
 * <p><b>Threading contract:</b> an engine calls {@code onTrade} one trade at a time and never from two
 * threads at once, so a sink does not need to be thread-safe. The baseline engine calls it while holding
 * its lock; the lock-free engine calls it from its single matching thread. The order of calls is the
 * order of trades and is what the parity check compares.
 */
@FunctionalInterface
public interface TradeSink {

    void onTrade(long buyOrderId, long sellOrderId, long price, long quantity);

    /** A sink that ignores every trade (useful in benchmarks). */
    TradeSink NOOP = (buyOrderId, sellOrderId, price, quantity) -> { };
}
