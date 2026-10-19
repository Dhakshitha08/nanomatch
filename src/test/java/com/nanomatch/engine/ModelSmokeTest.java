package com.nanomatch.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nanomatch.model.Order;
import com.nanomatch.model.MatchingEngine;
import com.nanomatch.model.Side;
import com.nanomatch.model.Trade;
import com.nanomatch.model.TradeSink;
import org.junit.jupiter.api.Test;

class ModelSmokeTest {

    @Test
    void sideHasBuyAndSell() {
        assertEquals(2, Side.values().length);
        assertNotEquals(Side.BUY, Side.SELL);
    }

    @Test
    void orderStoresItsFields() {
        Order o = new Order(7, Side.SELL, 10_050, 25);
        assertEquals(7, o.id());
        assertEquals(Side.SELL, o.side());
        assertEquals(10_050, o.price());
        assertEquals(25, o.quantity());
    }

    @Test
    void orderRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new Order(1, Side.BUY, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new Order(1, Side.BUY, -5, 10));
        assertThrows(IllegalArgumentException.class, () -> new Order(1, Side.BUY, 100, 0));
        assertThrows(IllegalArgumentException.class, () -> new Order(1, Side.BUY, 100, -1));
        assertThrows(NullPointerException.class, () -> new Order(1, null, 100, 1));
    }

    @Test
    void tradeRecordsCompareByValue() {
        assertEquals(new Trade(1, 2, 100, 5), new Trade(1, 2, 100, 5));
        assertNotEquals(new Trade(1, 2, 100, 5), new Trade(1, 2, 101, 5));
    }

    @Test
    void noopSinkAcceptsTrades() {
        assertDoesNotThrow(() -> TradeSink.NOOP.onTrade(1, 2, 100, 5));
    }

    /** A tiny engine used only to check the interface's default methods. */
    private static final class StubEngine implements MatchingEngine {
        long lastId;
        Side lastSide;
        long lastPrice;
        long lastQty;
        long submitted;
        long processed;

        @Override
        public long submit(long orderId, Side side, long price, long quantity) {
            lastId = orderId;
            lastSide = side;
            lastPrice = price;
            lastQty = quantity;
            return submitted++;
        }

        @Override
        public long submittedCount() {
            return submitted;
        }

        @Override
        public long processedCount() {
            return processed;
        }

        @Override
        public void close() { }
    }

    @Test
    void submitOrderOverloadPassesTheFieldsThrough() {
        StubEngine engine = new StubEngine();
        long ticket = engine.submit(new Order(9, Side.BUY, 123, 4));
        assertEquals(0, ticket);
        assertEquals(9, engine.lastId);
        assertEquals(Side.BUY, engine.lastSide);
        assertEquals(123, engine.lastPrice);
        assertEquals(4, engine.lastQty);
        assertEquals(1, engine.submit(new Order(10, Side.SELL, 124, 1)));
    }

    @Test
    void awaitIdleReturnsWhenEverythingSubmittedIsProcessed() {
        StubEngine engine = new StubEngine();
        engine.submit(new Order(1, Side.BUY, 100, 1));
        engine.processed = 1;
        assertDoesNotThrow(engine::awaitIdle);
    }
}
