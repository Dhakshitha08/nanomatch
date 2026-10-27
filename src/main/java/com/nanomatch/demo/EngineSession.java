package com.nanomatch.demo;

import com.nanomatch.lockfree.LockFreeEngine;
import com.nanomatch.model.MatchingEngine;
import com.nanomatch.model.Order;
import com.nanomatch.model.Side;
import com.nanomatch.model.TradeSink;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Drives one real {@link MatchingEngine} interactively, for the GUI and the terminal demo.
 *
 * <h2>How it stays correct</h2>
 * <ul>
 *   <li><b>One worker thread owns the engine.</b> Every submit, reset and close runs on a single background
 *       thread, so the GUI thread never blocks on the engine and the engine never sees two demo producers at
 *       once.</li>
 *   <li><b>Trades come only from the engine.</b> The engine's {@link TradeSink} (called on the caller's thread
 *       by the baseline and on the matching thread by the lock-free engine) only appends to a thread-safe
 *       queue. After each order the worker waits until the engine reports it fully processed
 *       ({@link MatchingEngine#awaitIdle(long)}), then drains the queue. The engine contract guarantees every
 *       trade of that order has been reported by then.</li>
 *   <li><b>The book view is rebuilt from engine output, never read from engine internals.</b> Neither engine
 *       exposes its queues, so the session keeps the orders it submitted (in ticket order, which is the
 *       engine's time priority) and subtracts the quantities in the engine's trades. Orders with quantity left
 *       are, by the engine's matching rules, exactly the resting orders. As an independent check, the rebuilt
 *       book is compared after every order with the engine's own read-only diagnostics (best bid, best ask,
 *       resting quantity per side), which are safe to read because the engine is idle at that point.</li>
 *   <li><b>Order ids are unique per session.</b> The engine reports trades by id, so the session refuses a
 *       repeated id before it reaches the engine.</li>
 * </ul>
 */
public final class EngineSession implements AutoCloseable {

    private static final long AWAIT_MILLIS = 10_000;
    private static final int MAX_EVENTS = 400;

    /** Collects the engine's trades. Called by at most one engine thread at a time (engine contract). */
    private static final class QueueSink implements TradeSink {
        final ConcurrentLinkedQueue<long[]> trades = new ConcurrentLinkedQueue<>();

        @Override
        public void onTrade(long buyOrderId, long sellOrderId, long price, long quantity) {
            trades.add(new long[] {buyOrderId, sellOrderId, price, quantity});
        }
    }

    private static final class SubmittedOrder {
        final long ticket;
        final long id;
        final Side side;
        final long price;
        final long quantity;
        long remaining;

        SubmittedOrder(long ticket, long id, Side side, long price, long quantity) {
            this.ticket = ticket;
            this.id = id;
            this.side = side;
            this.price = price;
            this.quantity = quantity;
            this.remaining = quantity;
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "nanomatch-session");
        thread.setDaemon(true);
        return thread;
    });
    private final CopyOnWriteArrayList<Consumer<SessionSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<SessionSnapshot> latest = new AtomicReference<>();
    private volatile boolean closed;

    // ---- state below is touched only by the worker thread ----
    private EngineKind kind;
    private MatchingEngine engine;
    private QueueSink sink;
    private final LinkedHashMap<Long, SubmittedOrder> orders = new LinkedHashMap<>();
    private final List<SessionSnapshot.Execution> trades = new ArrayList<>();
    private final ArrayDeque<SessionSnapshot.Event> events = new ArrayDeque<>();
    private long tradedVolume;
    private long lastRoundTripNanos;
    private long highestOrderId;
    private long version;
    private SessionSnapshot.Status status = SessionSnapshot.Status.RUNNING;
    private String statusDetail = "";

    /** Starts a session with a fresh engine of the given kind. */
    public EngineSession(EngineKind initialKind) {
        try {
            worker.submit(() -> startEngine(initialKind)).get();
        } catch (Exception e) {
            worker.shutdownNow();
            throw new IllegalStateException("could not start the " + initialKind.displayName() + " engine", e);
        }
    }

    /** Registers a listener that receives every new snapshot (on the session's worker thread). */
    public void addListener(Consumer<SessionSnapshot> listener) {
        listeners.add(listener);
    }

    /** The most recent snapshot. Never null after construction. */
    public SessionSnapshot snapshot() {
        return latest.get();
    }

    public EngineKind engineKind() {
        return latest.get().engine();
    }

    /** Submits one limit order to the engine. Completes once the engine has fully processed it (or rejected it). */
    public CompletableFuture<SubmitResult> submit(long orderId, Side side, long price, long quantity) {
        return async(() -> doSubmit(orderId, side, price, quantity));
    }

    /** Submits several orders one after another, waiting for each to be processed. */
    public CompletableFuture<List<SubmitResult>> submitAll(List<Order> batch) {
        List<Order> copy = List.copyOf(batch);
        return async(() -> {
            List<SubmitResult> results = new ArrayList<>(copy.size());
            for (Order order : copy) {
                results.add(doSubmit(order.id(), order.side(), order.price(), order.quantity()));
            }
            return results;
        });
    }

    /** Closes the current engine and starts a fresh engine of the given kind with an empty book. */
    public CompletableFuture<SessionSnapshot> reset(EngineKind newKind) {
        return async(() -> {
            stopEngine();
            startEngine(newKind);
            return latest.get();
        });
    }

    /** Convenience for code that is not on the GUI thread (terminal demo, tests). */
    public SubmitResult submitAndWait(long orderId, Side side, long price, long quantity) {
        return submit(orderId, side, price, quantity).join();
    }

    /** Stops the engine (including the lock-free matching thread) and the worker. Safe to call more than once. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            worker.submit(() -> {
                stopEngine();
                status = SessionSnapshot.Status.CLOSED;
                statusDetail = "session closed";
                publish();
            }).get(15, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // shutting down anyway
        } finally {
            worker.shutdown();
            try {
                worker.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------------------------- worker thread

    private <T> CompletableFuture<T> async(java.util.function.Supplier<T> task) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("the session is closed"));
        }
        try {
            return CompletableFuture.supplyAsync(task, worker);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(new IllegalStateException("the session is closed", e));
        }
    }

    private void startEngine(EngineKind newKind) {
        kind = newKind;
        sink = new QueueSink();
        engine = newKind.create(sink);
        orders.clear();
        trades.clear();
        tradedVolume = 0;
        lastRoundTripNanos = 0;
        highestOrderId = 0;
        status = SessionSnapshot.Status.RUNNING;
        statusDetail = newKind == EngineKind.LOCK_FREE
                ? "matching thread running, prices " + newKind.minPrice() + " to " + newKind.maxPrice()
                : "matching on the caller's thread under one ReentrantLock";
        event("INFO", "Started a fresh " + newKind.displayName() + " (" + newKind.className() + "), empty book");
        publish();
    }

    private void stopEngine() {
        if (engine != null) {
            try {
                engine.awaitIdle(AWAIT_MILLIS);
            } catch (RuntimeException ignored) {
                // close anyway
            }
            engine.close();
            event("INFO", "Closed the " + kind.displayName());
            engine = null;
        }
    }

    private SubmitResult doSubmit(long orderId, Side side, long price, long quantity) {
        String problem = validate(orderId, side, price, quantity);
        if (problem != null) {
            return reject(orderId, side, price, quantity, problem);
        }
        final long start = System.nanoTime();
        final long ticket;
        try {
            ticket = engine.submit(orderId, side, price, quantity);
        } catch (RuntimeException e) {
            checkEngineFailure();
            return reject(orderId, side, price, quantity, "The engine rejected the order: " + e.getMessage());
        }
        SubmittedOrder order = new SubmittedOrder(ticket, orderId, side, price, quantity);
        orders.put(orderId, order);
        highestOrderId = Math.max(highestOrderId, orderId);
        try {
            engine.awaitIdle(AWAIT_MILLIS);
        } catch (IllegalStateException e) {
            status = SessionSnapshot.Status.FAILED;
            statusDetail = e.getMessage();
        }
        final long roundTrip = System.nanoTime() - start;
        lastRoundTripNanos = roundTrip;

        List<SessionSnapshot.Execution> fills = drainTrades(orderId, side);
        checkEngineFailure();

        StringBuilder text = new StringBuilder();
        text.append(side).append(" #").append(orderId).append("  ").append(quantity).append(" @ ").append(price)
                .append("  ->  ticket ").append(ticket).append(", ");
        if (fills.isEmpty()) {
            text.append("no trade");
        } else {
            text.append(fills.size()).append(fills.size() == 1 ? " trade" : " trades");
        }
        if (order.remaining > 0) {
            text.append(", ").append(order.remaining).append(" resting");
        }
        event("ORDER", text.toString());
        for (SessionSnapshot.Execution fill : fills) {
            event("TRADE", "Trade: buy #" + fill.buyOrderId() + " / sell #" + fill.sellOrderId() + "  "
                    + fill.quantity() + " @ " + fill.price());
        }
        SessionSnapshot snapshot = publish();
        return new SubmitResult(true, fills.isEmpty() ? "Accepted, no trade" : "Accepted, traded", orderId, side,
                price, quantity, ticket, List.copyOf(fills), order.remaining, roundTrip, snapshot);
    }

    private String validate(long orderId, Side side, long price, long quantity) {
        if (closed || engine == null) {
            return "The session is closed.";
        }
        if (status == SessionSnapshot.Status.FAILED) {
            return "The engine has failed (" + statusDetail + "). Reset the engine to continue.";
        }
        if (side == null) {
            return "Choose BUY or SELL.";
        }
        if (orderId <= 0) {
            return "The order id must be a positive whole number.";
        }
        if (orders.containsKey(orderId)) {
            return "Order id " + orderId + " was already used in this session. Ids must be unique, because the "
                    + "engine reports trades by order id.";
        }
        if (price <= 0) {
            return "The price must be greater than 0 ticks.";
        }
        if (quantity <= 0) {
            return "The quantity must be greater than 0.";
        }
        if (price < kind.minPrice() || price > kind.maxPrice()) {
            return "The " + kind.displayName() + " accepts prices from " + kind.minPrice() + " to "
                    + kind.maxPrice() + " ticks.";
        }
        return null;
    }

    private SubmitResult reject(long orderId, Side side, long price, long quantity, String message) {
        event("ERROR", "Rejected " + (side == null ? "order" : side) + " #" + orderId + ": " + message);
        SessionSnapshot snapshot = publish();
        return new SubmitResult(false, message, orderId, side, price, quantity, -1, List.of(), 0, 0, snapshot);
    }

    private List<SessionSnapshot.Execution> drainTrades(long aggressorId, Side aggressorSide) {
        List<SessionSnapshot.Execution> fills = new ArrayList<>();
        long now = System.currentTimeMillis();
        long[] raw;
        while ((raw = sink.trades.poll()) != null) {
            SessionSnapshot.Execution trade = new SessionSnapshot.Execution(trades.size() + 1L, now, raw[0], raw[1],
                    raw[2], raw[3], aggressorId, aggressorSide);
            trades.add(trade);
            fills.add(trade);
            tradedVolume += raw[3];
            applyFill(raw[0], raw[3]);
            applyFill(raw[1], raw[3]);
        }
        return fills;
    }

    private void applyFill(long orderId, long quantity) {
        SubmittedOrder order = orders.get(orderId);
        if (order == null) {
            event("ERROR", "The engine reported a trade for unknown order #" + orderId);
            return;
        }
        order.remaining -= quantity;
        if (order.remaining < 0) {
            event("ERROR", "Order #" + orderId + " was filled for more than its quantity");
        }
    }

    private void checkEngineFailure() {
        if (engine instanceof LockFreeEngine lockFree && lockFree.failure() != null) {
            status = SessionSnapshot.Status.FAILED;
            statusDetail = "matching thread failed: " + lockFree.failure();
        }
    }

    private void event(String type, String text) {
        events.addLast(new SessionSnapshot.Event(System.currentTimeMillis(), type, text));
        while (events.size() > MAX_EVENTS) {
            events.removeFirst();
        }
    }

    private SessionSnapshot publish() {
        TreeMap<Long, List<SessionSnapshot.RestingOrder>> bidLevels = new TreeMap<>(Comparator.reverseOrder());
        TreeMap<Long, List<SessionSnapshot.RestingOrder>> askLevels = new TreeMap<>();
        List<SessionSnapshot.RestingOrder> resting = new ArrayList<>();
        for (SubmittedOrder order : orders.values()) { // insertion order = ticket order = time priority
            if (order.remaining > 0) {
                SessionSnapshot.RestingOrder view = new SessionSnapshot.RestingOrder(order.ticket, order.id,
                        order.side, order.price, order.quantity, order.remaining);
                resting.add(view);
                (order.side == Side.BUY ? bidLevels : askLevels)
                        .computeIfAbsent(order.price, p -> new ArrayList<>()).add(view);
            }
        }
        List<SessionSnapshot.Level> bids = toLevels(bidLevels, Side.BUY);
        List<SessionSnapshot.Level> asks = toLevels(askLevels, Side.SELL);

        SessionSnapshot.EngineView engineView = engineView(bids, asks);
        long submitted = engine == null ? orders.size() : engine.submittedCount();
        long processed = engine == null ? orders.size() : engine.processedCount();
        SessionSnapshot snapshot = new SessionSnapshot(++version, kind, status, statusDetail, submitted, processed,
                List.copyOf(trades), tradedVolume, bids, asks, List.copyOf(resting), engineView,
                lastRoundTripNanos, highestOrderId + 1, List.copyOf(events));
        latest.set(snapshot);
        for (Consumer<SessionSnapshot> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException ignored) {
                // a faulty listener must not break the session
            }
        }
        return snapshot;
    }

    private static List<SessionSnapshot.Level> toLevels(Map<Long, List<SessionSnapshot.RestingOrder>> byPrice,
                                                        Side side) {
        List<SessionSnapshot.Level> levels = new ArrayList<>(byPrice.size());
        for (Map.Entry<Long, List<SessionSnapshot.RestingOrder>> entry : byPrice.entrySet()) {
            long total = 0;
            for (SessionSnapshot.RestingOrder order : entry.getValue()) {
                total += order.remainingQuantity();
            }
            levels.add(new SessionSnapshot.Level(entry.getKey(), side, total, List.copyOf(entry.getValue())));
        }
        return List.copyOf(levels);
    }

    /** Reads the engine's own diagnostics. Only called on the worker after the engine is idle. */
    private SessionSnapshot.EngineView engineView(List<SessionSnapshot.Level> bids, List<SessionSnapshot.Level> asks) {
        if (engine == null || status == SessionSnapshot.Status.FAILED
                || engine.processedCount() != engine.submittedCount()) {
            return new SessionSnapshot.EngineView(false, 0, 0, 0, 0, false);
        }
        long[] reported = EngineKind.engineReportedTopOfBook(engine);
        if (reported == null) {
            return new SessionSnapshot.EngineView(false, 0, 0, 0, 0, false);
        }
        long bestBid = bids.isEmpty() ? 0 : bids.get(0).price();
        long bestAsk = asks.isEmpty() ? 0 : asks.get(0).price();
        long bidQty = 0;
        for (SessionSnapshot.Level level : bids) {
            bidQty += level.totalQuantity();
        }
        long askQty = 0;
        for (SessionSnapshot.Level level : asks) {
            askQty += level.totalQuantity();
        }
        boolean consistent = reported[0] == bestBid && reported[1] == bestAsk && reported[2] == bidQty
                && reported[3] == askQty;
        if (!consistent) {
            event("ERROR", "Rebuilt book differs from the engine's own report (engine: bid " + reported[0] + ", ask "
                    + reported[1] + ", qty " + reported[2] + "/" + reported[3] + ")");
        }
        return new SessionSnapshot.EngineView(true, reported[0], reported[1], reported[2], reported[3], consistent);
    }
}
