---
id: CIC-1786264762917922
title: Run a recurring real-VPS AmneziaWG and NAT lane
kind: chore
status: dropped
area: ci
priority: high
owner: AWG real-VPS lane
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-07-16
updated: 2026-08-09
spec_reason: tooling-only
status_detail: Local systemd execution and fail-closed validators are shipped; real-VPS evidence is blocked by missing operator-owned provider inputs
closed_at: "2026-08-09T11:12:20Z"
closed_reason: implementation belongs to the deployment repository
evidence_summary: The real-VPS harness lives in ripdpi-vpn-deploy; no remaining RIPDPI deliverable is owned by this repository.
---

## Goal

Exercise AmneziaWG and NAT against a real recurring VPS through initial connection, restart, reload, and recovery with real bidirectional TCP and UDP payloads.

## Scope

- Add scheduled and manual workflow entry points with an explicit secret/inventory contract and no hard-coded credentials.
- Verify initial connect, service restart, configuration reload, reconnect/recovery, TCP and UDP round-trips, counters/readiness, and teardown.
- Distinguish product failure, unavailable infrastructure, and missing credentials; the required scheduled lane must never green-skip.
- Publish the redacted dual-vantage evidence manifest plus safe log/pcap digests.

## Ship definition

- Workflow/script contract tests pin ordering, mandatory TCP+UDP observations, stale/partial evidence rejection, and cleanup on failure.
- A real configured run produces complete evidence; without external infrastructure the task remains open with an exact blocker.

## Work log

- 2026-07-22: The primary recurring executor is now the local systemd timer rather than hosted CI. Deploy SHA `11450ad0372f8a317b2b29e537ba2c672e052a28` closes a false-green old-key control: acceptance by either TCP or UDP now fails the negative phase and strict manifest validation; 124 focused offline tests pass. A complete live run remains blocked because the operator-owned provider environment is unavailable, so no restart/reload, bidirectional payload, NAT, or teardown PASS is claimed.
- 2026-07-17: Deploy commit `5429f6d39c0e2816febfd1935c9932b138c8629e` added the fail-closed `INFRA_UNAVAILABLE/MISSING_CREDENTIALS` reason, exact `PrivateKey`/`PresharedKey` cardinality checks, and runtime/rollback regressions. Malformed inventory, unsafe paths, and missing operator hooks remain separately classified as `CONFIG_INVALID`.
- 2026-07-17: Dispatched [run 29541767920](https://github.com/po4yka/ripdpi-vpn-deploy/actions/runs/29541767920) on deploy SHA `a4e28e9fc67c78720c7d1f2db934a0029fd0c194`. Job [87765296645](https://github.com/po4yka/ripdpi-vpn-deploy/actions/runs/29541767920/job/87765296645) remained queued for 6m52s with `runner_id=0`, no runner name/group, labels `self-hosted, linux, ripdpi-awg-vps`, and zero steps/artifacts; the repository runners API reported `total_count=0`. The run was cancelled as cleanup. No AWG restart/reload, TCP/UDP, NAT, teardown, manifest, or PASS can be claimed until that runner is provisioned.
- 2026-07-16: Assigned to the real-VPS lane; implementation waits on the shared evidence schema but read-only contract audit may proceed.
