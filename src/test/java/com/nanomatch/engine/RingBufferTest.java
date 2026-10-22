package com.nanomatch.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nanomatch.lockfree.OrderRingBuffer;
import com.nanomatch.lockfree.OrderSlot;
import com.nanomatch.model.Side;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Tests for the multi-producer, single-consumer ring buffer on its own (without the matching engine). */
class RingBufferTest {

    @Test
    void capacityMustBeAPowerOfTwo() {
        assertThrows(IllegalArgumentException.class, () -> new OrderRingBuffer(0));
        assertThrows(IllegalArgumentException.class, () -> new OrderRingBuffer(1));
        assertThrows(IllegalArgumentException.class, () -> new OrderRingBuffer(3));
        assertThrows(IllegalArgumentException.class, () -> new OrderRingBuffer(1_000));
        assertEquals(8, new OrderRingBuffer(8).capacity());
    }

    @Test
    void singleThreadPublishAndConsumeInSequenceOrder() {
        OrderRingBuffer ring = new OrderRingBuffer(4);
        for (long i = 0; i < 20; i++) { // five full rounds of a four-slot ring
            long sequence = ring.claim();
            assertEquals(i, sequence);
            OrderSlot slot = ring.slot(sequence);
            slot.orderId = 100 + i;
            slot.side = Side.BUY;
            slot.price = 5;
            slot.quantity = 1;
            ring.publish(sequence);

            assertTrue(ring.isPublished(sequence));
            assertEquals(100 + i, ring.slot(sequence).orderId);
            ring.markConsumed(sequence + 1);
        }
        assertEquals(20, ring.claimedCount());
        assertEquals(20, ring.consumedCount());
    }

    @Test
    void aClaimedButUnpublishedSlotIsNotVisibleToTheConsumer() {
        OrderRingBuffer ring = new OrderRingBuffer(4);
        long sequence = ring.claim();
        assertFalse(ring.isPublished(sequence));
        ring.publish(sequence);
        assertTrue(ring.isPublished(sequence));
        // the slot for the next round has not been published yet, even though the slot index is reused
        assertFalse(ring.isPublished(sequence + 4));
    }

    @Test
    void claimWaitsWhenTheRingIsFullUntilTheConsumerFreesASlot() throws Exception {
        OrderRingBuffer ring = new OrderRingBuffer(4);
        for (int i = 0; i < 4; i++) {
            ring.publish(ring.claim());
        }
        AtomicLong claimedByBlockedProducer = new AtomicLong(-1);
        Thread producer = new Thread(() -> claimedByBlockedProducer.set(ring.claim()));
        producer.start();

        Thread.sleep(200);
        assertEquals(-1, claimedByBlockedProducer.get(), "claim must wait while the ring is full");
        assertEquals(4, ring.claimedCount());

        ring.markConsumed(1); // frees the slot of sequence 0
        producer.join(10_000);
        assertFalse(producer.isAlive(), "claim should complete once a slot is free");
        assertEquals(4, claimedByBlockedProducer.get());
    }

    @Test
    void haltReleasesAProducerThatIsWaitingOnAFullRing() throws Exception {
        OrderRingBuffer ring = new OrderRingBuffer(2);
        ring.publish(ring.claim());
        ring.publish(ring.claim());
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                ring.claim();
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        producer.start();
        Thread.sleep(100);
        ring.halt();
        producer.join(10_000);
        assertFalse(producer.isAlive());
        assertTrue(thrown.get() instanceof IllegalStateException);
    }

    /**
     * Several producers write numbered values while this thread consumes them. Every value must arrive exactly
     * once, in strictly increasing order per producer, and the ring is small enough to wrap around many times.
     */
    @Test
    void manyProducersDeliverEveryValueExactlyOnceAndInOrderPerProducer() throws Exception {
        final int producers = 4;
        final int perProducer = 50_000;
        final long total = (long) producers * perProducer;
        final OrderRingBuffer ring = new OrderRingBuffer(1_024);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread[] threads = new Thread[producers];
        for (int p = 0; p < producers; p++) {
            final int producer = p;
            threads[p] = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perProducer; i++) {
                        long sequence = ring.claim();
                        OrderSlot slot = ring.slot(sequence);
                        slot.orderId = producer;
                        slot.price = i;
                        slot.side = Side.SELL;
                        slot.quantity = 1;
                        ring.publish(sequence);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            threads[p].start();
        }
        start.countDown();

        long[] lastSeen = new long[producers];
        long[] count = new long[producers];
        java.util.Arrays.fill(lastSeen, -1);
        int waits = 0;
        long deadline = System.nanoTime() + 120_000_000_000L;
        for (long next = 0; next < total; ) {
            if (ring.isPublished(next)) {
                waits = 0;
                OrderSlot slot = ring.slot(next);
                int producer = (int) slot.orderId;
                assertTrue(slot.price > lastSeen[producer], "producer " + producer + " values out of order");
                lastSeen[producer] = slot.price;
                count[producer]++;
                next++;
                ring.markConsumed(next);
            } else {
                OrderRingBuffer.backoff(++waits);
                assertTrue(System.nanoTime() < deadline, "timed out waiting for sequence " + next);
                if (failure.get() != null) {
                    throw new AssertionError("a producer failed", failure.get());
                }
            }
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertEquals(null, failure.get());
        for (int p = 0; p < producers; p++) {
            assertEquals(perProducer, count[p], "values delivered for producer " + p);
        }
        assertEquals(total, ring.claimedCount());
        assertEquals(total, ring.consumedCount());
    }
}
