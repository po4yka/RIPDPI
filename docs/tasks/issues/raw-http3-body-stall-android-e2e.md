---
id: DGN-1795367419931764
title: Exercise the RAW HTTP/3 body stall in Android history
kind: feature
status: review
area: diagnostics
priority: medium
owner: Diagnostics maintenance
parent: null
blocked_by: []
spec_mode: required
openspec_change: raw-http3-body-stall-android-e2e
created: 2026-11-22
updated: 2026-11-22
---

## Goal

Verify that a successful HTTPS/TCP response and a stalled HTTP/3 body remain distinguishable in Android history.

## Acceptance criteria

- Serve both protocols from one fixture address, port, certificate, and object path.
- Start the manual RAW profile on Android.
- Check the saved difference stage and both byte counts.

## Worktree ownership

- Local QUIC fixture and Rust fixture tests: fixture worker in an isolated checkout.
- Android instrumentation: Android worker in an isolated checkout.
- Runner, workflow, and task specification: integration checkout.
