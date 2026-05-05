---
title: Split ConfigViewModel into draft, credential, and capability concerns
type: task
status: backlog
area: ui
priority: high
owner: unassigned
parent: epic-srp-and-architecture-refactoring
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Split ConfigViewModel into draft, credential, and capability concerns #repo/RIPDPI #area/ui #status/backlog ⏫

## Objective

Extract relay credential hydration/persistence and capability evidence observation out of `ConfigViewModel` into a `RelayCredentialRepository` and a `CapabilityObserver`, leaving the ViewModel as a thin draft-editing coordinator.

## Context

`ConfigViewModel` (ConfigViewModel.kt:104–542, 11 constructor params) mixes four responsibilities: config draft editing/validation, relay credential hydration from `RelayProfileStore`/`RelayCredentialStore`, relay artifact persistence (`persistRelayArtifacts`, 100-line suspend function at lines 407–503), and capability evidence observation via `ServerCapabilityStore`/`NetworkFingerprintProvider`. The ViewModel calls `networkFingerprintProvider.capture()` and builds `RelayProfileRecord`/`RelayCredentialRecord` inline — repository-layer logic inside the ViewModel.

Source: `app/src/main/kotlin/com/poyka/ripdpi/activities/ConfigViewModel.kt:104-542`

## Acceptance criteria

- [ ] `RelayCredentialRepository` (interface + impl) owns hydration of relay secrets from `RelayProfileStore`/`RelayCredentialStore` and all persistence of `RelayProfileRecord`/`RelayCredentialRecord`.
- [ ] `CapabilityObserver` (@Singleton service or UseCase) owns `networkFingerprintProvider.capture()` and `serverCapabilityStore.rememberRelayObservation()` calls; `ConfigViewModel` subscribes to its output.
- [ ] `ConfigViewModel` retains draft state, validation, and preset selection only; constructor params drop from 11 to ≤6.
- [ ] Hilt module updated with `@Binds` for `RelayCredentialRepository`.
- [ ] Existing config screen UI tests pass.

## Definition of done

`ConfigViewModel` LOC < 300; no direct calls to `RelayProfileStore`/`RelayCredentialStore` in the ViewModel; Hilt `@Binds` wires the new interface.
