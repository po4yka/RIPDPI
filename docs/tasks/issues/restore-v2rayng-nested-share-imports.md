---
id: AND-1794055823897986
title: Restore v2rayng nested proxy share-link imports
kind: bug
status: review
area: android
priority: medium
owner: Import parser
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-07
updated: 2026-11-07
spec_reason: regression-tested-single-module
status_detail: Nested Trojan import restored; full app suite 3433/3433, staticAnalysis, ci-gates, and independent review passed.
---

## Goal

Restore profile imports from v2rayng://install-config?url=<encoded share-link>. This single-module regression appeared when the subscription parser began to require HTTPS.

## Acceptance criteria

- A nested Trojan share-link imports the expected profile fields after one outer query decode.
- The wrapper requires the v2rayng scheme and exact install-config authority; malformed wrappers and unsupported or invalid nested profiles remain invalid.
- First-value query handling, profile secret redaction, strict HTTPS subscriptions, and Hiddify IPv6 imports remain intact.
- Reproduce the scanner failure, then pass the full :app:testGithubFullDebugUnitTest suite, staticAnalysis, ci-gates, and independent review.
- Commit the implementation on main and close this task and AND-1794054244138121 after successful verification.

## Ownership and approach

Root is the sole writer of app/src/main/kotlin/com/poyka/ripdpi/proxyimport/ScannerUriDispatcher.kt and its existing test class. Use the existing URI/query helpers to read the install wrapper and leave profile validation to ProxyUriShareDispatcher. Subscription validation remains owned by SingBoxDeepLinkParser. Review agents are read-only.

The user authorized direct main work and commits, overriding worktree isolation. No dependencies, schemas, resources, or network operations change. No push or publication is authorized.

## Verification

- Red reproduction: app unit tests filtered to ScannerUriDispatcherTest.
- Final gate: ./gradlew :app:testGithubFullDebugUnitTest.
- ./gradlew staticAnalysis after both app KSP variants.
- just ci-gates and ./taskctl validate.

## Evidence

- Before the fix, 17 scanner tests ran and two nested-profile cases failed at the existing HTTPS-only subscription boundary.
- The complete :app:testGithubFullDebugUnitTest gate passed: 3433 tests, zero failures, errors, or skipped tests. ScannerUriDispatcherTest passed all 17 tests; SingBoxDeepLinkParserTest passed all 25 tests, including the Hiddify IPv6 regression.
- The profile test checks server, port, password, SNI, and display name against independent values after nested percent decoding. Additional tests cover wrapper case, first-value duplicates, malformed input, unsupported targets, double encoding, and unchanged HTTPS subscription classification.
- Both app KSP variants, staticAnalysis, just ci-gates, task contract validation, and independent pr-reviewer passed. The Darwin CI recipe skips the Linux-owned runtime-platform API snapshot.
- Logs: /tmp/ripdpi-trojan-red.log, /tmp/ripdpi-trojan-app-tests.log, /tmp/ripdpi-trojan-static.log, and /tmp/ripdpi-trojan-ci.log.
- No dependencies, golden files, or baselines changed. No device or remote CI acceptance is claimed.

The implementation is ready for committed review and task closure. This also resolves the full-app acceptance block of AND-1794054244138121.
