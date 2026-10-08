---
id: DGN-1791484491975137
title: Keep network metadata in one capture scope
kind: feature
status: doing
area: diagnostics
priority: medium
owner: metadata-worker
parent: null
blocked_by: []
spec_mode: required
openspec_change: network-metadata-capture-scope
created: 2026-10-08
updated: 2026-10-08
---

## Goal

Reject network metadata that crosses a physical or default network change.

## Acceptance criteria

- Targeted regression tests cover public IP handover and stable capture.
- Metadata worker owns provider/factory and tests; root owns resources/integration.
- Combined static analysis and architecture checks pass.
