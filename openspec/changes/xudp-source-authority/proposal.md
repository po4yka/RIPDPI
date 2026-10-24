# Change: Preserve XUDP reply source authority

Task ID: `TRN-1792853642811403`

## Why

The physical Hysteria scenario switches automatically to its TCP reserve and
completes a download, but the next DNS query times out. The peer receives the
query. The client rejects its response because XUDP metadata omits the source
before a previous downlink source exists. The pinned server intentionally omits
that source when its redirect keeps the original request destination as authority.

## What Changes

- Derive an omitted reply source from the accepted initial request of the same
  XUDP carrier and session.
- Keep explicit response addresses authoritative and preserve rejection when no
  valid request authority exists.
- Prove reserve DNS and explicit UDP recovery on the authorized USB device scope.
- No public schema or dependency changes are planned.

## Capabilities

### New Capabilities

- `xudp-source-authority`: bounded request-derived source authority for XUDP replies.

### Modified Capabilities

- None.

## Impact

- Native XUDP codec and association state, with adjacent behavior tests.
- The Android transport acceptance scenario consumes the corrected runtime.
- No server, provider, credential or live deployment change.
