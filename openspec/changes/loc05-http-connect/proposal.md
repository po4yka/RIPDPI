# Change: Repair HTTP CONNECT audit defects

Task ID: `PRX-1794118814905211`

## Why

The local CONNECT listener accepts invalid HTTP input, rejects supported clients and can report success before an upstream exists. Seven confirmed audit findings need regression coverage and separate fixes.

## What Changes

- Reject invalid framing, malformed fields and signed authority ports.
- Accept HTTP/1.0 without Host and legal field octets with valid Basic credentials.
- Send success only after upstream establishment and return an HTTP error for excessive headers.
- BREAKING: malformed requests that previously opened a tunnel now fail.

## Capabilities

### New Capabilities

- `http-connect-contracts`: local HTTP/1.x tunnel parsing, authentication and success ordering.

### Modified Capabilities

- None. Existing SOCKS, TLS stream and routing contracts must remain intact.

## Impact

- Rust session, proxy runtime and proxy runtime adapter crates.
- Local HTTP wire behavior only; no dependencies, JNI/protobuf/storage/config schema or Android UI changes.
