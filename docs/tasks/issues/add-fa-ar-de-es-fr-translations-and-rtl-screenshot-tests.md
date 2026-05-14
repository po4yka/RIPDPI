---
title: Add fa ar de es fr translations and RTL screenshot tests
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

- [ ] #task Add fa ar de es fr translations and RTL screenshot tests #repo/RIPDPI #area/ui #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-fa-ar-de-es-fr-translations-and-rtl-screenshot-tests`
- **Verify:** `just test-screenshots`
- **Scope (only modify these + this file + the ledger):** `app/**`, `app/src/main/res/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Land human-reviewed translations for Persian (fa), Arabic (ar), German
(de), Spanish (es), and French (fr). Add RTL-variant Roborazzi screenshot
tests for fa and ar to catch layout regressions.

## Context

Persian and Arabic are RTL and represent the next-largest bypass user
cohorts after Chinese. German / Spanish / French are coverage locales;
their volume is lower but their review cost is lowest (native-speaker
contributors are easier to recruit).

## Acceptance criteria

- [ ] `values-fa/`, `values-ar/`, `values-de/`, `values-es/`,
    `values-fr/` each cover ≥95% of default strings.
- [ ] Each locale has documented human reviewer sign-off.
- [ ] Roborazzi RTL screenshot tests for fa and ar on Home, Config,
    Diagnostics, Settings, Onboarding.
- [ ] RTL padding / chevron / icon-flip regressions, if any, fixed in
    the same PR stack.
- [ ] Persian and Arabic glyph coverage for the Geist font family is
    verified; fallback is wired where needed.
- [ ] Weekly string-diff from the pipeline keeps these locales fresh
    without manual polling.

## Source references

**Translation corpora — use as reference only, NOT verbatim copy** (string keys and license headers differ):

- **Reference implementation notes:** — 20 locale directories under `app/src/main/res/`. Relevant paths: `values-fa/`, `values-ar/`, `values-de/`, `values-es/`, `values-fr/`. Use for proxy/protocol terminology reference in each language.
- **amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`) — 35 locale directories; the richest RTL reference among WireGuard-ecosystem clients. Paths: `ui/src/main/res/values-fa-rIR/`, `values-ar-rSA/`, `values-de/`, `values-es-rES/`, `values-fr/`. RTL layout survey is especially strong here — look at how AWG handles bidirectional text in their `strings.xml` with HTML entities and bidi marks.

**Adapt (glossary alignment):** Consistent terminology for tunnel/peer/interface across WireGuard-ecosystem clients (AWG baseline); for proxy protocol names, reference implementation is the broader reference. **Skip:** verbatim value copy. **License note:** Both upstreams are Apache 2.0 / GPL-3.0; string-value copies would propagate headers — use as terminology reference only.

## Links

- [[Epic - Localization expansion]]
- [[Select and set up translation pipeline for RIPDPI]]
