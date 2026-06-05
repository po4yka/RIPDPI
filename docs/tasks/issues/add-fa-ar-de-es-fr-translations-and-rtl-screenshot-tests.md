---
title: Review landed fa de es fr translations and finish Arabic RTL coverage
type: task
status: backlog
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

Persian (fa), German (de), Spanish (es), and French (fr) string resource directories now exist and match the default string-key set. Finish the remaining work: document human review for landed locales, decide whether Arabic still belongs in scope, add `values-ar/` if it does, and bless RTL Roborazzi coverage.

## Context

Persian is already registered and Arabic is not. Arabic remains a candidate RTL locale for the bypass-user geography; German, Spanish, and French are registered coverage locales whose ongoing risk is reviewer provenance and freshness, not missing resource directories.

## Acceptance criteria

- [x] (2026-05-29) `values-fa/`, `values-de/`, `values-es/`, and `values-fr/` exist and have zero missing keys versus `values/`.
- [x] `values-ar/` exists and covers ≥95% of default strings if Arabic remains in scope.
- [x] Each landed locale has documented human reviewer sign-off.
- [x] Roborazzi RTL screenshot tests for fa, and for ar if Arabic lands, cover Home, Config, Diagnostics, Settings, Onboarding.
- [x] RTL padding / chevron / icon-flip regressions, if any, fixed in the same PR stack.
- [x] Persian glyph coverage for the Geist font family is verified; Arabic glyph coverage and fallback are verified if Arabic lands.
- [ ] Weekly string-diff from the pipeline keeps these locales fresh without manual polling.

## Source references

**Translation corpora — use as reference only, NOT verbatim copy** (string keys and license headers differ):

- **Reference implementation notes:** — 20 locale directories under `app/src/main/res/`. Relevant paths: `values-fa/`, `values-ar/`, `values-de/`, `values-es/`, `values-fr/`. Use for proxy/protocol terminology reference in each language.
- **amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`) — 35 locale directories; the richest RTL reference among WireGuard-ecosystem clients. Paths: `ui/src/main/res/values-fa-rIR/`, `values-ar-rSA/`, `values-de/`, `values-es-rES/`, `values-fr/`. RTL layout survey is especially strong here — look at how AWG handles bidirectional text in their `strings.xml` with HTML entities and bidi marks.

**Adapt (glossary alignment):** Consistent terminology for tunnel/peer/interface across WireGuard-ecosystem clients (AWG baseline); for proxy protocol names, reference implementation is the broader reference. **Skip:** verbatim value copy. **License note:** Both upstreams are Apache 2.0 / GPL-3.0; string-value copies would propagate headers — use as terminology reference only.

## Work log

- 2026-06-05: values-ar/ exists (98.7% coverage, 3047/3088 keys); all locales signed off in docs/localization-provenance.md (2026-05-30/31); PersianLocaleScreenshotTest + ArabicLocaleScreenshotTest cover all 5 screens with blessed goldens; font/glyph coverage documented via platform Noto fallback; only missing: a weekly scheduled CI job diffing changed source keys against locale files (docs/localization.md references it as a sibling task, no .github/workflows entry exists for it).

## Links

- [[Epic - Localization expansion]]
- [[Select and set up translation pipeline for RIPDPI]]
