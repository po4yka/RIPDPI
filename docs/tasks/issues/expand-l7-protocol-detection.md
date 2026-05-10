---
title: Expand L7 protocol detection to cover WireGuard, DTLS, MTProto, STUN, DHT, and DNS
type: task
status: review
area: rust-native
priority: high
owner: unassigned
parent: zapret2-feature-parity-epic
blocks: []
blocked_by: [add-ripdpi-strategy-trait-crate]
created: 2026-05-09
updated: 2026-05-10
---

- [ ] #task Expand L7 protocol detection to cover WireGuard, DTLS, MTProto, STUN, DHT, and DNS #repo/RIPDPI #area/rust-native #status/review 🔼

## Objective

Create or extend a `ripdpi-protocol-detect` crate to classify six additional L7 protocols beyond the existing TLS and QUIC: WireGuard, DTLS, MTProto, STUN, DHT (BitTorrent), and DNS. These new protocol types feed into `Dissect.proto` so that strategy `matches()` functions can filter on them.

## Context

zapret2's `protocol.h` (`/Users/po4yka/GitRep/zapret2/nfq2/protocol.h`) defines `t_l7proto` with 20+ protocol types. RIPDPI currently detects TLS ClientHello and QUIC Initial in `native/rust/crates/ripdpi-desync/src/first_flight_ir.rs`. The new `classify_l7()` function replaces the current detection with a complete classifier. Protocol detection runs on the first payload bytes of each new connection — it must be fast (no allocations, pure byte slice scanning, O(1) or O(n) where n≤16 bytes).

Detection signatures:

- **WireGuard (UDP):** First 4 bytes: `0x01 0x00 0x00 0x00` (Initiation), `0x02 0x00 0x00 0x00` (Response), `0x03 0x00 0x00 0x00` (Cookie), `0x04 0x00 0x00 0x00` (Data). Packet size for Initiation is exactly 148 bytes.
- **DTLS (UDP):** Content-type byte 0 is 0x16 (Handshake) or 0x14/0x15/0x17; version bytes 1-2 are `0xFE 0xFF` (DTLS 1.0) or `0xFE 0xFD` (DTLS 1.2).
- **STUN (UDP):** Magic cookie at bytes 4-7: `0x21 0x12 0xA4 0x42`; message type bits 14-15 of byte 0-1 must be 0.
- **DHT/BitTorrent (UDP):** Payload starts with `d1:` (bencoded dict) or `d8:` or contains `1:y` key.
- **MTProto (TCP or UDP):** First 8 bytes are random (no fixed signature); reliable detection requires heuristics: payload length >= 64, first 4 bytes not matching any other known protocol, and typically observed on port 443 or 80. Use port + absence of other signatures as classifier.
- **DNS (UDP port 53 / TCP with 2-byte length prefix):** Standard DNS message: bytes 2-3 are flags, QR bit, OPCODE. Simple check: port 53 + payload length >= 12.

Output: `classify_l7(payload: &[u8], src_port: u16, dst_port: u16, is_udp: bool) -> L7Protocol`

## Acceptance criteria

- [ ] `classify_l7()` correctly identifies all 8 protocols: TLS, QUIC, WireGuard, DTLS, STUN, DHT, MTProto, DNS — returns `L7Protocol::Unknown` for unrecognized payloads
- [ ] No heap allocation in the classifier (must be suitable for hot packet path)
- [ ] WireGuard detection does not false-positive on QUIC (QUIC first byte has high bits set; WireGuard first 4 bytes are LE u32 1-4)
- [ ] STUN magic cookie check rules out all other protocols that happen to be on port 3478
- [ ] MTProto detection is explicitly documented as heuristic (not signature-based) in code comment
- [ ] `L7Protocol` enum in `ripdpi-strategy-trait` is updated with `WireGuard(WireGuardDissect)`, `Dtls(DtlsDissect)`, `Stun(StunDissect)`, `Dht(DhtDissect)`, `Mtproto(MtprotoDissect)`, `Dns(DnsDissect)` variants
- [ ] Unit tests cover: each protocol's known first bytes classify correctly, empty payload returns Unknown, 1-byte payload returns Unknown
- [ ] `cargo test -p ripdpi-protocol-detect` green with 100% branch coverage on the classifier

