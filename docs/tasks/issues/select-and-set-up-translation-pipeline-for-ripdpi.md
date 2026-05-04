---
title: Select and set up translation pipeline for RIPDPI
type: task
status: backlog
area: ui
priority: medium
owner: unassigned
parent: epic-localization-expansion
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Select and set up translation pipeline for RIPDPI #repo/RIPDPI #area/ui #status/backlog 🔼

## Summary

Pick and stand up the translation pipeline: evaluate self-hosted Weblate
vs SaaS Crowdin vs a pure GitHub-PR-based workflow, make the call, and
land the chosen flow in `docs/` + CI.

## Context

Picking wrong here makes every future locale slower. Bias is toward a
self-hosted or pure-PR workflow because the project cannot tolerate a
SaaS service being geofenced or priced-out. Weblate is the default
candidate; a PR-only flow is the fallback if ops budget is zero.

## Acceptance criteria

- [ ] Decision doc in `docs/localization.md` with: compared options,
    chosen tool, ops cost estimate, contributor instructions,
    escalation plan if the chosen tool disappears.
- [ ] CI check that exports `values/strings.xml` into the pipeline's
    ingestion format on every main merge.
- [ ] `translatable="false"` audit complete: any string the translator
    must not touch is marked.
- [ ] Translator-visible glossary committed (at minimum: protocol
    names, service-mode names, diagnostic verdict names).
- [ ] README has a "Translate RIPDPI" section pointing at the chosen
    tool.

## Links

- [[Epic - Localization expansion]]


## native-hotspot-decomposition
