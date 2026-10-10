---
id: UIX-1791634100675692
title: Present verified Google Play marketing assets in READMEs
kind: chore
status: doing
area: ui
priority: high
owner: Play Store presentation maintainer
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-10
updated: 2026-10-10
spec_reason: tooling-only
---

## Goal

Show Google Play feature graphics and designed screenshots in all README galleries. Use verified current Android UI captures and accurate feature copy. Keep the app UI pixels, capture states, branding and locale provenance intact.

## Acceptance criteria

- Render six 1080x1920 phone assets and one 1024x500 feature graphic per existing marketing locale; outputs are RGB with no alpha and within the size limit.
- The first three cards show real Home, Scan setup and Relay editor UI. Do not add invented results, active-state badges, future features or guaranteed network outcomes.
- Keep added screenshot taglines within 20 percent of the canvas and preserve the complete source frame where shown. Check clipping, overlap, fonts and RTL at full size and thumbnail size.
- All nine README galleries use the designed assets with localized text and a labelled English fallback for Hindi and Brazilian Portuguese.
- Source-capture validation, renderer build, strict asset validation, README selectors and independent review pass. Commit each finished unit, integrate and push main under the existing user authorization.

## Parallel ownership

| Owner | Paths |
| --- | --- |
| Asset worker in isolated play-store-assets worktree | play-store-screenshots/src, capture.mjs, scripts/validate-play-store.mjs, package scripts, renderer README, generated docs/screenshots marketing PNGs; no raw capture changes |
| Integration owner in play-store-presentation worktree | Nine root/localized READMEs, generator AGENTS.md and screenshot skill factual guidance, task records and generated board, combined checks, integration and push |
| Read-only reviewers | Claims, source evidence, layout and final diff |

The existing seven marketing locales remain en, ru, es, de, fr, fa and zh-CN. Raw Android frames and app source are not changed. The app icon uses the canonical launcher asset. Generator and output files have one writer.