## Source references

- zapret2 protocol detection: `/Users/po4yka/GitRep/zapret2/nfq2/protocol.h` — all detection functions and `t_l7proto` enum
- zapret2 QUIC AEAD decrypt: `/Users/po4yka/GitRep/zapret2/nfq2/crypto/` — for QUIC SNI extraction reference (already done in RIPDPI)
- RIPDPI existing TLS/QUIC detection: `native/rust/crates/ripdpi-desync/src/first_flight_ir.rs` — `normalize_tls_client_hello()`, `normalize_quic_initial()`
- WireGuard protocol spec: message type constants are in WireGuard whitepaper; 0x01-0x04 are stable

## TDD workflow

1. **Write tests first** — before any implementation code, write one test per protocol with a known first-bytes fixture and assert the correct `L7Protocol` variant is returned.
2. **Confirm red** — run `cargo test -p ripdpi-protocol-detect` and confirm all new protocol tests fail because `classify_l7` does not handle them yet.
3. **Implement** — add each protocol classifier one at a time, making its test green before moving to the next.
4. **Confirm green** — run the full crate test suite; zero regressions on TLS and QUIC detection.
5. **Refactor** — clean up while keeping tests green.

**Test files to create before implementation:**
- `native/rust/crates/ripdpi-protocol-detect/tests/wireguard.rs` — assert `classify_l7(&[0x01,0x00,0x00,0x00, ...148 bytes], 0, 51820, true)` returns `L7Protocol::WireGuard(_)`; assert `[0x02,0x00,0x00,0x00,...]` (Response) also returns WireGuard; fails until WireGuard classifier exists
- `native/rust/crates/ripdpi-protocol-detect/tests/dtls.rs` — assert bytes starting with `[0x16, 0xFE, 0xFF, ...]` (DTLS 1.0) and `[0x16, 0xFE, 0xFD, ...]` (DTLS 1.2) return `L7Protocol::Dtls(_)`; fails until DTLS classifier exists
- `native/rust/crates/ripdpi-protocol-detect/tests/stun.rs` — assert bytes with magic cookie `[0x21, 0x12, 0xA4, 0x42]` at offset 4 return `L7Protocol::Stun(_)`; fails until STUN classifier exists
- `native/rust/crates/ripdpi-protocol-detect/tests/dht.rs` — assert bytes starting with `b"d1:a"` return `L7Protocol::Dht(_)`; fails until DHT classifier exists
- `native/rust/crates/ripdpi-protocol-detect/tests/no_alloc.rs` — run all classifiers under `cargo test` with a custom allocator that panics on any heap allocation; assert classify_l7 makes zero allocations; fails until hot path is allocation-free
- `native/rust/crates/ripdpi-protocol-detect/tests/empty_payload.rs` — assert `classify_l7(&[], 0, 443, false)` returns `L7Protocol::Unknown` without panicking; fails if any classifier dereferences without bounds checking
- `native/rust/crates/ripdpi-protocol-detect/tests/tls_quic_regression.rs` — assert existing TLS and QUIC detection still works correctly after adding new classifiers (regression guard)
- `native/rust/crates/ripdpi-protocol-detect/tests/wireguard_no_quic_false_positive.rs` — assert a QUIC Initial packet does NOT classify as WireGuard; fails if detection logic is too broad

## Definition of done

`cargo test -p ripdpi-protocol-detect` green; WireGuard and DTLS classification verified with packet samples captured from real WireGuard and DTLS handshakes. Tests were written and confirmed red before implementation began; the relevant test command is green with no regressions.

## Work log

- Added `ripdpi-protocol-detect` with allocation-free `classify_l7()` coverage for TLS, QUIC, WireGuard, DTLS, STUN, DHT, DNS, MTProto, and Unknown.
- Verification: `CARGO_TARGET_DIR=target/codex-protocol-detect cargo test -p ripdpi-protocol-detect --locked`; `CARGO_TARGET_DIR=target/codex-protocol-detect cargo clippy -p ripdpi-protocol-detect --all-targets -- -D warnings`.
- Remaining review evidence: real WireGuard/DTLS capture samples and explicit branch-coverage report.
