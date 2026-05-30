---
title: Package libXray for Android ABIs
type: task
status: backlog
area: outbound
priority: high
owner: unassigned
parent: epic-xray-provider-mode
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-30
---

- [ ] #task Package libXray for Android ABIs #repo/RIPDPI #area/outbound #status/backlog ⏫

## Summary

Add a reproducible build/import path for `libXray` Android artifacts across RIPDPI's supported ABIs.

## Motivation

The app currently builds repo-owned Rust native libraries through Gradle. Xray will introduce Go/gomobile-built native artifacts, so the build needs a pinned, auditable path before runtime work begins.

## Scope

- In scope: version pinning, build script or vendored artifact policy, ABI outputs, license notices, Gradle wiring, APK size checks, and CI smoke.
- Out of scope: server provisioning and non-Xray provider packaging.

## Acceptance criteria

- [x] `libXray` and Xray-core versions are pinned with a documented stable vs canary update policy. — `gradle/libs.versions.toml` (libxray 1.4.4 / xray-core 26.4.7 / gomobile pin) with the stable-vs-canary runbook in `docs/native/libxray-packaging.md`.
- [ ] Android artifacts cover RIPDPI's release ABI set and local iteration ABI defaults without hardcoding SDK/NDK values outside existing build properties. — `scripts/native/build-libxray.sh` reads ABI/SDK/NDK only from `gradle.properties` and refuses to run without Go+gomobile, but it CANNOT execute here (Go/gomobile absent from the offline toolchain) so no real per-ABI `.aar` was produced. OPEN: blocked on the gomobile/libXray build toolchain.
- [ ] Build output is wired into `:core:engine` or an approved adjacent module without committing generated binary churn unexpectedly. — the `:core:engine:verifyLibXrayArtifacts` Exec gate and gitignored artifact dir are wired in `core/engine/build.gradle.kts`, but the gate has never consumed a real produced artifact here. OPEN: depends on the gomobile build above (NDK29 native link absent).
- [x] License/notice obligations for libXray (Apache-2.0), Xray-core (MPL-2.0), Go/gomobile output (BSD-3-Clause), and bundled geo assets (CC-BY-SA) are captured. — `docs/native/libxray-packaging.md`.
- [ ] CI or a local verification task fails on missing ABI artifacts, version drift, or oversized native payloads. — `scripts/native/verify-libxray-artifacts.sh` was smoke-tested green against absent/missing-AAR/drift/oversize/missing-ABI/canary-ship and the valid stable+canary paths, so the gate logic is proven; but it has never run against a real produced artifact, and the `:core:engine` Exec wrapper is not attached to `assemble`. PARTIAL: verification logic landed and unit-smoked, end-to-end run blocked on the produced artifact.

## Progress

**2026-05-30** — Packaging substrate landed (commit `build(xray): pin libXray/xray-core and add gomobile packaging + artifact verification`):

- Version pins + stable/canary update policy and the full license/NOTICE capture are done.
- `scripts/native/build-libxray.sh` (fail-closed gomobile build) and `scripts/native/verify-libxray-artifacts.sh` (drift/missing/oversize gate, smoke-tested green against every failure and success state) are committed, plus the `:core:engine:verifyLibXrayArtifacts` Gradle Exec gate over a gitignored artifact dir.

Remaining (blocked on external toolchain, not on missing code):

- Actually building the per-ABI libXray `.aar` requires Go + gomobile + NDK 29, none of which are present in this environment — so no real artifact exists and the end-to-end verify/oversize/ABI-coverage gate has never run against produced output.

## Design notes

Official libXray recommends its build script and notes Android support through `gomobile`; keep the packaging path close to upstream unless there is a clear reproducibility problem.

## Risks / open questions

- `libXray` compatibility is tied to the latest Xray-core release, which may conflict with a conservative stable app-release cadence.
- Geo assets and MPH cache files can dominate size if bundled uncritically.

## Links

- [[Epic - Xray provider mode]]
- ripdpi-android-xray-provider-plan-2026-04-24
- Recurring upstream watch for xray-core REALITY ECH XHTTP changes (closed task)
