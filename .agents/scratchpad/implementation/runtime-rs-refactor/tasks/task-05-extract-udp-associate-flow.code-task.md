---
status: completed
created: 2026-03-13
started: 2026-03-16
completed: 2026-03-16
---
# Task: Extract UDP associate flow handling

## Description
Move the UDP worker lifecycle and flow state into `runtime/udp.rs` without disturbing TCP behavior. This includes `handle_socks5_udp_associate`, `build_udp_relay_socket`, `udp_associate_loop`, `expire_udp_flows`, `UdpFlowActivationState`, the UDP packet codec, and UDP-specific cache/desync helpers.

## Background
UDP associate code at ~290 lines (runtime.rs:1368-1657 plus handler at 416) forms a mostly self-contained unit. Its worker-thread lifetime, flow map, and `udp_client_addr` pinning are tightly coupled. After routing/adaptive/retry are extracted, `handshake.rs` should delegate UDP associate handling entirely to `udp.rs`.

Key memories:
- UDP associate e2e flows are pinned to the first client socket address; vary the target port instead of opening a second socket (mem-1773398549-ec21).
- UDP expiry test can run a little over 60s because network_e2e waits up to UDP_EXPIRY_TIMEOUT=70s (mem-1773401903-619d).

## Reference Documentation
**Required:**
- Design: .agents/planning/2026-03-13-runtime-rs-refactor/design.md (udp.rs component spec)

**Additional References:**
- .agents/planning/2026-03-13-runtime-rs-refactor/requirements.md (UDP associate responsibilities at lines 96-113)
- native/rust/crates/ripdpi-runtime/src/runtime.rs

**Note:** You MUST read the design document before beginning implementation.

## Technical Requirements
1. Create `src/runtime/udp.rs` with UDP associate setup, relay worker thread, `UdpFlowActivationState`, flow expiry handling, SOCKS5 UDP packet encode/decode, UDP host-cache decision helper
2. `handshake.rs` delegates `handle_socks5_udp_associate` to `udp.rs`
3. `udp.rs` uses extracted routing/adaptive/retry/desync modules for its operations
4. Preserve per-associate UDP relay socket binding and single client address pinning
5. Preserve UDP flow activation state keyed by `(client_addr, target_addr)`
6. Preserve UDP inactivity -> retry/adaptive failure -> route advancement pipeline
7. UDP host-cache routing for eligible host sources and QUIC modes unchanged

## Dependencies
- Task 04 (routing/adaptive/retry must be extracted so UDP can depend on them)

## Implementation Approach
1. Read runtime.rs UDP sections to map all functions and their dependencies on routing/adaptive/retry
2. Move UDP functions as a closed unit to `udp.rs`
3. Update `handshake.rs` to delegate UDP associate handling
4. Add focused tests for `expire_udp_flows` covering failure-to-retry behavior
5. Add property-style tests for UDP packet codec round-trip across address families and payload sizes
6. Preserve existing e2e SOCKS5 UDP round-trip and QUIC host-routing coverage
7. Run full test suite

## Acceptance Criteria

1. **UDP module created**
   - Given `src/runtime/udp.rs`
   - When inspecting contents
   - Then all UDP associate, flow state, codec, and expiry logic is present

2. **UDP round-trip preserved**
   - Given a SOCKS5 UDP associate session
   - When sending and receiving UDP packets through the proxy
   - Then round-trip behavior is identical to before

3. **Flow expiry behavior preserved**
   - Given an unanswered UDP flow that exceeds the idle timeout
   - When expiry fires
   - Then retry/adaptive failure is recorded and route advancement occurs

4. **UDP codec round-trip**
   - Given arbitrary SOCKS5 UDP packets
   - When encoding then decoding
   - Then the round-trip produces identical packets

5. **All tests pass**
   - Given all characterization, unit, and existing tests
   - When running the full ripdpi-runtime test suite
   - Then all tests pass with zero failures

## Metadata
- **Complexity**: Medium
- **Labels**: refactor, udp, flow-state, codec
- **Required Skills**: Rust, UDP/SOCKS5 protocol, ripdpi-runtime UDP internals
