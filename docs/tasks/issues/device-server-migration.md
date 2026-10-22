---
id: TRN-1792673614153980
title: Export offline device migration receipts after exact new-path tests
kind: feature
status: doing
area: transport
priority: high
owner: "transport_audit: all client migration paths"
parent: null
blocked_by: []
spec_mode: required
openspec_change: device-server-migration
created: 2026-10-22
updated: 2026-10-22
---

## Goal

Receive a per-device server replacement delivery, test its exact new relay or
AmneziaWG path, and explicitly export a secret-free offline receipt that separates
delivery receipt from successful data-plane verification.

Related server task: `po4yka/ripdpi-vpn-deploy#OPS-1792673523985723`.

## Ownership

- `transport_audit` owns all client changes in this worktree: subscription parsing,
  storage, service verification, UI, locales, contract fixtures, tests and planning.
- The parent owns all deployment-repository artifacts and the shared wire contract.
- Serialize all locale and contract-fixture edits through the client owner. Other
  worktrees and unrelated changes remain untouched.

## Acceptance criteria

- Validate migration metadata and hash exact delivered response bytes before
  retaining the received identity with the successfully refreshed subscription.
- A changed delivery invalidates prior tested evidence; failed, stale, ambiguous,
  interrupted or partially applied refreshes cannot produce a tested receipt.
- User-triggered verification exercises an exact declared green target over real
  authenticated Reality/Vision, XHTTP, Hysteria2 or AmneziaWG data-plane traffic.
  Active AWG verification requires the exact imported configuration and a stable
  runtime generation; generic connected state and old/direct fallback do not count.
- Export only on explicit user action. The versioned JSON receipt has a keyed
  per-delivery proof, received/tested distinction and no credentials, keys, endpoint
  addresses, device names or network identifiers. No required telemetry or backend.
- Tests cover strict metadata, raw-byte digest, stale-result rejection, refresh
  failure/crash boundaries, four transport routes, cancellation and export privacy;
  all affected locale, architecture, local and hosted gates report observed results.
- Refusal-only behavior for a baseline transport does not complete this feature.
  Device/live acceptance gaps remain explicit and cannot be recorded as passed.
