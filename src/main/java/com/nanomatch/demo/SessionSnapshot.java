package com.nanomatch.demo;

import com.nanomatch.model.Side;
import java.util.List;

/**
 * An immutable picture of an {@link EngineSession} after its last processed order: counters, the trades the
 * engine reported, the resting orders and the engine's own top-of-book diagnostics.
 *
 * <p>Snapshots are built on the session's worker thread while the engine is idle and are then safe to read
 * from any thread, including the Swing event thread.
 *
 * @param version            increases with every new snapshot
 * @param engine             which engine produced the data
 * @param status             RUNNING, FAILED or CLOSED
 * @param statusDetail       human-readable detail (for example the engine's failure message)
 * @param submitted          {@code engine.submittedCount()}
 * @param processed          {@code engine.processedCount()}
 * @param trades             every trade the engine reported, oldest first
 * @param tradedVolume       sum of the trade quantities
 * @param bids               resting buy levels, best (highest) price first
 * @param asks               resting sell levels, best (lowest) price first
 * @param resting            every resting order, in arrival (ticket) order
 * @param engineView         what the engine itself reports about its book, and whether it agrees
 * @param lastRoundTripNanos time the GUI waited for its last order: submit call until processed
 * @param nextOrderId        a suggested unused order id
 * @param events             recent session events, newest last
 */
public record SessionSnapshot(long version, EngineKind engine, Status status, String statusDetail,
                              long submitted, long processed, List<Execution> trades, long tradedVolume,
                              List<Level> bids, List<Level> asks, List<RestingOrder> resting,
                              EngineView engineView, long lastRoundTripNanos, long nextOrderId,
                              List<Event> events) {

    public enum Status { RUNNING, FAILED, CLOSED }

    /**
     * One trade as reported by the engine through its {@code TradeSink}.
     *
     * @param sequence      1-based position in the session's trade list
     * @param timeMillis    wall-clock time the GUI received the trade
     * @param buyOrderId    id of the buy order
     * @param sellOrderId   id of the sell order
     * @param price         execution price (the resting order's price)
     * @param quantity      executed quantity
     * @param aggressorId   id of the incoming order that caused the trade
     * @param aggressorSide side of the incoming order
     */
    public record Execution(long sequence, long timeMillis, long buyOrderId, long sellOrderId, long price,
                            long quantity, long aggressorId, Side aggressorSide) {

        /** Id of the order that was resting on the book (the one whose price was used). */
        public long restingOrderId() {
            return aggressorSide == Side.BUY ? sellOrderId : buyOrderId;
        }
    }

    /**
     * An order still resting on the book.
     *
     * @param ticket            position in the engine's processing sequence (time priority)
     * @param orderId           order id
     * @param side              BUY or SELL
     * @param price             limit price
     * @param originalQuantity  quantity when submitted
     * @param remainingQuantity quantity not yet traded
     */
    public record RestingOrder(long ticket, long orderId, Side side, long price, long originalQuantity,
                               long remainingQuantity) {

        public boolean partiallyFilled() {
            return remainingQuantity < originalQuantity;
        }
    }

    /**
     * One price level of the book.
     *
     * @param price         level price
     * @param side          BUY or SELL
     * @param totalQuantity sum of the remaining quantities at this price
     * @param queue         resting orders at this price, first in line first (FIFO)
     */
    public record Level(long price, Side side, long totalQuantity, List<RestingOrder> queue) {
    }

    /**
     * The engine's own read-only report of its book, compared with the book the session rebuilt from the
     * engine's trades.
     *
     * @param available  false if the engine offers no such report
     * @param bestBid    engine's best bid (0 = none)
     * @param bestAsk    engine's best ask (0 = none)
     * @param bidQuantity engine's total resting buy quantity
     * @param askQuantity engine's total resting sell quantity
     * @param consistent true if all four values match the rebuilt book
     */
    public record EngineView(boolean available, long bestBid, long bestAsk, long bidQuantity, long askQuantity,
                             boolean consistent) {
    }

    /**
     * Something that happened in the session.
     *
     * @param timeMillis wall-clock time
     * @param kind       INFO, ORDER, TRADE or ERROR
     * @param text       message
     */
    public record Event(long timeMillis, String kind, String text) {
    }

    public long bestBid() {
        return bids.isEmpty() ? 0 : bids.get(0).price();
    }

    public long bestAsk() {
        return asks.isEmpty() ? 0 : asks.get(0).price();
    }

    /** Best ask minus best bid, or -1 if either side is empty. */
    public long spread() {
        return bids.isEmpty() || asks.isEmpty() ? -1 : bestAsk() - bestBid();
    }

    public long restingQuantity(Side side) {
        long total = 0;
        for (Level level : side == Side.BUY ? bids : asks) {
            total += level.totalQuantity();
        }
        return total;
    }

    public int restingOrderCount(Side side) {
        int count = 0;
        for (RestingOrder order : resting) {
            if (order.side() == side) {
                count++;
            }
        }
        return count;
    }

    /** Price of the most recent trade, or 0 if there was none. */
    public long lastTradePrice() {
        return trades.isEmpty() ? 0 : trades.get(trades.size() - 1).price();
    }
}
