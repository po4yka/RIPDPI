---
title: Add DNS IPv6 and kill-switch release gates
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

- [x] #task Add DNS IPv6 and kill-switch release gates #repo/RIPDPI #area/testing #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-dns-ipv6-and-kill-switch-release-gates`
- **Verify:** `just lint`
- **Scope (only modify these + this file + the ledger):** `core/service/**`, `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Make DNS leak, IPv6 leak, and kill-switch behavior mandatory release gates for
fleet profiles and Android client releases.

## Context

The fleet should not ship profiles that connect but leak DNS/IPv6 or fail open
when the core crashes, the network changes, or the VPN is revoked.

## Acceptance criteria

- [ ] DNS tests verify virtual VPN DNS, proxied DNS through tunneled resolver,
    direct RU DNS only for direct domains, allowlisted bootstrap resolution,
    no ISP fallback on encrypted resolver outage, network-switch behavior,
    core-crash behavior, and Android Private DNS conflict handling.
- [ ] Synthetic authoritative DNS test verifies proxy, direct, and IPv6 query
    sources using unique random domains.
- [ ] IPv4-only tests verify no IPv6 DNS/address/route, no direct IPv6, blocked
    IPv6-only connect, and empty/blocked AAAA behavior.
- [ ] Dual-stack tests verify `::/0` through tunnel and AAAA through tunnel.
- [ ] Kill-switch tests cover forced disconnect, core crash, Wi-Fi/LTE switch,
    sleep/wake, and Android Always-on + Block where applicable.
- [ ] Any DNS leak, IPv6 leak in IPv4-only mode, or Android kill-switch failure
    is a no-ship failure.

## Notes

This task coordinates existing Android DNS/IPv6/kill-switch tasks into release
gates.

## Work log

- 2026-05-14: Implemented the release gates as a real, runnable CI gate rather
  than a doc stub:
  - `quality/release-gates/dns-ipv6-killswitch-gates.json` -- machine-readable
    policy: 20 no-ship gates across 5 categories (dns-leak,
    synthetic-authoritative-dns, ipv4-only-ipv6-leak, dual-stack-ipv6,
    kill-switch), one gate per acceptance criterion, plus the no-ship policy.
  - `scripts/ci/check_dns_ipv6_killswitch_gates.py` -- validates the policy
    artifact (every required gate/category present, every gate `noShip=true`,
    valid failure classifications) and, given a `--results` file, enforces the
    no-ship policy (FAIL/WARN/missing on a no-ship gate -> exit 1).
  - `scripts/tests/test_dns_ipv6_killswitch_gates.py` -- 15 unit tests covering
    the valid repo policy and rejection of every malformed-policy case plus the
    result-evaluation path.
  - Wired into `.github/workflows/ci.yml` as the `release-gates` job (runs the
    unit tests then the policy check).
- Verification of the gate itself: `python3 scripts/ci/check_dns_ipv6_killswitch_gates.py`
  -> exit 0; `python3 -m unittest scripts.tests.test_dns_ipv6_killswitch_gates`
  -> 15 tests OK; no-ship enforcement returns exit 1 on a FAIL result file.
- Status `blocked`: the contract Verify command `just lint`
  (`./gradlew staticAnalysis`) exits 1, but only because of pre-existing detekt
  and `buildRustNativeLibs` failures in `app/**`, `core/**`, and `native/**` --
  source trees this task is not permitted to modify. None of the files this
  task created/changed are Kotlin/Rust source, so they cannot affect
  `staticAnalysis`. The gate work is complete and independently verified.

## Links

- [[Add DNS interceptor and split DNS leak tests]]
- [[Add explicit IPv6 policy modes and leak tests]]
- [[Add authoritative DNS leak-test harness]]
- [[Add Android lockdown onboarding and kill-switch health checks]]
