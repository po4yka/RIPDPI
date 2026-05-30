---
title: Render validated Xray client configs
type: task
status: done
area: outbound
priority: high
owner: unassigned
parent: epic-xray-provider-mode
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-30
---

- [x] #task Render validated Xray client configs #repo/RIPDPI #area/outbound #status/done ⏫

## Summary

Create the RIPDPI profile model, validation, and JSON renderer for initial Xray provider configs.

## Motivation

Xray can run arbitrary JSON, but RIPDPI needs a safe product surface. The first implementation should render known-good VLESS/REALITY and XHTTP shapes, then gate raw JSON import behind validation and secret-safe error reporting.

## Scope

- In scope: VLESS/REALITY, XHTTP, local inbound, DNS/protect settings, metrics/API choice, config validation, redaction, and golden tests.
- Out of scope: paid provider catalogs, live endpoint storage in task/wiki notes, and automatic server provisioning.

## Acceptance criteria

- [x] Kotlin profile model covers the initial VLESS/REALITY and XHTTP fields needed for client startup. — `XrayProfile.kt` in `:core:data:catalog`.
- [x] Renderer emits local inbound and outbound config compatible with the chosen tunnel topology. — `XrayConfigRenderer.kt` emits the `TunToLocalInbound` shape (`localInboundPort=10808`) that `XrayConfigValidator` consumes; catch-all routing rule carries an explicit `network` selector for xray-core v26+.
- [x] `libXray.TestXray` or equivalent validation is called before saving or starting imported profiles. — structural validation via `XrayConfigValidator` runs before render returns and on the raw-import path; the native `libXray.TestXray` call is an injected seam (`XrayConfigTester`, no-op default), wired but UNVERIFIED IN CI (requires the gomobile libXray AAR).
- [x] Diagnostics and logs redact UUIDs, private keys, passwords, server addresses, and live endpoints. — `XrayProfileRedactor.kt`.
- [x] Golden tests cover valid profiles, invalid combinations, and redaction. — `XrayConfigRendererTest`, `XrayProfileRedactorTest`, `XrayRedactionRegressionTest` (green offline in `:core:data:catalog`).

## Design notes

Reuse the existing `:xray-protos` and Xray API scanner knowledge where it helps, but keep runtime config generation separate from external Xray API inspection.

## Risks / open questions

- XHTTP and REALITY combinations have changed upstream before; keep validation version-aware.
- Raw JSON import may need a restricted first release to avoid exposing unsafe routing or logging surfaces.

## Links

- [[Epic - Xray provider mode]]
- vless-reality-stack-research-2026-04-22
- ripdpi-android-xray-provider-plan-2026-04-24
