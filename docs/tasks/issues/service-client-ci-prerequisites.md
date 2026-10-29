---
id: CIC-1792987990860435
title: Restore service client CI prerequisites
kind: bug
status: review
area: ci
priority: high
owner: po4yka
parent: null
blocked_by: []
spec_mode: required
openspec_change: cic-1792987990860435-service-client-ci-prerequisites
created: 2026-10-26
updated: 2026-10-29
status_detail: All execution steps checked; awaiting archive verification
---

## Goal

Restore client packaging and Simple Home regression checks so the service contract mirror can pass hosted CI.

## Acceptance criteria

- A locked non-test WebTunnel check compiles the existing `tokio::select!` call.
- Simple Home tests confirm disconnect through the visible dialog, keep callback assertions, and test share/save availability.
- Required hosted checks pass before merge; no test or safety gate is skipped.
