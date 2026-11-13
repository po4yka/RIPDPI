# Change: Fix multidisorder delivery and configuration contracts

Task ID: `RST-1794556971423909`

## Why

The DPI-13 audit found unsafe replay after possible payload delivery, a control-frame limit on helper payloads, discarded marker settings, an ordinary write that bypasses original flags, and an incorrect fallback claim.

## What Changes

- Prevent retry when multidisorder payload delivery is uncertain.
- Permit bounded bulk helper payloads only when the peer advertises support.
- Define one deterministic set of settings for a grouped run and reject conflicts before sending.
- Keep genuine bytes in the repair send queue without an immediate ordinary write.
- State the actual fail-closed capability gate in documentation.
- BREAKING: conflicting grouped settings that were silently ignored are rejected.

## Capabilities

### New Capabilities

- `multidisorder-delivery`: safe native grouped TCP delivery and helper limits.

### Modified Capabilities

- None.

## Impact

- Rust configuration, planner/runtime, privileged sender, root-helper protocol/client, Kotlin chain validation and native documentation.
- No dependency, JNI, protobuf, storage or release change. Helper metadata additions must be backward compatible.
