---
id: UIX-1793534441702605
title: Give cards a tonal hierarchy and one elevation rule
kind: feature
status: done
area: ui
priority: medium
owner: UI overhaul
parent: null
blocked_by: []
spec_mode: required
openspec_change: m3-card-hierarchy
created: 2026-11-01
updated: 2026-11-02
closed_at: "2026-11-02T08:09:21Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: One card rule is documented in DESIGN.md and docs/design-system.md, and RipDpiCard defaults to Tonal across the swept call sites (22331bd47, cc3e74d4f, 54a4d9ac8). Goldens were blessed by the owner (498222d19, 5bbbe1452). Device captures on API 35 only; tonal card against page contrast is 1.11 light and 1.13 dark at standard contrast, with the outline at medium and high. CI run 41401888905 on 415f31e87 passed on attempt 3 (attempts 1 and 2 failed only rust-workspace-tests on an AmneziaWG interop trailer case, assessed as an unrelated validator flake because the branch changes no Rust code).
---

## Goal

Cards carry a clear weight. Today almost every surface is the same outlined card on the page color, so primary and secondary content look equally important. Some cards use a grey fill with no rule, and shadows are inconsistent. The 2026-11-01 Material 3 UI/UX audit found about 20 call sites that use the Elevated or Status card role for ordinary content. This contradicts `DESIGN.md`: "default cards stay flat".

The goal is one documented rule, applied everywhere:

- `surfaceContainer` tonal steps group content.
- An outline marks only interactive or selected cards.
- A shadow marks only hero or selected cards.

## Call sites to change

- `HistoryCards.kt:50`, `:102`
- `DiagnosticsScanSection.kt:355`
- `DiagnosticsToolsSection.kt:459`, `:505`
- `DiagnosticsScanProgress.kt:413`
- `DiagnosticsOutcomeCard.kt:45`
- `DiagnosticsCards.kt:551`
- Settings sections: `QuicSection.kt:155`, `DesyncAdaptiveFakeTtlSection.kt:93`, `DesyncFakePayloadSection.kt:60`, `AdaptiveFallbackSection.kt:133`, `DetectionResistanceSection.kt:99`, `ActivationWindowSection.kt:119`, `HttpParserSection.kt:97`
- `OwnedStackBrowserScreen.kt:237`
- `DetectionResultCards.kt:86`, `:131`
- `DnsSettingsCards.kt:83`
- `DetectionCheckScreen.kt:447`
- `HomeModeCard.kt:65`, `:70`
- The `RipDpiRemediationCard` default (`RipDpiScreenChrome.kt:192`)

Line numbers come from the audit and can drift. Re-grep the Elevated and Status roles before editing.

## Ownership

UI module paths only. Golden fixtures (Roborazzi baselines) are a serialized lane with one writer. The task does not touch protobuf, JNI, or locale resources.

## Acceptance criteria

- `DESIGN.md` and `docs/design-system.md` state the one card rule and name the role for each use: grouping, interactive or selected, hero.
- Every call site above follows the rule. No ordinary-content card uses the Elevated or Status role. A card shows an outline only when it is interactive or selected, and a shadow only when it is a hero or selected card.
- Roborazzi goldens for the changed screens are re-recorded and blessed with owner approval, following `golden-bless-discipline.md`.
- A device capture of Home, Diagnostics, and Settings shows primary content visibly heavier than secondary content.
