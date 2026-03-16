---
status: completed
created: 2026-03-13
started: 2026-03-16
completed: 2026-03-16
---
# Task: Extract desync execution helpers

## Description
Isolate TCP and UDP desync execution into `runtime/desync.rs` so the relay code no longer owns plan execution details. Move `activation_context_from_progress`, `send_with_group`, `should_desync_tcp`, `has_tcp_actions`, `requires_special_tcp_execution`, `execute_tcp_actions`, `execute_tcp_plan`, `execute_udp_actions`, `send_out_of_band`, `set_stream_ttl`, and UDP TTL helpers.

## Background
Desync execution code at ~340 lines (runtime.rs:2265-2646) handles TCP/UDP desync plan construction and execution using `ciadpi_desync`. It includes platform-specific syscalls (TTL manipulation, urgent writes, MD5SIG) that are brittle and should stay as direct platform calls, not abstractions.

## Reference Documentation
**Required:**
- Design: .agents/planning/2026-03-13-runtime-rs-refactor/design.md (desync.rs component spec)

**Additional References:**
- .agents/planning/2026-03-13-runtime-rs-refactor/requirements.md (desync responsibilities at lines 133-148)
- native/rust/crates/ripdpi-runtime/src/runtime.rs

**Note:** You MUST read the design document before beginning implementation.

## Technical Requirements
1. Create `src/runtime/desync.rs` with all desync execution helpers
2. Move: `activation_context_from_progress`, `send_with_group`, `should_desync_tcp`, `has_tcp_actions`, `requires_special_tcp_execution`, `execute_tcp_actions`, `execute_tcp_plan`, `execute_udp_actions`, `send_out_of_band`, `set_stream_ttl`, UDP TTL helpers
3. Keep direct platform calls (no abstraction layers over syscalls)
4. Preserve fallback-to-raw-write behavior on plan construction failure
5. Preserve default TTL restore behavior
6. Relay and UDP modules now call into desync executor

## Dependencies
- Task 05 (UDP must be extracted so desync boundaries are clear and UDP desync helpers have a consumer)

## Implementation Approach
1. Read runtime.rs desync sections to map all functions and platform dependencies
2. Move functions as a unit to `desync.rs`
3. Update relay and UDP modules to import from `desync`
4. Preserve current helper tests for desync action gating and special TCP execution detection
5. Add focused tests around plan-bound validation and fallback-to-raw-write behavior where practical
6. Rerun e2e TCP flows that exercise desync-capable groups
7. Run full test suite

## Acceptance Criteria

1. **Desync module created**
   - Given `src/runtime/desync.rs`
   - When inspecting contents
   - Then all desync execution helpers and platform calls are present

2. **TCP desync preserved**
   - Given a configuration with TCP desync actions
   - When TCP flows exercise desync-capable groups
   - Then desync execution behavior is identical to before

3. **UDP desync preserved**
   - Given UDP flows through desync-capable groups
   - When UDP desync actions fire
   - Then TTL manipulation and execution behavior is unchanged

4. **Fallback behavior preserved**
   - Given a plan construction failure
   - When `send_with_group` falls back
   - Then raw `write_all` is used as before

5. **All tests pass**
   - Given all characterization, unit, and existing tests
   - When running the full ripdpi-runtime test suite
   - Then all tests pass with zero failures

## Metadata
- **Complexity**: Medium
- **Labels**: refactor, desync, platform-calls
- **Required Skills**: Rust, platform syscalls, desync plan execution
