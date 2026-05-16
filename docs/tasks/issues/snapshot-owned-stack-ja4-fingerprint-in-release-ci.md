---
title: Snapshot owned-stack JA4 fingerprint in release CI
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-14
---

- [ ] #task Snapshot owned-stack JA4 fingerprint in release CI #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `snapshot-owned-stack-ja4-fingerprint-in-release-ci`
- **Verify:** `just build`
- **Scope (only modify these + this file + the ledger):** `.github/**`, `core/diagnostics/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add a release-time CI step that records owned-stack outbound JA4 against
a fixture endpoint and fails the build on drift from the intended
browser-class spec, including explicit assertion of `X25519MLKEM768`
presence in the key-share list.

## Research citation

[[ripdpi-android-research-2026-04-20]] §TLS fingerprinting tooling — by
early 2026 post-quantum `X25519MLKEM768` is in 57.4% of browser
ClientHellos, so its *absence* is now a fingerprintable anomaly. JA4+
rotates roughly yearly with TLS-library updates; a drift gate catches
Conscrypt or OEM TLS changes before they ship.

## Acceptance criteria

- [ ] CI step captures owned-stack outbound JA4 against a pinned fixture
    endpoint on every release build.
- [ ] Expected JA4 baseline committed to the repo; build fails on drift.
- [ ] Assertion explicitly verifies `X25519MLKEM768` is present in the
    ClientHello key-share list.
- [ ] Runbook documents how to update the baseline when Conscrypt
    intentionally rotates browser-class fingerprint.

## Links

- [[Epic - Owned-stack mode with Android 17 ECH]]
- [[Implement owned-stack request pipeline]]
- [[ripdpi-android-research-2026-04-20]]
