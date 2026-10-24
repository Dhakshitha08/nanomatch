package com.nanomatch.smoke;

import com.nanomatch.lockfree.OrderRingBuffer;
import com.nanomatch.lockfree.OrderSlot;
import com.nanomatch.model.Side;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Day 5 evidence for the cache-line padding: does padding the ring buffer's hot counters make the ring faster?
 *
 * <p>JMH threads are the producers (set their number with {@code -t}); one background thread plays the matching
 * thread and just consumes. Run it twice and compare the {@code produce} score (orders per second):
 * <pre>
 * java -jar target/benchmarks.jar PaddingBenchmark -f 1 -wi 2 -i 3 -w 2s -r 2s -t 3
 * java -jar target/benchmarks.jar PaddingBenchmark -f 1 -wi 2 -i 3 -w 2s -r 2s -t 3 -jvmArgsAppend "-Dnanomatch.padding=false"
 * </pre>
 * The second run switches padding off. A result that shows no difference is a valid result too: it is reported
 * as measured, whatever it is.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(1)
public class PaddingBenchmark {

    private OrderRingBuffer ring;
    private Thread consumer;
    private volatile boolean stop;

    @Setup(Level.Trial)
    public void startConsumer() {
        ring = new OrderRingBuffer(1 << 14);
        stop = false;
        consumer = new Thread(() -> {
            long next = 0;
            int idle = 0;
            for (;;) {
                if (ring.isPublished(next)) {
                    idle = 0;
                    next++;
                    ring.markConsumed(next);
                } else if (stop && next >= ring.claimedCount()) {
                    return;
                } else {
                    OrderRingBuffer.backoff(++idle);
                }
            }
        }, "padding-benchmark-consumer");
        consumer.setDaemon(true);
        consumer.start();
    }

    @TearDown(Level.Trial)
    public void stopConsumer() throws InterruptedException {
        stop = true;
        consumer.join();
    }

    @Benchmark
    public long produce() {
        long sequence = ring.claim();
        OrderSlot slot = ring.slot(sequence);
        slot.orderId = sequence;
        slot.side = Side.BUY;
        slot.price = 1;
        slot.quantity = 1;
        ring.publish(sequence);
        return sequence;
    }
}
