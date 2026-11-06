# Change: Use one library DNS codec for Kotlin clients and probes

Task ID: `DNS-1793945565828575`

## Why

Independent wire parsers disagree on response validation, truncation and record handling. The direct resolver cannot retry a truncated UDP response over TCP.

## What Changes

- Use dnsjava for Kotlin DNS encoding and decoding.
- Match responses to the full question and reject malformed records.
- Retry truncated direct resolution on TCP with the same network binding.
- Preserve diagnostics, ECH evidence and UDP payload measurements.
- BREAKING: malformed or unrelated replies previously accepted by loose parsers are rejected.

## Capabilities

### New Capabilities

- `kotlin-dns-wire`: Shared DNS wire validation and transport-specific response policy.

### Modified Capabilities

- None.

## Impact

- Kotlin diagnostics-data, diagnostics, service and app debug probes.
- Add dnsjava 3.7.1, BSD-3-Clause, and its SLF4J API dependency. No backend, storage, JNI or Rust schema changes.
