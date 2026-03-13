---
status: completed
created: 2026-03-13
started: 2026-03-13
completed: 2026-03-13
---
# Task: Extract protocol dispatch and handshake helpers

## Description
Move protocol-specific client handling into `runtime/handshake.rs` without changing handshake bytes or timeout behavior. This includes `handle_client`, protocol handlers, request readers, `resolve_name`, `HandshakeKind`, `DelayConnect`, and `maybe_delay_connect`.

## Background
Handshake code at ~445 lines (runtime.rs:242-687) mixes protocol dispatch, byte parsing, delayed connect logic, and domain resolution. Extracting it isolates protocol semantics from listener setup while keeping parsing helpers close to their handlers.

Key memory: `handshake::resolve_name` is needed by the inline SOCKS5 UDP/domain parser path until UDP extraction in Step 5; preserve that shared seam (mem-1773404145-1c88).

## Reference Documentation
**Required:**
- Design: .agents/planning/2026-03-13-runtime-rs-refactor/design.md (handshake.rs component spec)

**Additional References:**
- .agents/planning/2026-03-13-runtime-rs-refactor/requirements.md (protocol dispatch responsibilities at lines 41-61)
- native/rust/crates/ripdpi-runtime/src/runtime.rs (lines 242-687)

**Note:** You MUST read the design document before beginning implementation.

## Technical Requirements
1. Create `src/runtime/handshake.rs` with all protocol dispatch and parsing code
2. Move: `handle_client`, `handle_transparent`, `handle_socks4`, `handle_socks5`, `handle_http_connect`, `handle_shadowsocks`, `handle_socks5_udp_associate`, request parsing helpers, `resolve_name`, `HandshakeKind`, `DelayConnect`, `maybe_delay_connect`
3. Key interface: `pub(super) fn handle_client(client: TcpStream, state: &RuntimeState) -> io::Result<()>`
4. Keep `resolve_name` accessible to the UDP path that will be extracted in Step 5
5. Preserve handshake read/write deadlines, protocol selection logic, and reply encoding exactly
6. Preserve `delay_conn` semantics: reply before first payload only when payload-aware routing requires it

## Dependencies
- Task 02 (module scaffold and state types must exist)

## Implementation Approach
1. Read runtime.rs lines 242-687 to identify all handshake functions and their internal dependencies
2. Move functions as a unit to `handshake.rs`, keeping byte parsing helpers adjacent to their protocol handlers
3. Update `listeners.rs` to call `handshake::handle_client` instead of the local function
4. Verify `resolve_name` is `pub(super)` so UDP code can use it
5. Run characterization tests for protocol replies and delayed-connect behavior
6. Run full `network_e2e` suite for SOCKS5 TCP, HTTP CONNECT, and chained upstream tests

## Acceptance Criteria

1. **Handshake module created**
   - Given the refactor
   - When inspecting `src/runtime/handshake.rs`
   - Then all protocol dispatch, parsing, and delayed-connect code is present

2. **Protocol semantics preserved**
   - Given SOCKS5, HTTP CONNECT, SOCKS4, and Shadowsocks clients
   - When completing handshakes through the proxy
   - Then reply bytes and error codes are identical to before

3. **Delayed-connect behavior preserved**
   - Given payload-aware routing configuration
   - When a delayed-connect client connects
   - Then the proxy replies before reading the first payload

4. **resolve_name accessible**
   - Given `handshake.rs`
   - When UDP extraction needs `resolve_name` in Step 5
   - Then it is accessible via `pub(super)` visibility

5. **All tests pass**
   - Given all characterization and existing tests
   - When running the full ripdpi-runtime test suite
   - Then all tests pass with zero failures

## Metadata
- **Complexity**: High
- **Labels**: refactor, handshake, protocol-dispatch
- **Required Skills**: Rust, SOCKS5/HTTP CONNECT protocol knowledge, ripdpi-runtime internals
