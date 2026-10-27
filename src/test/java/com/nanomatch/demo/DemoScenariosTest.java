package com.nanomatch.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nanomatch.model.Order;
import com.nanomatch.model.Trade;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Every demo scenario must pass on both real engines, so the demo never shows a wrong expectation. */
class DemoScenariosTest {

    @Test
    void everyScenarioPassesOnBothEngines() {
        for (EngineKind kind : EngineKind.values()) {
            for (DemoScenario scenario : DemoScenarios.all()) {
                List<Trade> actual = new ArrayList<>();
                try (EngineSession session = new EngineSession(kind)) {
                    for (Order order : scenario.orders()) {
                        SubmitResult r = session.submitAndWait(order.id(), order.side(), order.price(),
                                order.quantity());
                        assertTrue(r.accepted(), kind + " / " + scenario.key() + ": " + r.message());
                        for (SessionSnapshot.Execution e : r.fills()) {
                            actual.add(new Trade(e.buyOrderId(), e.sellOrderId(), e.price(), e.quantity()));
                        }
                    }
                    assertTrue(session.snapshot().engineView().consistent(), kind + " / " + scenario.key());
                }
                DemoScenario.Check check = scenario.check(actual);
                assertTrue(check.passed(), kind + " / " + scenario.key() + ": " + check.summary());
            }
        }
    }

    @Test
    void scenarioKeysAreUniqueAndOrderIdsUniqueWithinEachScenario() {
        Set<String> keys = new HashSet<>();
        for (DemoScenario scenario : DemoScenarios.all()) {
            assertTrue(keys.add(scenario.key()), "duplicate key " + scenario.key());
            Set<Long> ids = new HashSet<>();
            for (Order order : scenario.orders()) {
                assertTrue(ids.add(order.id()), scenario.key() + ": duplicate order id " + order.id());
            }
            assertEquals(scenario, DemoScenarios.byKey(scenario.key()));
        }
        assertThrows(IllegalArgumentException.class, () -> DemoScenarios.byKey("nope"));
    }

    @Test
    void checkReportsTheFirstDifference() {
        DemoScenario scenario = DemoScenarios.byKey("price");
        DemoScenario.Check wrong = scenario.check(List.of(new Trade(4, 3, 101, 5), new Trade(4, 2, 100, 5)));
        assertFalse(wrong.passed());
        assertTrue(wrong.summary().contains("trade 1"));
        assertFalse(scenario.check(List.of()).passed());
    }
}
