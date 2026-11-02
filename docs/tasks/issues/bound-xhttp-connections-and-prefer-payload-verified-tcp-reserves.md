---
id: TRN-1793634968382224
title: Bound XHTTP connections and prefer payload-verified TCP reserves
kind: feature
status: review
area: transport
priority: high
owner: transport-resilience
parent: null
blocked_by: []
spec_mode: required
openspec_change: trn-1793634968382224-bound-xhttp-connections-and-prefer-payload-verified-tcp-reserves
created: 2026-11-02
updated: 2026-11-02
status_detail: Local implementation, 284 native tests, 123 Kotlin tests, static analysis and independent review passed; hosted CI remains required.
---

## Goal

Keep delivered XHTTP connection limits through activation and recover from UDP payload failure through a verified TCP reserve.

## Acceptance criteria

- A paired bundle retains the exact 1–4 stream-up connection limit through import, persistence and native configuration.
- Invalid or unsupported tuning refuses before profile replacement.
- Failed UDP payload checks prefer TCP reserves, require recovery thresholds and preserve cooldown.
- All-candidate failure retains the encrypted selection.
- Targeted Kotlin and Rust gates pass; an independent review is complete.
- Local tests do not claim physical-device or live-network acceptance.
