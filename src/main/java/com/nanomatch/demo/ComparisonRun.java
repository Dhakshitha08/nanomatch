package com.nanomatch.demo;

import com.nanomatch.model.MatchingEngine;
import com.nanomatch.model.Order;
import com.nanomatch.model.TradeSink;
import com.nanomatch.sim.LoadConfig;
import com.nanomatch.sim.LoadPattern;
import com.nanomatch.sim.LoadSimulator;
import com.nanomatch.sim.OrderGenerator;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * A quick, live side-by-side run of both engines for the demo, built from the project's own tools:
 * {@link LoadSimulator} drives the engines with several producer threads, and the parity check replays the
 * lock-free engine's recorded ticket order through the baseline, as {@code ParityTest} does.
 *
 * <p><b>This is not the benchmark.</b> It runs for a second or two, without JMH's forks, warm-up control or
 * percentiles. It shows the behaviour live; the measured results are the frozen JMH runs.
 */
public final class ComparisonRun {

    private ComparisonRun() {
    }

    /**
     * Throughput of one engine over several repetitions.
     *
     * @param kind            the engine
     * @param ordersPerSecond end-to-end orders per second of each timed repetition
     * @param tradesPerRun    trades produced in the last repetition
     * @param ordersPerRun    orders submitted in each repetition
     */
    public record EngineMeasurement(EngineKind kind, double[] ordersPerSecond, long tradesPerRun, long ordersPerRun) {

        public double median() {
            double[] sorted = ordersPerSecond.clone();
            Arrays.sort(sorted);
            int n = sorted.length;
            return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
        }

        public double min() {
            return Arrays.stream(ordersPerSecond).min().orElse(0);
        }

        public double max() {
            return Arrays.stream(ordersPerSecond).max().orElse(0);
        }
    }

    /**
     * Outcome of a parity check.
     *
     * @param passed         true if both trade lists are identical
     * @param tradesCompared number of trades compared
     * @param orders         number of orders replayed
     * @param detail         explanation, or the first difference
     */
    public record Parity(boolean passed, long tradesCompared, long orders, String detail) {
    }

    /**
     * Everything one live comparison produced.
     *
     * @param producers           producer threads per engine
     * @param ordersPerThread     orders each producer submitted
     * @param baseline            lock-based throughput
     * @param lockFree            lock-free throughput
     * @param concurrentParity    lock-free run with all producers, replayed in ticket order through the baseline
     * @param singleProducerParity the same seeded sequence through both engines
     * @param elapsedMillis       wall-clock time of the whole comparison
     */
    public record Result(int producers, int ordersPerThread, EngineMeasurement baseline, EngineMeasurement lockFree,
                         Parity concurrentParity, Parity singleProducerParity, long elapsedMillis) {
    }

    /**
     * Runs the comparison.
     *
     * @param producers       producer threads (1 or more)
     * @param ordersPerThread orders per producer
     * @param repetitions     timed repetitions per engine (after one untimed warm-up run)
     * @param seed            seed for the order generators
     * @param progress        receives short progress messages (may be called from this thread only)
     */
    public static Result run(int producers, int ordersPerThread, int repetitions, long seed,
                             Consumer<String> progress) throws InterruptedException {
        long start = System.currentTimeMillis();
        EngineMeasurement baseline = measure(EngineKind.LOCK_BASED, producers, ordersPerThread, repetitions, seed,
                progress);
        EngineMeasurement lockFree = measure(EngineKind.LOCK_FREE, producers, ordersPerThread, repetitions, seed,
                progress);
        progress.accept("Parity: lock-free run with " + producers + " producers, replayed through the baseline...");
        Parity concurrent = concurrentParity(producers, ordersPerThread, seed);
        progress.accept("Parity: one producer, same sequence through both engines...");
        Parity single = singleProducerParity(producers * ordersPerThread, seed);
        progress.accept("Done.");
        return new Result(producers, ordersPerThread, baseline, lockFree, concurrent, single,
                System.currentTimeMillis() - start);
    }

