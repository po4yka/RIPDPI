---
title: Epic - Localization expansion
type: epic
status: backlog
area: epic
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-29
---

- [ ] #task Epic - Localization expansion #repo/RIPDPI #area/epic #status/backlog 🔼

## Goal

Reach realistic language coverage for the target user base. Today RIPDPI registers seven app locales (`en`, `ru`, `es`, `de`, `fr`, `fa`, `zh-CN`) and ships string resource directories for the six non-English mirrors (`ru`, `es`, `de`, `fr`, `fa`, `zh-CN`); there is no `values-ar` directory. The remaining work is to keep those locales sustainable, add Arabic if still justified by users, and land a translation pipeline that sustains updates.

## Why now

Most of the actual bypass-client user base outside Russia includes Persian-, Chinese-, and Arabic-speaking regions. Persian and Simplified Chinese have landed; Arabic and a sustainable translation pipeline remain the adoption gap. Picking a translation pipeline now also prevents the one-off-PR-per-language chaos other projects suffer.

## Key decisions

- **Pick a self-hosted pipeline (Weblate) over a SaaS (Crowdin)** to avoid coupling release cadence to an external service that could be geofenced or priced-out. Defer final decision to the pipeline task but bias toward self-hosted.
- **Current registered locale set:** en, ru, es, de, fr, fa, zh-CN. Arabic remains the only first-wave geography from the original expansion note that has not landed.
- **Do not machine-translate and ship.** All strings go through a human translator before merging. MT pre-translations are acceptable as a starting point for translators, not a shipping state.
- **String freeze N weeks before release.** Translators need a stable source.
- **Drop strings that aren't translator-safe** (e.g., protocol names, acronyms, technical keys) via the standard Android `translatable="false"` marker.

## Scope

- **In scope:** translation pipeline selection and setup, Arabic locale addition if still justified, ongoing completeness checks for `values-zh-rCN`, `values-fa`, `values-de`, `values-es`, `values-fr`, and `values-ru`; `translatable="false"` audit on existing strings; right-to-left layout verification for Persian and Arabic if Arabic lands.
- **Out of scope:** the full reference implementation locale set (20+). Add additional languages as Tier 2 when pipeline is live and community interest materializes. No in-app language picker — rely on system locale.

## Ship definition

- [ ] Translation pipeline is documented in `docs/`; a new contributor can open a PR with a new locale by following README steps only.
- [ ] Registered non-English locales (`values-ru`, `values-es`, `values-de`, `values-fr`, `values-fa`, `values-zh-rCN`) stay complete against `values/`; `values-ar` exists and covers ≥95% of source strings if Arabic remains in scope.
- [ ] RTL layout renders correctly in fa, and in ar if Arabic lands (screenshot tests under Roborazzi cover the main screens in each).
- [ ] `translatable="false"` is set on strings that must not be translated (protocol names, internal keys).
- [ ] A CI check fails the build if a new source string is added without being picked up by the pipeline export.

## Child tasks

- [[Select and set up translation pipeline for RIPDPI]]
- [[Add zh-CN translation and initial human review]]
- [[Add fa ar de es fr translations and RTL screenshot tests]]

## Dependencies

- None hard-blocking. Best landed after the subscription/profile/routing epics stabilize so translators are not chasing moving strings.

## Risks / open questions

- Weblate self-hosting cost and ops; if the maintainer cannot absorb it, fall back to a read-only fork-based PR workflow (no runtime service).
- Translator recruiting; community chats are the most realistic source but introduce moderation overhead.
- RTL regression risk; bake in Roborazzi RTL variants during the setup task rather than adding post hoc.
- Locale-specific font fallbacks for Persian/Arabic with the Geist family — verify glyph coverage or wire fallbacks.

## Links

- [[ripdpi-android]]
- Child issues: 3
