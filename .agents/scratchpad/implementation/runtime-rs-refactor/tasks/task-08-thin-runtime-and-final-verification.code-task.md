---
status: completed
created: 2026-03-13
started: 2026-03-16
completed: 2026-03-16
---
# Task: Thin runtime.rs to public API wiring and run final verification

## Description
Finish the decomposition by removing remaining moved helpers from `runtime.rs`, keeping only module declarations plus the public API entry points. Make internal visibility explicit with `pub(super)` where possible. Review for leftover duplicated helpers or awkward cross-module imports. Run comprehensive final verification.

## Background
After Steps 2-7, most code has been extracted into internal modules. This final step ensures `runtime.rs` is a thin entry layer with no business logic, cleans up any remaining artifacts, and runs the full verification suite to confirm the refactor preserves all behavior.

## Reference Documentation
**Required:**
- Design: .agents/planning/2026-03-13-runtime-rs-refactor/design.md (runtime.rs final state)

**Additional References:**
- .agents/planning/2026-03-13-runtime-rs-refactor/implementation-plan.md (Step 8 details)
- .agents/planning/2026-03-13-runtime-rs-refactor/requirements.md (public API surface)

**Note:** You MUST read the design document before beginning implementation.

## Technical Requirements
1. Remove all remaining moved helpers from `runtime.rs`
2. Keep only: module declarations, public API entry points (`run_proxy`, `create_listener`, `run_proxy_with_listener`, `run_proxy_with_embedded_control`)
3. Make internal visibility explicit with `pub(super)` where possible
4. Review and eliminate duplicated helpers or awkward cross-module imports
5. Run full `ripdpi-runtime` unit suite
6. Run key `network_e2e` coverage
7. If feasible, run ignored soak coverage as a final confidence pass
8. Verify `runtime.rs` is thin and readable (target: <200 lines)

## Dependencies
- Task 07 (all extraction steps must be complete)

## Implementation Approach
1. Audit `runtime.rs` for any remaining non-wiring code
2. Move remaining helpers to their appropriate modules
3. Clean up imports and visibility across all runtime modules
4. Remove any dead code or unused imports
5. Run `cargo test -p ripdpi-runtime --manifest-path native/rust/Cargo.toml` (full unit suite)
6. Run `cargo test -p ripdpi-runtime --test network_e2e --manifest-path native/rust/Cargo.toml` (e2e coverage)
7. Run `cargo clippy -p ripdpi-runtime --manifest-path native/rust/Cargo.toml` and `cargo fmt --check -p ripdpi-runtime --manifest-path native/rust/Cargo.toml`
8. Optionally run soak tests if time permits

## Acceptance Criteria

1. **runtime.rs is thin**
   - Given the final `runtime.rs`
   - When inspecting its contents
   - Then it contains only module declarations and public API entry points (<200 lines)

2. **No business logic in runtime.rs**
   - Given the final `runtime.rs`
   - When searching for protocol handling, routing, relay, desync, or UDP logic
   - Then none is found

3. **All modules in place**
   - Given `src/runtime/`
   - When listing files
   - Then `mod.rs`, `state.rs`, `listeners.rs`, `handshake.rs`, `routing.rs`, `adaptive.rs`, `retry.rs`, `udp.rs`, `relay.rs`, `desync.rs` all exist

4. **Public API unchanged**
   - Given the refactored codebase
   - When calling the four public API functions
   - Then signatures, behavior, and error handling are identical to the original

5. **Full test suite passes**
   - Given all unit, characterization, and integration tests
   - When running the complete ripdpi-runtime test suite
   - Then all tests pass with zero failures

6. **Code quality gates pass**
   - Given the refactored codebase
   - When running clippy and rustfmt
   - Then zero warnings and formatting is clean

## Metadata
- **Complexity**: Medium
- **Labels**: refactor, cleanup, verification, final
- **Required Skills**: Rust, code review, ripdpi-runtime architecture
