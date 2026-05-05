---
title: Split RelayFields.kt into per-relay-kind composable files
type: task
status: backlog
area: ui
priority: medium
owner: unassigned
parent: epic-srp-and-architecture-refactoring
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Split RelayFields.kt into per-relay-kind composable files #repo/RIPDPI #area/ui #status/backlog 🔼

## Objective

Split `RelayFields.kt` (746 LOC) into one file per relay kind with a top-level `RelayFieldsContent` dispatcher, mirroring the pattern used by the `AdvancedSettingsScreen` refactoring task.

## Context

`RelayFields.kt` renders credential/server fields for every relay kind (VlessReality, Hysteria2, MASQUE, TUIC, ShadowTLS, Obfs4/Snowflake/WebTunnel, ChainRelay, CloudflareTunnel) in a single 746-LOC file. Each kind is a distinct UI subtree with no shared fields. Combined with `ModeEditorScreen.kt` (745 LOC), the config screen is 1490 LOC across two files. Relay kind additions always touch the same monolithic file.

Source: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/config/RelayFields.kt`

## Acceptance criteria

- [ ] Separate files: `RelayVlessRealityFields.kt`, `RelayHysteria2Fields.kt`, `RelayMasqueFields.kt`, `RelayTuicFields.kt`, `RelayShadowTlsFields.kt`, `RelayObfs4Fields.kt`, `RelayChainRelayFields.kt`, `RelayCloudflareTunnelFields.kt`.
- [ ] `RelayFieldsContent(draft, onDraftChange, relayKind, ...)` dispatcher in `RelayFields.kt` delegates to each file by `relayKind`; its own body is ≤50 LOC.
- [ ] Adding a new relay kind requires creating one new file and one `when` branch in the dispatcher — nothing else.
- [ ] Roborazzi config/mode-editor golden passes.

## Definition of done

`RelayFields.kt` dispatcher ≤50 LOC; each per-kind file ≤120 LOC; Roborazzi golden passes.
