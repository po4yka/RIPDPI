---
title: Adopt Android 17 NetworkSecurityConfig domainEncryption for per-domain ECH policy
type: task
status: done
area: diagnostics
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-25
updated: 2026-05-16
---

- [x] #task Adopt Android 17 NetworkSecurityConfig domainEncryption for per-domain ECH policy #repo/RIPDPI #area/diagnostics #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `adopt-android-17-networksecurityconfig-domainencryption-for-per-domain-ech`
- **Verify:** `just test-module core:diagnostics`
- **Scope (only modify these + this file + the ledger):** `core/diagnostics/**`, `app/src/main/res/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Wire RIPDPI's NSC (NetworkSecurityConfig) generator and control-plane to emit `<domainEncryption>` per-domain modes (`enabled` / `disabled` / `opportunistic`) per Android 17 (API 37). Couple this to the DnsResolver path that queries HTTPS DNS records carrying ECH configs, so owned-stack endpoints get hard-on ECH while everything else stays opportunistic.

## Research citation

[[ripdpi-android-research-2026-04-25]] §Android platform — Android 17 (API 37, behavior-changes-17, 2026-02-13) opportunistically enables ECH on TLS 1.3 by default; new `<domainEncryption>` NSC element accepts `enabled` / `disabled` / `opportunistic`; `DnsResolver` now queries HTTPS DNS records with ECH configs; Conscrypt `SSLEngine` gains explicit ECH-enable APIs.

## Acceptance criteria

- [x] NSC schema generator emits `<domainEncryption>` with `mode="enabled"` for Reality and owned-stack endpoints, `opportunistic` for everything else
- [x] Control-plane can override per-domain mode (`enabled` / `disabled` / `opportunistic`) via strategy pack
- [x] DnsResolver wired to query HTTPS DNS records for ECH config when `mode != disabled`
- [ ] Integration test on Android 17 emulator confirms ECH enabled on TLS 1.3 to a Reality endpoint and disabled on a misconfigured one — deferred; needs Android 17 emulator

## Links

- Project: [[ripdpi-android]]
- Epic: [[Epic - Owned-stack mode with Android 17 ECH]]
- Research: [[ripdpi-android-research-2026-04-25]] §Android platform
