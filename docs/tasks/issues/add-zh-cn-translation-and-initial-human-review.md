---
title: Review landed zh-CN translation and initial human sign-off
type: task
status: review
area: ui
priority: medium
owner: unassigned
parent: epic-localization-expansion
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-06-05
---

## Summary

`values-zh-rCN` now exists and matches the default string-key set. Finish the remaining review work: document human sign-off, keep the translation fresh through the pipeline, and keep zh-CN screenshot coverage from regressing.

## Context

MT pre-translation is acceptable as a starting point for the translator to work from, but shipping MT-only is not. The remaining task is reviewer provenance and screenshot coverage, not creating the resource directory.

## Acceptance criteria

- [x] (2026-05-29) `app/src/main/res/values-zh-rCN/strings.xml` exists and has zero missing keys versus `values/`.
- [x] At least one human reviewer sign-off documented in the merge PR.
- [x] Roborazzi screenshot tests in zh-CN for: Home, Config, Diagnostics, Settings, Onboarding.
- [ ] No hard-coded strings surface on the reviewed screens (manual audit + lint rule).
- [x] Glossary terms land in the shared glossary for consistency with other future locales.

## Source references

**Translation corpora — use as reference only, NOT verbatim copy** (string keys differ):

- **Reference implementation notes:**: `app/src/main/res/values-zh-rCN/strings.xml` — 20+ locale comparison baseline, zh-CN is their largest translation. Useful reference for proxy/protocol term translations (e.g. "订阅" for subscription, "节点" for node, "分流" for routing).
- **amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`): `ui/src/main/res/values-zh-rCN/strings.xml` — smaller vocabulary but aligned with WireGuard terminology. Reference for tunnel/peer/interface term translations.

**License note:** both upstreams are Apache 2.0 / GPL-3.0. Do NOT copy string values verbatim without proper attribution — the file headers would propagate. Use as **reference for terminology consistency** only; strings for RIPDPI must be translated independently from its own English source.

**Adapt (glossary alignment):** Match Reference implementation's zh-CN term choices for proxy/protocol vocabulary so subscription-importing users see familiar terminology. **Skip:** verbatim value copy.

## Work log

- 2026-06-05: Sign-off documented in docs/localization-provenance.md (Nikita Pochaev, 2026-05-30); zh-CN Roborazzi tests exist for all 5 screens (SimplifiedChineseLocaleScreenshotTest.kt); glossary in docs/localization-glossary.md. Remaining: HardcodedText lint rule not present in lint.xml — manual audit + lint enforcement for hardcoded strings still needed.
- 2026-06-05 (audit): Source-verified all criteria. Criterion 1: values-zh-rCN/strings.xml exists; 41 apparent missing keys are all translatable="false" in values/strings.xml, so zero translatable keys are absent. Criterion 2: provenance ledger at docs/localization-provenance.md records Nikita Pochaev review on 2026-05-30 for zh-CN row. Criterion 3: SimplifiedChineseLocaleScreenshotTest.kt covers all 5 required screens with @Config(qualifiers = "zh-rCN"). Criterion 4: no HardcodedText severity entry found in any lint.xml — [ ] is correct. Criterion 5: docs/localization-glossary.md contains zh-CN column with canonical term mappings. Status remains review (4/5 criteria done; criterion 4 unresolved).

## Links

- [[Epic - Localization expansion]]
- [[Select and set up translation pipeline for RIPDPI]]
