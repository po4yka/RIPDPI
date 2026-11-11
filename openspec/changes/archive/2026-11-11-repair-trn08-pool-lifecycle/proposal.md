# Change: Repair relay pool admission and lifecycle

Task ID: `TRN-1794387054491059`

## Why

The TRN-08 audit found late carrier opens outside dial admission, idle time shared across session generations, and retained completed cleanup failures. Independent XUDP opens also share unrelated yamux ownership. Shutdown admission needs an explicit terminal contract.

## What Changes

- Limit concurrent establishment attempts to eight through initial stream or datagram open. Established traffic keeps its lease without an opening slot.
- Track idle use per cached session generation.
- Release fully joined failed owners while preserving the first cleanup error.
- Isolate independent datagram sessions from shared TCP carriers.
- Reject and cancel opens during terminal shutdown before factory cleanup.
- Verify the actual Android-owned runtime teardown boundary for abort-only drivers.
- BREAKING: the internal Rust pool open API can return a pool-closed error distinct from a backend error. Relay-core preserves its io::Error surface.

## Capabilities

### New Capabilities

- `relay-pool-lifecycle`: bounded admission, independent leases, and explicit shutdown ownership.

### Modified Capabilities

- None.

## Impact

- Rust relay-mux, relay-core, relay-tls-transports, and relay-android lifecycle tests. No JNI, wire, config, storage, dependency, locale, or golden change is planned.
