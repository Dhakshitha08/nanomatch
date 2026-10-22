package com.nanomatch.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nanomatch.model.MatchingEngine;
import com.nanomatch.model.Order;
import com.nanomatch.model.Side;
import com.nanomatch.model.Trade;
import com.nanomatch.model.TradeSink;
import com.nanomatch.sim.OrderGenerator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The behaviour every {@link MatchingEngine} must have: price-time priority and the rules written in the
 * {@code MatchingEngine} Javadoc. It only uses the public interface, so the exact same tests run against
 * every engine. To test another engine, add a small subclass that implements {@link #createEngine}
 * (see {@code BaselineEngineContractTest}).
 */
abstract class EngineContractTest {

    /** Creates a fresh engine that reports to the given sink. */
    protected abstract MatchingEngine createEngine(TradeSink sink);

    private RecordingSink sink;
    private MatchingEngine engine;

    @BeforeEach
    void setUpEngine() {
        sink = new RecordingSink();
        engine = createEngine(sink);
    }

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    private void buy(long id, long price, long quantity) {
        engine.submit(id, Side.BUY, price, quantity);
    }

    private void sell(long id, long price, long quantity) {
        engine.submit(id, Side.SELL, price, quantity);
    }

    private List<Trade> trades() {
        engine.awaitIdle();
        return sink.trades();
    }

    private static Trade trade(long buyId, long sellId, long price, long quantity) {
        return new Trade(buyId, sellId, price, quantity);
    }

    @Test
    void nonCrossingOrdersProduceNoTrades() {
        buy(1, 99, 10);
        sell(2, 100, 10);
        assertEquals(List.of(), trades());
    }

    @Test
    void exactMatchProducesOneTrade() {
        sell(1, 100, 10);
        buy(2, 100, 10);
        assertEquals(List.of(trade(2, 1, 100, 10)), trades());
    }

    @Test
    void incomingBuyTradesAtTheRestingSellPrice() {
        sell(1, 100, 10);
        buy(2, 105, 10);
        assertEquals(List.of(trade(2, 1, 100, 10)), trades());
    }

    @Test
    void incomingSellTradesAtTheRestingBuyPrice() {
        buy(1, 105, 10);
        sell(2, 100, 10);
        assertEquals(List.of(trade(1, 2, 105, 10)), trades());
    }

    @Test
    void lowestAskIsMatchedFirstForABuy() {
        sell(1, 102, 5);
        sell(2, 100, 5);
        sell(3, 101, 5);
        buy(4, 102, 10);
        assertEquals(List.of(trade(4, 2, 100, 5), trade(4, 3, 101, 5)), trades());
    }

    @Test
    void highestBidIsMatchedFirstForASell() {
        buy(1, 98, 5);
        buy(2, 100, 5);
        buy(3, 99, 5);
        sell(4, 98, 10);
        assertEquals(List.of(trade(2, 4, 100, 5), trade(3, 4, 99, 5)), trades());
    }

    @Test
    void earlierOrderAtTheSamePriceIsMatchedFirst() {
        sell(1, 100, 5);
        sell(2, 100, 5);
        buy(3, 100, 7);
        assertEquals(List.of(trade(3, 1, 100, 5), trade(3, 2, 100, 2)), trades());
    }

    @Test
    void partiallyFilledRestingOrderKeepsItsPlaceInTheQueue() {
        sell(1, 100, 10);
        sell(2, 100, 5);
        buy(3, 100, 4);
        buy(4, 100, 7);
        assertEquals(List.of(trade(3, 1, 100, 4), trade(4, 1, 100, 6), trade(4, 2, 100, 1)), trades());
    }

    @Test
    void unmatchedPartOfAnIncomingOrderRestsAndCanBeMatchedLater() {
        sell(1, 100, 5);
        buy(2, 100, 8);
        sell(3, 100, 3);
        assertEquals(List.of(trade(2, 1, 100, 5), trade(2, 3, 100, 3)), trades());
    }

    @Test
    void aggressiveOrderSweepsSeveralPriceLevels() {
        sell(1, 100, 2);
        sell(2, 101, 2);
        sell(3, 102, 2);
        buy(4, 102, 5);
        buy(5, 102, 1);
        assertEquals(List.of(trade(4, 1, 100, 2), trade(4, 2, 101, 2), trade(4, 3, 102, 1), trade(5, 3, 102, 1)),
                trades());
    }

    @Test
    void restingBuyIsFilledAcrossTwoLaterSellsAtItsOwnPrice() {
        buy(1, 100, 10);
        sell(2, 100, 4);
        sell(3, 99, 10);
        buy(4, 99, 4);
        assertEquals(List.of(trade(1, 2, 100, 4), trade(1, 3, 100, 6), trade(4, 3, 99, 4)), trades());
    }

    @Test
    void ticketsFollowSubmissionOrderAndCountsTrack() {
        assertEquals(0, engine.submit(1, Side.BUY, 100, 1));
        assertEquals(1, engine.submit(2, Side.SELL, 200, 1));
        assertEquals(2, engine.submit(3, Side.BUY, 100, 1));
        assertEquals(3, engine.submittedCount());
        engine.awaitIdle();
        assertEquals(3, engine.processedCount());
    }

    @Test
    void invalidOrdersAreRejectedWithoutUsingATicket() {
        assertThrows(IllegalArgumentException.class, () -> engine.submit(1, Side.BUY, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> engine.submit(1, Side.SELL, 100, 0));
        assertEquals(0, engine.submittedCount());
        assertEquals(0, engine.submit(1, Side.BUY, 100, 10));
    }

    /**
     * A longer single-threaded run with seeded random orders. Checks the rules that must hold for every
     * trade: sides are right, nobody pays more or receives less than their limit, nobody trades more than
     * they asked for.
     */
    @Test
    void randomFlowRespectsEveryOrdersLimitAndQuantity() {
        Order[] orders = new OrderGenerator(2024).generate(5_000);
        for (Order order : orders) {
            engine.submit(order);
        }
        long[] filled = new long[orders.length + 1]; // indexed by order id (ids run from 1)
        List<Trade> trades = trades();
        assertTrue(trades.size() > 100, "expected plenty of trades but got " + trades.size());
        for (Trade t : trades) {
            Order buyOrder = orders[(int) t.buyOrderId() - 1];
            Order sellOrder = orders[(int) t.sellOrderId() - 1];
            assertEquals(Side.BUY, buyOrder.side());
            assertEquals(Side.SELL, sellOrder.side());
            assertTrue(t.quantity() > 0, "trade quantity must be positive");
            assertTrue(t.price() <= buyOrder.price(), "buyer paid above their limit: " + t);
            assertTrue(t.price() >= sellOrder.price(), "seller received below their limit: " + t);
            filled[(int) t.buyOrderId()] += t.quantity();
            filled[(int) t.sellOrderId()] += t.quantity();
        }
        for (Order order : orders) {
            assertTrue(filled[(int) order.id()] <= order.quantity(), "order overfilled: " + order);
        }
    }
}
