package com.nanomatch.demo;

import com.nanomatch.model.Order;
import com.nanomatch.model.Side;
import com.nanomatch.model.Trade;
import java.util.List;

/**
 * The scripted demonstrations. Each uses only behaviour the engines implement: limit orders, price priority,
 * FIFO time priority, partial fills, execution at the resting order's price. There is no cancel or modify in
 * the engines, so there is no scenario for them.
 */
public final class DemoScenarios {

    private DemoScenarios() {
    }

    public static List<DemoScenario> all() {
        return List.of(simpleMatch(), noMatch(), partialFill(), pricePriority(), timePriority(), restingPrice(),
                sweep());
    }

    public static DemoScenario byKey(String key) {
        for (DemoScenario scenario : all()) {
            if (scenario.key().equalsIgnoreCase(key)) {
                return scenario;
            }
        }
        throw new IllegalArgumentException("unknown scenario '" + key + "'");
    }

    private static DemoScenario.Step step(long id, Side side, long price, long quantity, String narration) {
        return new DemoScenario.Step(new Order(id, side, price, quantity), narration);
    }

    private static Trade trade(long buyId, long sellId, long price, long quantity) {
        return new Trade(buyId, sellId, price, quantity);
    }

    static DemoScenario simpleMatch() {
        return new DemoScenario("match", "Simple match",
                "A BUY whose price is at least the best SELL price trades with it.",
                List.of(
                        step(1, Side.SELL, 100, 10, "SELL #1 offers 10 at 100. The book is empty, so it rests."),
                        step(2, Side.BUY, 100, 10, "BUY #2 bids 100 for 10. 100 >= 100, so it crosses and trades.")),
                List.of(trade(2, 1, 100, 10)),
                "One trade of 10 at 100. Both orders are fully filled, so the book is empty again.");
    }

    static DemoScenario noMatch() {
        return new DemoScenario("nomatch", "No match (orders do not cross)",
                "A BUY below the best SELL price does not trade; both orders rest and a spread appears.",
                List.of(
                        step(1, Side.SELL, 101, 10, "SELL #1 offers 10 at 101 and rests."),
                        step(2, Side.BUY, 99, 10, "BUY #2 bids only 99. 99 < 101, so nothing trades.")),
                List.of(),
                "No trade. Best bid 99, best ask 101, spread 2 ticks. Both orders wait on the book.");
    }

    static DemoScenario partialFill() {
        return new DemoScenario("partial", "Partial fills",
                "Each trade is for the smaller remaining quantity; what is left keeps resting.",
                List.of(
                        step(1, Side.SELL, 100, 10, "SELL #1 offers 10 at 100 and rests."),
                        step(2, Side.BUY, 100, 4, "BUY #2 wants 4: it takes 4 of the 10. SELL #1 keeps 6, "
                                + "still first in line."),
                        step(3, Side.BUY, 100, 8, "BUY #3 wants 8: only 6 are left, so it buys 6 and its last 2 "
                                + "rest as a new bid.")),
                List.of(trade(2, 1, 100, 4), trade(3, 1, 100, 6)),
                "SELL #1 was filled in two parts (4 + 6). BUY #3 was partly filled: 2 now rest as the best bid.");
    }

    static DemoScenario pricePriority() {
        return new DemoScenario("price", "Price priority",
                "An incoming order is matched against the best price first, then the next best.",
                List.of(
                        step(1, Side.SELL, 102, 5, "SELL #1: 5 at 102 (the worst price) arrives first."),
                        step(2, Side.SELL, 100, 5, "SELL #2: 5 at 100 (the best price) arrives second."),
                        step(3, Side.SELL, 101, 5, "SELL #3: 5 at 101 arrives third."),
                        step(4, Side.BUY, 102, 10, "BUY #4 for 10 up to 102: it takes 100 first, then 101.")),
                List.of(trade(4, 2, 100, 5), trade(4, 3, 101, 5)),
                "The buyer got the cheapest sellers first (100, then 101), even though SELL #1 arrived earliest. "
                        + "SELL #1 at 102 is untouched.");
    }

    static DemoScenario timePriority() {
        return new DemoScenario("time", "Time priority (FIFO at the same price)",
                "At the same price, the order that arrived first is filled first.",
                List.of(
                        step(1, Side.SELL, 100, 5, "SELL #1: 5 at 100, first in the queue."),
                        step(2, Side.SELL, 100, 5, "SELL #2: 5 at 100, second in the queue."),
                        step(3, Side.SELL, 100, 5, "SELL #3: 5 at 100, third in the queue."),
                        step(4, Side.BUY, 100, 7, "BUY #4 for 7 at 100: fills #1 completely, then 2 from #2.")),
                List.of(trade(4, 1, 100, 5), trade(4, 2, 100, 2)),
                "Same price, so arrival order decides: #1 (5) then #2 (2). #2 keeps 3 and stays ahead of #3.");
    }

    static DemoScenario restingPrice() {
        return new DemoScenario("execprice", "Execution at the resting order's price",
                "The trade price is the price of the order already on the book, not the incoming one.",
                List.of(
                        step(1, Side.BUY, 105, 10, "BUY #1 bids 105 for 10 and rests."),
                        step(2, Side.SELL, 100, 10, "SELL #2 would accept 100, but the resting bid is 105.")),
                List.of(trade(1, 2, 105, 10)),
                "The trade happens at 105, the resting buyer's price. The incoming seller gets the better price.");
    }

    static DemoScenario sweep() {
        return new DemoScenario("sweep", "Sweep several price levels",
                "A large order walks through several levels until its limit price stops it.",
                List.of(
                        step(1, Side.SELL, 100, 3, "SELL #1: 3 at 100."),
                        step(2, Side.SELL, 101, 3, "SELL #2: 3 at 101."),
                        step(3, Side.SELL, 102, 3, "SELL #3: 3 at 102."),
                        step(4, Side.SELL, 110, 3, "SELL #4: 3 at 110 (above the buyer's limit)."),
                        step(5, Side.BUY, 105, 8, "BUY #5 for 8 up to 105: takes 3 + 3 + 2 across three levels.")),
                List.of(trade(5, 1, 100, 3), trade(5, 2, 101, 3), trade(5, 3, 102, 2)),
                "Three trades at three prices. SELL #3 keeps 1 at 102. SELL #4 at 110 is above the limit of 105, "
                        + "so it is never touched.");
    }
}
