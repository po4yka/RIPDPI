---
id: AND-1790433824419175
title: Keep app available without camera or GPS hardware
kind: bug
status: review
area: android
priority: medium
owner: Android app
parent: null
blocked_by: []
spec_mode: required
openspec_change: make-app-hardware-optional
created: 2026-09-26
updated: 2026-09-26
---

## Goal

Prevent the CAMERA and location permissions from making optional hardware a
Google Play installation requirement. The app's core network toolkit must
remain available on camera-free and GPS-free devices.

Ownership: this worktree owns `app/src/main/AndroidManifest.xml` and this
task's OpenSpec artifacts. No permissions, locale keys, or shared registries
change.

## Acceptance criteria

- [ ] Camera, autofocus, GPS, network location, and location are declared optional.
- [ ] The merged app manifest retains the optional declarations.
- [ ] Android lint no longer reports the two permission-implied hardware warnings.
