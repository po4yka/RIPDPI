---
id: CIC-1788801446864715
title: Make secret-less dependabot PR CI green
kind: bug
status: done
area: ci
priority: high
owner: CI flake fix
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-09-07
updated: 2026-09-07
spec_reason: tooling-only
closed_at: "2026-09-07T17:18:55Z"
closed_reason: Fixture, pin adoption, and arti lockstep verified by hosted CI.
evidence_summary: "Secret-blocked PR group green after fixture: runs 34131520027 (499), 34131531602 (500), 34131560993 (508), 34131569629 (511), 34131579307 (514), 34131598544 (517), 34131607776 (505) all success; arti migration landed in PR 518 run f4e90b193 success; CiRelayBundleFixtureTest 2/2, full workflow contract suite and actionlint green on main 641524cdb2e8ee8764dcc10c2b3a02f2fd963b53."
---

## Goal

Make CI green on every open pull request that fails only because Dependabot runs without access to Actions secrets. Nine open PRs (gradle/npm/workflow bumps) failed deterministically at `verifyEmbeddedRelayBundle` because the embedded relay bundle is secret-materialized; PR 505 failed the tool pinning contract; PR 518 failed because tor-rtcompat 0.46.0 is incompatible with the pinned arti-client 0.44.0.

## Acceptance criteria

- On `pull_request` events with no secret, `setup-android-rust` materializes the committed CI fixture bundle (`scripts/fixtures/embedded-relay-bundle/ci-fixture.json`); push, tag, and schedule events keep requiring the real secret bundle.
- The fixture passes the Simple seeder contract: parser success with no skipped outbounds, a VLESS+Reality primary, a TCP-diverse VLESS/xHTTP reserve, a Hysteria2 reserve, an AWG reserve passing `requireRuntimeReady`; locked by `CiRelayBundleFixtureTest`.
- `taiki-e/install-action` uses one pinned SHA (`v2.87.5`) across `ci.yml`, `fuzz-nightly.yml`, `mutation-testing.yml`, with the pinning test updated.
- The arti stack moves in lockstep (`arti-client` 0.46.0 alongside tor-rtcompat 0.46.0) in the PR 518 branch, with workspace check, clippy, and ripdpi-tor tests green.
- Hosted CI passes on the secret-blocked PR group and on main.

## Ownership

The CI flake fix role owns `.github/actions/setup-android-rust/action.yml`, `scripts/fixtures/embedded-relay-bundle/`, `scripts/tests/test_ci_tool_pinning.py`, `core/data/src/test/kotlin/com/poyka/ripdpi/data/CiRelayBundleFixtureTest.kt`, the `taiki-e/install-action` pins, and this record. The PR 518 migration owns `native/rust/Cargo.toml` and `native/rust/Cargo.lock` only on the PR branch. No workflow routing, gates, or release verification strictness changes.
