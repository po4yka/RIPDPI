---
id: AND-1794036523065646
title: Parse import deep links with standard URI components
kind: bug
status: review
area: android
priority: high
owner: Standard-library migration
parent: EPC-1794036260770217
blocked_by: [DAT-1794036514216544]
spec_mode: required
openspec_change: parse-import-deep-links-with-uri
created: 2026-11-07
updated: 2026-11-07
status_detail: Targeted tests, staticAnalysis, ci-gates, and independent re-review passed.
---

## Goal

Import links must separate query from fragment and accept only complete HTTPS subscription targets.

## Audit evidence

Audit finding 2, 2026-11-07, baseline 202f970bb3574980dd2eb87fea002cac8fbe29f7. This is a confirmed replacement candidate, not proof that an implementation is complete. Recheck callers and tests before editing.

## Scope

Use java.net.URI for the outer custom scheme and existing OkHttp HttpUrl for the decoded HTTP target. Keep a narrow adapter for provider-specific link forms.

Keep sub before url, first-value duplicate handling, typed encoding errors, subscription versus one-shot intent, and Hiddify nested URL forms.

## Acceptance criteria

- The outer #label is not part of the URL extracted from ripdpi://import?sub=https%3A%2F%2Fexample.com%2Fsub#label.
- An incomplete target https://, a non-HTTPS target, and malformed escapes return typed errors without navigation or network access.
- A SingBox name #A+B remains A+B; existing provider link fixtures and import intent still pass.
- Extend RipDpiImportDeepLinkParserTest and SingBoxDeepLinkParserTest; test literal and encoded plus, fragment separation, duplicate parameters, and nested URLs.
- Named local gates and repository-required independent review pass; record exact implementation SHA and command results. Commit each complete atomic implementation unit on main.

## Dependencies and order

Priority band 1; recommended position 2 in the epic queue. Prerequisites: DAT-1794036514216544

No dependency is implied by priority alone. Shared Cargo and Gradle files use one writer; the epic's queue prevents concurrent edits without introducing false semantic blockers.

## Ownership

- `app/src/main/kotlin/com/poyka/ripdpi/proxyimport/RipDpiImportDeepLinkParser.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/proxyimport/SingBoxDeepLinkParser.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/proxyimport/ImportDeepLinkUri.kt`

The same writer owns related module tests, direct dependency declarations, and task artifacts. Shared dependency and generated-output lanes follow the epic. The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

## Dependency decision

Use only existing platform or workspace libraries. Check the direct module dependency before editing.

## Verification

- `./gradlew :app:testGithubFullDebugUnitTest --tests "*RipDpiImportDeepLinkParserTest" --tests "*SingBoxDeepLinkParserTest"`
- `./gradlew staticAnalysis`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
