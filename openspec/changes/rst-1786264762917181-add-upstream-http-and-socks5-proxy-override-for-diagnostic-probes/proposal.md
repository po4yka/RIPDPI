# Change: Add upstream HTTP and SOCKS5 proxy override for diagnostic probes

Task ID: `RST-1786264762917181`

## Why

Allow diagnostic probes (TLS reachability, TCP 16-20KB cutoff, DNS resolver availability, HTTP injection) to be routed through an arbitrary upstream HTTP or SOCKS5 proxy supplied by the user, so the operator can compare results across paths without leaving the app

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `add-upstream-http-and-socks5-proxy-override-for-diagnostic-probes`: Add upstream HTTP and SOCKS5 proxy override for diagnostic probes

### Modified Capabilities

- None.

## Impact

- Portfolio area: `rust-native`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
