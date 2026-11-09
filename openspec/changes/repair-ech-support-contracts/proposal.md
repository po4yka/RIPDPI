# Change: Repair ECH support contracts

Task ID: `TRN-1794198500598113`

## Why

The STD-03 audit reproduced exposed inner SNI with unusable Real ECH, a Boring retry getter abort, incompatible DNS selection, lost HTTPS aliases, missing retry integration, and delayed cancellation.

## What Changes

- Fail closed for unusable Real ECH and require acceptance.
- Read retry configs only after authenticated ECH rejection.
- Exclude incompatible HTTPS records and follow bounded aliases.
- Retry authenticated rejection once on a fresh transport.
- Stop active DNS and native ECH work on cancellation.
- BREAKING (internal JNI): change the native ECH connect descriptor to `(Long, String)` and add cancellation lifecycle calls. Ship Kotlin and native outputs together; stored configuration is unchanged.

## Capabilities

### New Capabilities

- `transport/ech-client-safety`: ECH mode, rejection, retry, DNS discovery, and cancellation contracts.

### Modified Capabilities

- None. Existing REALITY policy is preserved.

## Impact

- TLS profiles, vendored BoringSSL, DNS resolver, MASQUE, xHTTP, diagnostics and native ECH JNI bridge.
- The changed JNI connect descriptor and added lifecycle calls require Kotlin and Rust to ship together. No persisted schema, dependency, baseline, or locale change.
