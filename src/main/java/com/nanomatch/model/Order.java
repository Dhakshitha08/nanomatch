package com.nanomatch.model;

import java.util.Objects;

/**
 * A limit order. Prices are integer ticks and quantities are integer units (never floating point).
 *
 * <p>This record is convenient for generators and tests. The engines themselves take the four fields
 * as primitives (see {@link MatchingEngine#submit(long, Side, long, long)}) so the hot path never
 * has to allocate.
 *
 * @param id       unique order id (uniqueness is the caller's responsibility)
 * @param side     BUY or SELL
 * @param price    limit price in ticks, must be greater than 0
 * @param quantity quantity, must be greater than 0
 */
public record Order(long id, Side side, long price, long quantity) {

    public Order {
        Objects.requireNonNull(side, "side");
        if (price <= 0) {
            throw new IllegalArgumentException("price must be > 0 but was " + price);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0 but was " + quantity);
        }
    }
}
