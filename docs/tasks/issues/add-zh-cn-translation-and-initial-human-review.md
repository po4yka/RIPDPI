---
title: Review landed zh-CN translation and initial human sign-off
type: task
status: backlog
area: ui
priority: medium
owner: unassigned
parent: epic-localization-expansion
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-29
---

- [ ] #task Review landed zh-CN translation and initial human sign-off #repo/RIPDPI #area/ui #status/backlog 🔼

## Summary

`values-zh-rCN` now exists and matches the default string-key set. Finish the remaining review work: document human sign-off, keep the translation fresh through the pipeline, and keep zh-CN screenshot coverage from regressing.

## Context

MT pre-translation is acceptable as a starting point for the translator to work from, but shipping MT-only is not. The remaining task is reviewer provenance and screenshot coverage, not creating the resource directory.

## Acceptance criteria

- [x] (2026-05-29) `app/src/main/res/values-zh-rCN/strings.xml` exists and has zero missing keys versus `values/`.
- [ ] At least one human reviewer sign-off documented in the merge PR.
- [ ] Roborazzi screenshot tests in zh-CN for: Home, Config, Diagnostics, Settings, Onboarding.
- [ ] No hard-coded strings surface on the reviewed screens (manual audit + lint rule).
- [ ] Glossary terms land in the shared glossary for consistency with other future locales.

## Source references

**Translation corpora — use as reference only, NOT verbatim copy** (string keys differ):

- **Reference implementation notes:**: `app/src/main/res/values-zh-rCN/strings.xml` — 20+ locale comparison baseline, zh-CN is their largest translation. Useful reference for proxy/protocol term translations (e.g. "订阅" for subscription, "节点" for node, "分流" for routing).
- **amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`): `ui/src/main/res/values-zh-rCN/strings.xml` — smaller vocabulary but aligned with WireGuard terminology. Reference for tunnel/peer/interface term translations.

**License note:** both upstreams are Apache 2.0 / GPL-3.0. Do NOT copy string values verbatim without proper attribution — the file headers would propagate. Use as **reference for terminology consistency** only; strings for RIPDPI must be translated independently from its own English source.

**Adapt (glossary alignment):** Match Reference implementation's zh-CN term choices for proxy/protocol vocabulary so subscription-importing users see familiar terminology. **Skip:** verbatim value copy.

## Links

- [[Epic - Localization expansion]]
- [[Select and set up translation pipeline for RIPDPI]]
