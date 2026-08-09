# Change: Add tun2socks UID validation to close SO_BINDTODEVICE escape (kernel 5.7+)

Task ID: `VPN-1786264762917166`

## Why

On Linux kernel 5.7+ (predominantly Android 12+, API 31+), SOBINDTODEVICE privilege was dropped — any unprivileged app can bind a socket directly to a network interface (e.g., tun0) and bypass all Android VPN split-tunneling routing rules. Standard tun2socks reads packets off the TUN interface but has no UID attribution, so any per-app split-tunnel enforcement done at the routing layer is invisible to it

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `add-tun2socks-uid-validation-against-so-bindtodevice-bypass`: Add tun2socks UID validation to close SO_BINDTODEVICE escape (kernel 5.7+)

### Modified Capabilities

- None.

## Impact

- Portfolio area: `vpn`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
