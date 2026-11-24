---
id: TST-1793415829632618
title: Move the keepalive-grammar fixture out of the vendored contract directory
kind: chore
status: done
area: testing
priority: medium
owner: Deployment contract sync
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-31
updated: 2026-11-24
spec_reason: test-only
closed_at: "2026-11-24T15:53:52Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Commit c3b001491 moved the unchanged fixture and updated all three readers. The 36 frozen producer contract files matched byte-for-byte. Data and app unit tests passed in Kotlin coverage job 124433985998 on main CI run 41165805189.
---

## Goal

`core/data/src/test/resources/contract/` holds only files vendored byte-identically from `ripdpi-vpn-deploy/contract/`. The client-only keepalive-grammar fixture `awg-keepalive-invalid.txt` lives beside the other local test resources, so the deployment repository's `contract-sync` check no longer reports it as an orphan.

## Acceptance criteria

- `awg-keepalive-invalid.txt` is read from `core/data/src/test/resources/` by `WireGuardConfParserTest`, `AmneziaWgUriCodecTest` and `AmneziaWgEditorStateTest`, with unchanged assertions.
- Every file under `core/data/src/test/resources/contract/` except `README.md` has a byte-identical source under `ripdpi-vpn-deploy/contract/`, and no file there lacks one.
- `./gradlew :core:data:testDebugUnitTest` and the app unit tests pass in hosted CI.
