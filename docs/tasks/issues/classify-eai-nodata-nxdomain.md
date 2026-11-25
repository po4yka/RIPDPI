---
id: DNS-1795610833842590
title: Detect injected NXDOMAIN that bionic reports as EAI_NODATA
kind: chore
status: backlog
area: dns
priority: low
owner: comment-ratchet
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-25
updated: 2026-11-25
spec_reason: mechanical-refactor
---

## Goal

`native/rust/crates/ripdpi-diagnostics-transport/src/transport/address.rs#classify_gai_code` cannot tell an injected NXDOMAIN from no data, because Android bionic returns `EAI_NODATA` for a real NXDOMAIN. The DNS tampering verdict is then missed.

## Acceptance criteria

The probe gets the DNS rcode from a resolver that sees it (for example the encrypted resolver path) and reports DNS tampering for an injected NXDOMAIN. A test covers both codes.
