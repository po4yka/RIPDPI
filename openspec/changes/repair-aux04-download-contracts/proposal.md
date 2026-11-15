# Change: Repair AUX-04 catalog download contracts

Task ID: `SVC-1794755640074922`

## Why

Catalog refresh can buffer an unbounded response before its byte limit. Native fetch can accept an incomplete TLS body, retain canceled work, and lose the response media type. Repair these confirmed defects without new network features.

## What Changes

- Enforce response byte limits before Retrofit buffers success or error bodies.
- Reject close-delimited HTTPS bodies after an unauthenticated EOF.
- Stop owned HTTP work when its Kotlin caller is canceled.
- Preserve Content-Type through native fetch and browser fallback.
- JNI cancellation uses new request lifecycle methods. Kotlin and Rust ship together; no persisted data changes.

## Capabilities

### New Capabilities

- `network/owned-catalog-fetch`: bounded, complete, cancellable data delivery with response media types.

### Modified Capabilities

- None.

## Impact

- core/service, core/engine, ripdpi-android-fetch-adapter, and ripdpi-android.
- Additive HTTP response JSON and coordinated JNI lifecycle changes.
- No dependency, locale, golden, signing, or production configuration changes.
