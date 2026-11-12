---
id: ENG-1794501890187395
title: Repair DPI-04 fake send safety and payload contracts
kind: bug
status: done
area: engine
priority: high
owner: DPI-04 repair coordinator
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-dpi04-fake-send-contracts
created: 2026-11-12
updated: 2026-11-12
closed_at: "2026-11-12T18:54:50Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Eight DPI-04 findings have separate repair commits and independent reviews. Exact-source Linux contracts 560 passed; privileged 93 passed and three BPF EPERM failures recorded outside DPI-04; relay 48 passed; affected Clippy and offline CI gates passed. Android raw/root and external compatibility remain unverified.
---

## Goal

Repair the eight confirmed DPI-04 audit findings while preserving real TCP
payloads, bounded helper requests, and explicit capability failures.

## Acceptance criteria

- A skipped fake group sends the original payload without entropy padding (F1).
- Every fake-send error restores queued real bytes before normal TTL (F2).
- A possible payload commit prevents fallback or replay without inventing a byte count (F3).
- Raw genuine packets use real TTL and no decoy MD5 signature (F4).
- Malformed Unicode fake-data input cannot panic (F5).
- TCP sequence arithmetic wraps modulo 2^32 (F6).
- Bounded fake and ordered payloads reach a capable helper without the 8192-byte control-frame limit (F7).
- Unsupported IPv4 zero-ID requests fail before any packet is emitted (F8).
- Each finding has its own reviewed fix commit and regression evidence.
- Combined Rust checks and offline CI source gates pass before integration.

## Parallel ownership

- Coordinator: this portfolio record, OpenSpec artifacts, generated board, combined verification, integration, and remote push.
- `activation_parser_sequence`: `ripdpi-desync-runtime/src/tcp.rs` and its owning activation tests (F1); `ripdpi-config` fake-data parser and owning tests (F5); `ripdpi-privileged-ops/src/linux/tcp_repair/send_queue.rs` and owning arithmetic tests (F6).
- `fake_send_safety`: privileged fake/raw TCP senders and packet construction, fake-send replay/error contracts and callers, fake send IPv4 ID capability checks, and their tests (F2/F3/F4/F8). This lane does not edit the F6 arithmetic owner or helper IPC encoding/transport.
- `helper_payload_bounds`: root-helper protocol request framing/validation, preflight limit advertisement, helper client payload transport, helper dispatch, and owning tests (F7). This lane owns all helper protocol/version constants.
- Audit and review agents are read-only. Writers use separate worktrees. No worker edits task state or planning files.
- Serialized files: the coordinator owns architecture reports and API snapshot regeneration. Cargo.lock, dependency manifests, locale sets, and golden fixtures are not in scope.
