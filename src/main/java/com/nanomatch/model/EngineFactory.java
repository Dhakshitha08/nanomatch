package com.nanomatch.model;

/** Creates a fresh engine that reports its trades to the given sink. */
@FunctionalInterface
public interface EngineFactory {

    MatchingEngine create(TradeSink sink);
}
