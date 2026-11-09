# Change: Correct standard TLS certificate verification policies

Task ID: `TRN-1794198667426286`

## Why

The STD-06 audit found three concrete defects. Standard BoringSSL clients accept CN-only identities and load implicit filesystem roots. Diagnostic helpers expose a verification flag that has no effect. These behaviors disagree with the declared identity and trust policies.

## What Changes

- Require SAN-based DNS/IP identity in standard BoringSSL verification.
- Trust only the bundled Mozilla roots and explicit caller roots in the standard native builder.
- BREAKING internal Rust API: remove the unused diagnostic verification flag. Update every caller. Explicit verifier overrides remain available.
- Preserve explicit pin-only and observational verifier policies.

## Capabilities

### New Capabilities

- `transport/standard-certificate-policy`: standard native identity and trust policy, with explicit caller overrides.

### Modified Capabilities

None.

## Impact

Rust TLS profiles, diagnostic helpers, and their Rust callers change. No JNI, protobuf, wire, persisted configuration, dependency, locale, or signing change is required. Tests use synthetic loopback peers and process-scoped environments.
