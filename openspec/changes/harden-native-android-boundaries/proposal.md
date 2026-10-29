# Change: Harden native and Android trust boundaries

Task ID: `AND-1793270044084741`

## Why

The audit found unauthenticated helper access, replayable legacy UDP, unbounded SS2022 session state, blocked import workers, and a release-only fixed-buffer length defect. These affect private relay use, packet integrity, and process availability.

## What Changes

- Require session credentials at helper frontends that use stored user configuration.
- BREAKING: remove legacy Shadowsocks UDP support, as requested by the user. Keep legacy TCP. UDP requires SS2022.
- Limit SS2022 server session state without admitting recent replays.
- Bound import work and terminate pipe reads on cancellation or deadline.
- Update fixed-read buffer length in optimized builds.

## Capabilities

### New Capabilities

- `native-android-security`: enforce local helper authentication and bounded native/Android input handling.

### Modified Capabilities

None.

## Impact

Affected areas are app proxyimport, core/service helper orchestration, ripdpi-naiveproxy, ripdpi-shadowsocks, relay-tls-transports and ripdpi-io-uring. Helper stdin authentication changes with its caller in one commit. No JNI, protobuf, persisted schema or dependency change is planned.
