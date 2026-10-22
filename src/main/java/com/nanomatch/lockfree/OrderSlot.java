package com.nanomatch.lockfree;

import com.nanomatch.model.Side;

/**
 * One reusable entry of the ring buffer. The ring buffer creates all its slots once, up front, and then only
 * overwrites their fields, so putting an order into the ring never allocates.
 *
 * <p>The fields are plain (non-volatile) on purpose: the ring buffer's publish/consume protocol
 * ({@link OrderRingBuffer#publish} and {@link OrderRingBuffer#isPublished}) provides the memory ordering.
 */
public final class OrderSlot {
    public long orderId;
    public Side side;
    public long price;
    public long quantity;
}
