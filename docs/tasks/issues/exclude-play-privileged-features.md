---
id: AND-1795444347241489
title: Exclude privileged Android features from Google Play
kind: feature
status: done
area: android
priority: high
owner: Android distribution
parent: null
blocked_by: []
spec_mode: required
openspec_change: exclude-play-privileged-features
created: 2026-11-23
updated: 2026-11-24
closed_at: "2026-11-24T17:43:49Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: CI 45441455734 attempt 2 passed at d1b75749a2. Play, GitHub and F-Droid debug and release packages passed. The package scanner and non-rooted API 35 VPN, proxy and PCAP checks are recorded in the archived verification. Signed AAB and split APK checks remain release promotion gates.
---

## Goal

Ship Google Play builds without Android superuser functionality. Keep opt-in privileged features in F-Droid and GitHub.

## Acceptance criteria

- Verify the Play package boundary and the GitHub and F-Droid debug APKs from one checkout.
- Verify migration with repository tests. Exercise the ordinary Play VPN, proxy, and PCAP paths on a non-root emulator.
- Verify the release scanner and its promotion wiring. Check the signed Play AAB and its APK splits in the release workflow before promotion.

## Ownership

The integration owner controls Gradle, CI, protobuf, and all ten locale resource sets. The Android UI writer owns root settings and navigation Kotlin files. The native writer owns the privileged operation and runtime-platform crates. The artifact verifier writer owns its Python checks and tests. Writers use separate worktrees and the integration owner commits completed parts on main.
