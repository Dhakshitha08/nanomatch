package com.nanomatch.gui;

import com.nanomatch.demo.EngineKind;
import com.nanomatch.demo.EngineSession;
import com.nanomatch.demo.SessionSnapshot;
import com.nanomatch.demo.SubmitResult;
import com.nanomatch.model.Order;
import com.nanomatch.model.Side;
import com.nanomatch.sim.OrderGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * Connects the Swing screens to the {@link EngineSession}. All engine work runs on the session's worker thread;
 * results and snapshots are handed to listeners on the Swing event thread, so no screen ever blocks or touches the
 * engine directly.
 */
final class SessionController {

    private final EngineSession session;
    private final List<Consumer<SessionSnapshot>> snapshotListeners = new ArrayList<>(); // event thread only
    private final List<Consumer<Boolean>> busyListeners = new ArrayList<>();               // event thread only
    private final AtomicBoolean dispatchPending = new AtomicBoolean();
    private int busy; // event thread only
    private long lastDispatched = -1;
    private long randomSeed = 1;

    SessionController(EngineKind initial) {
        session = new EngineSession(initial);
        session.addListener(snapshot -> {
            if (dispatchPending.compareAndSet(false, true)) {
                SwingUtilities.invokeLater(() -> {
                    dispatchPending.set(false);
                    dispatch(session.snapshot());
                });
            }
        });
    }

    /** Registers a listener (event thread) and immediately gives it the current snapshot. */
    void onSnapshot(Consumer<SessionSnapshot> listener) {
        snapshotListeners.add(listener);
        listener.accept(session.snapshot());
    }

    void onBusy(Consumer<Boolean> listener) {
        busyListeners.add(listener);
    }

    SessionSnapshot snapshot() {
        return session.snapshot();
    }

    EngineKind engineKind() {
        return session.engineKind();
    }

    void submit(long id, Side side, long price, long quantity, Consumer<SubmitResult> done) {
        track(session.submit(id, side, price, quantity), done);
    }

    void submitAll(List<Order> orders, Consumer<List<SubmitResult>> done) {
        track(session.submitAll(orders), done);
    }

    void reset(EngineKind kind, Consumer<SessionSnapshot> done) {
        track(session.reset(kind), done);
    }

    /**
     * Sends {@code count} random orders from the project's seeded {@link OrderGenerator} (prices 95 to 105,
     * quantity 1 to 20), with ids continuing after the highest id used so far.
     */
    void submitRandom(int count, Consumer<List<SubmitResult>> done) {
        long firstId = session.snapshot().nextOrderId();
        OrderGenerator generator = new OrderGenerator(randomSeed++, 100, 5, 1, 20, 50);
        List<Order> orders = new ArrayList<>(count);
        for (Order o : generator.generate(count)) {
            orders.add(new Order(firstId + o.id() - 1, o.side(), o.price(), o.quantity()));
        }
        submitAll(orders, done);
    }

    void close() {
        session.close();
    }

    private <T> void track(CompletableFuture<T> future, Consumer<T> done) {
        setBusy(+1);
        future.whenComplete((value, error) -> SwingUtilities.invokeLater(() -> {
            setBusy(-1);
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                javax.swing.JOptionPane.showMessageDialog(null, cause.getMessage(), "NanoMatch",
                        javax.swing.JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (done != null) {
                done.accept(value);
            }
        }));
    }

    private void setBusy(int delta) {
        boolean before = busy > 0;
        busy += delta;
        boolean after = busy > 0;
        if (before != after) {
            for (Consumer<Boolean> listener : busyListeners) {
                listener.accept(after);
            }
        }
    }

    private void dispatch(SessionSnapshot snapshot) {
        if (snapshot.version() == lastDispatched) {
            return;
        }
        lastDispatched = snapshot.version();
        for (Consumer<SessionSnapshot> listener : snapshotListeners) {
            listener.accept(snapshot);
        }
    }
}
