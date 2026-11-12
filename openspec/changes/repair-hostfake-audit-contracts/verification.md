---
task_id: RST-1794499062183726
change: repair-hostfake-audit-contracts
commit_sha: null
local: required
local_evidence: Owner regressions and combined source gates pending.
remote_ci: not_applicable
remote_ci_evidence: No hosted dispatch required; push status reported separately.
device: not_applicable
device_evidence: No root, device or external probes authorized.
artifact: not_applicable
artifact_evidence: No release artifact owned.
deployment: not_applicable
deployment_evidence: No deployment owned.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-HOSTFAKE-FALLBACK | RST-1794499244045896 | Runtime owner suite: 149 passed; independent review approved; c1aaf9354e | passed |
| REQ-HOSTFAKE-READINESS | RST-1794499244045896 | UI regression and render passed; app/service lint passed; 06afc0154e | passed |
| REQ-HOSTFAKE-MD5 | RST-1794499244635496 | Linux actual packet byte regression passed; combined gates pending | required |
| REQ-HOSTFAKE-SEQUENCE | RST-1794499245130397 | Linux modulo and oversized-offset regressions passed; combined gates pending | required |
| REQ-HOSTFAKE-SPANS | RST-1794499245610183 | Owner tests and combined gates pending | required |
| REQ-HOSTFAKE-TEMPLATE | RST-1794499246094525 | Config 182, native converter 155 and Kotlin model 69 passed; df2fc34249 | passed |
| REQ-HOSTFAKE-RANDOM | RST-1794499246590676 | Full owner batch 1474 passed; final settings/engine follow-up 412 passed | passed |
| REQ-HOSTFAKE-LATE-ERROR | RST-1794499247075214 | Linux restoration tests and runtime no-replay loopback passed; combined gates pending | required |

## Observed evidence

- F1 RED: the receipt expected split and returned HostFake. GREEN: all 149 ripdpi-desync-runtime tests passed, including later-step/suffix preservation and terminal-error loopback byte checks. No raw send was used.
- F5 RED: slash template accepted natively and Unicode accepted by Kotlin. Owner GREEN: 182 config, 155 proxy-config and 69 model tests. Kotlin validates ASCII before case folding, including Kelvin sign rejection.
- UI: app/service lint passed. A configured nonroot HostFake card rendered; the changed prerequisite/fallback text is readable. The UI regression passed. No matching HostFake-specific RDS deck exists; the existing card layout is retained, only state text changes. No screenshot baseline was changed.
- F6: full Kotlin owner batch passed 1474 tests (model74/settings93/data989/engine318), zero failures or skips. After the backup-kind correction, final settings94 and engine318 passed: 412 tests, zero failures or skips.
- Linux raw owner checks passed: actual IPv4/IPv6 packet bytes 1, modulo/oversized-offset 2, and restoration/error context 2. Clippy all-targets with warnings denied passed. These five tests use no raw socket syscalls.
- Independent pr-reviewer source passes cover F1/F4, amended F2/F3/H1, and F5/F6. Parent reviewed the small canonical backup-kind follow-up.
- TCP_REPAIR cleanup was checked against primary Linux sources: [Linux 4.14](https://github.com/torvalds/linux/blob/v4.14/net/ipv4/tcp.c#L2441) and [Linux 6.1](https://github.com/torvalds/linux/blob/v6.1/net/ipv4/tcp.c#L3400). Repair queue reset after repair is off can fail; disabling repair itself is separate and its failure must remain terminal. This is source evidence, not an OS acceptance run.

## Validation limits

Root activation, physical device, external server/DPI, release and deployment acceptance are not part of this repair. Pure Linux packet tests use nonroot UID, no capabilities and no network. Synthetic TLS fixtures prove framing/SNI invariants, not a complete compliant handshake.

Initial Kotlin RED native build was cancelled without test credit. Kotlin unit/lint/staticAnalysis use the repository-supported ripdpi.skipNativeBuild=true CI option; tests themselves are not skipped. The first Linux container attempt failed before tests because the base image lacked cmake; the successful retry used the cached complete image ripdpi-dpi05-linux-check:rust1.98.1. No failed setup is counted as GREEN.
