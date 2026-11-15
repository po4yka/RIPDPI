---
id: DGN-1794747967332880
title: Fix DIA-16 STUN observations and path contracts
kind: bug
status: doing
area: diagnostics
priority: high
owner: STUN repair team
parent: null
blocked_by: []
spec_mode: required
openspec_change: dia16-stun-integrity
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Fix findings F1–F7 from the DIA-16 STUN audit. Network consent, path labels, wire validation, peer correlation, outcome reporting, classification, and cancellation must agree with observed evidence.

## Acceptance criteria

- Network requests off prevents WebRTC STUN work.
- Default-path observations do not claim VPN ownership. An empty supported path set stays empty.
- Complete STUN messages pass strict header and attribute checks. Independent RFC IPv4 and IPv6 vectors decode correctly.
- UDP results belong to the selected peer and a valid SOCKS5 envelope.
- Address differences alone do not produce a high-confidence leak.
- Missing or unsupported measurements do not display OK.
- Cancellation closes blocking probes promptly. Local regressions, affected module gates, source CI gates, and independent review pass.

## Ownership

- Root: integration worktree; CallTransportLeakChecker result/classification and path selection (F2/F5), CallTransportPath enum, consensus provenance, planning and evidence.
- wire_integrity: isolated wire worktree; StunBindingResponseDecoder, WebRTC validation delegation and decoder tests (F3).
- transport_lifecycle: isolated transport worktree; ByPassProxyProbes, WebRTC network methods, DefaultCallTransport network implementations and transport tests (F4/F7).
- controls_outcomes: isolated controls worktree; presenter, CategoryResult evaluation contract, WebRTC result evaluation, UI/report/export and tests (F1/F6).
- Shared files are serialized during integration: DetectionModels (controls owns CategoryResult; root owns path enum), WebRtcLeakChecker (wire validation, transport network, controls result), CallTransportLeakChecker (root upper checker; transport lower clients). Root resolves combined-tree changes and runs final gates. No locale or golden writer is authorized; existing strings are reused.
- Audit and review agents read only. Each finding gets a separate reviewed implementation commit. Root alone merges main and pushes.
