---
id: RLY-1794757174777852
title: Correct AUX-02 relay clock trust and replay handling
kind: bug
status: review
area: relay
priority: high
owner: Relay runtime
parent: null
blocked_by: []
spec_mode: required
openspec_change: correct-aux-02-relay-clock
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Keep each configured relay reachable when a different relay reports a different time. Keep existing freshness and replay guarantees.

## Acceptance criteria

- Relay profiles retain independent calibration.
- A restart uses corrected wall time.
- Only valid replies calibrate. UDP replay state uses one stable timeline.
- Clock failures, delayed observations, and bounds return safe results.

## Ownership

- Integration owns the clock crate, Mieru factory, and planning artifacts.
- Mieru worktree owns ripdpi-mieru receiver admission and time bounds.
- Shadowsocks worktree owns ripdpi-shadowsocks, its transport adapter and tests, and the pinned Shadowsocks integration patch.
- Serialize the clock API call-site update after both wire implementations. Cargo manifests and dependency locks are outside this change.

## Verification

Run owning crate tests for time, admitted replies, replay retention, and recovery. Run the source gates and independent review on the combined tree. Record device and external peer evidence separately.
