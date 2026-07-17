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
status_detail: Repository contract includes independently hashed network and vantage identities; zero configured physical-Android runners and no runner config block the first real dual-vantage run
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

- 2026-07-17: Commit `0b1eac50f276405d02f4f4cccff60f3ab54f9cad` made `networkIdSha256` independent from `vantageIdSha256`, domain-separated both hashes, rejected missing/duplicate/cross-type identities, and added executable Draft 2020-12 validation for emitted observations and manifests. The private runner config must now provide four distinct high-entropy identifiers.
- 2026-07-17: Live infrastructure audit found zero registered repository runners and no `/etc/ripdpi/network-evidence-runner.json`. A physical Android device is locally attached, but the active workflow still requires a configured runner with labels `self-hosted, linux, ripdpi-network-evidence, physical-android` plus independent client/observer hooks. No dual-vantage run or PASS artifact is claimed.
- 2026-07-16: Assigned to the serialized evidence/schema lane for the active network-evidence hardening goal.
- 2026-07-16: Added strict canonical observation/manifest validation, runner-stamped collector/vantage/APK provenance, full process-tree cleanup, exact-SHA physical-client install verification, release workflow provenance checks, and fail-closed regression coverage. No physical ADB device was attached during that implementation pass, so real capture evidence remained pending.
