package com.nanomatch.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nanomatch.model.Order;
import com.nanomatch.model.Side;
import com.nanomatch.model.Trade;
import com.nanomatch.sim.OrderGenerator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The demo session must show exactly what the real engines do, for both engines. */
class EngineSessionTest {

    @Test
    void restingOrderAppearsInTheBookAndMatchesTheEnginesReport() {
        for (EngineKind kind : EngineKind.values()) {
            try (EngineSession session = new EngineSession(kind)) {
                SubmitResult result = session.submitAndWait(1, Side.SELL, 101, 7);
                assertTrue(result.accepted(), kind + ": " + result.message());
                assertEquals(0, result.ticket());
                assertTrue(result.fills().isEmpty());
                assertEquals(7, result.restedQuantity());

                SessionSnapshot s = session.snapshot();
                assertEquals(1, s.submitted());
                assertEquals(1, s.processed());
                assertEquals(101, s.bestAsk());
                assertEquals(0, s.bestBid());
                assertEquals(1, s.asks().size());
                assertEquals(7, s.asks().get(0).totalQuantity());
                assertTrue(s.engineView().available(), kind.toString());
                assertTrue(s.engineView().consistent(), kind.toString());
                assertEquals(101, s.engineView().bestAsk());
                assertEquals(7, s.engineView().askQuantity());
            }
        }
    }

    @Test
    void crossingOrderProducesTheEnginesTradeAndPartialRemainder() {
        for (EngineKind kind : EngineKind.values()) {
            try (EngineSession session = new EngineSession(kind)) {
                session.submitAndWait(1, Side.SELL, 100, 4);
                SubmitResult result = session.submitAndWait(2, Side.BUY, 102, 10);
                assertEquals(1, result.fills().size(), kind.toString());
                SessionSnapshot.Execution fill = result.fills().get(0);
                assertEquals(2, fill.buyOrderId());
                assertEquals(1, fill.sellOrderId());
                assertEquals(100, fill.price(), "trade at the resting order's price");
                assertEquals(4, fill.quantity());
                assertEquals(2, fill.aggressorId());
                assertEquals(1, fill.restingOrderId());
                assertEquals(6, result.restedQuantity());

                SessionSnapshot s = session.snapshot();
                assertEquals(102, s.bestBid());
                assertEquals(0, s.bestAsk());
                assertEquals(6, s.restingQuantity(Side.BUY));
                assertEquals(1, s.trades().size());
                assertEquals(4, s.tradedVolume());
                assertTrue(s.resting().get(0).partiallyFilled());
                assertTrue(s.engineView().consistent(), kind.toString());
            }
        }
    }

    @Test
    void randomFlowGivesIdenticalTradesOnBothEnginesAndAConsistentBookAfterEveryOrder() {
        Order[] orders = new OrderGenerator(7, 100, 6, 1, 20, 50).generate(400);
        List<List<Trade>> perEngine = new ArrayList<>();
        for (EngineKind kind : EngineKind.values()) {
            try (EngineSession session = new EngineSession(kind)) {
                for (Order order : orders) {
                    SubmitResult r = session.submitAndWait(order.id(), order.side(), order.price(), order.quantity());
                    assertTrue(r.accepted());
                    assertTrue(r.snapshot().engineView().consistent(),
                            kind + ": rebuilt book differs from the engine after order " + order.id());
                    assertFalse(r.snapshot().bestBid() > 0 && r.snapshot().bestAsk() > 0
                            && r.snapshot().bestBid() >= r.snapshot().bestAsk(), "book must never be crossed");
                }
                List<Trade> trades = new ArrayList<>();
                for (SessionSnapshot.Execution e : session.snapshot().trades()) {
                    trades.add(new Trade(e.buyOrderId(), e.sellOrderId(), e.price(), e.quantity()));
                }
                perEngine.add(trades);
            }
        }
        assertFalse(perEngine.get(0).isEmpty());
        assertEquals(perEngine.get(0), perEngine.get(1), "both engines must produce the same trades");
    }

    @Test
    void invalidAndDuplicateOrdersAreRejectedBeforeReachingTheEngine() {
        try (EngineSession session = new EngineSession(EngineKind.LOCK_FREE)) {
            assertTrue(session.submitAndWait(5, Side.BUY, 100, 1).accepted());
            SubmitResult duplicate = session.submitAndWait(5, Side.SELL, 100, 1);
            assertFalse(duplicate.accepted());
            assertTrue(duplicate.message().contains("already used"));
            assertFalse(session.submitAndWait(6, Side.BUY, 0, 1).accepted());
            assertFalse(session.submitAndWait(7, Side.BUY, 100, 0).accepted());
            assertFalse(session.submitAndWait(0, Side.BUY, 100, 1).accepted());
            SubmitResult outOfRange = session.submitAndWait(8, Side.BUY, 70_000, 1);
            assertFalse(outOfRange.accepted());
            assertTrue(outOfRange.message().contains("65535"));
            assertEquals(1, session.snapshot().submitted(), "rejected orders never reach the engine");
            assertEquals(1, session.snapshot().nextOrderId() - 5);
        }
    }

    @Test
    void resetSwitchesEngineAndClearsTheBook() {
        try (EngineSession session = new EngineSession(EngineKind.LOCK_BASED)) {
            session.submitAndWait(1, Side.BUY, 100, 3);
            SessionSnapshot after = session.reset(EngineKind.LOCK_FREE).join();
            assertEquals(EngineKind.LOCK_FREE, after.engine());
            assertEquals(0, after.submitted());
            assertTrue(after.bids().isEmpty());
            assertTrue(after.trades().isEmpty());
            assertTrue(session.submitAndWait(1, Side.BUY, 100, 3).accepted(), "ids may be reused after a reset");
        }
    }

    @Test
    void closeStopsTheSessionAndIsIdempotent() {
        EngineSession session = new EngineSession(EngineKind.LOCK_FREE);
        session.submitAndWait(1, Side.SELL, 100, 1);
        session.close();
        session.close();
        assertEquals(SessionSnapshot.Status.CLOSED, session.snapshot().status());
        assertTrue(session.submit(2, Side.BUY, 100, 1).isCompletedExceptionally());
    }

    @Test
    void listenersReceiveEverySnapshot() {
        List<Long> versions = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (EngineSession session = new EngineSession(EngineKind.LOCK_BASED)) {
            session.addListener(s -> versions.add(s.version()));
            session.submitAndWait(1, Side.SELL, 100, 1);
            session.submitAndWait(2, Side.BUY, 100, 1);
        }
        assertTrue(versions.size() >= 2);
        for (int i = 1; i < versions.size(); i++) {
            assertTrue(versions.get(i) > versions.get(i - 1));
        }
    }
}
