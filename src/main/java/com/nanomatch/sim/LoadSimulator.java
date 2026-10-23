package com.nanomatch.sim;

import com.nanomatch.model.EngineFactory;
import com.nanomatch.model.MatchingEngine;
import com.nanomatch.model.Order;
import com.nanomatch.model.TradeSink;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Multi-threaded client simulator: N threads submit orders to one engine at the same time, following a
 * {@link LoadPattern}.
 *
 * <p>All orders are generated <b>before</b> the clock starts (one {@link OrderGenerator} per thread, seeded
 * from {@link LoadConfig#seed()}), so order generation never affects the measurement and a run is
 * reproducible. Order ids are unique across threads.
 *
 * <p>One run: create the engine from the factory, release all threads at once, wait until the threads have
 * submitted everything, wait until the engine has processed everything, then close the engine.
 */
public final class LoadSimulator {

    /** Spacing of order ids between threads, so ids never collide. Greater than MAX_ORDERS_PER_THREAD. */
    private static final long ID_STRIDE = 100_000_000L;

    private LoadSimulator() {
    }

    /**
     * The outcome of one run.
     *
     * @param threads          number of client threads
     * @param totalOrders      orders submitted in total
     * @param submitNanos      time from the start signal until the last thread finished submitting
     * @param totalNanos       time from the start signal until the engine had processed every order
     * @param submittedCount   {@code engine.submittedCount()} at the end
     * @param processedCount   {@code engine.processedCount()} at the end
     * @param ordersByTicket   the order that received each ticket (index = ticket), or null if tickets were not
     *                         recorded. Replaying this sequence through another engine must give identical trades.
     */
    public record Result(int threads, long totalOrders, long submitNanos, long totalNanos,
                         long submittedCount, long processedCount, Order[] ordersByTicket) {

        /** End-to-end throughput: orders per second until everything was processed. */
        public double ordersPerSecond() {
            return totalOrders * 1_000_000_000.0 / totalNanos;
        }

        /** Rate at which the threads managed to hand orders to the engine. */
        public double submitOrdersPerSecond() {
            return totalOrders * 1_000_000_000.0 / submitNanos;
        }
    }

    public static Result run(EngineFactory factory, TradeSink sink, LoadConfig config) throws InterruptedException {
        // 1. Setup, not timed: generate every order up front.
        final Order[][] orders = new Order[config.threads()][];
        for (int t = 0; t < config.threads(); t++) {
            Order[] generated = new OrderGenerator(config.seed() + t).generate(config.ordersPerThread());
            orders[t] = new Order[generated.length];
            for (int i = 0; i < generated.length; i++) {
                Order o = generated[i];
                orders[t][i] = new Order(t * ID_STRIDE + o.id(), o.side(), o.price(), o.quantity());
            }
        }
        final Order[] byTicket = config.recordTickets() ? new Order[config.totalOrders()] : null;

        final MatchingEngine engine = factory.create(sink);
        try {
            final CountDownLatch ready = new CountDownLatch(config.threads());
            final CountDownLatch go = new CountDownLatch(1);
            final long[] endNanos = new long[config.threads()];
            final AtomicReference<Throwable> failure = new AtomicReference<>();

            Thread[] clients = new Thread[config.threads()];
            for (int t = 0; t < config.threads(); t++) {
                final int index = t;
                clients[t] = new Thread(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        submitAll(engine, orders[index], config.pattern(), byTicket);
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        endNanos[index] = System.nanoTime();
                    }
                }, "client-" + t);
                clients[t].start();
            }

            // 2. Timed section.
            ready.await();
            final long startNanos = System.nanoTime();
            go.countDown();
            for (Thread client : clients) {
                client.join();
            }
            if (failure.get() != null) {
                throw new IllegalStateException("a client thread failed", failure.get());
            }
            long lastSubmitEnd = startNanos;
            for (long end : endNanos) {
                lastSubmitEnd = Math.max(lastSubmitEnd, end);
            }
            engine.awaitIdle();
            final long idleNanos = System.nanoTime();

            return new Result(config.threads(), config.totalOrders(), lastSubmitEnd - startNanos,
                    idleNanos - startNanos, engine.submittedCount(), engine.processedCount(), byTicket);
        } finally {
            engine.close();
        }
    }

    private static void submitAll(MatchingEngine engine, Order[] mine, LoadPattern pattern, Order[] byTicket) {
        switch (pattern.kind()) {
            case MAX_SPEED:
                for (Order order : mine) {
                    submit(engine, order, byTicket);
                }
                break;
            case STEADY: {
                final long interval = 1_000_000_000L / pattern.ordersPerSecondPerThread();
                long due = System.nanoTime();
                for (Order order : mine) {
                    waitUntil(due);
                    submit(engine, order, byTicket);
                    due += interval; // fixed schedule: a late order is followed straight away, not delayed further
                }
                break;
            }
            case BURST:
                for (int i = 0; i < mine.length; i++) {
                    submit(engine, mine[i], byTicket);
                    boolean endOfBurst = (i + 1) % pattern.burstSize() == 0;
                    if (endOfBurst && i + 1 < mine.length) {
                        waitUntil(System.nanoTime() + pattern.burstGapNanos());
                    }
                }
                break;
            default:
                throw new IllegalStateException("unknown pattern " + pattern);
        }
    }

    private static void submit(MatchingEngine engine, Order order, Order[] byTicket) {
        long ticket = engine.submit(order);
        if (byTicket != null) {
            byTicket[(int) ticket] = order; // tickets are unique, so threads never write the same index
        }
    }

    /** Busy-waits (sleeping is far too coarse for microsecond-level pacing). */
    private static void waitUntil(long deadlineNanos) {
        while (System.nanoTime() - deadlineNanos < 0) {
            Thread.onSpinWait();
        }
    }
}
