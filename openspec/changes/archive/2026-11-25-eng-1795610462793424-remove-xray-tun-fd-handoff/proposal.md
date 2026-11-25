# Remove the unused Xray TUN file descriptor handoff

Task ID: `ENG-1795610462793424`

## Why

The Xray provider configuration accepts a handoff mode that rejects startup when selected. The app already uses the local Xray SOCKS inbound.

## What Changes

- Remove the direct handoff selector from the runtime configuration.
- Keep the Xray loopback endpoint, the managed tunnel's DNS ownership, and the diagnostic topology label.

## Capabilities

### Modified Capabilities

- `bridge-tun-traffic-through-xray-local-inbound`

## Impact

The Kotlin Xray provider configuration, its upstream resolver, and its diagnostics use the supported handoff. No settings, JNI, or native data plane changes are required.
