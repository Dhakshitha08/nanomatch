package com.nanomatch.model;

/**
 * One executed trade between a buy order and a sell order.
 *
 * @param buyOrderId  id of the buy order
 * @param sellOrderId id of the sell order
 * @param price       execution price in ticks (always the price of the resting order)
 * @param quantity    executed quantity
 */
public record Trade(long buyOrderId, long sellOrderId, long price, long quantity) {
}
