# Change: Add optional Cloudflare Workers transport mode

Task ID: `RST-1786264762917044`

## Why

Add an optional operator-supplied Cloudflare Workers transport mode. The outer TLS metadata uses the Worker hostname, and the Worker forwards an authenticated framed stream to an operator-configured upstream

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `add-cloudflare-workers-transport-mode`: Add optional Cloudflare Workers transport mode

### Modified Capabilities

- None.

## Impact

- Portfolio area: `rust-native`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
