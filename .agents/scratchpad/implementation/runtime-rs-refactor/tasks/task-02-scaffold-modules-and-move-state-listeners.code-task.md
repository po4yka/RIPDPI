---
status: completed
created: 2026-03-13
started: 2026-03-13
completed: 2026-03-13
---
# Task: Introduce internal module scaffold and move state/listener primitives

## Description
Create the internal `runtime/` module structure and move low-risk shared types first: `RuntimeState`, `RuntimeCleanup`, `ClientSlotGuard`, shared constants, `build_listener`, and the accept-loop implementation. Keep the public API in `runtime.rs` forwarding into the extracted modules.

## Background
This step creates the module skeleton that all later extractions target. By moving only shared state types and the listener lifecycle first, risk is minimal. The public API remains in `runtime.rs` and calls into `listeners::run_proxy_with_listener_internal`.

## Reference Documentation
**Required:**
- Design: .agents/planning/2026-03-13-runtime-rs-refactor/design.md (state.rs and listeners.rs component specs)

**Additional References:**
- .agents/planning/2026-03-13-runtime-rs-refactor/implementation-plan.md (Step 2 details)
- native/rust/crates/ripdpi-runtime/src/runtime.rs (source of extraction)

**Note:** You MUST read the design document before beginning implementation.

## Technical Requirements
1. Create `src/runtime/mod.rs` with module declarations
2. Create `src/runtime/state.rs` with `RuntimeState`, `RuntimeCleanup`, `ClientSlotGuard`, shared constants (handshake timeout, UDP idle timeout)
3. Create `src/runtime/listeners.rs` with `build_listener`, nonblocking configuration, `mio::Poll` accept loop, telemetry lifecycle events, client thread spawning, shutdown polling
4. Key interface: `pub(super) fn run_proxy_with_listener_internal(...)` in listeners.rs
5. `runtime.rs` keeps only public API functions + `mod runtime` declaration + forwarding calls
6. Use `pub(super)` visibility for extracted items; avoid leaking internals beyond the runtime module
7. All Step 1 characterization tests must continue to pass

## Dependencies
- Task 01 (characterization coverage must be in place as safety net)

## Implementation Approach
1. Read `runtime.rs` to identify exact boundaries of state types and listener code
2. Create `src/runtime/mod.rs` with `pub(super)` re-exports
3. Move `RuntimeState`, `RuntimeCleanup`, `ClientSlotGuard`, and constants to `state.rs`
4. Move `build_listener`, accept loop, and thread spawning to `listeners.rs`
5. Update `runtime.rs` to import from the new modules and forward public API calls
6. Run characterization tests after each move to catch breakage immediately
7. Run `cargo test -p ripdpi-runtime --manifest-path native/rust/Cargo.toml` for full suite

## Acceptance Criteria

1. **Module scaffold exists**
   - Given the refactor is applied
   - When inspecting `src/runtime/`
   - Then `mod.rs`, `state.rs`, and `listeners.rs` exist with correct module declarations

2. **State types extracted**
   - Given `state.rs`
   - When inspecting its contents
   - Then `RuntimeState`, `RuntimeCleanup`, `ClientSlotGuard`, and shared constants are defined there

3. **Listener logic extracted**
   - Given `listeners.rs`
   - When inspecting its contents
   - Then `build_listener`, accept loop, thread spawning, and shutdown polling are present

4. **Public API unchanged**
   - Given the refactored codebase
   - When calling `run_proxy`, `create_listener`, `run_proxy_with_listener`, `run_proxy_with_embedded_control`
   - Then signatures and behavior are identical to before

5. **All tests pass**
   - Given all characterization and existing tests
   - When running the full ripdpi-runtime test suite
   - Then all tests pass with zero failures

## Metadata
- **Complexity**: Medium
- **Labels**: refactor, module-scaffold, state, listeners
- **Required Skills**: Rust module system, visibility rules, ripdpi-runtime internals
