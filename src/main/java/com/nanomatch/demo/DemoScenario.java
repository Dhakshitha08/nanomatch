package com.nanomatch.demo;

import com.nanomatch.model.Order;
import com.nanomatch.model.Trade;
import java.util.ArrayList;
import java.util.List;

/**
 * A short, scripted sequence of orders that demonstrates one matching rule of the engines.
 *
 * <p>The orders are sent to a real engine; the engine produces the trades. {@link #expectedTrades()} is what the
 * engine contract ({@code MatchingEngine}) says must happen, so the demo can show that the engine's actual
 * output matches the rule. The expected list is a check, never displayed as if it were engine output.
 *
 * @param key            short identifier (used on the command line)
 * @param title          title shown to the user
 * @param rule           the rule being demonstrated, in one sentence
 * @param steps          the orders, in submission order, each with a narration
 * @param expectedTrades trades the engine must produce, in order
 * @param explanation    what to point out once the scenario has run
 */
public record DemoScenario(String key, String title, String rule, List<Step> steps, List<Trade> expectedTrades,
                           String explanation) {

    /**
     * One order of a scenario.
     *
     * @param order     the order sent to the engine
     * @param narration what this order is meant to show
     */
    public record Step(Order order, String narration) {
    }

    /** Result of comparing the engine's actual trades with the expected ones. */
    public record Check(boolean passed, String summary) {
    }

    public List<Order> orders() {
        List<Order> orders = new ArrayList<>(steps.size());
        for (Step step : steps) {
            orders.add(step.order());
        }
        return orders;
    }

    /** Compares the engine's trades (in the order reported) with the expected ones. */
    public Check check(List<Trade> actual) {
        if (actual.equals(expectedTrades)) {
            return new Check(true, "PASS: the engine produced exactly the " + expectedTrades.size()
                    + " expected trade(s)" + (expectedTrades.isEmpty() ? " (none)" : "") + ".");
        }
        int size = Math.min(actual.size(), expectedTrades.size());
        for (int i = 0; i < size; i++) {
            if (!actual.get(i).equals(expectedTrades.get(i))) {
                return new Check(false, "FAIL: trade " + (i + 1) + " was " + describe(actual.get(i)) + " but the rule "
                        + "requires " + describe(expectedTrades.get(i)) + ".");
            }
        }
        return new Check(false, "FAIL: the engine produced " + actual.size() + " trade(s), the rule requires "
                + expectedTrades.size() + ".");
    }

    public static String describe(Trade trade) {
        return "buy #" + trade.buyOrderId() + " / sell #" + trade.sellOrderId() + " " + trade.quantity() + " @ "
                + trade.price();
    }
}
