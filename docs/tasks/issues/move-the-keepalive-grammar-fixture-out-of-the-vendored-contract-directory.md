---
id: TST-1793415829632618
title: Move the keepalive-grammar fixture out of the vendored contract directory
kind: chore
status: doing
area: testing
priority: medium
owner: Deployment contract sync
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-31
updated: 2026-10-31
spec_reason: test-only
---

## Goal

`core/data/src/test/resources/contract/` holds only files vendored byte-identically from `ripdpi-vpn-deploy/contract/`. The client-only keepalive-grammar fixture `awg-keepalive-invalid.txt` lives beside the other local test resources, so the deployment repository's `contract-sync` check no longer reports it as an orphan.

## Acceptance criteria

- `awg-keepalive-invalid.txt` is read from `core/data/src/test/resources/` by `WireGuardConfParserTest`, `AmneziaWgUriCodecTest` and `AmneziaWgEditorStateTest`, with unchanged assertions.
- Every file under `core/data/src/test/resources/contract/` except `README.md` has a byte-identical source under `ripdpi-vpn-deploy/contract/`, and no file there lacks one.
- `./gradlew :core:data:testDebugUnitTest` and the app unit tests pass in hosted CI.