    private static EngineMeasurement measure(EngineKind kind, int producers, int ordersPerThread, int repetitions,
                                             long seed, Consumer<String> progress) throws InterruptedException {
        LoadConfig config = new LoadConfig(producers, ordersPerThread, LoadPattern.maxSpeed(), seed, false);
        progress.accept(kind.displayName() + ": warm-up run (not timed)...");
        LoadSimulator.run(kind.factory(), new CountingSink(), config);
        double[] rates = new double[repetitions];
        long trades = 0;
        for (int i = 0; i < repetitions; i++) {
            progress.accept(kind.displayName() + ": timed run " + (i + 1) + " of " + repetitions + "...");
            CountingSink sink = new CountingSink();
            LoadSimulator.Result result = LoadSimulator.run(kind.factory(), sink, config);
            if (result.processedCount() != result.totalOrders()) {
                throw new IllegalStateException(kind.displayName() + " processed " + result.processedCount()
                        + " of " + result.totalOrders() + " orders");
            }
            rates[i] = result.ordersPerSecond();
            trades = sink.count;
        }
        return new EngineMeasurement(kind, rates, trades, (long) producers * ordersPerThread);
    }

    static Parity concurrentParity(int producers, int ordersPerThread, long seed) throws InterruptedException {
        TradeRecorder lockFreeTrades = new TradeRecorder();
        LoadSimulator.Result run = LoadSimulator.run(EngineKind.LOCK_FREE.factory(), lockFreeTrades,
                new LoadConfig(producers, ordersPerThread, LoadPattern.maxSpeed(), seed, true));
        Order[] byTicket = run.ordersByTicket();

        TradeRecorder baselineTrades = new TradeRecorder();
        try (MatchingEngine baseline = EngineKind.LOCK_BASED.create(baselineTrades)) {
            for (Order order : byTicket) {
                baseline.submit(order);
            }
            baseline.awaitIdle();
        }
        return compare(lockFreeTrades, baselineTrades, byTicket.length,
                "the lock-free engine with " + producers + " concurrent producers, replayed in ticket order "
                        + "through the baseline");
    }

    static Parity singleProducerParity(int orders, long seed) {
        Order[] sequence = new OrderGenerator(seed).generate(orders);
        TradeRecorder baselineTrades = new TradeRecorder();
        TradeRecorder lockFreeTrades = new TradeRecorder();
        try (MatchingEngine baseline = EngineKind.LOCK_BASED.create(baselineTrades);
             MatchingEngine lockFree = EngineKind.LOCK_FREE.create(lockFreeTrades)) {
            for (Order order : sequence) {
                baseline.submit(order);
                lockFree.submit(order);
            }
            baseline.awaitIdle();
            lockFree.awaitIdle();
        }
        return compare(lockFreeTrades, baselineTrades, orders, "the same seeded sequence through both engines");
    }

    private static Parity compare(TradeRecorder lockFree, TradeRecorder baseline, long orders, String what) {
        if (lockFree.size() != baseline.size()) {
            return new Parity(false, Math.min(lockFree.size(), baseline.size()), orders,
                    "Different number of trades: lock-free " + lockFree.size() + ", baseline " + baseline.size()
                            + " (" + what + ").");
        }
        for (int i = 0; i < lockFree.size(); i++) {
            if (!lockFree.same(i, baseline)) {
                return new Parity(false, i, orders, "First difference at trade " + (i + 1) + ": lock-free "
                        + lockFree.describe(i) + ", baseline " + baseline.describe(i) + " (" + what + ").");
            }
        }
        return new Parity(true, lockFree.size(), orders, "All " + lockFree.size() + " trades identical: buy id, "
                + "sell id, price and quantity, in the same order (" + what + ").");
    }

    /** Counts trades. The engine calls it from one thread at a time; read after the run has finished. */
    private static final class CountingSink implements TradeSink {
        long count;

        @Override
        public void onTrade(long buyOrderId, long sellOrderId, long price, long quantity) {
            count++;
        }
    }

    /** Records trades as primitive values (compact even for hundreds of thousands of trades). */
    static final class TradeRecorder implements TradeSink {
        private long[] data = new long[4 * 1024];
        private int size;

        @Override
        public void onTrade(long buyOrderId, long sellOrderId, long price, long quantity) {
            if (4 * (size + 1) > data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            int at = 4 * size;
            data[at] = buyOrderId;
            data[at + 1] = sellOrderId;
            data[at + 2] = price;
            data[at + 3] = quantity;
            size++;
        }

        int size() {
            return size;
        }

        boolean same(int index, TradeRecorder other) {
            int at = 4 * index;
            return data[at] == other.data[at] && data[at + 1] == other.data[at + 1]
                    && data[at + 2] == other.data[at + 2] && data[at + 3] == other.data[at + 3];
        }

        String describe(int index) {
            int at = 4 * index;
            return "buy #" + data[at] + " / sell #" + data[at + 1] + " " + data[at + 3] + " @ " + data[at + 2];
        }
    }
}
