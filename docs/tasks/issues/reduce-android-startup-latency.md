---
id: AND-1795494902099648
title: Reduce Android startup latency
kind: feature
status: doing
area: android
priority: high
owner: Android performance
parent: null
blocked_by: []
spec_mode: required
openspec_change: reduce-android-startup-latency
created: 2026-11-24
updated: 2026-11-24
---

## Goal

Reduce the work before the first usable Android screen and measure startup regressions.

## Acceptance criteria

- Required recovery completes before the first screen can use the stores.
- Diagnostics maintenance runs outside the startup wait.
- The first usable screen reports full display.
- The benchmark checks cold and warm metrics against a measured reference.
