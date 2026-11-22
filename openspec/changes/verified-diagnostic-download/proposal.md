# Verify a bounded manual HTTPS download

Task ID: `DGN-1795320365568134`

## Why

A connection can reach HTTPS response headers and then stop. A short control body can also complete before the throughput window. The operator needs evidence of a complete, correct transfer.

## What Changes

- Add a manual diagnostics profile for one HTTPS download to a user-provided URL.
- Require the expected body size and SHA-256. Retain the measured progress and report the verification result.

## Capabilities

### New Capabilities

- `verified-diagnostic-download` — One bounded HTTPS download with body verification.

### Modified Capabilities

- None.

## Impact

Android diagnostics, the native throughput runner, and their optional request and result fields.
