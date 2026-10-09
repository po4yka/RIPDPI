---
id: UIX-1791534456996035
title: Make diagnostics flow actionable and readable
kind: feature
status: review
area: ui
priority: high
owner: root
parent: null
blocked_by: []
spec_mode: required
openspec_change: diagnostics-guided-flow
created: 2026-10-09
updated: 2026-10-09
status_detail: UI source units are pushed on main. Final Full APK and emulator checks passed. Exact-source app tests, coverage, Roborazzi and static analysis passed in CI. Release acceptance is blocked by the pre-existing native size gate; Simple remains unverified without its real relay asset.
---

## Goal

Make diagnostics run controls visible, next actions accurate, and measured results easy to inspect and copy.

## Acceptance criteria

- Quick and full Home analysis expose whole-run stop. Active profile identity remains stable.
- Saved persona controls disclosure. Recommendation actions describe their real effect and recheck uses measured evidence.
- Catalogs and attempts collapse. Copied results preserve transfer and stage evidence.
- Blockcheck does not infer an IP or SNI mechanism from one failure.
- Focused behavior tests, locale lint, static analysis, review, and disposable visual checks have observed outcomes.
- Each finished unit is committed, integrated on main, and pushed as authorized. Artifact, remote CI, and device evidence are recorded separately.
