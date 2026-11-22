---
id: DGN-1795363995827246
title: Exercise manual RAW HTTPS transfers end to end on Android
kind: feature
status: review
area: diagnostics
priority: medium
owner: Diagnostics maintenance
parent: null
blocked_by: []
spec_mode: required
openspec_change: raw-https-download-android-e2e
created: 2026-11-22
updated: 2026-11-22
---

## Goal

Verify the manual RAW HTTPS pair through Android, JNI, and diagnostic history.

## Acceptance criteria

- A controlled HTTPS peer serves a complete short response and a long response that stops after a known byte count.
- Instrumentation checks proof, timeout, bytes, and both body timing fields from saved history.
- Fixture trust is restricted to explicit Android instrumentation builds.

## Worktree ownership

- Host HTTPS fixture: native fixture worker, isolated checkout.
- Android instrumentation and fixture manifest reader: Android worker, isolated checkout.
- Fixture trust, build and run wiring, and task specification: integration checkout.
