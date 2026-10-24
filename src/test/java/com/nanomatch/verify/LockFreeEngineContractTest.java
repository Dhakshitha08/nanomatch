package com.nanomatch.verify;

import com.nanomatch.lockfree.LockFreeEngine;
import com.nanomatch.model.MatchingEngine;
import com.nanomatch.model.TradeSink;

/** Runs the shared contract tests against the lock-free engine: the same rules, the same expected trades. */
class LockFreeEngineContractTest extends EngineContractTest {

    @Override
    protected MatchingEngine createEngine(TradeSink sink) {
        return new LockFreeEngine(sink);
    }
}
