package com.nanomatch.verify;

import com.nanomatch.baseline.LockBasedEngine;
import com.nanomatch.model.MatchingEngine;
import com.nanomatch.model.TradeSink;

/** Runs the shared contract tests against the lock-based baseline engine. */
class BaselineEngineContractTest extends EngineContractTest {

    @Override
    protected MatchingEngine createEngine(TradeSink sink) {
        return new LockBasedEngine(sink);
    }
}
