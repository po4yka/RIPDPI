---
id: DGN-1791529750911166
title: Separate IPv4 IPv6 and NAT64 diagnostic evidence
kind: feature
status: review
area: diagnostics
priority: high
owner: ip-family-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: separate-ip-family-probes
created: 2026-10-09
updated: 2026-10-09
---

## Goal

Expose independent IPv4, IPv6 and NAT64 measurements in standalone scans, full analysis, history and exports.

## Acceptance criteria

- A success in one address family cannot hide failure in another.
- DNS64 discovery is not reported as NAT64 reachability.
- Tests cover family forcing, prefix formats, cancellation, malformed data, legacy records and export privacy.
- Local validation and remote/device/artifact acceptance are reported separately.

## Ownership

- Native writer: Rust diagnostic contracts, DNS helpers, probe/runner/registry and native tests. No Cargo.lock, schema version, fixtures or API snapshot writes.
- UI writer: app result mapping, details/history UI, app tests and all locale resource files.
- Integration writer: Kotlin shared contracts, catalog/request/home stage, export and tests, task/spec/docs, golden fixtures and API snapshots. Each writer uses a separate worktree. Gradle and shared contract changes are serialized.
