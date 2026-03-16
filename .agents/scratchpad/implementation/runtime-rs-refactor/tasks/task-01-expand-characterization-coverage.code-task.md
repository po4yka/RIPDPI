---
status: completed
created: 2026-03-13
started: 2026-03-13
completed: 2026-03-13
---
# Task: Expand characterization coverage before moving code

## Description
Lock the current runtime behavior in tests before any extraction begins. Add missing behavior-focused tests around the existing monolith, prioritizing runtime entry points and invariants that will be hardest to reason about after code movement.

## Background
`runtime.rs` is 2870 lines mixing listener lifecycle, handshake parsing, route orchestration, adaptive/retry glue, UDP flow handling, TCP relay logic, and desync execution. The existing test baseline includes unit tests in `runtime::tests` and `network_e2e` integration tests. This step extends that baseline to freeze observable behavior before any structural changes.

Key memories:
- Delayed-connect SOCKS/TCP flows bypass `connect_target` and do not emit `on_route_selected` telemetry; characterize payload-driven route choice through downstream signals like `host_autolearn`/`host_promoted` (mem-1773400933-ddf9).
- `network_e2e` tests share one global runtime telemetry slot; starting a second proxy replaces the previous sink (mem-1773400517-b6cc).
- A TcpReset fixture reaches first-response classification as SilentDrop because the proxy observes EOF on the first upstream read; recoverable first-response route-advance tests should pair that fault with DETECT_SILENT_DROP (mem-1773400517-b6c4).
- UDP associate e2e flows are pinned to the first client socket address; to characterize sibling UDP flows, reuse one UDP socket and vary the target port (mem-1773398549-ec21).

## Reference Documentation
**Required:**
- Design: .agents/planning/2026-03-13-runtime-rs-refactor/design.md

**Additional References:**
- .agents/planning/2026-03-13-runtime-rs-refactor/requirements.md (verified responsibilities and risk areas)
- .agents/planning/2026-03-13-runtime-rs-refactor/implementation-plan.md (overall strategy)
- native/rust/crates/ripdpi-runtime/tests/network_e2e.rs (existing e2e harness)
- native/rust/crates/ripdpi-runtime/src/runtime.rs (monolith under test)

**Note:** You MUST read the design document before beginning implementation.

## Technical Requirements
1. Add characterization tests for protocol reply bytes and delayed-connect behavior
2. Add targeted tests for route advancement telemetry on recoverable failures
3. Add targeted tests for UDP associate state transitions and host-aware routing
4. Add property-style tests for pure invariants: UDP packet codec round-trip and TLS record chunk splitting
5. All tests MUST pass against the unchanged `runtime.rs`
6. Use existing `network_e2e` fixtures and `TEST_LOCK` serialization; do not introduce new test-only internals

## Dependencies
- None (first step)

## Implementation Approach
1. Read the existing `runtime::tests` and `network_e2e` test files to understand current coverage
2. Identify gaps against the requirements (protocol replies, delayed-connect, route advancement, UDP flows, pure invariants)
3. Write failing characterization tests one category at a time
4. Verify each test passes against the unchanged monolith before adding the next
5. Run `cargo test -p ripdpi-runtime --manifest-path native/rust/Cargo.toml` to confirm full baseline is green

## Acceptance Criteria

1. **Protocol reply characterization**
   - Given the unchanged runtime.rs
   - When SOCKS5 TCP, HTTP CONNECT, and Shadowsocks handshakes complete
   - Then reply byte sequences and error codes match the current implementation

2. **Delayed-connect behavior**
   - Given a config with payload-aware routing enabled
   - When a SOCKS5 client connects with delay_conn
   - Then the proxy replies before reading the first payload, and downstream route signals are observable

3. **Route advancement on recoverable failure**
   - Given a first-response failure classified as SilentDrop
   - When the proxy reconnects on the advanced route
   - Then the retried flow succeeds and route-advance telemetry is emitted

4. **UDP associate state transitions**
   - Given a SOCKS5 UDP associate session
   - When multiple flows target different ports from the same client socket
   - Then each flow tracks activation state independently and host-aware routing applies for QUIC

5. **Pure invariant property tests**
   - Given arbitrary SOCKS5 UDP packets across representative address families
   - When encode then decode is applied
   - Then the round-trip produces identical packets

6. **Full baseline green**
   - Given all new and existing tests
   - When running the full ripdpi-runtime test suite
   - Then all tests pass with zero failures

## Metadata
- **Complexity**: High
- **Labels**: testing, characterization, runtime, safety-net
- **Required Skills**: Rust testing, network protocol knowledge, ripdpi-runtime internals
