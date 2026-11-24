---
id: AND-1787932839013427
title: Migrate Android runtime behavior to target SDK 37
kind: feature
status: done
area: android
priority: high
owner: Codex
parent: null
blocked_by: []
spec_mode: required
openspec_change: migrate-target-sdk-37
created: 2026-08-28
updated: 2026-11-24
closed_at: "2026-11-24T17:35:13Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: CI 45441455734 passed the Android instrumentation APK build, all five API levels, all four native ABI builds, and the three release packaging shards at d1b75749a2. Local API 37 grant and regrant and packaged JNI smoke passed within the approved app acceptance scope.
---

## Goal

Run every application variant at target SDK 37 without losing public-network, VPN,
proxy, diagnostics, or export behavior. Local-network access is requested on demand.

## Acceptance criteria

- Verify LAN permission grants, denials, and revocations in the app tests.
  Only dependent operations can be interrupted. Verify working LAN grants
  and regrants on an API 37 emulator.
- Verify that TLS trust failures prevent transport fallback.
- Verify APK and native builds and the hosted API 27/33/35/36/37 matrix.
  Physical Android 17 and same-L2 LAN measurements are outside app acceptance.

## Ownership

Codex owns application, service, data, diagnostics, native, SDK/catalog, locales,
CI and OpenSpec edits in the target37 worktree. The test subagent owns OwnedStackBrowserServiceTest.kt, AndroidLocalNetworkAccessTest.kt,
UnresolvedHostnameNetworkShadow.kt, MainViewModelTest.kt, LocalNetworkRuntimeTest.kt and DiagnosticsLocalNetworkPreflightTest.kt in its isolated target37-tests worktree; Codex imports its
reviewed test diffs. No concurrent writers own serialized files. Existing main
lifecycle edits are outside this task.
