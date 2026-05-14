---
title: Add zh-CN translation and initial human review
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

- [ ] #task Add zh-CN translation and initial human review #repo/RIPDPI #area/ui #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-zh-cn-translation-and-initial-human-review`
- **Verify:** `just test-screenshots`
- **Scope (only modify these + this file + the ledger):** `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Land a human-reviewed `values-zh-rCN` translation covering ≥95% of
`values/` strings. zh-CN is the first wave because the Chinese bypass
community is the largest non-Russian user cohort.

## Context

MT pre-translation is acceptable as a starting point for the translator
to work from, but shipping MT-only is not. Screenshot tests cover the
main screens in zh-CN to catch layout breakage from longer strings.

## Acceptance criteria

- [ ] `app/src/main/res/values-zh-rCN/strings.xml` covers ≥95% of
    default strings; uncovered strings list is tracked in the
    pipeline.
- [ ] At least one human reviewer sign-off documented in the merge PR.
- [ ] Roborazzi screenshot tests in zh-CN for: Home, Config,
    Diagnostics, Settings, Onboarding.
- [ ] No hard-coded strings surface on the reviewed screens (manual
    audit + lint rule).
- [ ] Glossary terms land in the shared glossary for consistency with
    other future locales.

## Source references

**Translation corpora — use as reference only, NOT verbatim copy** (string keys differ):

- **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`): `app/src/main/res/values-zh-rCN/strings.xml` — 20+ locale comparison baseline, zh-CN is their largest translation. Useful reference for proxy/protocol term translations (e.g. "订阅" for subscription, "节点" for node, "分流" for routing).
- **amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`): `ui/src/main/res/values-zh-rCN/strings.xml` — smaller vocabulary but aligned with WireGuard terminology. Reference for tunnel/peer/interface term translations.

**License note:** both upstreams are Apache 2.0 / GPL-3.0. Do NOT copy string values verbatim without proper attribution — the file headers would propagate. Use as **reference for terminology consistency** only; strings for RIPDPI must be translated independently from its own English source.

**Adapt (glossary alignment):** Match NekoBox's zh-CN term choices for proxy/protocol vocabulary so subscription-importing users see familiar terminology. **Skip:** verbatim value copy.

## Links

- [[Epic - Localization expansion]]
- [[Select and set up translation pipeline for RIPDPI]]
