---
id: UIX-1795615858043815
title: Complete the AmneziaWG editor
kind: feature
status: backlog
area: ui
priority: medium
owner: unassigned
parent: EPC-1795615697939690
blocked_by: []
spec_mode: required
openspec_change: uix-1795615858043815-complete-amneziawg-editor
created: 2026-11-25
updated: 2026-11-25
---

## Goal

A user can create an AmneziaWG profile by hand without an external key tool and save it without connecting.

## Context

Found in the 2026-11-25 on-device user-flow audit (Pixel 8 Pro, githubDebug at 66673515dd, clean install). The editor has no name field, so every profile is named AmneziaWG. The private key field is empty and has no key generation. The only action is Connect; there is no Save.

## Acceptance criteria

- The editor has a name field and the list shows that name.
- The editor can generate a key pair and shows the public key for copying; the private key stays protected by FLAG_SECURE.
- Save stores the profile without starting the VPN.

## Ownership

One writer in a dedicated worktree owns the AmneziaWG editor in `app`, key generation through the existing native or platform API, strings, and baselines. Locale resource sets are a serialized lane: only this writer changes them while the task is in progress..
