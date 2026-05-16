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
updated: 2026-05-14
---

- [ ] #task Epic - Localization expansion #repo/RIPDPI #area/epic #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `epic-localization-expansion`
- **Verify:** `all child rows in GOAL_LEDGER.md are DONE or BLOCKED`
- **Scope (only modify these + this file + the ledger):** _epic — coordination only; child tasks carry the file scope_
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Goal

Reach realistic language coverage for the target user base. Today RIPDPI
ships English and Russian only; reference implementation ships ~20 locales. Pick the subset
that corresponds to the actual bypass-user geographies and get translations
landed with a pipeline that sustains updates.

## Why now

Most of the actual bypass-client user base outside Russia is in Persian-,
Chinese-, and Arabic-speaking regions. Shipping EN+RU only is a hard
adoption barrier in those geographies. Picking a translation pipeline now
also prevents the one-off-PR-per-language chaos other projects suffer.

## Key decisions

- **Pick a self-hosted pipeline (Weblate) over a SaaS (Crowdin)** to avoid
coupling release cadence to an external service that could be geofenced
or priced-out. Defer final decision to the pipeline task but bias toward
self-hosted.
- **First wave targets user-geography match:** zh-CN, fa (Persian),
ar (Arabic), de, es, fr. These cover ~70% of realistic non-RU users
sampled from community chat demographics.
- **Do not machine-translate and ship.** All strings go through a human
translator before merging. MT pre-translations are acceptable as a
starting point for translators, not a shipping state.
- **String freeze N weeks before release.** Translators need a stable
source.
- **Drop strings that aren't translator-safe** (e.g., protocol names,
acronyms, technical keys) via the standard Android `translatable="false"`
marker.

## Scope

- **In scope:** translation pipeline selection and setup, `values-zh-rCN`,
`values-fa`, `values-ar`, `values-de`, `values-es`, `values-fr` initial
wave; `translatable="false"` audit on existing strings; right-to-left
layout verification for Arabic and Persian.
- **Out of scope:** the full reference implementation locale set (20+). Add additional
languages as Tier 2 when pipeline is live and community interest
materializes. No in-app language picker — rely on system locale.

## Ship definition

- [ ] Translation pipeline is documented in `docs/`; a new contributor can
    open a PR with a new locale by following README steps only.
- [ ] `values-zh-rCN`, `values-fa`, `values-ar`, `values-de`, `values-es`,
    and `values-fr` directories exist and cover ≥95% of `values/` strings.
- [ ] RTL layout renders correctly in fa and ar (screenshot tests under
    Roborazzi cover the main screens in each).
- [ ] `translatable="false"` is set on strings that must not be translated
    (protocol names, internal keys).
- [ ] A CI check fails the build if a new source string is added without
    being picked up by the pipeline export.

## Child tasks

- [[Select and set up translation pipeline for RIPDPI]]
- [[Add zh-CN translation and initial human review]]
- [[Add fa ar de es fr translations and RTL screenshot tests]]

## Dependencies

- None hard-blocking. Best landed after the subscription/profile/routing
epics stabilize so translators are not chasing moving strings.

## Risks / open questions

- Weblate self-hosting cost and ops; if the maintainer cannot absorb it,
fall back to a read-only fork-based PR workflow (no runtime service).
- Translator recruiting; community chats are the most realistic source but
introduce moderation overhead.
- RTL regression risk; bake in Roborazzi RTL variants during the setup task
rather than adding post hoc.
- Locale-specific font fallbacks for Persian/Arabic with the Geist family —
verify glyph coverage or wire fallbacks.

## Links

- [[ripdpi-android]]
- Child issues: 3
