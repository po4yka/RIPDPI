---
id: EPC-1795085162356362
title: Deliver opt-in anonymous field analytics for network, blocking, and server health
kind: epic
status: backlog
area: epic
priority: high
owner: Product owner
parent: null
blocked_by: []
spec_mode: required
openspec_change: epc-1795085162356362-field-analytics-epic
created: 2026-11-19
updated: 2026-11-19
---

## Goal

Correct, complete, opt-in anonymous analytics of network conditions, blocking, client-to-server connections, and server/VPN health, with no user identification and no required backend.

## Children

R1 no secrets in shipped assets; R2 vendored contracts and failure-stage taxonomy; R3 opt-in field report client; R4 bundle v3 consumption. Counterparts in ripdpi-vpn-deploy: D1 contracts, D2 ingest, D3 protocol probes, D4 bundle v3 generation, D5 block detection, D6 retention/alert delivery, D7 cross-repo e2e.

## Acceptance criteria

- All four children are done and their verification records show observed evidence.
- Nothing is uploaded unless the user opted in; reports contain only `client-field-report` v1 fields.
- Bundle v3 replaces v2 with no compatibility path.
