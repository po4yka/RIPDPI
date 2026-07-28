---
title: Capture P1 device and network evidence
type: task
status: doing
area: diagnostics
priority: high
owner: Codex diagnostics P1 coordinator
parent: null
blocks: []
blocked_by: []
created: 2026-07-28
updated: 2026-07-28
---

## Goal

Make user diagnostics explain Android/OEM lifecycle and data-plane failures over time instead of relying on a late terminal snapshot.

## Scope

1. Record a bounded, event-driven Android/OEM state timeline at VPN start/ready, screen and Doze changes, network handover, failure/recovery, and stop.
2. Generalize `ApplicationExitInfo` capture into redacted reason and importance bands.
3. Capture distinct VPN and underlay path snapshots with privacy-safe capability, route-family, NAT64, bandwidth, and generation metadata.
4. Record a bounded `NetworkCallback` event timeline, including losing/lost/capability/link-property transitions.
5. Correlate real data-plane counters and first/last forwarded-flow evidence with the connection runtime.

## Boundaries

- Do not add Room migrations, diagnostics wire/schema changes, archive golden changes, or locale strings in this task.
- Do not record serials, Android IDs, interface names, IP addresses, SSIDs/BSSIDs, carrier identifiers, DNS addresses, hostnames, endpoints, profile secrets, or raw free-form exception text.
- Use bounded categorical/count/timestamp projections and existing diagnostics export surfaces.
- Preserve the unfinished P0 diagnostics worktree and all unrelated local-main work.

## Acceptance

- Each of the five P1 slices lands as an atomic Conventional Commit with focused regression tests.
- Timeline and counter collections are bounded and correlation-safe.
- Exported evidence is fail-closed and covered by hostile-value privacy tests.
- A late terminal snapshot cannot erase earlier device, path, callback, exit, or data-plane evidence.
- Affected unit tests, static analysis, architecture health, task-board check, and locked Cargo metadata pass.

## Work log

- 2026-07-28: Scope reconstructed from the user diagnostic review. Dedicated worktree created from refreshed `origin/main`; serialized wire/Room/golden lanes explicitly excluded.
