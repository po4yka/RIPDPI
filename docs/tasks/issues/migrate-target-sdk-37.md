---
id: AND-1787932839013427
title: Migrate Android runtime behavior to target SDK 37
kind: feature
status: doing
area: android
priority: high
owner: Codex
parent: null
blocked_by: []
spec_mode: required
openspec_change: migrate-target-sdk-37
created: 2026-08-28
updated: 2026-08-28
---

## Goal

Run every application variant at target SDK 37 without losing public-network, VPN,
proxy, diagnostics, or export behavior. Local-network access is requested on demand.

## Acceptance criteria

LAN grants, denials and revocations affect only dependent operations; TLS trust failures
cannot fall back to a weaker stack. API 27/33/35/36/37 CI and physical API 37 acceptance
must be observed before closure. Refusal-only behavior does not satisfy LAN capability.

## Ownership

Codex owns application, service, data, diagnostics, native, SDK/catalog, locales,
CI and OpenSpec edits in the target37 worktree. The test subagent owns OwnedStackBrowserServiceTest.kt, AndroidLocalNetworkAccessTest.kt,
UnresolvedHostnameNetworkShadow.kt, MainViewModelTest.kt, LocalNetworkRuntimeTest.kt and DiagnosticsLocalNetworkPreflightTest.kt in its isolated target37-tests worktree; Codex imports its
reviewed test diffs. No concurrent writers own serialized files. Existing main
lifecycle edits are outside this task. No commit, push, integration or release is authorized.
