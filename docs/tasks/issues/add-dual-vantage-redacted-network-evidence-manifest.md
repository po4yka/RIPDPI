---
title: Add a redacted dual-vantage network evidence manifest
type: task
status: doing
area: testing
priority: high
owner: Evidence contract lane
parent: null
blocks: []
blocked_by: []
created: 2026-07-16
updated: 2026-07-17
status_detail: Repository contract implemented; zero physical-Android runners/devices and no runner config currently block the first real dual-vantage run
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

- 2026-07-17: Live infrastructure audit found zero registered repository runners, zero locally attached ADB devices, and no `/etc/ripdpi/network-evidence-runner.json`. The active workflow requires labels `self-hosted, linux, ripdpi-network-evidence, physical-android`, exactly one authorized physical device, and independent client/observer hooks. No run was dispatched because it could only remain queued; no physical artifact or PASS is claimed.
- 2026-07-16: Assigned to the serialized evidence/schema lane for the active network-evidence hardening goal.
- 2026-07-16: Added strict canonical observation/manifest validation, runner-stamped collector/vantage/APK provenance, full process-tree cleanup, exact-SHA physical-client install verification, release workflow provenance checks, and fail-closed regression coverage. No physical ADB device is attached locally, so real capture evidence remains pending.
