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
updated: 2026-05-14
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

- [ ] `libXray` and Xray-core versions are pinned with a documented stable vs canary update policy.
- [ ] Android artifacts cover RIPDPI's release ABI set and local iteration ABI defaults without hardcoding SDK/NDK values outside existing build properties.
- [ ] Build output is wired into `:core:engine` or an approved adjacent module without committing generated binary churn unexpectedly.
- [ ] License/notice obligations for libXray, Xray-core, Go/gomobile output, and bundled geo assets are captured.
- [ ] CI or a local verification task fails on missing ABI artifacts, version drift, or oversized native payloads.

## Design notes

Official libXray recommends its build script and notes Android support through `gomobile`; keep the packaging path close to upstream unless there is a clear reproducibility problem.

## Risks / open questions

- `libXray` compatibility is tied to the latest Xray-core release, which may conflict with a conservative stable app-release cadence.
- Geo assets and MPH cache files can dominate size if bundled uncritically.

## Links

- [[Epic - Xray provider mode]]
- ripdpi-android-xray-provider-plan-2026-04-24
- Recurring upstream watch for xray-core REALITY ECH XHTTP changes (closed task)
