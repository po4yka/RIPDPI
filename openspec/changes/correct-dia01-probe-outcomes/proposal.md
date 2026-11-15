# Change: Correct active probe outcomes

Task ID: `DGN-1794730862541358`

## Why

An active scan can report a late WebSocket response as success or mark a skipped
probe as unreachable. It can also attribute TLS alerts, DNS results, and socket
TTL to the wrong source. These results can change the conclusions of a scan.

## What Changes

- Stop pending TCP, TLS, and WebSocket I/O when the scan cancels or expires.
- Use eligible alternative direct peers and honor the configured WSS authority
  and opening budget.
- Report missing subprotocol agreement separately from path failure.
- Report observed TLS messages, verification errors, socket scope, and timing.
- Skip the extra system lookup for a Web probe through SOCKS5.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `diagnostics`: active transport probe control, facts, and reporting.

## Impact

Rust diagnostic transports, runners, report details, and Kotlin report consumers.
BREAKING: Replace ambiguous timing and TLS-evidence detail keys with names that
match the measurements and give administrative outcomes their own status.
