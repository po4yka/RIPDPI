---
id: AND-1795444347241489
title: Exclude privileged Android features from Google Play
kind: feature
status: doing
area: android
priority: high
owner: Android distribution
parent: null
blocked_by: []
spec_mode: required
openspec_change: exclude-play-privileged-features
created: 2026-11-23
updated: 2026-11-23
---

## Goal

Ship Google Play builds without Android superuser functionality. Keep opt-in privileged features in F-Droid and GitHub.

## Acceptance criteria

- Verify that Play release DEX, libraries, assets, resources, reports, and manifests exclude privileged features.
- Check Play startup and old data on a device with privilege available. Exercise the normal VPN, proxy, and PCAP paths.
- Verify both extended builds and a combined build from the same checkout.

## Ownership

The integration owner controls Gradle, CI, protobuf, and all ten locale resource sets. The Android UI writer owns root settings and navigation Kotlin files. The native writer owns the privileged operation and runtime-platform crates. The artifact verifier writer owns its Python checks and tests. Writers use separate worktrees and the integration owner commits completed parts on main.
