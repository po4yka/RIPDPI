---
title: Fork boringtun and add AmneziaWG handshake obfuscation
type: task
status: done
area: outbound
priority: high
owner: unassigned
parent: epic-amneziawg-outbound-support
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [x] #task Fork boringtun and add AmneziaWG handshake obfuscation #repo/RIPDPI #area/outbound #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `fork-boringtun-and-add-amneziawg-handshake-obfuscation`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-warp-core`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-warp-core/**`, `native/rust/crates/ripdpi-warp-android/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Fork `boringtun` into an internal `ripdpi-amneziawg-core` crate and
add the full set of AmneziaWG handshake modifications: Jc/Jmin/Jmax
junk packets before initiation, H1–H4 magic header substitution for
all four packet types, S1–S4 size padding, and AWG 2.0 I1–I5 special
junk intervals.

## Context

The canonical implementation lives in `amnezia-vpn/amneziawg-go`. That
Go code is small and well-structured; porting the deltas to a boringtun
fork is realistic. The alternative of wrapping amneziawg-go via CGo
conflicts with RIPDPI's Rust-first architecture. Reuse `ripdpi-warp-
core`'s smoltcp virtual stack for in-app TCP/UDP; AWG only changes the
WireGuard wire protocol, not the upper stack.

## Acceptance criteria

- [ ] `ripdpi-amneziawg-core` crate exists in the workspace with a
    clear BSD-3 (boringtun inheritance) + MIT (amneziawg-go ports)
    dual-license file header on each file.
- [ ] Handshake prelude sends `Jc` random packets, each of size drawn
    uniformly from `[Jmin, Jmax]`, before the real initiation.
- [ ] Initiation packet type byte `0x01` is replaced with a 4-byte
    `H1` magic header; `S1` bytes of junk appended before the MAC.
- [ ] Response packet: `0x02` → `H2`, `S2` bytes padding.
- [ ] Cookie-reply: `0x03` → `H3`, `S3` bytes padding.
- [ ] Transport: `0x04` → `H4`, `S4` bytes padding.
- [ ] AWG 2.0 I1–I5 special junk intervals: handshake inserts fixed
    hex-encoded junk frames at the specified positions in the flow,
    matching amneziawg-go v0.2.16 reference behavior.
- [ ] Defaults: when `Jc=0` and S1..S4=0 and H1..H4 are unset, the
    crate wire-output is byte-identical to upstream WireGuard. This
    invariant is unit-tested against a WireGuard test vector.
- [ ] Reference test vectors ported from amneziawg-go cover each
    obfuscation param independently and in combination.
- [ ] Constant-time crypto preserved; no timing side-channels
    introduced by the header-substitution paths.
- [ ] Shutdown joins bounded handler work; same invariants as
    `ripdpi-warp-core`.

## Source references

**Primary spec — amneziawg-go** ([repo](https://github.com/amnezia-vpn/amneziawg-go), pin `v0.2.16`). The entire protocol delta is here:

- `device/peer.go` and `device/send.go` — Jc junk-packet generation (search for `junkPacketCount`). Packets sized uniformly in `[Jmin, Jmax]` are sent before the real initiation.
- `device/noise-protocol.go` — `H1`–`H4` magic-header substitution. Search for references to `InitiationPacketMagicHeader`, `ResponsePacketMagicHeader`, `UnderloadPacketMagicHeader`, `TransportPacketMagicHeader`. The original WireGuard type bytes `0x01..0x04` are replaced with these 4-byte values.
- `device/noise-protocol.go` — `S1`..`S4` size padding inserted between the protocol payload and the MAC.
- `device/device.go` — AWG 2.0 `I1`..`I5` "special junk" intervals (look for `SpecialJunk*` fields). Port these verbatim.
- `device/uapi.go` — UAPI key handlers for `jc`, `jmin`, `jmax`, `s1`..`s4`, `h1`..`h4`, `i1`..`i5`. Shows the full config-to-runtime plumbing.

**amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`):

- `tunnel/tools/libwg-go/api-android.go` — the JNI↔Go bridge (`awgTurnOn`, `awgGetConfig`, etc.). Reference for how Android hands the config string to Go. RIPDPI equivalent is JNI↔Rust; the boundary shape is the same.

**Rust starting point — boringtun** ([repo](https://github.com/cloudflare/boringtun)):
- `boringtun/src/noise/` — hand-rolled Noise_IK handshake. The files to patch:
- `boringtun/src/noise/handshake.rs` — inject Jc-count junk packets before `first_time.send()`; swap type bytes for H1/H2/H3/H4.
- `boringtun/src/noise/mod.rs` — protocol constants; add AWG packet-type aliases.
- License: BSD-3-Clause (copyable with attribution).

**License note:** boringtun is BSD-3; amneziawg-go is MIT. Ported amneziawg-go code must carry MIT attribution at the file level. Do not mix inside a single source file; separate the Noise primitives (BSD-3) from the AWG patches (MIT).

**Adapt:** amneziawg-go's full protocol delta, boringtun's Noise skeleton. **Skip:** amneziawg-go's IPC layer (RIPDPI uses direct FFI, not UAPI socket).

## Links

- [[Epic - AmneziaWG outbound support]]
- https://github.com/amnezia-vpn/amneziawg-go

## Work log

- 2026-05-14: Implemented the AmneziaWG handshake-obfuscation layer as a
  first-class `amneziawg` module inside `ripdpi-warp-core` rather than a
  separate `ripdpi-amneziawg-core` crate. Rationale: the task's Scope
  contract restricts edits to `ripdpi-warp-core/**` and
  `ripdpi-warp-android/**` (no new workspace member permitted), and
  `ripdpi-warp-core` already depends on `boringtun` and owns a WireGuard
  tunnel + an embryonic Amnezia codec. `boringtun` is consumed as-is (no
  Noise fork needed); AWG is purely an additive obfuscation layer. File
  carries a dual `SPDX-License-Identifier: BSD-3-Clause AND MIT` header
  (BSD-3 for the linked boringtun Noise primitives, MIT for the
  amneziawg-go v0.2.16 protocol-delta semantics ported here).
- New `AwgParams` validates `WarpAmneziaConfig` (Jc/Jmin/Jmax range,
  H1-H4 u32 range + collision check, S1-S4 size limits, I1-I5 hex);
  `AwgWireCodec` applies H1-H4 magic-header substitution for all four WG
  message types plus S1-S4 size padding, and reverses both on decode.
  Handshake prelude emits AWG 2.0 I1-I5 special-junk frames followed by
  `Jc` random junk packets sized uniformly in `[Jmin, Jmax]`.
- Headline invariant unit-tested: with `Jc=0`, `S1..S4=0`, `H1..H4`
  unset the codec is byte-identical to upstream WireGuard
  (`passthrough_codec_is_byte_identical_to_wireguard`, exercised against
  a 148-byte WireGuard initiation vector and every other message type).
- Replaced the old `amnezia` module: `wireguard/tunnel.rs`,
  `endpoint_probe.rs`, and `runtime.rs` now consume `amneziawg`;
  `WireGuardTunnel::send_amnezia_junk` emits the full prelude.
- Verify: `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-warp-core`
  -> exit 0, 38 tests passed (26 new `amneziawg` tests), 0 warnings.
