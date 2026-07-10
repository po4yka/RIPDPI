---
title: Self-audit Android app identity against package-based VPN detection
type: task
status: done
area: ci
priority: high
owner: Codex
parent: null
blocks: []
blocked_by: []
created: 2026-07-10
updated: 2026-07-10
---

## Summary

Add a blocking per-release review of RIPDPI's resolved Android application IDs against package-based circumvention-tool detection, while preserving the published stable ID and its upgrade continuity.

## Acceptance criteria

- [x] A checked-in review records the current app version, all resolved release variants, threat-source provenance, the reviewed package catalog, derived matches, recognizability findings, and an explicit identity decision.
- [x] A Gradle task emits the resolved release application IDs through the Android Components API without parsing build scripts.
- [x] A deterministic checker rejects stale versions, variant or workflow drift, application-ID drift, incomplete provenance, and unresolved known-catalog matches.
- [x] Normal CI and release publishing run the checker before release artifacts are signed or published.
- [x] Distribution documentation explains the per-release review and stable-ID tradeoff.
- [x] Unit and integration checks cover the current accepted baseline and all blocking failure modes.

## Sources

- `/Users/po4yka/GitRep/censorship-bypass/wikis/mobile-platform-enforcement/wiki/concepts/app-level-vpn-detection.md`
- `/Users/po4yka/GitRep/censorship-bypass/wikis/mobile-platform-enforcement/wiki/concepts/mintsifry-vpn-detection-methodology.md`

## Work log

- 2026-07-10: Added the `0.1.3`/11 identity review, AGP-resolved six-variant manifest, deterministic checker, 12 focused checker tests, fleet client-release/no-ship policy membership, CI/release wiring, and distribution runbook. `python3 -m unittest scripts.tests.test_app_identity_review scripts.tests.test_fleet_release_gates` passed 39 tests; Ruff, ktlint, JSON parsing, workflow validation, Gradle identity generation, release-version verification, and both policy checkers passed.
- 2026-07-10: Full `staticAnalysis` was attempted and reached the repository-wide LoC gate, which failed on unchanged `native/rust/crates/ripdpi-relay-core/src/tests.rs` at 1,583 lines against a 1,500-line limit. Full Python discovery was also attempted; the same eight pre-existing fleet-fixture/ASN test failures reproduce on untouched `main`. Neither blocker is modified by this task.
