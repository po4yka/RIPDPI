---
id: SVC-1794755208139697
title: Fix signed shared-priors audit defects
kind: bug
status: review
area: service
priority: high
owner: AUX-05 maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-shared-priors-audit-defects
created: 2026-11-15
updated: 2026-11-15
status_detail: Three atomic fixes and focused tests pass; rebased combined validation is pending.
---

## Goal

Fix AUX-05 F1 HTTP buffering, F2 release URL provisioning, and F3 finite-prior arithmetic.

## Acceptance criteria

- Bound manifest and payload success/error reads before full buffering.
- Accept documented Gradle properties for both release URLs.
- Preserve the correct mean for large positive finite prior parameters.
- Run regression tests, source gates, and independent review before integration.
- Keep one fix commit per finding; integrate and push main as authorized.


## Ownership

- HTTP writer: `core/service/.../SharedPriorsCatalogNetwork.kt` and its network tests.
- Provisioning writer: `core/service/build.gradle.kts`, shared-priors build helpers and their tests.
- Arithmetic writer: `ripdpi-runtime-strategy` prior mean and selection tests only.
- Coordinator: portfolio, OpenSpec artifacts, generated board, combined validation, integration and push.
- No writer changes Cargo.lock, version catalog, locales, contracts, golden fixtures or baselines.
- Each writer uses a separate worktree. Build jobs share the machine gate, not output directories.
