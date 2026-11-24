---
id: AND-1795444347241489
title: Exclude privileged Android features from Google Play
kind: feature
status: review
area: android
priority: high
owner: Android distribution
parent: null
blocked_by: []
spec_mode: required
openspec_change: exclude-play-privileged-features
created: 2026-11-23
updated: 2026-11-24
---

## Goal

Ship Google Play builds without Android superuser functionality. Keep opt-in privileged features in F-Droid and GitHub.

## Acceptance criteria

- Verify the Play package boundary and the GitHub and F-Droid debug APKs from one checkout.
- Verify migration with repository tests. Exercise the ordinary Play VPN, proxy, and PCAP paths on a non-root emulator.
- Verify the release scanner and its promotion wiring. Check the signed Play AAB and its APK splits in the release workflow before promotion.

## Ownership

The integration owner controls Gradle, CI, protobuf, and all ten locale resource sets. The Android UI writer owns root settings and navigation Kotlin files. The native writer owns the privileged operation and runtime-platform crates. The artifact verifier writer owns its Python checks and tests. Writers use separate worktrees and the integration owner commits completed parts on main.
