---
title: Self-audit Android app identity against package-based VPN detection
type: task
status: doing
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

- [ ] A checked-in review records the current app version, all resolved release variants, threat-source provenance, the reviewed package catalog, derived matches, recognizability findings, and an explicit identity decision.
- [ ] A Gradle task emits the resolved release application IDs through the Android Components API without parsing build scripts.
- [ ] A deterministic checker rejects stale versions, variant or workflow drift, application-ID drift, incomplete provenance, and unresolved known-catalog matches.
- [ ] Normal CI and release publishing run the checker before release artifacts are signed or published.
- [ ] Distribution documentation explains the per-release review and stable-ID tradeoff.
- [ ] Unit and integration checks cover the current accepted baseline and all blocking failure modes.

## Sources

- `/Users/po4yka/GitRep/censorship-bypass/wikis/mobile-platform-enforcement/wiki/concepts/app-level-vpn-detection.md`
- `/Users/po4yka/GitRep/censorship-bypass/wikis/mobile-platform-enforcement/wiki/concepts/mintsifry-vpn-detection-methodology.md`

