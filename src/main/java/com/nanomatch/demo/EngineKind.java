package com.nanomatch.demo;

import com.nanomatch.baseline.LockBasedEngine;
import com.nanomatch.lockfree.LockFreeEngine;
import com.nanomatch.model.EngineFactory;
import com.nanomatch.model.MatchingEngine;
import com.nanomatch.model.Side;
import com.nanomatch.model.TradeSink;

/**
 * The two real engines of the project, as the demo tools (GUI and terminal demo) select them.
 *
 * <p>This only creates the existing engines with their normal constructors. It does not wrap or change
 * the matching code.
 */
public enum EngineKind {

    LOCK_BASED("Lock-based baseline", "LockBasedEngine", LockBasedEngine::new),
    LOCK_FREE("Lock-free ring buffer", "LockFreeEngine", LockFreeEngine::new);

    private final String displayName;
    private final String className;
    private final EngineFactory factory;

    EngineKind(String displayName, String className, EngineFactory factory) {
        this.displayName = displayName;
        this.className = className;
        this.factory = factory;
    }

    public String displayName() {
        return displayName;
    }

    public String className() {
        return className;
    }

    public EngineFactory factory() {
        return factory;
    }

    public MatchingEngine create(TradeSink sink) {
        return factory.create(sink);
    }

    /** Lowest price the engine accepts. */
    public long minPrice() {
        return this == LOCK_FREE ? LockFreeEngine.DEFAULT_MIN_PRICE : 1;
    }

    /** Highest price the engine accepts. */
    public long maxPrice() {
        return this == LOCK_FREE ? LockFreeEngine.DEFAULT_MAX_PRICE : Long.MAX_VALUE;
    }

    /** Short name used on the command line: {@code baseline} or {@code lockfree}. */
    public String cliName() {
        return this == LOCK_FREE ? "lockfree" : "baseline";
    }

    public static EngineKind fromCliName(String name) {
        for (EngineKind kind : values()) {
            if (kind.cliName().equalsIgnoreCase(name)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown engine '" + name + "' (use baseline or lockfree)");
    }

    /**
     * The engine's own view of its book: best bid, best ask and resting quantity per side. Both engines
     * expose these read-only diagnostics. For the lock-free engine they are only valid while the engine is
     * idle (every submitted order processed, no producer running), which the caller must guarantee.
     */
    static long[] engineReportedTopOfBook(MatchingEngine engine) {
        if (engine instanceof LockBasedEngine e) {
            return new long[] {e.bestBidPrice(), e.bestAskPrice(), e.restingQuantity(Side.BUY),
                    e.restingQuantity(Side.SELL)};
        }
        if (engine instanceof LockFreeEngine e) {
            return new long[] {e.bestBidPrice(), e.bestAskPrice(), e.restingQuantity(Side.BUY),
                    e.restingQuantity(Side.SELL)};
        }
        return null;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
