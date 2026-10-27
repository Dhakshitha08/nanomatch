package com.nanomatch.demo;

import com.nanomatch.model.Side;
import java.util.List;

/**
 * What happened to one order submitted through an {@link EngineSession}.
 *
 * @param accepted       false if the order was rejected (by the session's checks or by the engine)
 * @param message        explanation for the user
 * @param orderId        order id
 * @param side           BUY or SELL
 * @param price          limit price
 * @param quantity       quantity
 * @param ticket         ticket the engine returned (-1 if rejected)
 * @param fills          trades this order caused, in the order the engine reported them
 * @param restedQuantity quantity left resting on the book after matching
 * @param roundTripNanos time from the submit call until the engine had fully processed the order
 * @param snapshot       session state after the order
 */
public record SubmitResult(boolean accepted, String message, long orderId, Side side, long price, long quantity,
                           long ticket, List<SessionSnapshot.Execution> fills, long restedQuantity,
                           long roundTripNanos, SessionSnapshot snapshot) {

    public long filledQuantity() {
        long total = 0;
        for (SessionSnapshot.Execution fill : fills) {
            total += fill.quantity();
        }
        return total;
    }
}
