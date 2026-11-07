# Change: Resolve SOCKS5 CONNECT audit hypotheses

Task ID: `PRX-1794074402219640`

## Why

Local probes confirmed CONNECT bytes after terminal shutdown, false TTL classification of generic timeout, loss of upstream error replies, and rejection of absolute domain names. The remaining audit questions require evidence rather than extra protocol features.

## What Changes

- Track and cancel upstream SOCKS negotiation sockets before terminal shutdown; refuse new negotiation stages after shutdown.
- Report generic timeout as general failure, not TTL expiry without evidence.
- Preserve validated upstream SOCKS failure replies through the local CONNECT response.
- Accept nonempty SOCKS domain frames and one terminal DNS root dot.
- Record BND chaining and localhost fallback as unconfirmed contract defects; do not change them.
- BREAKING: timeout and chained failure replies become accurate rather than retaining incorrect prior values.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `socks5-connect-contracts`: cancellation, error replies, and absolute domain names.

## Impact

- Rust proxy runtime, session parser, and ws-bootstrap DNS query builder with local tests.
- No JNI, protobuf, persistent schema, Kotlin UI, locale, dependency, golden, or baseline changes.
