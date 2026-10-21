package com.nanomatch.verify;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nanomatch.model.Order;
import com.nanomatch.model.Side;
import com.nanomatch.sim.OrderGenerator;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class OrderGeneratorTest {

    @Test
    void sameSeedGivesIdenticalSequence() {
        Order[] first = new OrderGenerator(42).generate(1_000);
        Order[] second = new OrderGenerator(42).generate(1_000);
        assertArrayEquals(first, second);
    }

    @Test
    void generateMatchesRepeatedNext() {
        OrderGenerator a = new OrderGenerator(5);
        OrderGenerator b = new OrderGenerator(5);
        Order[] batch = a.generate(200);
        for (int i = 0; i < 200; i++) {
            assertEquals(batch[i], b.next());
        }
    }

    @Test
    void differentSeedsGiveDifferentSequences() {
        Order[] first = new OrderGenerator(1).generate(200);
        Order[] second = new OrderGenerator(2).generate(200);
        assertFalse(Arrays.equals(first, second));
    }

    /**
     * Pins the exact output for seed 42. If this fails, the sequence changed, which would silently
     * change every saved benchmark workload. Only update these numbers on purpose.
     */
    @Test
    void goldenValuesForSeed42() {
        OrderGenerator g = new OrderGenerator(42);
        assertEquals(new Order(1, Side.BUY, 9_981, 30), g.next());
        assertEquals(new Order(2, Side.SELL, 9_971, 32), g.next());
        assertEquals(new Order(3, Side.SELL, 10_000, 3), g.next());
    }

    @Test
    void idsAreSequentialStartingAtOne() {
        Order[] orders = new OrderGenerator(9).generate(500);
        for (int i = 0; i < orders.length; i++) {
            assertEquals(i + 1L, orders[i].id());
        }
    }

    @Test
    void pricesAndQuantitiesStayInBounds() {
        Order[] orders = new OrderGenerator(3, 1_000, 20, 5, 9, 50).generate(5_000);
        for (Order o : orders) {
            assertTrue(o.price() >= 980 && o.price() <= 1_020, "price out of range: " + o.price());
            assertTrue(o.quantity() >= 5 && o.quantity() <= 9, "quantity out of range: " + o.quantity());
        }
    }

    @Test
    void buyShareFollowsTheRequestedPercentage() {
        long buys = Arrays.stream(new OrderGenerator(7).generate(10_000))
                .filter(o -> o.side() == Side.BUY)
                .count();
        assertTrue(buys > 4_500 && buys < 5_500, "buys = " + buys);

        assertTrue(Arrays.stream(new OrderGenerator(7, 1_000, 10, 1, 10, 100).generate(1_000))
                .allMatch(o -> o.side() == Side.BUY));
        assertTrue(Arrays.stream(new OrderGenerator(7, 1_000, 10, 1, 10, 0).generate(1_000))
                .allMatch(o -> o.side() == Side.SELL));
    }

    @Test
    void zeroBandGivesExactlyTheMidPrice() {
        for (Order o : new OrderGenerator(11, 500, 0, 1, 1, 50).generate(100)) {
            assertEquals(500, o.price());
            assertEquals(1, o.quantity());
        }
    }

    @Test
    void rejectsInvalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> new OrderGenerator(1, 100, -1, 1, 10, 50));
        assertThrows(IllegalArgumentException.class, () -> new OrderGenerator(1, 10, 10, 1, 10, 50));
        assertThrows(IllegalArgumentException.class, () -> new OrderGenerator(1, 100, 5, 0, 10, 50));
        assertThrows(IllegalArgumentException.class, () -> new OrderGenerator(1, 100, 5, 10, 5, 50));
        assertThrows(IllegalArgumentException.class, () -> new OrderGenerator(1, 100, 5, 1, 10, 101));
        assertThrows(IllegalArgumentException.class, () -> new OrderGenerator(1).generate(-1));
    }
}
