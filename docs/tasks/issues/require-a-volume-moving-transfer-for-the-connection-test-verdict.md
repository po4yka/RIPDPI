---
id: DGN-1795704789266331
title: Require a volume-moving transfer for the connection test verdict
kind: feature
status: review
area: diagnostics
priority: high
owner: Diagnostics engineer
parent: EPC-1795615697939690
blocked_by: [SVC-1795615846684850, DAT-1795085420746769]
spec_mode: required
openspec_change: dgn-1795704789266331-require-a-volume-moving-transfer-for-the-connection-test-verdict
created: 2026-11-26
updated: 2026-11-27
status_detail: 'Merged; local gates and remote CI passed (run 46009834608 on d9aa00b99f). A device run remains: the onboarding connection test reports Verified against a server with payload controls and reports that volume was not tested against a server without them. After this task closes, run ./taskctl close purge SVC-1795615846684850; its done record stays until this incoming reference goes.'
---

## Goal

A connection test that reports Passed has moved a payload larger than the TCP-freeze threshold and checked it byte-exact, not only a handshake or a small request.

## Context

A TCP freeze lets the first kilobytes of a flow pass and then stops it. A test that ends after the handshake or a small response reports Passed on a path that stalls on any real download.

Evidence is public and single-source, and it is unverified here. A write-up dated 2026-11 describes a chained client where text messages passed over four hops but images did not. A separate topology tool treats the Ready state as acceptance of the configuration and validates chains only with real end-to-end HTTP traffic. Freeze reports show that the first kilobyte passes and then the flow stops.

Server counterpart: `po4yka/ripdpi-vpn-deploy#SCR-1795086521848661`. Bundle v3 already carries the `payload_failover` controls with SHA-256 hashes and sizes from 32 KiB to 1 MiB. This client task uses those controls and adds no third-party host.

Neighbours that this task extends and must not duplicate: SVC-1795615846684850 (route issue after a passed test) consumes the Passed verdict; DGN-1795361889557414 (transfer volume comparison), DGN-1791521873290293 (transfer progress and stopping points) and DGN-1795277862388284 (completed transfer for the active strategy verdict) already provide the transfer measurement and stop evidence.

## Acceptance criteria

- The connection test moves a payload larger than the TCP-freeze threshold through the active route and checks its length and hash against the expected values of the operator-owned payload controls.
- A test that completes the handshake and a small request but stalls before the full payload does not report Passed and records the byte count where the transfer stopped.
- The test adds no third-party host. When the server publishes no payload control, the verdict states that volume was not tested instead of Passed.
- The test reuses the existing transfer measurement and stop evidence and adds no second transfer engine.

## Ownership

Not assigned yet. Before a writer starts, record the owner of the onboarding connection-test path and of the diagnostics transfer code, and serialize any shared diagnostics schema-version constant.
