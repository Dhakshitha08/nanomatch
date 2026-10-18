# Scope Lock (Checkpoint 1)

Both team members must sign off before engine code is written. Any "just one more feature" idea is
written down in the **Future work** list at the bottom, not built.

## In scope
- Single instrument (one order book)
- In-memory only (no persistence, no crash recovery)
- Single machine (no network, no client-server split)
- Core price-time-priority matching of **limit orders** only
- Two engines (lock-based baseline, lock-free) compared with JMH
- Multi-threaded client simulator
- Correctness parity check between the two engines

## Out of scope (mention as future work in the report, do not build)
- Multiple instruments
- Network protocol (e.g. FIX), client/server separation
- Persistence and crash recovery
- Advanced order types (stop-loss, iceberg, market orders, cancel/modify)
- Regulatory / audit logging

## Sign-off

- [x] Dhakshitha S (Person A): date: ____________
- [ ] Esther Philemena S (Person B): date: ____________

(Change `[ ]` to `[x]` and add the date, then commit.)

## Future work ideas parked during the week
- (add here)
