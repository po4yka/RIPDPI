---
id: DGN-1794755342253604
title: Repair classic PCAP capture and export
kind: bug
status: review
area: diagnostics
priority: high
owner: PCAP integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-classic-pcap-export
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Repair the nine AUX-01 findings for explicit redacted Android PCAP export and its capture path.

## Acceptance criteria

- Run the separate regressions for export, consent, rotation, redaction metadata, parsing, retention, export failure, writer bounds, and listing errors.
- Validate the combined native and Android code and source gates. Integrate each repaired finding as an atomic commit.

## Ownership

Format owns the native PCAP crate. Capture owns tunnel capture/list, lease, retention, and state UI. Export owns JNI/controller redaction sections, viewer and picker. Integration owns Android parser and consent, locale integration after the export writer, and verification. Writers use separate worktrees. Shared bridge files have section ownership. Only one writer edits locale resources at a time.
