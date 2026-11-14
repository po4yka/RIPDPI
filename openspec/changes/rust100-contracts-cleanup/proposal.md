# Change: Adopt Rust 1.100 contracts and cleanup APIs

Task ID: `RST-1794629289019737`

## Why

The workspace now uses Rust 1.100. Some types still permit impossible results, and small cleanup guards duplicate standard APIs.

## What Changes

- BREAKING Rust source contract: TCP-only relay Datagram types become `!`; server success types become `!` where normal completion is impossible.
- Keep runtime errors, capability checks, listener retries, cancellation, DNS validation, and proxy state transitions.
- Use DropGuard, trim_suffix, and Vec::from_fn where they express the existing behavior.

## Capabilities

### New Capabilities

- `rust100-contracts`: Preserve runtime behavior while making impossible results explicit.

### Modified Capabilities

- None.

## Impact

- Rust relay transports/core, Naiveproxy, Cloudflare origin, Android proxy adapter, DNS resolver, TLS profiles, tunnel core, and Apps Script core.
- No Kotlin, JNI signature, wire, storage, configuration, dependency, or security-policy change.
