---
title: Implement SYN-ACK interception via TUN for synack and synack_split strategies
type: task
status: backlog
area: vpn
priority: medium
owner: unassigned
parent: zapret2-feature-parity-epic
blocks: []
blocked_by: [add-ripdpi-strategy-trait-crate]
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Implement SYN-ACK interception via TUN for synack and synack_split strategies #repo/RIPDPI #area/vpn #status/backlog 🔼

## Objective

Implement `synack` and `synack_split` strategies that intercept the server's SYN-ACK packet as it arrives through the TUN device and either resend it with a modified window/TTL or split it across two injected packets before the TCP handshake completes. These manipulate the DPI's view of the handshake from the server side.

## Context

In zapret2 (`/Users/po4yka/GitRep/zapret2/lua/zapret-antidpi.lua` — `synack` and `synack_split` functions), SYN-ACK manipulation works because zapret2 sits in the kernel packet path via NFQUEUE and can modify or drop/resend any packet. On Android with Mode.VPN, RIPDPI owns the TUN fd and reads every IP packet before it reaches the TCP stack. The synack strategy reads incoming TCP SYN-ACK packets from the TUN, holds them briefly (deferred packet queue), sends a modified version (lower TTL, or with window override), then delivers the original. The `synack_split` variant sends two SYN-ACK packets in rapid succession — one fake and one real — to desync the DPI.

A new `TunIngressInterceptor` component sits in the Mode.VPN packet-reading loop in `ripdpi-tunnel-*/`. It receives raw IP packets from TUN before forwarding them to the internal TCP stack. For packets matching `TCP SYN-ACK` flag (flags byte: ACK=1, SYN=1), it passes the packet to the registered `SynAckStrategy`. The strategy can:

1. `synack(ttl)`: Send a copy with low TTL via raw socket (dies at DPI, real packet goes to app), then forward original.
2. `synack_split`: Send fake SYN-ACK with spoofed sequence number, then real SYN-ACK — DPI sees two conflicting SYN-ACKs.

Both require a raw socket for injection (Tier 1), but the interception itself only requires Mode.VPN (Tier 3).

## Acceptance criteria

- [ ] `TunIngressInterceptor` is inserted into the Mode.VPN TUN read loop without breaking normal packet forwarding
- [ ] `SynAckStrategy` correctly identifies TCP SYN-ACK packets (flags: SYN+ACK, no FIN/RST) in raw IPv4 and IPv6 packets
- [ ] `synack(ttl: u8)` sends a low-TTL copy and then forwards the original to the app stack — verified: app TCP handshake still completes
- [ ] `synack_split` sends a fake SYN-ACK (modified sequence number) followed by the real one — verified: connection still establishes
- [ ] Injection raw socket is VPN-protected (calls VpnProtect JNI/Unix-socket mechanism)
- [ ] Strategies degrade to no-op (pass packet through unchanged) when raw socket is unavailable
- [ ] YAML config accepts `type: synack` with `ttl: 5`; `type: synack_split`
- [ ] No regression in existing Mode.VPN packet forwarding benchmarks

## Source references

- zapret2 Lua: `/Users/po4yka/GitRep/zapret2/lua/zapret-antidpi.lua` — `synack`, `synack_split`
- zapret2 darkmagic (synack detection): `/Users/po4yka/GitRep/zapret2/nfq2/darkmagic.c` — `tcp_synack_only()` detector
- RIPDPI TUN tunnel: `native/rust/crates/` — search for tunnel-related crates (ripdpi-tunnel-*)
- RIPDPI fake packet (for injection pattern): `native/rust/crates/ripdpi-privileged-ops/src/linux/raw_packet/fake_tcp.rs`
- RIPDPI VPN socket protection: the VPN protect JNI callback must be called on the raw injection socket

## TDD workflow

1. **Write tests first** — before any implementation code, write integration tests using a TUN loopback fixture that sends synthetic IP packets and verifies the interceptor's output.
2. **Confirm red** — run the integration test and confirm it fails because `TunIngressInterceptor` does not exist.
3. **Implement** — build the interceptor and strategy to make the failing tests pass.
4. **Confirm green** — run the full test suite; existing Mode.VPN forwarding tests pass unchanged.
5. **Refactor** — clean up while keeping tests green.

**Test files to create before implementation:**
- `native/rust/crates/ripdpi-tunnel-strategy/tests/synack_detection.rs` — feed raw IPv4 SYN-ACK bytes (SYN+ACK flags set) into `TunIngressInterceptor`; assert the interceptor identifies it as a SYN-ACK (returns true from `is_synack()`); fails until detector exists
- `native/rust/crates/ripdpi-tunnel-strategy/tests/non_synack_passthrough.rs` — feed a plain ACK packet; assert interceptor forwards it unmodified without calling any strategy; fails if interceptor incorrectly matches
- `native/rust/crates/ripdpi-tunnel-strategy/tests/synack_strategy_emits_two_packets.rs` — apply `synack(ttl=5)` to a known SYN-ACK packet; assert exactly two packets are produced on the raw socket mock (one modified low-TTL, one original); fails until strategy logic is implemented
- `native/rust/crates/ripdpi-tunnel-strategy/tests/synack_split_emits_two_packets.rs` — apply `synack_split` to a SYN-ACK; assert two packets emitted with differing sequence numbers; fails until split logic is implemented
- `native/rust/crates/ripdpi-tunnel-strategy/tests/vpn_protect_called.rs` — mock the VpnProtect callback; assert it is called exactly once when the raw injection socket is created; fails until VPN protection is wired in
- `native/rust/crates/ripdpi-tunnel-strategy/tests/noop_without_raw_socket.rs` — configure mock capabilities with `raw_socket: false`; assert strategy degrades to passthrough (no crash, SYN-ACK forwarded unchanged)

## Definition of done

Integration test with a controlled TUN loopback: send a SYN-ACK packet in, verify `synack` emits two packets (modified + original) on the raw socket path. Tests were written and confirmed red before implementation began; the relevant test command is green with no regressions.
