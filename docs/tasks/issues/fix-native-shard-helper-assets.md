---
title: Stage every native helper in Android CI shards
type: task
status: review
area: ci
priority: high
owner: Native asset shard lane
parent: null
blocks: []
blocked_by: []
created: 2026-07-17
updated: 2026-07-17
---

## Goal

Make every Android native shard contain the root helper, NaiveProxy, and Cloudflare
origin executables expected by prebuilt Gradle consumers.

## Scope

- Merge the three generated helper output directories into each ABI's `assetsBin` shard.
- Normalize staged helper modes and fail when an expected file is missing or cannot be
  staged as executable.
- Add a workflow contract regression test for both native shard producers.

## Ship definition

- Both native shard jobs stage all three helper executables into one per-ABI asset directory.
- The focused workflow contract test, `actionlint`, architecture health, and diff checks pass.

## Work log

- Updated both native shard producers to merge the root helper, NaiveProxy, and Cloudflare
  origin asset outputs, normalize the staged modes, and verify each helper is executable.
- Added workflow contract tests covering both producers and their debug, release, and
  instrumented-test consumers.
- Reproduced the regression first: the new producer contract failed for both jobs before
  the workflow fix.
- Verification: focused Python unit tests, `actionlint`, architecture health, and diff checks.
- Remaining risk: integration must confirm the rebased workflow on GitHub-hosted Android jobs.
