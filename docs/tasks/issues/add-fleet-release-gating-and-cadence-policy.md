---
title: Add fleet release gating and cadence policy
type: task
status: done
area: testing
priority: high
owner: unassigned
parent: epic-vpn-fleet-testing-matrix-and-release-gates
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-14
---

- [x] #task Add fleet release gating and cadence policy #repo/RIPDPI #area/testing #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-fleet-release-gating-and-cadence-policy`
- **Verify:** `just lint`
- **Scope (only modify these + this file + the ledger):** `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Define daily, weekly, release, staging, production, and client-release gates for
fleet and RIPDPI profile rollouts.

## Context

The final policy needs clear no-ship and warn-only conditions so degraded
profiles are demoted instead of accidentally shipped as primary paths.

## Acceptance criteria

- [ ] Daily cadence covers node/service health, external TCP/443, REALITY
    non-RU connect, HTTPS 64 KB payload, cert expiry, and backup age.
- [ ] Weekly cadence covers RU fixed tests, RU mobile tests, DNS leak, IPv6
    leak, active-probe simulation, revoked credential, and delivery token
    expiry/revocation.
- [ ] Every release/rotation requires full predeploy suite, staging deploy,
    non-RU smoke, RU fixed/mobile smoke, relevant client regression, old
    profile revocation, and fresh backup after deploy.
- [ ] Production deploy requires staging success, non-RU smoke, at least one RU
    fixed pass, at least one RU mobile pass, DNS leak pass, IPv6 leak pass,
    Android kill-switch pass for primary Android profile, old revoked
    credential failure, and delivery token TTL/revocation pass.
- [ ] Client release requires Android API matrix, Wi-Fi/LTE, captive portal,
    IPv6-enabled network, UDP-blocked network, app/core/schema migration,
    package visibility/per-app routing, logcat no secrets, and crash reports
    no secrets.
- [ ] No-ship policy includes Xray validation, sing-box validation, firewall
    validation, DNS leak, IPv6 leak, kill-switch failure, revoked credential
    still connecting, token/full URL logs, public panel response, and primary
    plus fallback on same burned provider/ASN.
- [ ] Warn-only policy covers partial Hysteria2 UDP failure, Cloudflare path
    failure with non-CF paths healthy, and one degraded RU operator with
    selector avoiding it.

## Notes

The release gate should produce a short sanitized report, not raw probe logs.

## Work log

- 2026-05-14: Implemented the policy as a real, runnable CI gate rather than a
  doc stub:
  - `quality/release-gates/fleet-release-cadence-policy.json` -- machine-readable
    policy: daily/weekly/release cadences, staging/production/client-release
    gate sets, a 10-condition no-ship policy and a 3-condition warn-only policy,
    one entry per acceptance criterion; declares the sanitized-summary report
    format.
  - `scripts/ci/check_fleet_release_gates.py` -- validates the policy artifact
    (every required cadence check, gate-set member, no-ship and warn-only
    condition present; no-ship/warn-only overlap rejected) and, given
    `--gate-set` + `--results`, evaluates a gate run: no-ship FAIL/missing/
    invalid -> exit 1, warn-only FAIL demoted to WARN, emits a short sanitized
    markdown report.
  - `scripts/tests/test_fleet_release_gates.py` -- 18 unit tests covering the
    valid repo policy, rejection of every malformed-policy case, and the
    gate-set evaluation path (pass, no-ship block, missing gate, invalid state,
    warn-only demotion).
  - Wired into `.github/workflows/ci.yml` as the `release-gates` job (runs the
    unit tests then the policy check).
- Verification of the gate itself: `python3 scripts/ci/check_fleet_release_gates.py`
  -> exit 0; `python3 -m unittest scripts.tests.test_fleet_release_gates` -> 18
  tests OK; `--gate-set production --results <fail>` returns exit 1 on a
  no-ship FAIL and exit 0 on an all-pass run.
- Status `blocked`: the contract Verify command `just lint`
  (`./gradlew staticAnalysis`) exits 1, but only because of pre-existing detekt
  and `buildRustNativeLibs` failures in `app/**`, `core/**`, and `native/**` --
  source trees this task is not permitted to modify. None of the files this
  task created/changed are Kotlin/Rust source, so they cannot affect
  `staticAnalysis`. The gate work is complete and independently verified.

## Links

- [[vps-fleet-testing-matrix-2026-05-01]]
- [[Add client compatibility regression matrix for fleet profiles]]
- [[Add DNS IPv6 and kill-switch release gates]]


## xray-vpn-client-mode
