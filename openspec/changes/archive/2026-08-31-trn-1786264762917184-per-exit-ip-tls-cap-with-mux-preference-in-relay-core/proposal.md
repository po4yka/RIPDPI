# Change: Per-exit-IP TLS cap with true mux-preference in relay-core backend

Task ID: `TRN-1786264762917184`

## Why

The per-exit-IP concurrent-TLS cap (ExitIpSessionLimiter, ripdpi-proxy-runtime/src/exitipcap.rs) was wired into ripdpi-proxy-runtime's outbound connect path as an admission gate with route-preference on cap (skip an at-cap exit-IP candidate for an alternate; advisory fall-through when all are capped). That closed the originally-filed task

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `per-exit-ip-tls-cap-with-mux-preference-in-relay-core`: Per-exit-IP TLS cap with true mux-preference in relay-core backend

### Modified Capabilities

- None.

## Impact

- Portfolio area: `transport`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
