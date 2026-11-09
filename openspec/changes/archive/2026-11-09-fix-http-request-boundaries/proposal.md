# Change: Preserve HTTP request boundaries during packet mutation

Task ID: `PRX-1794211693515272`

## Why

STD-09 found body and HTTP/2 data corruption, incomplete-head reconstruction, incorrect LF boundaries, ambiguous authority metadata, and a bare-CR builtin mutation. These faults can change user data.

## What Changes

- Mutate only the complete first HTTP/1 request head of a connection. Preserve subsequent opaque bytes and HTTP/2 traffic.
- Keep incomplete heads incomplete and select the first supported head terminator.
- Use request-target authority for decision metadata and suppress ambiguous Host metadata.
- Replace the invalid builtin method EOL output with a leading empty line.
- No JNI, protobuf, persisted configuration, dependency-version, or transport-ownership change.

## Capabilities

### New Capabilities

- `http-request-mutation`: Safe plaintext request inspection and mutation boundaries.

### Modified Capabilities

- None. Owned HTTP clients and CONNECT listeners keep their separate contracts.

## Impact

Rust packets, desync, proxy runtime, Lua strategies, TUN interception, and bundled Lua. Existing opt-in aggressive mutations remain opt-in. Later requests pass through unchanged rather than being inferred from arbitrary body slices.
