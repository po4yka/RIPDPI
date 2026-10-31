---
id: OUT-1793433244049580
title: Implement the Mieru UDP carrier
kind: feature
status: backlog
area: outbound
priority: medium
owner: unassigned
parent: EPC-1786264762917457
blocked_by: []
spec_mode: required
openspec_change: out-1793433244049580-implement-mieru-udp-carrier
created: 2026-10-31
updated: 2026-10-31
---

## Goal

A Mieru profile with `protocol=udp` connects through the Mieru UDP carrier and interoperates with the pinned upstream server, instead of being rejected with `MieruError::UdpUnsupported`.

## Acceptance criteria

- [ ] `native/rust/crates/ripdpi-mieru/PROTOCOL.md` records the UDP underlay and session ARQ semantics transcribed from a pinned upstream tag and commit.
- [ ] The UDP carrier delivers exact, ordered bytes over a lossy, reordering loopback path, protects every carrier socket, and fails closed on AEAD errors.
- [ ] The upstream Mieru CI oracle runs `udp_*` cases that pass, and only then do the Mieru capability registry rows report UDP support.

## Context

Follow-up of OUT-1786264762917513, whose REQ-OUT-1786264762917513-007 allowed moving the deferred UDP carrier into a follow-up task; the user approved that on 2026-10-31. A spec-only attempt on 2026-10-31 stopped because upstream `docs/protocol.md` at `155ebbd60f86e472586a60d7ffe58ec8f8682cb1` does not define the UDP payload nonce order, acknowledgement semantics, retransmission timing, window units, fragment reassembly, open/close retransmission, or server session keying and idle timeouts. Estimated size: about 2,000 to 2,500 lines of Rust; no new dependency.
