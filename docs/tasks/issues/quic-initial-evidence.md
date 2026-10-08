---
id: DGN-1791484611611171
title: Declare QUIC Initial probe measurement scope
kind: bug
status: doing
area: diagnostics
priority: medium
owner: controls
parent: null
blocked_by: []
spec_mode: required
openspec_change: quic-initial-evidence
created: 2026-10-08
updated: 2026-10-08
---

## Goal

Report QUIC Initial measurement scope on every outcome.

## Acceptance criteria

Loopback success and invalid response tests assert scope and absent HTTP/3 validation.

## Ownership

Controls worker owns connectivity/probes/quic.rs and monitor-engine execution/lanes/quic.rs; root owns UI display.
