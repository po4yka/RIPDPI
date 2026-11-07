# Change: Parse NaiveProxy CONNECT heads with httparse

Task ID: `PRX-1794036614727167`

## Why

The local NaiveProxy HTTP frontend must reject malformed request lines and headers before opening an upstream. The 2026-11-07 audit identifies this as finding 9 at source revision 202f970.

## What Changes

- Use httparse::Request for the bounded HTTP head and existing http types for CONNECT authority. Keep the server-side CONNECT policy.
- A valid authenticated CONNECT completes the existing h2 Naive padding round trip.
- HTTP/1.xyz, extra tokens, invalid authority, duplicate auth, and oversized heads are rejected before upstream creation.
- Correct only the specified defect or infrastructure duplication. No other breaking change is planned; Keep the 16 KiB head limit, authentication and duplicate-auth rejection, CONNECT-only behavior, and bytes after the head boundary.

## Capabilities

### New Capabilities

- `standard-components/parse-naive-connect-with-httparse`: The local NaiveProxy HTTP frontend must reject malformed request lines and headers before opening an upstream.

### Modified Capabilities

- None. This delta records the previously unspecified contract for the audited path.

## Impact

- `native/rust/crates/ripdpi-naiveproxy/src/http_front.rs`
- `native/rust/crates/ripdpi-naiveproxy/src/tests.rs`

Use only existing platform or workspace libraries. Check the direct module dependency before editing. The parent epic owns programme order and shared lanes. No external publication, deployment, or unrelated protocol change is included.
