---
title: Render validated Xray client configs
type: task
status: backlog
area: outbound
priority: high
owner: unassigned
parent: epic-xray-vpn-client-mode
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Render validated Xray client configs #repo/RIPDPI #area/outbound #status/backlog ⏫

## Summary

Create the RIPDPI profile model, validation, and JSON renderer for initial
Xray VPN client configs.

## Motivation

Xray can run arbitrary JSON, but RIPDPI needs a safe product surface. The first
implementation should render known-good VLESS/REALITY and XHTTP shapes, then
gate raw JSON import behind validation and secret-safe error reporting.

## Scope

- In scope: VLESS/REALITY, XHTTP, local inbound, DNS/protect settings,
metrics/API choice, config validation, redaction, and golden tests.
- Out of scope: paid provider catalogs, live endpoint storage in task/wiki
notes, and automatic server provisioning.

## Acceptance criteria

- [ ] Kotlin profile model covers the initial VLESS/REALITY and XHTTP fields
    needed for client startup.
- [ ] Renderer emits local inbound and outbound config compatible with the
    chosen tunnel topology.
- [ ] `libXray.TestXray` or equivalent validation is called before saving or
    starting imported profiles.
- [ ] Diagnostics and logs redact UUIDs, private keys, passwords, server
    addresses, and live endpoints.
- [ ] Golden tests cover valid profiles, invalid combinations, and redaction.

## Design notes

Reuse the existing `:xray-protos` and Xray API scanner knowledge where it helps,
but keep runtime config generation separate from external Xray API inspection.

## Risks / open questions

- XHTTP and REALITY combinations have changed upstream before; keep validation
version-aware.
- Raw JSON import may need a restricted first release to avoid exposing unsafe
routing or logging surfaces.

## Links

- [[Epic - Xray VPN client mode]]
- [[vless-reality-stack-research-2026-04-22]]
- [[ripdpi-android-xray-vpn-client-plan-2026-04-24]]
