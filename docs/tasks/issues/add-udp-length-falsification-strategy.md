---
title: Add UDP length falsification strategy
type: task
status: review
area: rust-native
priority: medium
owner: unassigned
parent: zapret2-feature-parity-epic
blocks: []
blocked_by: [expose-existing-techniques-as-config-addressable]
created: 2026-05-09
updated: 2026-05-10
---

- [ ] #task Add UDP length falsification strategy #repo/RIPDPI #area/rust-native #status/review 🔼

## Objective

Implement the `udplen` strategy in a new `ripdpi-strategy-udp` crate. This sets the UDP header's length field to a value larger than the actual payload, confusing DPI systems that use the UDP length field for protocol fingerprinting (particularly QUIC classifiers).

## Context

In zapret2 (`/Users/po4yka/GitRep/zapret2/lua/zapret-antidpi.lua` — `udplen` function, and zapret2's nfq2 UDP handling), `udplen` modifies the UDP length field in the outgoing packet header without changing the actual payload. The IP total length remains correct; only the UDP-internal length field is falsified. On Android in Mode.VPN, packets flow through the TUN fd — the app reads a raw IP packet from TUN, modifies the UDP length field bytes at the correct offset (IP header size + 4 bytes for src/dst port = UDP length field at IP_hdr_len + 4), recalculates the UDP checksum (or zeros it for IPv4 where UDP checksum is optional), and writes the modified packet back to TUN.

Implementation:

```rust
// UDP header: src_port(2) dst_port(2) length(2) checksum(2)
// length field offset from start of UDP header: 4 bytes
// Modify: udp_length_field = actual_payload_length + delta (typical delta: 2-8 bytes)
// Recalculate UDP checksum or set to 0 (IPv4 only; IPv6 requires valid checksum)
```

This is a TUN-level packet transform, not a socket option. It runs in the Mode.VPN packet processing loop, applied to outgoing QUIC/UDP packets matching the strategy filter.

Parameters:

- `delta: i16` — amount to add to the UDP length field (positive or negative). Typical effective range: 2-8.

## Acceptance criteria

- [ ] `ripdpi-strategy-udp` compiles; `UdpLenStrategy` implements `DesyncStrategy`
- [ ] `matches()` returns true only for `L7Protocol::Quic` or `L7Protocol::Unknown` on UDP port (checked via `dissect.src_port` / `dissect.dst_port`)
- [ ] UDP length field is incremented by `delta` bytes in the outgoing packet
- [ ] UDP checksum is set to 0 for IPv4 (valid per RFC 768), recalculated correctly for IPv6
- [ ] Packet total length (IP header) is NOT modified — only the UDP internal length field changes
- [ ] Strategy requires Mode.VPN active (Tier 3 — TUN packet access); degrades to no-op with warning in Mode.Proxy
- [ ] YAML config accepts `type: udplen` with `delta: 4`
- [ ] Unit test: given a known UDP packet byte array, `udplen(4)` produces correct modified bytes (golden test against known output)

## Source references

- zapret2 Lua: `/Users/po4yka/GitRep/zapret2/lua/zapret-antidpi.lua` — `udplen` function
- zapret2 UDP processing: `/Users/po4yka/GitRep/zapret2/nfq2/darkmagic.h` — raw packet manipulation utilities
- RIPDPI TUN processing: `native/rust/crates/ripdpi-tunnel-*/` — TUN read/write loop to hook into
- RIPDPI fake packet building: `native/rust/crates/ripdpi-privileged-ops/src/linux/raw_packet/` — packet building utilities to reuse for checksum calculation

## TDD workflow

1. **Write tests first** — before any implementation code, write a golden byte test with a known UDP packet input and the exact expected modified output.
2. **Confirm red** — run `cargo test -p ripdpi-strategy-udp` and confirm the golden test fails because the modifier doesn't exist.
3. **Implement** — write the packet modifier to produce the exact expected bytes.
4. **Confirm green** — run the full crate test suite; zero regressions.
5. **Refactor** — clean up while keeping tests green.

**Test files to create before implementation:**
- `native/rust/crates/ripdpi-strategy-udp/tests/udplen_golden.rs` — construct a known raw IPv4+UDP packet bytes (hardcoded); apply `udplen(delta=4)`; assert the UDP length field at offset `ip_hdr_len + 4` is incremented by 4; assert IP total length is unchanged; fails until modifier is implemented
- `native/rust/crates/ripdpi-strategy-udp/tests/udplen_ipv4_checksum.rs` — after applying `udplen`, assert UDP checksum field is 0 (IPv4 optional per RFC 768); fails until checksum zeroing is implemented
- `native/rust/crates/ripdpi-strategy-udp/tests/udplen_ipv6_checksum.rs` — for IPv6 input, apply `udplen`, assert UDP checksum is recalculated (non-zero and valid); fails until IPv6 checksum computation is implemented
- `native/rust/crates/ripdpi-strategy-udp/tests/matches_quic_only.rs` — assert `UdpLenStrategy.matches()` returns true for `L7Protocol::Quic` and false for `L7Protocol::Tls`; fails until `matches()` is implemented
- `native/rust/crates/ripdpi-strategy-udp/tests/noop_in_proxy_mode.rs` — assert strategy `plan()` returns `Ok(())` with no actions added when `caps.vpn_mode == false`; fails until capability check is added

## Definition of done

`cargo test -p ripdpi-strategy-udp` green including golden byte test; `udplen` registered and selectable from YAML. Tests were written and confirmed red before implementation began; the relevant test command is green with no regressions.

## Work log

- Added `ripdpi-strategy-udp` with `UdpLenStrategy`, raw IPv4/IPv6 UDP length-field mutation, IPv4 checksum clearing, and IPv6 UDP checksum recalculation.
- Registered `udplen` through `StrategyRegistry::with_builtin_techniques()` and covered YAML `type: udplen` with `delta`.
- Verification: `CARGO_TARGET_DIR=target/codex-udp cargo test -p ripdpi-strategy-udp -p ripdpi-strategy-registry -p ripdpi-strategy-config --locked`; `CARGO_TARGET_DIR=target/codex-udp cargo clippy -p ripdpi-strategy-udp -p ripdpi-strategy-registry -p ripdpi-strategy-config --all-targets --locked -- -D warnings`.
- Added Android tunnel JNI evidence that `Tun2SocksConfig.strategyChainYaml` accepts `type: udplen` with `delta: 4` alongside zapret egress entries.
- Verification: clean detached worktree `ANDROID_HOME=$HOME/Library/Android/sdk ANDROID_SDK_ROOT=$HOME/Library/Android/sdk ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.poyka.ripdpi.integration.NativeBridgeInstrumentedTest#rawBindingsAcceptZapretEgressStrategyTunnelConfig -Pripdpi.localNativeAbis=arm64-v8a` passed on `Pixel_10_Pro(AVD) - 17` with 1 test.
- Remaining review evidence: Mode.VPN packet-loop integration proving the transformed packet is selected and emitted on live outgoing UDP/QUIC traffic.
