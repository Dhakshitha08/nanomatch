package com.nanomatch.model;

/** Which side of the book an order is on. */
public enum Side {
    /** A bid: willing to pay up to the order's price. */
    BUY,
    /** An ask: willing to sell down to the order's price. */
    SELL
}
