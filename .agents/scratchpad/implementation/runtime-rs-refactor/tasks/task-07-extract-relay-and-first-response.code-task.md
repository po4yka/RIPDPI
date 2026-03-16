---
status: completed
created: 2026-03-13
started: 2026-03-16
completed: 2026-03-16
---
# Task: Extract relay and first-response handling

## Description
Move the highest-risk relay logic into `runtime/relay.rs` now that all supporting modules are in place. Extract `relay`, `relay_streams`, `read_optional_first_request`, `read_first_response`, `TlsRecordTracker`, `FirstResponse`, `reconnect_target`, and the inbound/outbound copy loops.

## Background
Relay code at ~570 lines (runtime.rs:1696-2264) is the highest-risk extraction because it owns response classification, reconnect, and the steady-state hot path. It must move late (after routing, desync, adaptive, retry are all extracted) and its helpers must move together. Buffer sizes, thread counts, shutdown ordering, and session tracking must remain unchanged.

## Reference Documentation
**Required:**
- Design: .agents/planning/2026-03-13-runtime-rs-refactor/design.md (relay.rs component spec)

**Additional References:**
- .agents/planning/2026-03-13-runtime-rs-refactor/requirements.md (relay responsibilities at lines 114-132)
- native/rust/crates/ripdpi-runtime/src/runtime.rs

**Note:** You MUST read the design document before beginning implementation.

## Technical Requirements
1. Create `src/runtime/relay.rs` with first request capture, `relay`, `relay_streams`, `read_first_response`, `TlsRecordTracker`, `FirstResponse`, `reconnect_target`, `copy_inbound_half`, `copy_outbound_half`
2. Relay becomes the orchestrator over already-extracted routing and desync helpers
3. Keep buffer sizes, thread counts, shutdown ordering, and session tracking unchanged
4. Preserve first-response timeout selection and TLS partial-record tracking
5. Preserve route advancement on first-response failures and successful recovery after reconnect
6. Preserve `relay_streams` spawning two relay threads per TCP connection for inbound/outbound halves
7. Keep session tracking synchronized through `Arc<Mutex<SessionState>>`

## Dependencies
- Task 06 (desync must be extracted so relay can delegate to it)

## Implementation Approach
1. Read runtime.rs relay sections carefully, mapping all dependencies on routing/desync/adaptive/retry
2. Move `FirstResponse`, `TlsRecordTracker` first (data types)
3. Move helper functions next, updating imports to use extracted modules
4. Move `relay` and `relay_streams` last
5. Keep and expand tests around first-response timeout selection and TLS partial-record tracking
6. Add characterization for route advancement on first-response failures and recovery after reconnect
7. Rerun full `network_e2e` path and fault scenarios
8. Run full test suite

## Acceptance Criteria

1. **Relay module created**
   - Given `src/runtime/relay.rs`
   - When inspecting contents
   - Then all relay, first-response, and copy loop logic is present

2. **First-response inspection preserved**
   - Given various upstream response types (redirect, TLS alert, blockpage, silent drop)
   - When the proxy inspects the first response
   - Then classification behavior is identical to before

3. **Reconnect and recovery preserved**
   - Given a first-response failure on the initial route
   - When the proxy reconnects on an advanced route
   - Then recovery succeeds and route success is recorded

4. **Hot-path relay preserved**
   - Given steady-state TCP relay
   - When inbound/outbound copy loops run
   - Then buffer sizes, thread model, and shutdown ordering are unchanged

5. **TLS record tracking preserved**
   - Given partial TLS records across chunk boundaries
   - When `TlsRecordTracker` processes them
   - Then tracking behavior is identical to before

6. **All tests pass**
   - Given all characterization, unit, and existing tests
   - When running the full ripdpi-runtime test suite
   - Then all tests pass with zero failures

## Metadata
- **Complexity**: High
- **Labels**: refactor, relay, first-response, hot-path
- **Required Skills**: Rust, TLS record parsing, relay architecture, ripdpi-runtime internals
