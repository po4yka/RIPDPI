---
id: DAT-1794036514216544
title: Decode profile URI fragments exactly once
kind: bug
status: review
area: data
priority: high
owner: Standard-library migration
parent: EPC-1794036260770217
blocked_by: []
spec_mode: required
openspec_change: normalize-profile-uri-fragments
created: 2026-11-07
updated: 2026-11-07
status_detail: Implementation 4cbb4f47f passed targeted tests, static analysis, source gates, and independent review.
---

## Goal

Imported profile names must preserve plus signs, percent characters, and Unicode without a second decode.

## Audit evidence

Audit finding 1 (attachment 2), 2026-11-07, baseline 202f970bb3574980dd2eb87fea002cac8fbe29f7. This is a confirmed replacement candidate, not proof that an implementation is complete. Recheck callers and tests before editing.

## Scope

Use java.net.URI fragment semantics once. Make raw and decoded fragment inputs explicit; remove URLDecoder from already decoded inputs.

Keep each supported share-link format and export/import round trips. Do not change relay activation or stored profile identifiers.

## Acceptance criteria

- A profile with fragment #A%2BB imports with display name A+B.
- Malformed authority or userinfo escapes return null without partial import or credential disclosure; existing query and fragment repair preserves stray percent characters as text.
- Fragments with literal plus, %25, spaces, and Unicode retain one decode across each supported format and export/import.
- Extend existing runtime-state ProxyUriCodecTest and core/data ProxyUriCodecTest and ProxyUriCodecExportTest.
- Named local gates and repository-required independent review pass; record exact implementation SHA and command results. Commit each complete atomic implementation unit on main.

## Dependencies and order

Priority band 1; recommended position 1 in the epic queue. Prerequisites: None. Respect the epic priority and shared-file queue.

No dependency is implied by priority alone. Shared Cargo and Gradle files use one writer; the epic's queue prevents concurrent edits without introducing false semantic blockers.

## Ownership

- `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/uri/ProxyUriCodec.kt`

The same writer owns related module tests, direct dependency declarations, and task artifacts. Shared dependency and generated-output lanes follow the epic. The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

## Dependency decision

Use only existing platform or workspace libraries. Check the direct module dependency before editing.

## Verification

- `./gradlew :core:data:runtime-state:testDebugUnitTest :core:data:testDebugUnitTest`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
