---
id: UIX-1793534654918156
title: Give Home a clear status hierarchy on phone and tablet
kind: feature
status: backlog
area: ui
priority: medium
owner: UI overhaul
parent: null
blocked_by: []
spec_mode: required
openspec_change: m3-home-hierarchy
created: 2026-11-01
updated: 2026-11-01
---

## Goal

The Home screen shows connection status first. The 2026-11-01 Material 3 audit found three problems:

- On the phone, an amber "System lockdown unknown" warning takes about 45% of the first screen above connection health, and a large empty area follows.
- In the connected state, the status is pushed out of view.
- On the tablet, Home is half empty, the Connect button is oversized in an 800px column, and the floating side column does not line up.

The goal is a clear status hero that shows state, duration, and strategy. Notices collapse into a compact banner or chip. The primary button has a capped width. No dead area remains.

## Ownership

Home screen UI paths in `:app` only. Golden fixtures (Roborazzi baselines) are a serialized lane with one writer. The task does not touch protobuf, JNI, or locale resources, except any new string key, which lands in every locale in the same commit.

## Acceptance criteria

- On the phone, a status hero with state, duration, and strategy is the first element. Notices show as a compact banner or chip and do not take more than a small share of the first screen.
- In the connected state, the status hero is fully visible on the first screen without scrolling.
- On the tablet, the primary button width is capped, the columns line up, and no half-empty area remains.
- Phone and tablet Roborazzi goldens are re-recorded and blessed with owner approval, following `golden-bless-discipline.md`.
- A device capture of the phone and tablet layouts, in the disconnected and connected states, confirms the result.
