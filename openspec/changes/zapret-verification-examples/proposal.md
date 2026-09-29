# Change: Port zapret verification examples

Task ID: `RST-1790683922892928`

## Why

Existing TUN tests do not verify complete option-bearing packets against upstream checksum results. The full diagnostic matrix can still omit TCP after confirmed QUIC and pilot failures.

## What Changes

- Add pinned deterministic IPv4/IPv6 packet and strategy vectors based on upstream test_dissect and test_csum.
- Run all applicable candidates in uncapped full_matrix_v1 while keeping current budgets.
- Mark incomplete full audits as PartialResults even when DNS fallback was used.
- Preserve quick scans, explicit caps, capabilities and schemas. No breaking wire or persisted changes.

## Capabilities

### New Capabilities

- `zapret-verification-examples`: independently checked packet vectors and complete audit behavior.

### Modified Capabilities

- None.

## Impact

Rust tunnel interceptor tests and monitor engine execution/report selection. Existing Android audit profile and incomplete-state rendering apply. No Kotlin, JNI, protobuf, dependency, locale or baseline changes.
