---
id: DGN-1795331701582623
title: Compare verified HTTPS transfers with ECH and GREASE
kind: feature
status: review
area: diagnostics
priority: high
owner: Diagnostics
parent: null
blocked_by: []
spec_mode: required
openspec_change: ech-transfer-comparison
created: 2026-11-22
updated: 2026-11-22
---

## Goal

Compare verified HTTPS transfers on one endpoint with TLS 1.3, ECH GREASE, and real ECH.

## Acceptance criteria

- A manual scan uses one user-selected IP, port, origin, and network path for all three attempts.
- It verifies the bounded response size and digest for each attempt and reports configuration, handshake, and transfer outcomes separately.

## Ownership

- The TLS implementation owns the diagnostics TLS crate and its fixtures.
- The Android implementation owns the app UI, translations, and translation index.
- The diagnostics planning implementation owns core diagnostics, the profile generator, and its catalog snapshot.
- The native runner implementation owns other Rust code, the outcome fixture, the API snapshots, and task evidence.
