package com.nanomatch.lockfree;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A pre-allocated, Disruptor-style ring buffer: <b>many producers, one consumer</b>, no locks.
 *
 * <h2>How it works</h2>
 * Every order gets a <i>sequence number</i> (0, 1, 2, ...). Sequence {@code s} lives in slot
 * {@code s & mask}; the slots are reused round after round.
 * <ol>
 *   <li><b>Claim.</b> A producer reserves the next sequence with a compare-and-set (CAS) on {@code claimed}.
 *       No two producers can get the same sequence, and a producer never blocks another one: a producer
 *       that loses a CAS just tries again. A sequence can only be claimed while its slot is free, that is,
 *       while {@code sequence < consumed + capacity}. If the ring is full, the producer waits.</li>
 *   <li><b>Fill.</b> The producer writes the order into its slot with plain writes.</li>
 *   <li><b>Publish.</b> The producer stores the slot's <i>round number</i> ({@code sequence / capacity}) into
 *       {@code available[slot]} with release semantics. Because producers finish in any order, the consumer
 *       cannot just look at {@code claimed}: a claimed slot may still be half-written. It looks at
 *       {@code available[slot]} instead, which becomes equal to the expected round only when the slot is
 *       completely written.</li>
 *   <li><b>Consume.</b> The single consumer waits until sequence {@code n} is published (acquire read),
 *       processes it, then advances {@code consumed} to {@code n + 1} (release store), which frees the slot
 *       for the producers.</li>
 * </ol>
 *
 * <h2>Why the memory ordering is correct</h2>
 * <ul>
 *   <li>Producer: writes slot fields, then <i>release</i>-stores the round. Consumer: <i>acquire</i>-loads the
 *       round, then reads slot fields. So the consumer always sees the complete order.</li>
 *   <li>Consumer: reads slot fields, then <i>release</i>-stores {@code consumed}. Producer: reads
 *       {@code consumed} (volatile), then overwrites the slot. So a producer never overwrites a slot the
 *       consumer is still reading.</li>
 * </ul>
 *
 * <p>Wait loops back off (spin, then yield) via {@link #backoff(int)}. Cache-line padding of the hot counters
 * is added on Day 5.
 */
public final class OrderRingBuffer {

    private static final VarHandle AVAILABLE = MethodHandles.arrayElementVarHandle(int[].class);

    private final int capacity;
    private final int mask;
    private final int indexShift;
    private final OrderSlot[] slots;
    private final int[] available;

    /** Next sequence to claim, which is also the number of sequences claimed so far (tickets issued). */
    private final AtomicLong claimed = new AtomicLong();
    /** Number of sequences the consumer has finished with; sequences below this are free to be reused. */
    private final AtomicLong consumed = new AtomicLong();

    private volatile boolean halted;

    /** @param capacity number of slots; must be a power of two, at least 2 */
    public OrderRingBuffer(int capacity) {
        if (capacity < 2 || capacity > (1 << 30) || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two between 2 and 2^30 but was " + capacity);
        }
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.indexShift = Integer.numberOfTrailingZeros(capacity);
        this.slots = new OrderSlot[capacity];
        for (int i = 0; i < capacity; i++) {
            slots[i] = new OrderSlot();
        }
        this.available = new int[capacity];
        Arrays.fill(available, -1); // round numbers start at 0, so -1 means "never published"
    }

    public int capacity() {
        return capacity;
    }

    /**
     * Reserves the next sequence number, waiting while the ring is full. Safe to call from many threads.
     *
     * @throws IllegalStateException if the ring was {@link #halt() halted} while waiting
     */
    public long claim() {
        int waits = 0;
        for (;;) {
            long current = claimed.get();
            if (current - capacity < consumed.get()) { // the slot for 'current' is free
                if (claimed.compareAndSet(current, current + 1)) {
                    return current;
                }
                // another producer won this sequence; retry straight away with the new value
            } else {
                if (halted) {
                    throw new IllegalStateException("ring buffer halted");
                }
                backoff(++waits); // ring is full: wait for the consumer
            }
        }
    }

    /** The slot that belongs to {@code sequence}. Only touch it between {@link #claim()} and {@link #publish}. */
    public OrderSlot slot(long sequence) {
        return slots[(int) (sequence & mask)];
    }

    /** Makes a completely written slot visible to the consumer. Call once, after filling the slot. */
    public void publish(long sequence) {
        AVAILABLE.setRelease(available, (int) (sequence & mask), (int) (sequence >>> indexShift));
    }

    /** Consumer side: true once {@code sequence} has been completely written and published. */
    public boolean isPublished(long sequence) {
        int round = (int) AVAILABLE.getAcquire(available, (int) (sequence & mask));
        return round == (int) (sequence >>> indexShift);
    }

    /** Consumer side: tells the producers that every sequence below {@code nextToConsume} is finished with. */
    public void markConsumed(long nextToConsume) {
        consumed.setRelease(nextToConsume);
    }

    /** Number of sequences claimed so far (tickets issued). */
    public long claimedCount() {
        return claimed.get();
    }

    /** Number of sequences the consumer has finished with. */
    public long consumedCount() {
        return consumed.get();
    }

    /** Makes producers that are waiting on a full ring give up with an exception instead of waiting forever. */
    public void halt() {
        halted = true;
    }

    /** Wait strategy for every wait loop: spin briefly, then yield the CPU. */
    public static void backoff(int attempts) {
        if (attempts < 1_000) {
            Thread.onSpinWait();
        } else {
            Thread.yield();
        }
    }
}
