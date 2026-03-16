---
status: completed
created: 2026-03-13
started: 2026-03-16
completed: 2026-03-16
---
# Task: Extract routing, adaptive, and retry glue

## Description
Separate connection orchestration from protocol parsing and relay execution by extracting routing, adaptive tuning, and retry pacing glue into `routing.rs`, `adaptive.rs`, and `retry.rs`.

## Background
Route selection (~300 lines at runtime.rs:688-991), adaptive glue (~112 lines at runtime.rs:1004-1116), retry glue (~130 lines at runtime.rs:1128-1258), and direct/upstream connect helpers (~418 lines at runtime.rs:1270-1688) form the connection orchestration layer. These wrap the pure-logic modules (`runtime_policy.rs`, `adaptive_tuning.rs`, `adaptive_fake_ttl.rs`, `retry_stealth.rs`) with runtime-specific socket, telemetry, and cache lock behavior.

Key memory: Lock ordering is retry -> adaptive (existing pattern, do not invert) (mem-1773395595-9758). `build_retry_selection_penalties` always returns `diversification_rank` for eligible groups even when cooldown fields are zero (mem-1773396927-9dac).

## Reference Documentation
**Required:**
- Design: .agents/planning/2026-03-13-runtime-rs-refactor/design.md (routing.rs, adaptive.rs, retry.rs component specs)

**Additional References:**
- .agents/planning/2026-03-13-runtime-rs-refactor/requirements.md (route selection at lines 63-79, adaptive at lines 83-95)
- native/rust/crates/ripdpi-runtime/src/runtime.rs

**Note:** You MUST read the design document before beginning implementation.

## Technical Requirements
1. Create `src/runtime/routing.rs` with `select_route*`, `connect_target*`, connect-via-group, upstream SOCKS helpers, failure classification glue, route advancement, route success persistence, cache/autolearn flush points
2. Create `src/runtime/adaptive.rs` with resolve/note adaptive fake TTL, TCP hints, UDP hints
3. Create `src/runtime/retry.rs` with retry signature building, penalty computation, retry success/failure noting, retry sleep before reconnect, candidate diversification telemetry
4. Key interface: free functions taking `&RuntimeState` -- no new trait or object wrapper
5. Preserve lock ordering: retry -> adaptive (never inverted)
6. Keep `runtime_policy.rs`, `adaptive_tuning.rs`, `adaptive_fake_ttl.rs`, `retry_stealth.rs` unchanged
7. Preserve helper call order and telemetry emission order exactly

## Dependencies
- Task 03 (handshake code must already be extracted so routing boundaries are clear)

## Implementation Approach
1. Read runtime.rs routing/adaptive/retry sections to map all functions and their cross-references
2. Extract `adaptive.rs` first (smallest, fewest dependencies)
3. Extract `retry.rs` next (thin wrapper over `retry_stealth.rs`)
4. Extract `routing.rs` last (depends on adaptive and retry)
5. Add unit tests for runtime-specific routing glue, trigger-to-failure mapping, retry signature inputs, and candidate diversification
6. Verify lock-sensitive paths by rerunning characterization tests that exercise route advance and reconnect
7. Run full test suite

## Acceptance Criteria

1. **Routing module created**
   - Given `src/runtime/routing.rs`
   - When inspecting contents
   - Then route selection, connection, advancement, and cache flush logic is present

2. **Adaptive module created**
   - Given `src/runtime/adaptive.rs`
   - When inspecting contents
   - Then adaptive resolve/note wrappers for fake TTL, TCP hints, and UDP hints are present

3. **Retry module created**
   - Given `src/runtime/retry.rs`
   - When inspecting contents
   - Then retry signature building, penalty computation, and pacing glue is present

4. **Lock ordering preserved**
   - Given code paths that acquire both retry and adaptive locks
   - When inspecting lock acquisition order
   - Then retry lock is always acquired before adaptive lock

5. **Telemetry order preserved**
   - Given route selection, advancement, and pacing operations
   - When comparing telemetry emission order to the original
   - Then order is identical

6. **All tests pass**
   - Given all characterization, unit, and existing tests
   - When running the full ripdpi-runtime test suite
   - Then all tests pass with zero failures

## Metadata
- **Complexity**: High
- **Labels**: refactor, routing, adaptive, retry, locking
- **Required Skills**: Rust, mutex ordering, ripdpi-runtime routing internals
