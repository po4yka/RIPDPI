# Change: Fix SOCKS5 CONNECT contracts

Task ID: `PRX-1794071497680636`

## Why

The LOC-01 audit found false CONNECT success, malformed headers accepted as requests, missing address-type replies, wrong policy-denial replies, and empty authentication usernames. Local clients need accurate connection results and bounded input validation.

## What Changes

- Send successful CONNECT replies only after an upstream connection exists.
- Reject invalid request version and reserved fields before resolution or connection.
- Return REP 08 for unsupported address types and REP 02 for policy denial.
- Reject empty RFC 1929 credential fields.
- BREAKING: clients can no longer depend on optimistic CONNECT success or malformed input acceptance.

## Capabilities

### New Capabilities

- `socks5-connect-contracts`: local CONNECT validation, replies, and authentication framing.

### Modified Capabilities

- None.

## Impact

- Rust proxy runtime, session parser, and their tests. No new dependencies, JNI, storage, configuration schema, locale, or golden changes.
