---
id: TRN-1794477195384429
title: Align AWG startup and WSS validation contracts
kind: bug
status: review
area: transport
priority: high
owner: Readiness and validation worker
parent: null
blocked_by: []
spec_mode: required
openspec_change: tun05-startup-validation
created: 2026-11-12
updated: 2026-11-12
---

## Goal

Fix TUN-05 F1 and F3.

## Ownership
This worker owns engine-api helper, supervisor awaitReady call only, runtime-state validator, editor and tests. Recovery worker owns other supervisor edits. No schema, locale or lockfile changes.

## Acceptance criteria

WS readiness allows staged 10s+6s setup. UDP stays15s. Invalid URLs fail before activation; valid domain and IPv6 URLs pass. Targeted tests and combined gates pass.
