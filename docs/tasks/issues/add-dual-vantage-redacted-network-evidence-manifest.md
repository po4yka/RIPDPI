---
title: Add a redacted dual-vantage network evidence manifest
type: task
status: doing
area: testing
priority: high
owner: Evidence contract lane
parent: null
blocks: [run-recurring-real-vps-awg-nat-lane]
blocked_by: []
created: 2026-07-16
updated: 2026-07-16
---

## Goal

Make DNS, kill-switch, and direct-window release evidence deterministic, machine-readable, privacy-safe, and correlated across both the client/device and an external observer.

## Scope

- Define and validate a versioned JSON manifest with redacted run/build/vantage metadata, observation windows, artifact digests, provenance, and explicit pass/fail/inconclusive results.
- Capture the same correlation id from client/device and external/VPS vantages; fail closed on missing, stale, mismatched, malformed, or digest-tampered evidence.
- Enforce allowlist-based redaction for credentials, keys, auth headers, raw device ids, full client IP/MAC values, and sensitive payloads.
- Feed the manifest into the existing DNS/IPv6/kill-switch and direct-window CI evaluation and artifact flow.

## Ship definition

- Regression fixtures cover schema/version drift, deterministic serialization, redaction leaks, correlation/time-window mismatch, partial evidence, digest tampering, and pass/fail cases.
- CI never treats a single-vantage or malformed capture as release evidence.
- Artifacts contain no secret or direct device/network identifier from the negative leak corpus.

## Work log

- 2026-07-16: Assigned to the serialized evidence/schema lane for the active network-evidence hardening goal.
