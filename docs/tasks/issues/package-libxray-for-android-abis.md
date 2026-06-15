---
title: Package libXray for Android ABIs
type: task
status: in-progress
area: outbound
priority: high
owner: unassigned
parent: epic-xray-provider-mode
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-06-15
---

## Summary

Add a reproducible build/import path for `libXray` Android artifacts across RIPDPI's supported ABIs.

## Motivation

The app currently builds repo-owned Rust native libraries through Gradle. Xray will introduce Go/gomobile-built native artifacts, so the build needs a pinned, auditable path before runtime work begins.

## Scope

- In scope: version pinning, build script or vendored artifact policy, ABI outputs, license notices, Gradle wiring, APK size checks, and CI smoke.
- Out of scope: server provisioning and non-Xray provider packaging.

## Acceptance criteria

- [x] `libXray` and Xray-core versions are pinned with a documented stable vs canary update policy. — `gradle/libs.versions.toml` (`libxray = "v26.3.27"` / `xray-core = "1.260327.0"` / `gomobile` pin, plus `libxray-canary`/`xray-core-canary` = `main`) with the stable-vs-canary runbook in `docs/native/libxray-packaging.md`.
- [x] Android artifacts cover RIPDPI's release ABI set and local iteration ABI defaults without hardcoding SDK/NDK values outside existing build properties. — `scripts/native/build-libxray.sh` reads ABI/SDK/NDK only from `gradle.properties`; a real 4-ABI (`armeabi-v7a`/`arm64-v8a`/`x86`/`x86_64`) `libxray.aar` (libXray v26.3.27, xray-core 1.260327.0, NDK 29.0.14206865, payload < 160 MiB budget) is produced into the gitignored `native/xray/artifacts/` and consumed by `:core:engine`.
- [x] Build output is wired into `:core:engine` or an approved adjacent module without committing generated binary churn unexpectedly. — `core/engine/build.gradle.kts` links the gitignored AAR via `implementation(files(<dir>/libxray.aar))` when present (or `-Pripdpi.linkXray=true`), swaps in the `src/xrayLinked` real impl source set, and attaches the `verifyLibXrayArtifacts` gate to `preBuild`. The gate now consumes the real produced artifact; no generated binary is committed (only its location is a Gradle input).
- [x] License/notice obligations for libXray (Apache-2.0), Xray-core (MPL-2.0), Go/gomobile output (BSD-3-Clause), and bundled geo assets (CC-BY-SA) are captured. — `docs/native/libxray-packaging.md`.
- [x] CI or a local verification task fails on missing ABI artifacts, version drift, or oversized native payloads. — `scripts/native/verify-libxray-artifacts.sh` is now attached to the build: `core/engine/build.gradle.kts` makes `preBuild` depend on `verifyLibXrayArtifacts` whenever linking is ON, so a missing/drifted/oversized/incomplete artifact FAILS THE BUILD. Verified both ways: an empty `-Pripdpi.prebuiltXrayAarDir` with `-Pripdpi.linkXray=true` fails the build; the real artifact passes. A dedicated CI workflow remains a separate follow-up.

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

## Work log

- 2026-06-05: Version pins (libxray v26.3.27, xray-core 1.260327.0, gomobile pin) and license docs confirmed in gradle/libs.versions.toml + docs/native/libxray-packaging.md; build-libxray.sh and verify-libxray-artifacts.sh exist in scripts/native/; verifyLibXrayArtifacts Gradle task wired in core/engine/build.gradle.kts but intentionally not attached to assemble; native/xray/artifacts/ dir absent (no real .aar produced); task remains blocked on external Go+gomobile+NDK29 toolchain.
- 2026-06-05: Re-audit confirms all checkboxes accurate. Criteria 1 ([x]) and 4 ([x]) verified against gradle/libs.versions.toml and docs/native/libxray-packaging.md. Criteria 2, 3, 5 remain open/partial — no real .aar exists (native/xray/artifacts/ has only README.md), and no CI workflow (.github/workflows/) references verifyLibXrayArtifacts or verify-libxray-artifacts.sh. Status blocked is correct.
- 2026-06-11 (triage + unblock plan): Corrected criterion 1's version string to match `gradle/libs.versions.toml` (was the stale `1.4.4 / 26.4.7`; actual pins are `libxray v26.3.27` / `xray-core 1.260327.0`). Re-confirmed the Gradle seam: `core/engine/build.gradle.kts:40` registers `verifyLibXrayArtifacts` (`Exec`) over `ripdpi.prebuiltXrayAarDir` (default `native/xray/artifacts/`), with a release-like canary guard, deliberately detached from `assemble`; `grep -rl 'libxray\|verifyLibXray' .github/workflows/` is still empty — the CI workflow is the one remaining piece of pure RIPDPI work. Authored `docs/native/libxray-unblock-checklist.md`: ordered build steps (x86_64 host + Go + gomobile pin + NDK29 → `build-libxray.sh` → `verify-libxray-artifacts.sh` → `:core:engine:verifyLibXrayArtifacts`) and the suggested `native-libxray.yml` schedule/dispatch-gated workflow seam. No artifact fabricated; `native/xray/` still holds only README.md. Criteria 2/3 stay [ ] (no real `.aar`), criterion 5 stays PARTIAL (verify logic proven, never run end-to-end + no CI job yet); status stays `blocked`.
- 2026-06-15 (A-3 link + verify wiring): A real per-ABI `libxray.aar` (libXray v26.3.27, xray-core 1.260327.0, NDK 29.0.14206865, 4 ABIs, payload < 160 MiB) now exists in the gitignored `native/xray/artifacts/`. `core/engine/build.gradle.kts` gained presence/opt-in gating (`hasXrayAar` OR `-Pripdpi.linkXray=true`): when ON it links the AAR via `implementation(files(...))`, swaps in the `src/xrayLinked` real impl source set, and attaches `verifyLibXrayArtifacts` to `preBuild` (a bad/missing/oversized artifact fails the build — verified by pointing `prebuiltXrayAarDir` at an empty dir with `linkXray=true`); when OFF (offline default) it swaps in `src/xrayStub` and adds no AAR / no gate, preserving offline builds. Criteria 2, 3, 5 flipped to [x]; status → `in-progress`. Remaining: the dedicated CI workflow (`native-libxray.yml`) is still a separate follow-up.
