---
title: Add TCP window size clamping strategies
type: task
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: zapret2-feature-parity-epic
blocks: []
blocked_by: [expose-existing-techniques-as-config-addressable]
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Add TCP window size clamping strategies #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Objective

Implement `wsize` and `wssize` TCP window size manipulation strategies in a new `ripdpi-strategy-window` crate. `wsize` clamps the window advertised in the initial SYN; `wssize` sets the scale factor. These force the server to send smaller TCP segments, which in turn forces the DPI to process smaller units that may bypass pattern matching.

## Context

In zapret2 (`/Users/po4yka/GitRep/zapret2/lua/zapret-antidpi.lua` — `wsize` and `wssize` functions), these work by modifying the TCP SYN packet's window field and TCP Window Scale option (RFC 7323) before forwarding through NFQUEUE. On Android without root (Tier 0 path), the equivalent is setting `SO_RCVBUF` on the local-side socket before the three-way handshake — the kernel derives the advertised window from the receive buffer size. The Tier 1 path (raw socket available) allows direct modification of the outgoing SYN packet's window field via raw socket write.

Implementation tiers:

- **Tier 0 (non-root):** Set `SO_RCVBUF` on the client-side socket before connect via `setsockopt()`. This influences the window advertised in SYN. Effective for many kernels but not a precise window field control.
- **Tier 1 (raw socket):** Intercept the outgoing SYN (via TUN read in Mode.VPN), rewrite the TCP window field and TCP Window Scale option value, reinject via TUN write with correct checksum. Requires Mode.VPN (Tier 3 as defined in the architecture).
- **Tier 2 (preferred non-root):** Use `TCP_WINDOW_CLAMP` socket option (`setsockopt(fd, IPPROTO_TCP, TCP_WINDOW_CLAMP, &value, sizeof(value))`) — available without root on Android, sets the maximum receive window the stack will advertise.

Parameters:

- `wsize(n)`: set window to n bytes (1-65535). Common effective values from zapret2 usage: 1, 2, 4 (forces tiny segments from server).
- `wssize(n, scale)`: set window size to n with scale factor. Effective window = n << scale.

## Acceptance criteria

- [ ] `ripdpi-strategy-window` compiles with `wsize` and `wssize` strategy structs implementing `DesyncStrategy`
- [ ] Tier 0 path uses `TCP_WINDOW_CLAMP` setsockopt (verify: `setsockopt(fd, IPPROTO_TCP, 10 /*TCP_WINDOW_CLAMP*/, ...)` — constant value 10 on Linux/Android)
- [ ] `TCP_WINDOW_CLAMP` availability is detected at runtime via `ripdpi-capabilities` and reported in `describe().required_capabilities`
- [ ] `wsize` and `wssize` are registered with IDs `"wsize"` and `"wssize"` in `StrategyRegistry`
- [ ] YAML config accepts `type: wsize` with `value: 4` param; `type: wssize` with `size: 64` and `scale: 2`
- [ ] Unit test: `wsize(4)` strategy applied before connect results in TCP_WINDOW_CLAMP = 4 on the socket (verify via `getsockopt`)
- [ ] Strategy gracefully no-ops (returns `Ok(())`, logs warning) when `TCP_WINDOW_CLAMP` is unavailable

## Source references

- zapret2 Lua: `/Users/po4yka/GitRep/zapret2/lua/zapret-antidpi.lua` — `wsize`, `wssize` functions
- RIPDPI socket operations: `native/rust/crates/ripdpi-privileged-ops/src/linux.rs` — existing socket option setters to extend
- RIPDPI capabilities: `native/rust/crates/ripdpi-capabilities/` — add `TCP_WINDOW_CLAMP` detection

## TDD workflow

1. **Write tests first** — before any implementation code, write tests that verify the socket option is applied correctly and that capability detection works.
2. **Confirm red** — run `cargo test -p ripdpi-strategy-window` and confirm tests fail logically.
3. **Implement** — write the setsockopt calls and capability detection to make the failing tests pass.
4. **Confirm green** — run the full crate test suite; zero regressions.
5. **Refactor** — clean up while keeping tests green.

**Test files to create before implementation:**
- `native/rust/crates/ripdpi-strategy-window/tests/wsize_applies_sockopt.rs` — create a TCP socket, apply `wsize(4)` strategy, call `getsockopt(TCP_WINDOW_CLAMP)`, assert value is 4; fails until `setsockopt` call is implemented (requires running on Linux/Android)
- `native/rust/crates/ripdpi-strategy-window/tests/wssize_applies_sockopt.rs` — same pattern for `wssize(64, 2)`; assert clamp value reflects size × scale; fails until implemented
- `native/rust/crates/ripdpi-strategy-window/tests/capability_detection.rs` — mock `Capabilities` with `tcp_window_clamp: false`; assert strategy `plan()` returns `Ok(())` and logs a warning without panicking (graceful degradation); fails until capability check is implemented
- `native/rust/crates/ripdpi-strategy-window/tests/strategy_registration.rs` — assert `registry.get("wsize")` and `registry.get("wssize")` return `Some` after registration; assert YAML `type: wsize` with `value: 4` resolves correctly
- `native/rust/crates/ripdpi-strategy-window/tests/yaml_param_parsing.rs` — parse `type: wssize` with `size: 64, scale: 2`; assert correct `WssizeParams` are produced; fails until param deserialization exists

## Definition of done

`cargo test -p ripdpi-strategy-window` green; `wsize` selectable from YAML config and verified via `getsockopt` in integration test. Tests were written and confirmed red before implementation began; the relevant test command is green with no regressions.
