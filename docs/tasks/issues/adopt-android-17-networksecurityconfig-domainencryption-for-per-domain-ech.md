---
title: Adopt Android 17 NetworkSecurityConfig domainEncryption for per-domain ECH policy
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-25
updated: 2026-04-25
---

- [ ] #task Adopt Android 17 NetworkSecurityConfig domainEncryption for per-domain ECH policy #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Summary

Wire RIPDPI's NSC (NetworkSecurityConfig) generator and control-plane to emit `<domainEncryption>` per-domain modes (`enabled` / `disabled` / `opportunistic`) per Android 17 (API 37). Couple this to the DnsResolver path that queries HTTPS DNS records carrying ECH configs, so owned-stack endpoints get hard-on ECH while everything else stays opportunistic.

## Research citation

[[ripdpi-android-research-2026-04-25]] §Android platform — Android 17 (API 37, behavior-changes-17, 2026-02-13) opportunistically enables ECH on TLS 1.3 by default; new `<domainEncryption>` NSC element accepts `enabled` / `disabled` / `opportunistic`; `DnsResolver` now queries HTTPS DNS records with ECH configs; Conscrypt `SSLEngine` gains explicit ECH-enable APIs.

## Acceptance criteria

- [ ] NSC schema generator emits `<domainEncryption>` with `mode="enabled"` for Reality and owned-stack endpoints, `opportunistic` for everything else
- [ ] Control-plane can override per-domain mode (`enabled` / `disabled` / `opportunistic`) via strategy pack
- [ ] DnsResolver wired to query HTTPS DNS records for ECH config when `mode != disabled`
- [ ] Integration test on Android 17 emulator confirms ECH enabled on TLS 1.3 to a Reality endpoint and disabled on a misconfigured one

## Links

- Project: [[ripdpi-android]]
- Epic: [[Epic - Owned-stack mode with Android 17 ECH]]
- Research: [[ripdpi-android-research-2026-04-25]] §Android platform
