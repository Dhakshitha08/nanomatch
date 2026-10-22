package com.nanomatch.verify;

import com.nanomatch.model.Trade;
import com.nanomatch.model.TradeSink;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test helper that records every trade in the order it was reported.
 *
 * <p>Not thread-safe, because engines never call a sink from two threads at once. Read {@link #trades()} only
 * after {@code engine.awaitIdle()} (or after the producer threads have been joined and the engine is idle), so
 * that the matching thread's writes are visible.
 */
final class RecordingSink implements TradeSink {

    private final List<Trade> trades = new ArrayList<>();

    @Override
    public void onTrade(long buyOrderId, long sellOrderId, long price, long quantity) {
        trades.add(new Trade(buyOrderId, sellOrderId, price, quantity));
    }

    /** Read-only view of the trades recorded so far. */
    List<Trade> trades() {
        return Collections.unmodifiableList(trades);
    }
}
