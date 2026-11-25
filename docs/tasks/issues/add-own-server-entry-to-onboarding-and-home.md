---
id: UIX-1795615850526190
title: Add an own-server entry point to onboarding and home
kind: feature
status: review
area: ui
priority: high
owner: own-server-entry
parent: EPC-1795615697939690
blocked_by: []
spec_mode: required
openspec_change: uix-1795615850526190-add-own-server-entry-to-onboarding-and-home
created: 2026-11-25
updated: 2026-11-25
---

## Goal

A new user who has their own VPN server finds how to add it during onboarding and from the home and Connection screens.

## Context

Found in the 2026-11-25 on-device user-flow audit (Pixel 8 Pro, githubDebug at 66673515dd, clean install). Onboarding covers only local DPI mode; its VPN permission card says `No remote VPN server is used`. The home screen and the Connection tab have no add-server action. Today the only path is Connection, tap the grey Relay node in the diagram, then `Relay off`, which opens the Profiles sheet.

## Acceptance criteria

- Onboarding has an optional, skippable step that offers Paste link, Scan QR, Import file, and Add manually.
- The home screen shows an add-server action while no server is saved.
- The Connection tab has a visible Add server control that does not depend on the diagram node.
- Onboarding copy does not claim that no remote server is used when the user added one.
- New strings exist in all ten locales and Roborazzi baselines are updated under the bless workflow.

## Ownership

One writer in a dedicated worktree owns onboarding, home, and Connection screens in `app` with their strings and baselines. Locale resource sets are a serialized lane: only this writer changes them while the task is in progress..
