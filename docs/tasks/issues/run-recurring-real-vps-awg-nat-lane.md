---
title: Run a recurring real-VPS AmneziaWG and NAT lane
type: task
status: doing
area: ci
priority: high
owner: AWG real-VPS lane
parent: null
blocks: []
blocked_by: [add-dual-vantage-redacted-network-evidence-manifest]
created: 2026-07-16
updated: 2026-07-16
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

- 2026-07-16: Assigned to the real-VPS lane; implementation waits on the shared evidence schema but read-only contract audit may proceed.
